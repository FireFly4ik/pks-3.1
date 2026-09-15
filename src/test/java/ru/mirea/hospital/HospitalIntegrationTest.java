package ru.mirea.hospital;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.mirea.hospital.model.*;
import ru.mirea.hospital.repository.*;
import ru.mirea.hospital.service.*;
import ru.mirea.hospital.util.*;
import ru.mirea.hospital.exception.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class HospitalIntegrationTest {
    private DatabaseManager db;
    private UserService users;
    private AppointmentService appointments;
    private JdbcAppointmentRepository repository;
    private User patient;
    private User other;
    private User admin;
    private final Clock clock = Clock.fixed(Instant.parse("2030-09-02T05:00:00Z"), ZoneId.of("Europe/Moscow"));
    private final LocalDateTime slot = LocalDateTime.of(2030, 9, 2, 10, 0);
    @TempDir Path directory;

    @BeforeEach void setup() throws Exception {
        db = new DatabaseManager(System.getenv("TEST_DB_URL"), System.getenv().getOrDefault("TEST_DB_USER", "hospital"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        try (var c = db.connect(); var s = c.prepareStatement("SELECT current_database()"); var r = s.executeQuery()) {
            r.next(); assertTrue(r.getString(1).endsWith("_test"), "Тесты удаляют данные: имя БД обязано заканчиваться на _test");
        }
        db.initialize();
        try (var c = db.connect(); var s = c.prepareStatement("TRUNCATE appointments, users RESTART IDENTITY CASCADE")) { s.execute(); }
        var userRepository = new UserRepository(db);
        users = new UserService(userRepository);
        patient = users.register("patient", "Иванов Иван", "Password123!");
        other = users.register("other", "Петров Пётр", "Password123!");
        admin = userRepository.create("admin", "Администратор", PasswordHasher.hash("Password123!"), Role.ADMIN);
        repository = new JdbcAppointmentRepository(db);
        appointments = new AppointmentService(repository, users, clock);
    }

    @Test void registrationAndAuthentication() {
        assertEquals(patient.getId(), users.login(" PATIENT ", "Password123!").getId());
        assertThrows(BusinessException.class, () -> users.login("patient", "wrong"));
        assertThrows(BusinessException.class, () -> users.register("new", "Имя", "short"));
        assertThrows(BusinessException.class, () -> users.register("x' OR 1=1", "Имя", "Password123!"));
        assertThrows(DataAccessException.class, () -> users.register("PATIENT", "Имя", "Password123!"));
        assertNotEquals("Password123!", new UserRepository(db).passwordHash("patient").orElseThrow());
    }

    @Test void patientCannotAccessOthersOrAdminFunctions() {
        long id = appointments.book(patient, 1, slot);
        assertTrue(appointments.list(other).isEmpty());
        assertThrows(EntityNotFoundException.class, () -> appointments.find(other, id));
        assertThrows(EntityNotFoundException.class, () -> appointments.cancel(other, id));
        assertThrows(EntityNotFoundException.class, () -> appointments.reschedule(other, id, 2, slot.plusHours(1)));
        assertThrows(EntityNotFoundException.class, () -> appointments.delete(other, id));
        assertThrows(BusinessException.class, () -> users.list(patient));
        assertThrows(BusinessException.class, () -> new DatabaseInspectionService(users, db).tables(patient));
        assertEquals(1, appointments.list(admin).size());
    }

    @Test void invalidScheduleAndMissingDoctor() {
        for (LocalDateTime time : List.of(slot.minusDays(1), slot.minusHours(3), slot.withHour(17),
                slot.withMinute(15), slot.withSecond(1), slot.plusDays(100))) {
            assertThrows(BusinessException.class, () -> appointments.book(patient, 1, time));
        }
        assertThrows(EntityNotFoundException.class, () -> appointments.book(patient, 99999, slot));
        assertTrue(appointments.list(admin).isEmpty());
    }

    @Test void collisionsCancellationAndReuse() {
        long id = appointments.book(patient, 1, slot);
        assertThrows(BusinessException.class, () -> appointments.book(other, 1, slot));
        assertThrows(BusinessException.class, () -> appointments.book(patient, 2, slot));
        assertTrue(appointments.availableDoctors(patient, "Терапевт", slot, null).isEmpty());
        assertEquals(List.of(2L), appointments.availableDoctors(other, "Терапевт", slot, null).stream().map(Doctor::id).toList());
        appointments.cancel(patient, id);
        long replacement = appointments.book(other, 1, slot);
        assertEquals(AppointmentStatus.CANCELLED, appointments.find(patient, id).status());
        assertEquals(AppointmentStatus.UPCOMING, appointments.find(other, replacement).status());
        assertThrows(BusinessException.class, () -> appointments.cancel(patient, id));
    }

    @Test void rescheduleIsAtomicAndCounted() {
        long id = appointments.book(patient, 1, slot);
        appointments.book(other, 2, slot.plusHours(1));
        assertThrows(BusinessException.class, () -> appointments.reschedule(patient, id, 2, slot.plusHours(1)));
        assertEquals(slot, appointments.find(patient, id).startsAt());
        assertEquals(0, appointments.find(patient, id).rescheduleCount());
        assertThrows(BusinessException.class, () -> appointments.reschedule(patient, id, 1, slot));
        appointments.reschedule(patient, id, 2, slot.plusHours(2));
        assertEquals(slot.plusHours(2), appointments.find(patient, id).startsAt());
        assertEquals(1, appointments.find(patient, id).rescheduleCount());
        assertEquals(1L, appointments.statistics(patient).get("Перенесённых записей"));
        appointments.book(other, 1, slot); // Старое время освободилось.
    }

    @Test void lifecycleBoundariesAndForbiddenTransitions() {
        long id = appointments.book(patient, 1, slot);
        var atStart = new AppointmentService(repository, users, Clock.fixed(slot.atZone(clock.getZone()).toInstant(), clock.getZone()));
        assertEquals(AppointmentStatus.IN_PROGRESS, atStart.find(patient, id).status());
        assertThrows(BusinessException.class, () -> atStart.cancel(patient, id));
        assertThrows(BusinessException.class, () -> atStart.reschedule(patient, id, 2, slot.plusHours(1)));
        assertThrows(BusinessException.class, () -> atStart.delete(patient, id));
        var atEnd = new AppointmentService(repository, users, Clock.fixed(slot.plusMinutes(30).atZone(clock.getZone()).toInstant(), clock.getZone()));
        assertEquals(AppointmentStatus.COMPLETED, atEnd.find(patient, id).status());
        atEnd.delete(patient, id);
        assertThrows(EntityNotFoundException.class, () -> atEnd.find(patient, id));
    }

    @Test void searchFiltersSortingStatistics() {
        long later = appointments.book(patient, 3, slot.plusDays(1));
        long earlier = appointments.book(patient, 1, slot);
        assertEquals(1, appointments.search(patient, "ИВАН", false).size());
        assertTrue(appointments.search(patient, "Елена", false).isEmpty()); // По фамилии, не по имени.
        assertEquals(1, appointments.search(patient, "кардио", true).size());
        assertTrue(appointments.search(patient, "' OR 1=1 --", false).isEmpty());
        assertEquals(earlier, appointments.sorted(patient, false, false).getFirst().id());
        assertEquals(later, appointments.sorted(patient, false, true).getFirst().id());
        assertEquals(later, appointments.sorted(patient, true, false).getFirst().id());
        assertEquals(1, appointments.filter(patient, AppointmentStatus.UPCOMING, slot.toLocalDate(), slot.toLocalDate()).size());
        assertThrows(BusinessException.class, () -> appointments.filter(patient, null, slot.toLocalDate().plusDays(1), slot.toLocalDate()));
        appointments.cancel(patient, earlier);
        assertEquals(1L, appointments.statistics(patient).get("Отменена"));
        assertEquals(2L, appointments.statistics(patient).get("Всего записей"));
    }

    @Test void usersCrudAndForeignKeyProtection() {
        users.rename(admin, other.getId(), "Сидоров Иван");
        assertEquals("Сидоров Иван", users.login("other", "Password123!").getFullName());
        users.delete(admin, other.getId());
        assertThrows(BusinessException.class, () -> users.login("other", "Password123!"));
        assertThrows(BusinessException.class, () -> users.delete(admin, admin.getId()));
        long id = appointments.book(patient, 1, slot);
        assertThrows(DataAccessException.class, () -> users.delete(admin, patient.getId()));
        appointments.cancel(patient, id); appointments.delete(patient, id); users.delete(admin, patient.getId());
        assertEquals(1, users.list(admin).size());
    }

    @Test void databasePersistsAcrossRepositoryInstances() {
        long id = appointments.book(patient, 1, slot);
        var fresh = new AppointmentService(new JdbcAppointmentRepository(db), new UserService(new UserRepository(db)), clock);
        assertEquals(id, fresh.find(patient, id).id());
    }

    @Test void exportsCanBeReadAndDoNotOverwrite() throws Exception {
        appointments.book(patient, 1, slot);
        users.rename(admin, patient.getId(), "=SUM(1;2) \"Иван\"");
        List<Appointment> data = appointments.list(patient);
        Path csv = directory.resolve("data.csv");
        Path xlsx = directory.resolve("data.xlsx");
        new CsvExporter().export(data, csv);
        String content = Files.readString(csv);
        assertTrue(content.startsWith("\ufeff"));
        assertTrue(content.contains("\"'=SUM(1;2) \"\"Иван\"\"\""));
        new ExcelExporter().export(data, xlsx);
        try (var book = new XSSFWorkbook(Files.newInputStream(xlsx))) {
            var row = book.getSheetAt(0).getRow(1);
            assertEquals("=SUM(1;2) \"Иван\"", row.getCell(1).getStringCellValue());
            assertEquals(slot, row.getCell(4).getLocalDateTimeCellValue());
            assertEquals(2, book.getSheetAt(0).getPhysicalNumberOfRows());
        }
        assertThrows(FileAlreadyExistsException.class, () -> new CsvExporter().export(data, csv));
        assertThrows(FileAlreadyExistsException.class, () -> new ExcelExporter().export(data, xlsx));
        new ExcelExporter().export(List.of(), directory.resolve("empty.xlsx"));
    }

    @Test void concurrentBookingHasExactlyOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (User user : List.of(patient, other)) results.add(pool.submit(() -> {
                start.await();
                try { appointments.book(user, 1, slot); return true; }
                catch (BusinessException | DataAccessException e) { return false; }
            }));
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, appointments.list(admin).size());
        }
    }
}
