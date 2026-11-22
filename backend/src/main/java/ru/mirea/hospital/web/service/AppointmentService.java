package ru.mirea.hospital.web.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.hospital.web.config.AppProperties;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.domain.AppointmentStatus;
import ru.mirea.hospital.web.domain.Doctor;
import ru.mirea.hospital.web.domain.UserAccount;
import ru.mirea.hospital.web.dto.AppointmentDtos.BulkAction;
import ru.mirea.hospital.web.dto.AppointmentDtos.BulkFailure;
import ru.mirea.hospital.web.dto.AppointmentDtos.BulkRequest;
import ru.mirea.hospital.web.dto.AppointmentDtos.BulkResult;
import ru.mirea.hospital.web.dto.AppointmentDtos.AppointmentRequest;
import ru.mirea.hospital.web.dto.AppointmentDtos.AppointmentResponse;
import ru.mirea.hospital.web.error.BadRequestException;
import ru.mirea.hospital.web.error.ConflictException;
import ru.mirea.hospital.web.error.NotFoundException;
import ru.mirea.hospital.web.filter.SmartFilterParser;
import ru.mirea.hospital.web.filter.AppointmentFilter;
import ru.mirea.hospital.web.filter.AppointmentSpecifications;
import ru.mirea.hospital.web.repository.AppointmentNoteRepository;
import ru.mirea.hospital.web.repository.AppointmentRepository;
import ru.mirea.hospital.web.repository.DoctorRepository;
import ru.mirea.hospital.web.security.CurrentUserService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Основной прикладной сервис карточек приёмов. */
@Service
public class AppointmentService {
    private final AppointmentRepository appointments;
    private final AppointmentNoteRepository notes;
    private final DoctorRepository doctors;
    private final CurrentUserService currentUser;
    private final SmartFilterParser parser;
    private final AppointmentMapper mapper;
    private final PriorityCalculator priorityCalculator;
    private final AuditService audit;
    private final AppProperties properties;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public AppointmentService(AppointmentRepository appointments, AppointmentNoteRepository notes,
                          DoctorRepository doctors, CurrentUserService currentUser,
                          SmartFilterParser parser, AppointmentMapper mapper,
                          PriorityCalculator priorityCalculator, AuditService audit,
                          AppProperties properties, org.springframework.transaction.support.TransactionTemplate transaction) {
        this.transaction = transaction;
        this.appointments = appointments;
        this.notes = notes;
        this.doctors = doctors;
        this.currentUser = currentUser;
        this.parser = parser;
        this.mapper = mapper;
        this.priorityCalculator = priorityCalculator;
        this.audit = audit;
        this.properties = properties;
    }

    /** Выполняет безопасный умный поиск с пагинацией. */
    @Transactional(readOnly = true)
    public Page<AppointmentResponse> search(String query, Pageable pageable) {
        AppointmentFilter filter = parser.parse(query);
        return appointments.findAll(AppointmentSpecifications.from(filter), pageable).map(mapper::toResponse);
    }

    /** Возвращает одну карточку независимо от архивного статуса. */
    @Transactional(readOnly = true)
    public AppointmentResponse get(UUID id) {
        return mapper.toResponse(require(id));
    }

    /** Создаёт карточку и рассчитывает приоритет на сервере. */
    @Transactional
    public AppointmentResponse create(AppointmentRequest request) {
        UserAccount actor = currentUser.requireEntity();
        validateExternalId(request.externalId(), null);
        Appointment appointment = new Appointment(UUID.randomUUID(), normalizeRequired(request.patientLogin()),
                normalizeRequired(request.reason()), actor);
        apply(appointment, request, actor);
        appointment = appointments.saveAndFlush(appointment);
        audit.record("APPOINTMENT_CREATED", "APPOINTMENT", appointment.getId(),
                "Создана карточка приёма " + displayName(appointment), Map.of());
        return mapper.toResponse(appointment);
    }

    /** Изменяет карточку только при совпадении optimistic version. */
    @Transactional
    public AppointmentResponse update(UUID id, AppointmentRequest request) {
        Appointment appointment = require(id);
        verifyVersion(request.version(), appointment.getVersion());
        validateExternalId(request.externalId(), id);
        UserAccount actor = currentUser.requireEntity();
        apply(appointment, request, actor);
        appointments.flush();
        audit.record("APPOINTMENT_UPDATED", "APPOINTMENT", id,
                "Изменена карточка приёма " + displayName(appointment), Map.of("version", appointment.getVersion()));
        return mapper.toResponse(appointment);
    }

    /** Архивирует карточку вместо физического удаления. */
    @Transactional
    public AppointmentResponse archive(UUID id) {
        Appointment appointment = require(id);
        archiveInternal(appointment, currentUser.requireEntity());
        audit.record("APPOINTMENT_ARCHIVED", "APPOINTMENT", id,
                "Карточка приёма перемещена в архив", Map.of());
        appointments.flush();
        return mapper.toResponse(appointment);
    }

    /** Возвращает карточку из архива. */
    @Transactional
    public AppointmentResponse restore(UUID id) {
        Appointment appointment = require(id);
        appointment.setArchived(false);
        appointment.setArchivedAt(null);
        appointment.setArchivedBy(null);
        appointment.setUpdatedBy(currentUser.requireEntity());
        audit.record("APPOINTMENT_RESTORED", "APPOINTMENT", id,
                "Карточка приёма восстановлена из архива", Map.of());
        appointments.flush();
        return mapper.toResponse(appointment);
    }

    /**
     * Безвозвратно удаляет запись только при включённом feature flag и явном подтверждении.
     */
    @Transactional
    public void permanentDelete(UUID id, String confirmation) {
        if (!properties.permanentDeleteEnabled()) {
            throw new BadRequestException("Безвозвратное удаление отключено конфигурацией сервера");
        }
        if (!"PERMANENTLY_DELETE".equals(confirmation)) {
            throw new BadRequestException("Для удаления требуется заголовок X-Confirm-Delete: PERMANENTLY_DELETE");
        }
        Appointment appointment = require(id);
        String name = displayName(appointment);
        appointments.delete(appointment);
        audit.record("APPOINTMENT_PERMANENTLY_DELETED", "APPOINTMENT", id,
                "Безвозвратно удалена карточка " + name, Map.of());
    }

    /** Выполняет ограниченную массовую операцию и сообщает ошибки отдельных UUID. */
    public BulkResult bulk(BulkRequest request) {
        validateBulkRequest(request);
        int succeeded = 0;
        var failures = new ArrayList<BulkFailure>();
        for (UUID id : request.ids()) {
            try {
                transaction.executeWithoutResult(tx -> applyBulk(require(id), request, currentUser.requireEntity()));
                succeeded++;
            } catch (RuntimeException ex) {
                failures.add(new BulkFailure(id, ex instanceof NotFoundException || ex instanceof BadRequestException ? ex.getMessage() : "Изменение конфликтует с текущими данными"));
            }
        }
        audit.record("APPOINTMENT_BULK_" + request.action().name(), "APPOINTMENT", null,
                "Выполнена массовая операция", Map.of("requested", request.ids().size(), "succeeded", succeeded));
        return new BulkResult(request.ids().size(), succeeded, failures);
    }

    /** Используется CSV-сервисом внутри транзакции без дублирования правил. */
    @Transactional
    public boolean importRow(AppointmentRequest request, boolean createOnly) {
        var existing = appointments.findByExternalIdIgnoreCase(request.externalId());
        if (createOnly && existing.isPresent()) throw new ConflictException("Запись уже существует");
        UserAccount actor = currentUser.requireEntity();
        Appointment target = existing.orElseGet(() -> new Appointment(UUID.randomUUID(), request.patientLogin(), request.reason(), actor));
        apply(target, request, actor);
        appointments.saveAndFlush(target);
        return existing.isEmpty();
    }

    private void apply(Appointment appointment, AppointmentRequest request, UserAccount actor) {
        validateBooking(appointment, request);
        appointment.setExternalId(normalizeNullable(request.externalId()));
        appointment.setPatientLogin(normalizeRequired(request.patientLogin()).toLowerCase(Locale.ROOT));
        appointment.setReason(normalizeRequired(request.reason()));
        appointment.setDetails(normalizeNullable(request.details()));
        appointment.setEmail(normalizeEmail(request.email()));
        appointment.setPhone(normalizeNullable(request.phone()));
        appointment.setStartsAt(request.startsAt());
        appointment.setDoctor(resolveDoctor(request.doctorId()));
        appointment.setStatus(request.status());
        appointment.setReadiness(request.readiness());
        appointment.setNeedsCall(request.needsCall());
        appointment.setTags(normalizeTags(request.tags()));
        appointment.setPriorityLevel(priorityCalculator.calculate(request.status(), request.readiness(), request.needsCall()));
        appointment.setUpdatedBy(actor);
    }

    void validateBooking(Appointment existing, AppointmentRequest request) {
        var starts = request.startsAt();
        AppointmentStatusPolicy.validate(existing, request.status(), starts);
        if (existing == null || !starts.equals(existing.getStartsAt())) {
            var now = java.time.LocalDateTime.now(java.time.ZoneId.of("Europe/Moscow"));
            if (!starts.isAfter(now) || starts.toLocalDate().isAfter(now.toLocalDate().plusDays(90))) {
                throw new BadRequestException("Выберите будущее время в ближайшие 90 дней");
            }
            if (starts.getDayOfWeek().getValue() > 5 || starts.getHour() < 9 || starts.getHour() >= 17
                    || starts.getMinute() % 30 != 0 || starts.getSecond() != 0 || starts.getNano() != 0) {
                throw new BadRequestException("Приём возможен по будням с 09:00 до 16:30 с шагом 30 минут");
            }
        }
        String login = request.patientLogin().trim().toLowerCase(Locale.ROOT);
        if (request.status() != AppointmentStatus.CANCELLED && appointments.occupied(
                existing == null ? new UUID(0, 0) : existing.getId(), starts, request.doctorId(), login)) {
            throw new ConflictException("На это время у врача или пациента уже есть запись");
        }
        if ((existing == null || !login.equals(existing.getPatientLogin()) || existing.getStartsAt() == null)
                && !appointments.activePatient(login)) {
            throw new BadRequestException("Активный пациент с таким логином не найден");
        }
    }

    private void applyBulk(Appointment appointment, BulkRequest request, UserAccount actor) {
        switch (request.action()) {
            case ARCHIVE -> archiveInternal(appointment, actor);
            case RESTORE -> {
                appointment.setArchived(false);
                appointment.setArchivedAt(null);
                appointment.setArchivedBy(null);
                appointment.setUpdatedBy(actor);
            }
            case SET_STATUS -> {
                AppointmentStatusPolicy.validate(appointment, request.status(), appointment.getStartsAt());
                appointment.setStatus(request.status());
                appointment.setPriorityLevel(priorityCalculator.calculate(appointment.getStatus(), appointment.getReadiness(), appointment.isNeedsCall()));
                appointment.setUpdatedBy(actor);
            }
            case ADD_TAG -> {
                Set<String> tags = new LinkedHashSet<>(appointment.getTags());
                tags.add(normalizeTag(request.tag()));
                appointment.setTags(tags);
                appointment.setUpdatedBy(actor);
            }
            case REMOVE_TAG -> {
                Set<String> tags = new LinkedHashSet<>(appointment.getTags());
                tags.remove(normalizeTag(request.tag()));
                appointment.setTags(tags);
                appointment.setUpdatedBy(actor);
            }
        }
    }

    private static void validateBulkRequest(BulkRequest request) {
        if (request.action() == BulkAction.SET_STATUS && request.status() == null) {
            throw new BadRequestException("Для SET_STATUS требуется status");
        }
        if ((request.action() == BulkAction.ADD_TAG || request.action() == BulkAction.REMOVE_TAG)
                && (request.tag() == null || request.tag().isBlank())) {
            throw new BadRequestException("Для операции с тегом требуется tag");
        }
    }

    private static void archiveInternal(Appointment appointment, UserAccount actor) {
        appointment.setArchived(true);
        appointment.setArchivedAt(Instant.now());
        appointment.setArchivedBy(actor);
        appointment.setUpdatedBy(actor);
    }

    private Appointment require(UUID id) {
        return appointments.findById(id).orElseThrow(() -> new NotFoundException("Приём не найден"));
    }

    private Doctor resolveDoctor(UUID id) {
        if (id == null) throw new BadRequestException("Выберите врача");
        Doctor doctor = doctors.findById(id).orElseThrow(() -> new BadRequestException("Врач не найден"));
        if (!doctor.isActive()) throw new BadRequestException("Нельзя назначить неактивного врача");
        return doctor;
    }

    private void validateExternalId(String externalId, UUID currentId) {
        String normalized = normalizeNullable(externalId);
        if (normalized == null) return;
        boolean exists = currentId == null
                ? appointments.existsByExternalIdIgnoreCase(normalized)
                : appointments.existsByExternalIdIgnoreCaseAndIdNot(normalized, currentId);
        if (exists) throw new ConflictException("Внешний идентификатор уже используется");
    }

    private static void verifyVersion(Long requested, long actual) {
        if (requested == null) throw new BadRequestException("Для изменения требуется поле version");
        if (requested != actual) throw new ConflictException("Карточка уже изменена. Обновите данные и повторите операцию");
    }

    private static Set<String> normalizeTags(Set<String> tags) {
        if (tags == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String tag : tags) result.add(normalizeTag(tag));
        if (result.size() > 20) throw new BadRequestException("Допустимо не более 20 тегов");
        return result;
    }

    private static String normalizeTag(String value) {
        String normalized = normalizeRequired(value).toLowerCase(Locale.ROOT);
        if (normalized.length() > 50) throw new BadRequestException("Тег длиннее 50 символов");
        return normalized;
    }

    private static String normalizeEmail(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeRequired(String value) {
        if (value == null || value.trim().isEmpty()) throw new BadRequestException("Обязательное текстовое поле не заполнено");
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeNullable(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim().replaceAll("\\s+", " ");
    }

    private static String displayName(Appointment appointment) {
        return appointment.getReason() + " " + appointment.getPatientLogin();
    }
}
