package ru.mirea.hospital.service;

import ru.mirea.hospital.model.*;
import ru.mirea.hospital.repository.UserRepository;
import ru.mirea.hospital.exception.BusinessException;
import ru.mirea.hospital.util.PasswordHasher;
import java.util.*;

public class UserService {
    private final UserRepository users;
    public UserService(UserRepository users) { this.users = users; }
    public User register(String login, String fullName, String password) {
        login = login.strip().toLowerCase(Locale.ROOT);
        if (!login.matches("[a-z0-9_.-]{3,40}")) throw new BusinessException("Логин: 3–40 латинских букв, цифр или символов _ . -");
        validateName(fullName);
        if (password.length() < 8 || password.length() > 128) throw new BusinessException("Пароль должен содержать от 8 до 128 символов.");
        return users.create(login, fullName.strip(), PasswordHasher.hash(password), Role.PATIENT);
    }
    public User login(String login, String password) {
        String normalized = login.strip().toLowerCase(Locale.ROOT);
        String hash = users.passwordHash(normalized).orElseThrow(() -> new BusinessException("Неверный логин или пароль."));
        if (!PasswordHasher.verify(password, hash)) throw new BusinessException("Неверный логин или пароль.");
        return users.list().stream().filter(u -> u.getLogin().equals(normalized)).findFirst().orElseThrow();
    }
    public User requireUser(User actor) {
        if (actor == null) throw new BusinessException("Необходимо войти в систему.");
        User user = users.find(actor.getId());
        if (!user.isActive()) throw new BusinessException("Учётная запись заблокирована.");
        return user;
    }
    public void requireAdmin(User actor) {
        if (requireUser(actor).getRole() != Role.ADMIN) throw new BusinessException("Действие доступно только администратору.");
    }
    public List<User> list(User actor) { requireAdmin(actor); return users.list(); }
    public void rename(User actor, long id, String fullName) {
        requireAdmin(actor); validateName(fullName); users.rename(id, fullName.strip());
    }
    public void delete(User actor, long id) {
        requireAdmin(actor);
        if (users.find(id).getRole() == Role.ADMIN) throw new BusinessException("Удаление администраторов запрещено.");
        users.delete(id);
    }
    private void validateName(String name) {
        if (name == null || name.strip().length() < 2 || name.strip().length() > 120 || name.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessException("ФИО должно содержать от 2 до 120 символов без управляющих символов.");
    }
}
