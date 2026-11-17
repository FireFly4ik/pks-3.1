package ru.mirea.hospital.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Точка входа серверного приложения Регистратура клиники.
 *
 * <p>Bootstrap-класс содержит только запуск Spring Boot. Бизнес-логика, безопасность
 * и инфраструктурный код вынесены в отдельные компоненты.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HospitalWebApplication {

    /**
     * Запускает Spring Boot и создаёт контекст приложения.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        SpringApplication.run(HospitalWebApplication.class, args);
    }
}
