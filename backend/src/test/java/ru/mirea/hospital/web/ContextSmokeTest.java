package ru.mirea.hospital.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Поднимает полный Spring context на H2 и ловит ошибки wiring/конфигурации. */
@SpringBootTest
@ActiveProfiles("test")
class ContextSmokeTest {
    @Test
    void contextLoads() {
    }
}
