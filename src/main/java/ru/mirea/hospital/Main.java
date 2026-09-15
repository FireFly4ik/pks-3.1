package ru.mirea.hospital;

import ru.mirea.hospital.repository.*;
import ru.mirea.hospital.service.*;
import ru.mirea.hospital.ui.ConsoleUi;
import ru.mirea.hospital.util.*;
import ru.mirea.hospital.exception.*;
import java.time.*;
import java.util.TimeZone;

public final class Main {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        String password = System.getenv("DB_PASSWORD");
        if (password == null) {
            System.err.println("Задайте DB_PASSWORD. Инструкция по запуску — README.md.");
            return;
        }
        var db = new DatabaseManager(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/hospital?connectTimeout=5&socketTimeout=15&options=-c%20TimeZone=Europe/Moscow"),
                System.getenv().getOrDefault("DB_USER", "hospital"), password);
        try {
            db.initialize();
            if (args.length > 0) {
                if (!args[0].equals("--seed") && !args[0].equals("--init-admin")) {
                    System.out.println("Параметры: --seed (демоданные) или --init-admin (пустая база с администратором)."); return;
                }
                DemoData.initialize(db, args[0].equals("--seed"), System.getenv("ADMIN_PASSWORD"));
                System.out.println(args[0].equals("--seed") ? "Созданы 5 пользователей и 12 записей. Логины: admin, patient1–patient4. Пароль: DemoPass123!" : "Создан администратор admin.");
                return;
            }
            var repository = new UserRepository(db);
            if (repository.list().isEmpty()) { System.out.println("Сначала запустите приложение с --seed или --init-admin. См. README.md."); return; }
            var users = new UserService(repository);
            var appointments = new AppointmentService(new JdbcAppointmentRepository(db), users, Clock.system(ZoneId.of("Europe/Moscow")));
            new ConsoleUi(users, appointments, db).run();
        } catch (BusinessException | DataAccessException e) { System.err.println("Ошибка: " + e.getMessage()); }
    }
}
