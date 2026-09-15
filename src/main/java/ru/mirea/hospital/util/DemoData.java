package ru.mirea.hospital.util;

import ru.mirea.hospital.exception.BusinessException;
import java.sql.*;
import java.time.*;

public final class DemoData {
    private DemoData() {}
    public static void initialize(DatabaseManager db, boolean demo, String adminPassword) {
        if (!demo && (adminPassword == null || adminPassword.length() < 8 || adminPassword.length() > 128))
            throw new BusinessException("Для --init-admin задайте ADMIN_PASSWORD (8–128 символов).");
        try (var c = db.connect()) {
            c.setAutoCommit(false);
            try {
                try (var lock = c.prepareStatement("LOCK TABLE users IN EXCLUSIVE MODE")) { lock.execute(); }
                try (var s = c.prepareStatement("SELECT count(*) FROM users"); var r = s.executeQuery()) {
                    r.next(); if (r.getLong(1) != 0) throw new BusinessException("Инициализация доступна только для пустой базы пользователей.");
                }
                String[] names = {"Администратор клиники", "Иванов Иван Иванович", "Петрова Анна Сергеевна", "Сидоров Пётр Олегович", "Смирнова Мария Игоревна"};
                long[] ids = new long[5];
                for (int i = 0; i < (demo ? 5 : 1); i++) {
                    try (var s = c.prepareStatement("INSERT INTO users(login, full_name, password_hash, role) VALUES (?, ?, ?, ?) RETURNING id")) {
                        s.setString(1, i == 0 ? "admin" : "patient" + i); s.setString(2, names[i]);
                        s.setString(3, PasswordHasher.hash(demo ? "DemoPass123!" : adminPassword));
                        s.setString(4, i == 0 ? "ADMIN" : "PATIENT");
                        try (var r = s.executeQuery()) { r.next(); ids[i] = r.getLong(1); }
                    }
                }
                if (demo) {
                    LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));
                    for (int i = 0; i < 12; i++) {
                        LocalDate date = today.plusDays(i < 4 ? -14 + i : i + 1);
                        while (date.getDayOfWeek().getValue() > 5) date = date.plusDays(1);
                        try (var s = c.prepareStatement("INSERT INTO appointments(patient_id, doctor_id, starts_at, created_at, status, reschedule_count) VALUES (?, ?, ?, ?, ?, ?)")) {
                            s.setLong(1, ids[1 + i % 4]); s.setLong(2, 1 + i % 4);
                            s.setObject(3, date.atTime(9 + i % 7, (i % 2) * 30));
                            s.setObject(4, today.minusDays(30 - i).atTime(10, 0));
                            s.setString(5, i < 4 ? "COMPLETED" : i < 7 ? "CANCELLED" : "UPCOMING");
                            s.setInt(6, i == 8 ? 2 : i == 9 ? 1 : 0); s.executeUpdate();
                        }
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
}
