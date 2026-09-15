package ru.mirea.hospital.repository;

import ru.mirea.hospital.util.DatabaseManager;
import java.sql.SQLException;
import java.util.*;


public class DatabaseInspectionRepository {
    private final DatabaseManager db;
    public DatabaseInspectionRepository(DatabaseManager db) { this.db = db; }
    public Map<String, List<String>> tables() {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("users (без хешей паролей)", "SELECT id, login, full_name, role, active FROM users ORDER BY id");
        queries.put("doctors", "SELECT id, full_name, specialty FROM doctors ORDER BY id");
        queries.put("appointments", "SELECT id, patient_id, doctor_id, starts_at, created_at, status, reschedule_count FROM appointments ORDER BY id");
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (var c = db.connect()) {
            for (var query : queries.entrySet()) {
                try (var s = c.prepareStatement(query.getValue()); var r = s.executeQuery()) {
                    var metadata = r.getMetaData();
                    List<String> rows = new ArrayList<>();
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) values.add(metadata.getColumnLabel(i));
                    rows.add(String.join(" | ", values));
                    while (r.next()) {
                        values = new ArrayList<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) values.add(r.getString(i));
                        rows.add(String.join(" | ", values));
                    }
                    result.put(query.getKey(), rows);
                }
            }
        } catch (SQLException e) { throw DatabaseManager.failure(e); }
        return result;
    }
}
