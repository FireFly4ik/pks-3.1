package ru.mirea.hospital.web.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Безопасный формат ошибки API.
 *
 * <p>Клиент получает только предусмотренные поля ошибки. Stack trace, SQL и имена
 * внутренних классов в ответ не включаются.</p>
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        Map<String, String> validationErrors) {
}
