package ru.mirea.hospital;

import org.junit.jupiter.api.Test;
import ru.mirea.hospital.util.PasswordHasher;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test void saltsDifferAndPasswordsAreVerified() {
        String first = PasswordHasher.hash("Пароль123!");
        String second = PasswordHasher.hash("Пароль123!");
        assertNotEquals(first, second);
        assertTrue(PasswordHasher.verify("Пароль123!", first));
        assertFalse(PasswordHasher.verify("ДругойПароль", first));
    }
}
