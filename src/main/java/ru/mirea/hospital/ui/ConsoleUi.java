package ru.mirea.hospital.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.UUID;
import ru.mirea.hospital.exception.BusinessException;
import ru.mirea.hospital.exception.DataAccessException;
import ru.mirea.hospital.model.Appointment;
import ru.mirea.hospital.model.AppointmentStatus;
import ru.mirea.hospital.model.Doctor;
import ru.mirea.hospital.model.Role;
import ru.mirea.hospital.model.User;
import ru.mirea.hospital.service.AppointmentService;
import ru.mirea.hospital.service.DatabaseInspectionService;
import ru.mirea.hospital.service.UserService;
import ru.mirea.hospital.util.AppointmentExporter;
import ru.mirea.hospital.util.CsvExporter;
import ru.mirea.hospital.util.DatabaseManager;
import ru.mirea.hospital.util.ExcelExporter;

public class ConsoleUi {
    private final UserService users;
    private final AppointmentService appointments;
    private final DatabaseInspectionService inspector;
    private final Scanner input = new Scanner(System.in, StandardCharsets.UTF_8);
    private final DateTimeFormatter format = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private User current;
    private boolean running = true;

    public ConsoleUi(UserService users, AppointmentService appointments, DatabaseManager db) {
        this.users = users;
        this.appointments = appointments;
        this.inspector = new DatabaseInspectionService(users, db);
    }

    public void run() {
        System.out.println("КЛИНИКА — ЗАПИСЬ НА ПРИЁМ (московское время)");
        while (running) {
            try {
                if (current == null) {
                    authenticationMenu();
                } else {
                    mainMenu();
                }
            } catch (BusinessException | DataAccessException e) {
                printError(e);
            } catch (DateTimeParseException e) {
                printDateError();
            } catch (IOException e) {
                printFileError(e);
            } catch (NoSuchElementException e) {
                running = false;
            }
        }
        System.out.println("До свидания!");
    }

    private void authenticationMenu() {
        System.out.println("\n1. Вход\n2. Регистрация\n0. Выход");
        switch (number("Выберите действие: ")) {
            case 1 -> {
                current = users.login(text("Логин: "), password());
                System.out.println("Здравствуйте, " + current.getFullName() + "!");
            }
            case 2 -> {
                current = users.register(text("Логин: "), text("ФИО: "), password());
                System.out.println("Регистрация завершена.");
            }
            case 0 -> running = false;
            default -> System.out.println("Нет такого пункта меню.");
        }
    }

    private void mainMenu() throws IOException {
        System.out.println(
                "\n"
                        + (current.getRole() == Role.ADMIN
                                ? "АДМИНИСТРАТОР — ВСЕ ЗАПИСИ"
                                : "ПАЦИЕНТ — МОИ ЗАПИСИ"));
        System.out.println(
                """
                1. Запись по специальности врача
                2. Все записи / сортировка / действия по ID
                3. Поиск записи
                4. Фильтрация
                5. Статистика
                6. Экспорт CSV / Excel
                7. Выход из программы
                0. Выйти из аккаунта""");
        if (current.getRole() == Role.ADMIN) {
            System.out.println("8. Пользователи\n9. Документация\n10. Вывести таблицы базы данных");
        }

        switch (number("Выберите действие: ")) {
            case 1 -> booking(null);
            case 2 -> runScreen(this::records);
            case 3 -> runScreen(this::search);
            case 4 -> runScreen(this::filter);
            case 5 ->
                    appointments
                            .statistics(current)
                            .forEach((label, count) -> System.out.println(label + ": " + count));
            case 6 -> runScreen(this::export);
            case 7 -> running = false;
            case 0 -> current = null;
            case 8 -> {
                if (current.getRole() == Role.ADMIN) {
                    runScreen(this::userMenu);
                } else {
                    System.out.println("Нет такого пункта меню.");
                }
            }
            case 9 -> {
                if (current.getRole() == Role.ADMIN) {
                    showDocumentation();
                } else {
                    System.out.println("Нет такого пункта меню.");
                }
            }
            case 10 -> {
                if (current.getRole() == Role.ADMIN) {
                    showDatabaseTables();
                } else {
                    System.out.println("Нет такого пункта меню.");
                }
            }
            default -> System.out.println("Нет такого пункта меню.");
        }
    }

    private void records() {
        System.out.println("\nВСЕ ЗАПИСИ");
        System.out.println("1 — по дате создания, 2 — по времени приёма");
        int sort = number("Сортировка (0 — главное меню): ");
        if (sort == 0) {
            return;
        }
        if (sort != 1 && sort != 2) {
            throw new BusinessException("Выберите 1, 2 или 0 для возврата.");
        }

        int direction = number("1 — сначала старые, 2 — сначала новые, 0 — главное меню: ");
        if (direction == 0) {
            return;
        }
        if (direction != 1 && direction != 2) {
            throw new BusinessException("Выберите 1, 2 или 0 для возврата.");
        }

        display(appointments.sorted(current, sort == 1, direction == 2));
        long id = number("ID записи для просмотра / изменения (0 — главное меню): ");
        if (id == 0) {
            return;
        }

        display(List.of(appointments.find(current, id)));
        switch (number(
                "1 — перенести / изменить врача, 2 — отменить, 3 — удалить, 0 — главное меню: ")) {
            case 1 -> booking(id);
            case 2 -> {
                if (text("Отменить запись? Введите да: ").equalsIgnoreCase("да")) {
                    appointments.cancel(current, id);
                    System.out.println("Запись отменена.");
                }
            }
            case 3 -> {
                if (text("Удалить запись безвозвратно? Введите да: ").equalsIgnoreCase("да")) {
                    appointments.delete(current, id);
                    System.out.println("Запись удалена.");
                }
            }
            case 0 -> {
                return;
            }
            default -> throw new BusinessException("Выберите действие от 0 до 3.");
        }
    }

    private void booking(Long id) {
        if (id != null && appointments.find(current, id).status() != AppointmentStatus.UPCOMING) {
            throw new BusinessException("Можно переносить только предстоящую запись.");
        }
        runScreen(() -> bookingForm(id));
    }

    private void bookingForm(Long id) {
        System.out.println(id == null ? "\nНОВАЯ ЗАПИСЬ" : "\nПЕРЕНОС ЗАПИСИ № " + id);
        List<String> specialties =
                appointments.doctors(current).stream()
                        .map(Doctor::specialty)
                        .distinct()
                        .sorted()
                        .toList();
        for (int i = 0; i < specialties.size(); i++) {
            System.out.println((i + 1) + ". " + specialties.get(i));
        }

        int choice = number("Специальность (0 — главное меню): ");
        if (choice == 0) {
            return;
        }
        if (choice < 1 || choice > specialties.size()) {
            throw new BusinessException("Нет такой специальности.");
        }

        String specialty = specialties.get(choice - 1);
        String rawDate = text("Дата (ГГГГ-ММ-ДД, будни, ближайшие 90 дней; 0 — главное меню): ");
        if (rawDate.equals("0")) {
            return;
        }

        LocalDate day = LocalDate.parse(rawDate);
        List<LocalTime> times = appointments.availableTimes(current, specialty, day, id);
        if (times.isEmpty()) {
            throw new BusinessException("На эту дату свободного времени нет.");
        }
        for (int i = 0; i < times.size(); i++) {
            System.out.println((i + 1) + ". " + times.get(i));
        }

        int timeChoice = number("Номер времени (0 — главное меню): ");
        if (timeChoice == 0) {
            return;
        }
        if (timeChoice < 1 || timeChoice > times.size()) {
            throw new BusinessException("Нет такого времени.");
        }

        LocalDateTime time = day.atTime(times.get(timeChoice - 1));
        List<Doctor> doctors = appointments.availableDoctors(current, specialty, time, id);
        for (int i = 0; i < doctors.size(); i++) {
            System.out.println((i + 1) + ". " + doctors.get(i));
        }

        int doctorChoice = number("Номер врача (0 — главное меню): ");
        if (doctorChoice == 0) {
            return;
        }
        if (doctorChoice < 1 || doctorChoice > doctors.size()) {
            throw new BusinessException("Нет такого врача.");
        }

        Doctor doctor = doctors.get(doctorChoice - 1);
        System.out.println(doctor + ", " + time.format(format));
        String confirmation = text("Подтвердить запись? Введите да или 0 для возврата: ");
        if (confirmation.equals("0")) {
            return;
        }
        if (!confirmation.equalsIgnoreCase("да")) {
            throw new BusinessException("Для подтверждения введите «да» или 0 для возврата.");
        }

        if (id == null) {
            System.out.println(
                    "Запись создана. ID: " + appointments.book(current, doctor.id(), time));
        } else {
            appointments.reschedule(current, id, doctor.id(), time);
            System.out.println("Запись перенесена.");
        }
    }

    private void search() {
        System.out.println("\nПОИСК ЗАПИСИ");
        int mode = number("1 — фамилия врача, 2 — специальность, 0 — главное меню: ");
        if (mode == 0) {
            return;
        }
        if (mode != 1 && mode != 2) {
            throw new BusinessException("Выберите 1, 2 или 0 для возврата.");
        }

        String query = text("Введите запрос (0 — главное меню): ");
        if (query.equals("0")) {
            return;
        }
        display(appointments.search(current, query, mode == 2));
    }

    private void filter() {
        System.out.println("\nФИЛЬТРАЦИЯ ЗАПИСЕЙ");
        System.out.println(
                "1 — в процессе, 2 — предстоит, 3 — завершена, 4 — отменена, "
                        + "5 — любой статус");
        int status = number("Статус (0 — главное меню): ");
        if (status == 0) {
            return;
        }
        if (status < 1 || status > 5) {
            throw new BusinessException("Выберите статус от 1 до 5 или 0 для возврата.");
        }

        String from = text("Дата приёма с (ГГГГ-ММ-ДД, Enter — без границы, 0 — назад): ");
        if (from.equals("0")) {
            return;
        }
        String to = text("Дата приёма по (ГГГГ-ММ-ДД, Enter — без границы, 0 — назад): ");
        if (to.equals("0")) {
            return;
        }

        display(
                appointments.filter(
                        current,
                        status == 5 ? null : AppointmentStatus.values()[status - 1],
                        from.isBlank() ? null : LocalDate.parse(from),
                        to.isBlank() ? null : LocalDate.parse(to)));
    }

    private void export() throws IOException {
        System.out.println("\nЭКСПОРТ ЗАПИСЕЙ");
        int type = number("1 — CSV, 2 — Excel (.xlsx), 0 — главное меню: ");
        if (type == 0) {
            return;
        }

        AppointmentExporter exporter =
                switch (type) {
                    case 1 -> new CsvExporter();
                    case 2 -> new ExcelExporter();
                    default -> throw new BusinessException("Выберите 1, 2 или 0 для возврата.");
                };
        List<Appointment> data = appointments.list(current);
        Path directory = Path.of("exports");
        Files.createDirectories(directory);
        Path path =
                directory.resolve(
                        "appointments-"
                                + current.getId()
                                + "-"
                                + UUID.randomUUID()
                                + (type == 1 ? ".csv" : ".xlsx"));
        exporter.export(data, path);
        System.out.println(
                "Экспортировано записей: " + data.size() + ". Файл: " + path.toAbsolutePath());
    }

    private void userMenu() {
        System.out.println("\nУПРАВЛЕНИЕ ПОЛЬЗОВАТЕЛЯМИ");
        users.list(current)
                .forEach(
                        user ->
                                System.out.printf(
                                        "%d | %s | %s | %s%n",
                                        user.getId(),
                                        user.getLogin(),
                                        user.getFullName(),
                                        user.getRole()));
        switch (number(
                "1 — добавить пациента, 2 — изменить ФИО, 3 — удалить пользователя, "
                        + "0 — главное меню: ")) {
            case 1 -> {
                users.register(text("Логин: "), text("ФИО: "), password());
                System.out.println("Пользователь добавлен.");
            }
            case 2 -> {
                users.rename(current, number("ID пользователя: "), text("Новое ФИО: "));
                System.out.println("Данные обновлены.");
            }
            case 3 -> {
                int id = number("ID пользователя: ");
                if (text("Удалить пользователя? Введите да: ").equalsIgnoreCase("да")) {
                    users.delete(current, id);
                    System.out.println("Пользователь удалён.");
                }
            }
            case 0 -> {
                return;
            }
            default -> throw new BusinessException("Выберите действие от 0 до 3.");
        }
    }

    private void showDocumentation() throws IOException {
        users.requireAdmin(current);
        try (var stream = ConsoleUi.class.getResourceAsStream("/manual.txt")) {
            if (stream == null) {
                throw new IOException("Документация не найдена.");
            }
            System.out.println(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void showDatabaseTables() {
        appointments.list(current);
        inspector
                .tables(current)
                .forEach(
                        (table, rows) -> {
                            System.out.println("\n" + table);
                            rows.forEach(System.out::println);
                        });
    }

    private void runScreen(ScreenAction screen) {
        while (running && current != null) {
            try {
                screen.run();
                return;
            } catch (BusinessException e) {
                printError(e);
            } catch (DataAccessException e) {
                printError(e);
                return;
            } catch (DateTimeParseException e) {
                printDateError();
            } catch (IOException e) {
                printFileError(e);
            } catch (NoSuchElementException e) {
                running = false;
                return;
            }
        }
    }

    private void display(List<Appointment> data) {
        if (data.isEmpty()) {
            System.out.println("Записей не найдено.");
            return;
        }
        System.out.println(
                "ID | Пациент | Врач | Специальность | Приём | Создана | Статус | Переносы");
        for (Appointment appointment : data) {
            System.out.printf(
                    "%d | %s | %s | %s | %s | %s | %s | %d%n",
                    appointment.id(),
                    appointment.patientName(),
                    appointment.doctorName(),
                    appointment.specialty(),
                    appointment.startsAt().format(format),
                    appointment.createdAt().format(format),
                    appointment.status(),
                    appointment.rescheduleCount());
        }
    }

    private String text(String prompt) {
        System.out.print(prompt);
        return input.nextLine().strip();
    }

    private String password() {
        if (System.console() != null) {
            char[] value = System.console().readPassword("Пароль: ");
            if (value == null) {
                throw new NoSuchElementException();
            }
            String result = new String(value);
            Arrays.fill(value, '\0');
            return result;
        }
        System.out.print("Пароль (в этой консоли ввод виден): ");
        return input.nextLine();
    }

    private int number(String prompt) {
        while (true) {
            try {
                return Integer.parseInt(text(prompt));
            } catch (NumberFormatException e) {
                System.out.println(
                        "Ошибка: введите целое число в диапазоне от -2147483648 до 2147483647.");
            }
        }
    }

    private void printError(RuntimeException error) {
        System.out.println("Ошибка: " + error.getMessage());
    }

    private void printDateError() {
        System.out.println("Ошибка: укажите существующую дату в формате ГГГГ-ММ-ДД.");
    }

    private void printFileError(IOException error) {
        System.out.println("Ошибка работы с файлом: " + error.getMessage());
    }

    @FunctionalInterface
    private interface ScreenAction {
        void run() throws IOException;
    }
}
