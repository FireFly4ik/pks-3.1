package ru.mirea.hospital.repository;

import java.sql.*;
import java.util.*;
import ru.mirea.hospital.exception.EntityNotFoundException;
import ru.mirea.hospital.model.*;
import ru.mirea.hospital.util.DatabaseManager;

public class UserRepository {
    private final DatabaseManager db;

    public UserRepository(DatabaseManager db) {
        this.db = db;
    }

    public User create(String login, String fullName, String hash, Role role) {
        String sql =
                "INSERT INTO users(login, full_name, password_hash, role) VALUES (?, ?, ?, ?) RETURNING *";
        try (var c = db.connect();
                var s = c.prepareStatement(sql)) {
            s.setString(1, login);
            s.setString(2, fullName);
            s.setString(3, hash);
            s.setString(4, role.name());
            try (var r = s.executeQuery()) {
                r.next();
                return map(r);
            }
        } catch (SQLException e) {
            throw DatabaseManager.failure(e);
        }
    }

    public Optional<String> passwordHash(String login) {
        String sql = "SELECT password_hash FROM users WHERE login = ? AND active";
        try (var c = db.connect();
                var s = c.prepareStatement(sql)) {
            s.setString(1, login);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw DatabaseManager.failure(e);
        }
    }

    public User find(long id) {
        return list().stream()
                .filter(u -> u.getId() == id)
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Пользователь"));
    }

    public List<User> list() {
        String sql = "SELECT * FROM users ORDER BY id";
        try (var c = db.connect();
                var s = c.prepareStatement(sql);
                var r = s.executeQuery()) {
            List<User> users = new ArrayList<>();
            while (r.next()) users.add(map(r));
            return users;
        } catch (SQLException e) {
            throw DatabaseManager.failure(e);
        }
    }

    public void rename(long id, String fullName) {
        String sql = "UPDATE users SET full_name = ? WHERE id = ?";
        try (var c = db.connect();
                var s = c.prepareStatement(sql)) {
            s.setString(1, fullName);
            s.setLong(2, id);
            if (s.executeUpdate() == 0) throw new EntityNotFoundException("Пользователь");
        } catch (SQLException e) {
            throw DatabaseManager.failure(e);
        }
    }

    public void delete(long id) {
        String sql = "DELETE FROM users WHERE id = ?";
        try (var c = db.connect();
                var s = c.prepareStatement(sql)) {
            s.setLong(1, id);
            if (s.executeUpdate() == 0) throw new EntityNotFoundException("Пользователь");
        } catch (SQLException e) {
            throw DatabaseManager.failure(e);
        }
    }

    private User map(ResultSet r) throws SQLException {
        return new User(
                r.getLong("id"),
                r.getString("login"),
                r.getString("full_name"),
                Role.valueOf(r.getString("role")),
                r.getBoolean("active"));
    }
}
