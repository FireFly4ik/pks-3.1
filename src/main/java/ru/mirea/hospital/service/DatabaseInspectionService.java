package ru.mirea.hospital.service;

import ru.mirea.hospital.model.User;
import ru.mirea.hospital.repository.DatabaseInspectionRepository;
import ru.mirea.hospital.util.DatabaseManager;
import java.util.*;

public class DatabaseInspectionService {
    private final UserService users;
    private final DatabaseInspectionRepository repository;
    public DatabaseInspectionService(UserService users, DatabaseManager db) {
        this.users = users; this.repository = new DatabaseInspectionRepository(db);
    }
    public Map<String, List<String>> tables(User actor) {
        users.requireAdmin(actor); return repository.tables();
    }
}
