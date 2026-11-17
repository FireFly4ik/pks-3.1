package ru.mirea.hospital.web.security;

import org.junit.jupiter.api.Test;
import ru.mirea.hospital.web.error.BadRequestException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверяет минимальную серверную политику паролей. */
class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsStrongPassword() {
        assertThatCode(() -> policy.validate("Correct-Horse-2026!", "editor@example.edu"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPasswordContainingLogin() {
        assertThatThrownBy(() -> policy.validate("Editor-Strong-2026!", "editor@example.edu"))
                .isInstanceOf(BadRequestException.class);
    }
}
