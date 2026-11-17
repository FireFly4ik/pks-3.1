package ru.mirea.hospital.web.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.List;

/**
 * Типобезопасная конфигурация приложения.
 *
 * <p>Параметры приложения собираются в одном объекте конфигурации. Это упрощает
 * валидацию обязательных настроек и не дублирует чтение переменных окружения в сервисах.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid Jwt jwt,
        @Valid Bootstrap bootstrap,
        @Valid Cors cors,
        boolean cookieSecure,
        boolean permanentDeleteEnabled,
        @Valid ImportLimits importLimits) {

    /** Настройки JWT и срока жизни сессии. */
    public record Jwt(
            @NotBlank String secretBase64,
            @NotBlank String issuer,
            @Min(1) long accessMinutes,
            @Min(1) long refreshDays) {
    }

    /** Данные одноразового bootstrap-администратора. */
    public record Bootstrap(String email, String name, String password) {
    }

    /** Белый список origin для браузерных клиентов. */
    public record Cors(String allowedOrigins) {
        /**
         * Возвращает нормализованный список origin без пустых элементов.
         *
         * @return разрешённые origin
         */
        public List<String> asList() {
            if (allowedOrigins == null || allowedOrigins.isBlank()) {
                return List.of();
            }
            return Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
    }

    /** Ограничения защищённого CSV-импорта. */
    public record ImportLimits(@Min(1) int maxRows) {
    }
}
