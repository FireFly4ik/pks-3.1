package ru.mirea.hospital.service;

import java.util.*;
import ru.mirea.hospital.exception.BusinessException;
import ru.mirea.hospital.model.*;
import ru.mirea.hospital.repository.UserRepository;
import ru.mirea.hospital.util.PasswordHasher;

public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public User register(String login, String fullName, String password) {
        validateRegistration(login, fullName, password);
        return users.create(
                login.strip().toLowerCase(Locale.ROOT),
                fullName.strip(),
                PasswordHasher.hash(password),
                Role.PATIENT);
    }

    public static void validateRegistration(String login, String fullName, String password) {
        if (login == null || login.isBlank())
            throw new BusinessException(
                    "Поле «Логин» не заполнено. Введите отдельный логин, например gg_wp. ФИО указывается в поле «ФИО для регистрации».");
        String normalized = login.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 40)
            throw new BusinessException(
                    "Поле «Логин»: нужно от 3 до 40 символов. Например: gg_wp.");
        if (!normalized.matches("[a-z0-9_.-]+"))
            throw new BusinessException(
                    "Поле «Логин»: разрешены только латинские буквы A–Z, цифры и символы _ . - без пробелов. Например: gg_wp. Русское ФИО введите в поле «ФИО для регистрации».");
        validateName(fullName);
        if (password == null || password.isEmpty())
            throw new BusinessException(
                    "Поле «Пароль» не заполнено. Придумайте пароль длиной от 8 до 128 символов.");
        if (password.length() < 8 || password.length() > 128)
            throw new BusinessException("Поле «Пароль»: нужно от 8 до 128 символов.");
    }

    public User login(String login, String password) {
        String normalized = login.strip().toLowerCase(Locale.ROOT);
        String hash =
                users.passwordHash(normalized)
                        .orElseThrow(() -> new BusinessException("Неверный логин или пароль."));
        if (!PasswordHasher.verify(password, hash))
            throw new BusinessException("Неверный логин или пароль.");
        return users.list().stream()
                .filter(u -> u.getLogin().equals(normalized))
                .findFirst()
                .orElseThrow();
    }

    public User requireUser(User actor) {
        if (actor == null) throw new BusinessException("Необходимо войти в систему.");
        User user = users.find(actor.getId());
        if (!user.isActive()) throw new BusinessException("Учётная запись заблокирована.");
        return user;
    }

    public void requireAdmin(User actor) {
        if (requireUser(actor).getRole() != Role.ADMIN)
            throw new BusinessException("Действие доступно только администратору.");
    }

    public List<User> list(User actor) {
        requireAdmin(actor);
        return users.list();
    }

    public void rename(User actor, long id, String fullName) {
        requireAdmin(actor);
        validateName(fullName);
        users.rename(id, fullName.strip());
    }

    public void delete(User actor, long id) {
        requireAdmin(actor);
        if (users.find(id).getRole() == Role.ADMIN)
            throw new BusinessException("Удаление администраторов запрещено.");
        users.delete(id);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank())
            throw new BusinessException(
                    "Поле «ФИО» не заполнено. Введите имя, например Иванов Иван Иванович. Русские буквы и пробелы разрешены.");
        if (name.strip().length() < 2
                || name.strip().length() > 120
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessException(
                    "Поле «ФИО»: нужно от 2 до 120 символов без переносов строк, табуляции и других управляющих символов. Русские буквы и пробелы разрешены.");
    }
}
