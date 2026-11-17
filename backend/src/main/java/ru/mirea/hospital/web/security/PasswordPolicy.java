package ru.mirea.hospital.web.security;

import org.springframework.stereotype.Component;
import ru.mirea.hospital.web.error.BadRequestException;

import java.util.Locale;

/** Правила проверки паролей. */
@Component
public class PasswordPolicy {

    /**
     * Проверяет длину и разнообразие символов без журналирования самого пароля.
     */
    public void validate(String password, String email) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new BadRequestException("Пароль должен содержать от 12 до 128 символов");
        }
        if (!password.chars().anyMatch(Character::isUpperCase)
                || !password.chars().anyMatch(Character::isLowerCase)
                || !password.chars().anyMatch(Character::isDigit)
                || password.chars().allMatch(Character::isLetterOrDigit)) {
            throw new BadRequestException("Пароль должен содержать верхний и нижний регистр, цифру и специальный символ");
        }
        String localPart = email == null ? "" : email.toLowerCase(Locale.ROOT).split("@", 2)[0];
        if (localPart.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(localPart)) {
            throw new BadRequestException("Пароль не должен содержать имя учётной записи");
        }
    }
}
