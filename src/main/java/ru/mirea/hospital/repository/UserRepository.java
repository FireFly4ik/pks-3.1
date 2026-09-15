package ru.mirea.hospital.repository;

import ru.mirea.hospital.model.*;
import ru.mirea.hospital.util.DatabaseManager;
import ru.mirea.hospital.exception.EntityNotFoundException;
import java.sql.*;
import java.util.*;

public class UserRepository {
    private final DatabaseManager db;
    public UserRepository(DatabaseManager db) { this.db = db; }

    public User create(String login, String fullName, String hash, Role role) {
        try (var c = db.connect(); var s = c.prepareStatement(
                "INSERT INTO users(login, full_name, password_hash, role) VALUES (?, ?, ?, ?) RETURNING *")) {
            s.setString(1, login); s.setString(2, fullName); s.setString(3, hash); s.setString(4, role.name());
            try (var r = s.executeQuery()) { r.next(); return map(r); }
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    public Optional<String> passwordHash(String login) {
        try (var c = db.connect(); var s = c.prepareStatement("SELECT password_hash FROM users WHERE login = ? AND active")) {
            s.setString(1, login);
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(r.getString(1)) : Optional.empty(); }
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    public User find(long id) {
        return list().stream().filter(u -> u.getId() == id).findFirst().orElseThrow(() -> new EntityNotFoundException("Пользователь"));
    }
    public List<User> list() {
        try (var c = db.connect(); var s = c.prepareStatement("SELECT * FROM users ORDER BY id"); var r = s.executeQuery()) {
            List<User> users = new ArrayList<>();
            while (r.next()) users.add(map(r));
            return users;
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    public void rename(long id, String fullName) {
        try (var c = db.connect(); var s = c.prepareStatement("UPDATE users SET full_name = ? WHERE id = ?")) {
            s.setString(1, fullName); s.setLong(2, id);
            if (s.executeUpdate() == 0) throw new EntityNotFoundException("Пользователь");
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    public void delete(long id) {
        try (var c = db.connect(); var s = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            s.setLong(1, id);
            if (s.executeUpdate() == 0) throw new EntityNotFoundException("Пользователь");
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
    }
    private User map(ResultSet r) throws SQLException {
        return new User(r.getLong("id"), r.getString("login"), r.getString("full_name"),
                Role.valueOf(r.getString("role")), r.getBoolean("active"));
    }
}
