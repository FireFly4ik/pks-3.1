package ru.mirea.hospital.util;

import ru.mirea.hospital.exception.DataAccessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;

public class DatabaseManager {
    private final String url;
    private final String username;
    private final String password;

    public DatabaseManager(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
    public void initialize() {
        try (var stream = DatabaseManager.class.getResourceAsStream("/schema.sql")) {
            if (stream == null) throw new IOException("Не найден schema.sql");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) { s.execute(); }
        } catch (SQLException | IOException e) { throw failure(e); }
    }
    public static DataAccessException failure(Exception e) {
        String message = "Ошибка базы данных. Проверьте доступность PostgreSQL и параметры DB_URL, DB_USER, DB_PASSWORD.";
        if (e instanceof SQLException sql) {
            String state = sql.getSQLState();
            if ("23505".equals(state)) message = "Логин уже занят или выбранное время уже забронировано.";
            else if ("23503".equals(state)) message = "Объект используется другими записями или больше не существует.";
            else if ("23514".equals(state)) message = "Данные нарушают ограничения базы данных.";
            else if (state != null && !state.startsWith("08") && !state.startsWith("28"))
                message = "Не удалось выполнить запрос к базе данных (SQLSTATE " + state + ").";
        }
        return new DataAccessException(message, e);
    }
}
