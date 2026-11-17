package ru.mirea.hospital.web.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.mirea.hospital.web.config.AppProperties;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.domain.AppointmentStatus;
import ru.mirea.hospital.web.domain.Doctor;
import ru.mirea.hospital.web.domain.UserAccount;
import ru.mirea.hospital.web.dto.ImportDtos.ImportMode;
import ru.mirea.hospital.web.dto.ImportDtos.ImportPreview;
import ru.mirea.hospital.web.dto.ImportDtos.ImportResult;
import ru.mirea.hospital.web.dto.ImportDtos.RowIssue;
import ru.mirea.hospital.web.dto.AppointmentDtos.AppointmentRequest;
import ru.mirea.hospital.web.dto.AppointmentDtos.AppointmentResponse;
import ru.mirea.hospital.web.error.BadRequestException;
import ru.mirea.hospital.web.filter.SmartFilterParser;
import ru.mirea.hospital.web.filter.AppointmentSpecifications;
import ru.mirea.hospital.web.repository.AppointmentRepository;
import ru.mirea.hospital.web.repository.DoctorRepository;
import ru.mirea.hospital.web.security.CurrentUserService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Защищённый CSV import/export.
 *
 * <p>Предварительная проверка отделена от импорта: ошибки видны до записи в базу.
 * При экспорте нейтрализуются значения, которые Excel может воспринять как формулы.</p>
 */
@Service
public class CsvAppointmentService {
    private static final String[] HEADERS = {
            "externalId", "reason", "patientLogin", "details", "email", "phone",
            "startsAt", "doctor", "status", "readiness", "needsCall", "tags"
    };

    private final AppointmentRepository appointments;
    private final DoctorRepository doctors;
    private final AppointmentService appointmentService;
    private final CurrentUserService currentUser;
    private final SmartFilterParser parser;
    private final AppointmentMapper mapper;
    private final Validator validator;
    private final AppProperties properties;
    private final AuditService audit;

    public CsvAppointmentService(AppointmentRepository appointments, DoctorRepository doctors,
                             AppointmentService appointmentService, CurrentUserService currentUser,
                             SmartFilterParser parser, AppointmentMapper mapper, Validator validator,
                             AppProperties properties, AuditService audit) {
        this.appointments = appointments;
        this.doctors = doctors;
        this.appointmentService = appointmentService;
        this.currentUser = currentUser;
        this.parser = parser;
        this.mapper = mapper;
        this.validator = validator;
        this.properties = properties;
        this.audit = audit;
    }

    /** Проверяет CSV без записи в базу. */
    @Transactional(readOnly = true)
    public ImportPreview preview(MultipartFile file, ImportMode mode) {
        ParsedFile parsed = parse(file, mode == null ? ImportMode.CREATE_ONLY : mode);
        return new ImportPreview(parsed.totalRows(), parsed.rows().size(), parsed.totalRows() - parsed.rows().size(), parsed.issues());
    }

    /** Применяет только валидные строки и возвращает детальный итог. */
    public ImportResult apply(MultipartFile file, ImportMode mode) {
        ImportMode effectiveMode = mode == null ? ImportMode.CREATE_ONLY : mode;
        ParsedFile parsed = parse(file, effectiveMode);
        int created = 0;
        int updated = 0;
        int skipped = parsed.totalRows() - parsed.rows().size();
        List<RowIssue> runtimeIssues = new ArrayList<>(parsed.issues());

        for (ParsedRow row : parsed.rows()) {
            try {
                if (appointmentService.importRow(row.request(), effectiveMode == ImportMode.CREATE_ONLY)) created++;
                else updated++;
            } catch (RuntimeException ex) {
                String message = ex instanceof BadRequestException ? ex.getMessage() : "Строка конфликтует с текущими данными";
                runtimeIssues.add(new RowIssue(row.rowNumber(), "row", message));
                skipped++;
            }
        }
        audit.record("APPOINTMENT_CSV_IMPORTED", "APPOINTMENT", null, "Импортирован CSV с приёмами",
                Map.of("created", created, "updated", updated, "skipped", skipped, "mode", effectiveMode.name()));
        return new ImportResult(parsed.totalRows(), created, updated, skipped, List.copyOf(runtimeIssues));
    }

    /** Потоково экспортирует текущую выборку без загрузки всего набора в память. */
    @Transactional(readOnly = true)
    public void export(String query, OutputStream output) throws IOException {
        // BOM записывается до создания CSVPrinter, иначе заголовок окажется перед BOM.
        output.write("\ufeff".getBytes(StandardCharsets.UTF_8));
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(HEADERS).get())) {
            int page = 0;
            while (true) {
                var result = appointments.findAll(AppointmentSpecifications.from(parser.parse(query)),
                        PageRequest.of(page, 500, Sort.by("reason").ascending().and(Sort.by("patientLogin").ascending())));
                for (Appointment appointment : result.getContent()) {
                    AppointmentResponse dto = mapper.toResponse(appointment);
                    printer.printRecord(
                            safeCell(dto.externalId()), safeCell(dto.reason()), safeCell(dto.patientLogin()),
                            safeCell(dto.details()), safeCell(dto.email()), safeCell(dto.phone()),
                            dto.startsAt(), safeCell(dto.doctorCode()), dto.status(), dto.readiness(),
                            dto.needsCall(), safeCell(String.join("|", dto.tags())));
                }
                if (result.isLast()) break;
                page++;
            }
            printer.flush();
        }
        audit.record("APPOINTMENT_CSV_EXPORTED", "APPOINTMENT", null, "Экспортирована выборка приёмов", Map.of("query", query == null ? "" : query));
    }

    private ParsedFile parse(MultipartFile file, ImportMode mode) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Выберите непустой CSV-файл");
        List<ParsedRow> validRows = new ArrayList<>();
        List<RowIssue> issues = new ArrayList<>();
        Set<String> externalIdsInFile = new LinkedHashSet<>();
        Set<String> reservedDoctors = new LinkedHashSet<>();
        Set<String> reservedPatients = new LinkedHashSet<>();
        int total = 0;

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            reader.mark(1);
            if (reader.read() != 0xfeff) reader.reset();
            try (
             CSVParser csv = CSVParser.parse(reader, format)) {
            validateHeaders(csv.getHeaderMap());
            for (CSVRecord record : csv) {
                total++;
                long rowNumber = record.getRecordNumber() + 1;
                if (total > properties.importLimits().maxRows()) {
                    throw new BadRequestException("CSV содержит больше " + properties.importLimits().maxRows() + " строк");
                }
                try {
                    ParsedRow row = toRow(record, rowNumber);
                    Set<ConstraintViolation<AppointmentRequest>> violations = validator.validate(row.request());
                    if (!violations.isEmpty()) {
                        for (ConstraintViolation<AppointmentRequest> violation : violations) {
                            issues.add(new RowIssue(rowNumber, violation.getPropertyPath().toString(), violation.getMessage()));
                        }
                        continue;
                    }
                    String key = row.request().externalId().toLowerCase(Locale.ROOT);
                    if (!externalIdsInFile.add(key)) {
                        issues.add(new RowIssue(rowNumber, "externalId", "Дубликат externalId внутри файла"));
                        continue;
                    }
                    if (mode == ImportMode.CREATE_ONLY && appointments.existsByExternalIdIgnoreCase(row.request().externalId())) {
                        issues.add(new RowIssue(rowNumber, "externalId", "Запись уже существует"));
                        continue;
                    }
                    appointmentService.validateBooking(appointments.findByExternalIdIgnoreCase(key).orElse(null), row.request());
                    if (row.request().status() != AppointmentStatus.CANCELLED) {
                        String doctorSlot = row.request().doctorId() + "/" + row.request().startsAt();
                        String patientSlot = row.request().patientLogin().toLowerCase(Locale.ROOT) + "/" + row.request().startsAt();
                        if (reservedDoctors.contains(doctorSlot) || reservedPatients.contains(patientSlot)) {
                            issues.add(new RowIssue(rowNumber, "startsAt", "В файле уже есть запись на это время"));
                            continue;
                        }
                        reservedDoctors.add(doctorSlot);
                        reservedPatients.add(patientSlot);
                    }
                    validRows.add(row);
                } catch (RuntimeException ex) {
                    issues.add(new RowIssue(rowNumber, "row", safeMessage(ex)));
                }
            }
            }
        } catch (IOException ex) {
            throw new BadRequestException("Не удалось прочитать CSV в UTF-8");
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Некорректная структура CSV: " + ex.getMessage());
        }
        return new ParsedFile(total, List.copyOf(validRows), List.copyOf(issues));
    }

    private ParsedRow toRow(CSVRecord record, long rowNumber) {
        String externalId = required(record, "externalId");
        String doctorCode = nullable(record, "doctor");
        Doctor doctor = doctorCode == null ? null : doctors.findByCodeIgnoreCase(doctorCode)
                .orElseThrow(() -> new BadRequestException("Врач не найден: " + doctorCode));
        if (doctor != null && !doctor.isActive()) throw new BadRequestException("Врач неактивен: " + doctorCode);

        String phone = nullable(record, "phone");
        if (phone != null && phone.startsWith("'+7-")) phone = phone.substring(1);
        AppointmentRequest request = new AppointmentRequest(
                externalId,
                required(record, "patientLogin"),
                required(record, "reason"),
                nullable(record, "details"),
                nullable(record, "email"),
                phone,
                parseDate(nullable(record, "startsAt")),
                doctor == null ? null : doctor.getId(),
                parseStatus(required(record, "status")),
                parseScore(nullable(record, "readiness")),
                parseBoolean(nullable(record, "needsCall")),
                parseTags(nullable(record, "tags")),
                null);
        return new ParsedRow(rowNumber, request);
    }

    private static void validateHeaders(Map<String, Integer> actual) {
        for (String header : HEADERS) {
            if (!actual.containsKey(header)) throw new BadRequestException("Отсутствует колонка: " + header);
        }
    }

    private static String required(CSVRecord record, String field) {
        String value = nullable(record, field);
        if (value == null) throw new BadRequestException("Не заполнено поле " + field);
        return value;
    }

    private static String nullable(CSVRecord record, String field) {
        String value = record.get(field);
        if (value == null) return null;
        value = value.replace("\ufeff", "").trim();
        return value.isEmpty() ? null : value;
    }

    private static LocalDateTime parseDate(String value) {
        if (value == null) return null;
        try { return LocalDateTime.parse(value); }
        catch (DateTimeParseException ex) { throw new BadRequestException("Дата должна иметь формат YYYY-MM-DDTHH:mm"); }
    }

    private static BigDecimal parseScore(String value) {
        if (value == null) return null;
        try { return new BigDecimal(value.replace(',', '.')); }
        catch (NumberFormatException ex) { throw new BadRequestException("Некорректный readiness"); }
    }

    private static boolean parseBoolean(String value) {
        if (value == null) return false;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "да" -> true;
            case "false", "0", "no", "нет" -> false;
            default -> throw new BadRequestException("needsCall принимает true/false или да/нет");
        };
    }

    private static AppointmentStatus parseStatus(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "upcoming", "предстоит" -> AppointmentStatus.UPCOMING;
            case "in_progress", "в_процессе" -> AppointmentStatus.IN_PROGRESS;
            case "cancelled", "отменена" -> AppointmentStatus.CANCELLED;
            case "completed", "завершена" -> AppointmentStatus.COMPLETED;

            default -> throw new BadRequestException("Неизвестный status: " + value);
        };
    }

    private static Set<String> parseTags(String value) {
        if (value == null) return Set.of();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Arrays.stream(value.split("\\|")).map(String::trim).filter(v -> !v.isEmpty())
                .map(v -> v.toLowerCase(Locale.ROOT)).forEach(tags::add);
        return tags;
    }

    private static String safeCell(Object value) {
        if (value == null) return "";
        String cell = String.valueOf(value);
        if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) return "'" + cell;
        return cell;
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "Некорректная строка" : message;
    }

    private record ParsedRow(long rowNumber, AppointmentRequest request) { }
    private record ParsedFile(int totalRows, List<ParsedRow> rows, List<RowIssue> issues) { }
}
