package ru.mirea.hospital.web.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mirea.hospital.web.dto.ApiError;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Обрабатывает системные HTTP-ошибки.
 *
 * <p>Браузер получает HTML-страницу ошибки, а REST-клиенты — JSON в формате
 * {@link ApiError}. JavaFX и Android поэтому не зависят от HTML-представления.</p>
 */
@RestController
public class AppErrorController implements ErrorController {

    private static final Set<Integer> DEDICATED_HTML_CODES = Set.of(
            400, 401, 403, 404, 405, 408, 409, 410, 413, 415, 422, 429,
            500, 502, 503, 504
    );

    @RequestMapping("/error")
    public ResponseEntity<?> error(HttpServletRequest request) {
        int status = resolveStatus(request);
        String originalPath = resolveOriginalPath(request);

        if (wantsHtml(request, originalPath)) {
            String page = pageFor(status);
            Resource resource = new ClassPathResource("static/errors/" + page + ".html");

            return ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_HTML)
                    .cacheControl(CacheControl.noStore())
                    .body(resource);
        }

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(
                        Instant.now(),
                        status,
                        errorCode(status),
                        message(status),
                        originalPath,
                        MDC.get("correlationId"),
                        Map.of()
                ));
    }

    /** Извлекает HTTP-код из атрибутов error-dispatch. */
    private int resolveStatus(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer integer) {
            return integer;
        }

        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return 500;
        }
    }

    /** Возвращает исходный URL запроса, а не служебный {@code /error}. */
    private String resolveOriginalPath(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return value == null ? request.getRequestURI() : String.valueOf(value);
    }

    /**
     * Browser page navigation обычно отправляет Accept:text/html.
     * Для {@code /api/**} при любых заголовках используется JSON.
     */
    private boolean wantsHtml(HttpServletRequest request, String originalPath) {
        if (originalPath != null && originalPath.startsWith("/api/")) {
            return false;
        }

        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }

    /** Выбирает универсальную страницу для неизвестного кода 4xx/5xx. */
    private String pageFor(int status) {
        if (DEDICATED_HTML_CODES.contains(status)) {
            return String.valueOf(status);
        }
        if (status >= 400 && status < 500) {
            return "4xx";
        }
        return "5xx";
    }

    private String errorCode(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 408 -> "REQUEST_TIMEOUT";
            case 409 -> "CONFLICT";
            case 410 -> "GONE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 422 -> "UNPROCESSABLE_ENTITY";
            case 429 -> "TOO_MANY_REQUESTS";
            case 502 -> "BAD_GATEWAY";
            case 503 -> "SERVICE_UNAVAILABLE";
            case 504 -> "GATEWAY_TIMEOUT";
            default -> status >= 500 ? "INTERNAL_ERROR" : "CLIENT_ERROR";
        };
    }

    private String message(int status) {
        return switch (status) {
            case 400 -> "Некорректный запрос";
            case 401 -> "Требуется авторизация";
            case 403 -> "Недостаточно прав для операции";
            case 404 -> "Ресурс не найден";
            case 405 -> "HTTP-метод не поддерживается";
            case 408 -> "Истекло время ожидания запроса";
            case 409 -> "Конфликт данных";
            case 410 -> "Ресурс больше недоступен";
            case 413 -> "Запрос слишком большой";
            case 415 -> "Неподдерживаемый формат данных";
            case 422 -> "Не удалось обработать данные";
            case 429 -> "Слишком много запросов";
            case 502 -> "Ошибка шлюза";
            case 503 -> "Сервис временно недоступен";
            case 504 -> "Превышено время ожидания шлюза";
            default -> status >= 500
                    ? "Внутренняя ошибка сервера"
                    : "Некорректный запрос";
        };
    }
}
