package ru.mirea.hospital.web.error;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.mirea.hospital.web.dto.ApiError;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Преобразует исключения в HTTP-ответы API.
 *
 * <p>Для браузерной навигации используется HTML-страница ошибки, а запросы
 * {@code /api/**} сохраняют JSON-контракт {@link ApiError}.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Set<Integer> DEDICATED_HTML_CODES = Set.of(
            400, 401, 403, 404, 405, 408, 409, 410, 413, 415, 422, 429,
            500, 502, 503, 504
    );

    /**
     * Spring MVC 6 выбрасывает NoResourceFoundException для неизвестного web URL.
     *
     * <p>Отдельный обработчик нужен, чтобы неизвестный URL возвращал 404, а не попадал
     * в общий обработчик 500.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> noResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Страница или ресурс не найдены", request, Map.of());
    }

    /** Прикладная ошибка отсутствия сущности возвращается как 404. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> notFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, Map.of());
    }

    /** Неподдерживаемый HTTP-метод — это 405, а не внутренняя ошибка сервера. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> methodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                               HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "HTTP-метод не поддерживается", request, Map.of());
    }

    /** Неподдерживаемый Content-Type возвращается как 415. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> unsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                   HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Неподдерживаемый формат данных", request, Map.of());
    }

    /**
     * Ошибки синтаксиса JSON, обязательных query-параметров и преобразования типов —
     * клиентские 400, а не 500.
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<?> malformedRequest(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Некорректный запрос", request, Map.of());
    }

    @ExceptionHandler({ConflictException.class, OptimisticLockException.class, org.springframework.dao.OptimisticLockingFailureException.class})
    public ResponseEntity<?> conflict(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT",
                ex instanceof ConflictException
                        ? ex.getMessage()
                        : "Объект уже изменён другим пользователем",
                request, Map.of());
    }

    @ExceptionHandler({BadRequestException.class, ConstraintViolationException.class})
    public ResponseEntity<?> badRequest(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<?> authentication(AuthenticationFailedException ex,
                                             HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED",
                ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> accessDenied(AccessDeniedException ex,
                                          HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "Недостаточно прав для операции", request, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex,
                                        HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Проверьте заполнение полей", request, fields);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> integrity(DataIntegrityViolationException ex,
                                       HttpServletRequest request) {
        // Имя SQL constraint не возвращается клиенту, так как раскрывает внутреннюю схему БД.
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "Запись конфликтует с уже существующими данными", request, Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException ex,
                                      HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "Файл превышает допустимый размер", request, Map.of());
    }

    /**
     * Настоящие непредвиденные исключения остаются 500.
     * В серверном журнале сохраняется stack trace вместе с correlation ID,
     * а внутренние детали не включаются в ответ.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.error("Unhandled error, correlationId={}", correlationId, ex);

        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Внутренняя ошибка. Сообщите администратору correlation ID",
                request, Map.of());
    }

    /**
     * Выбирает HTML или JSON в зависимости от типа запроса.
     *
     * <p>/api/** всегда JSON даже если клиент по ошибке прислал Accept:text/html.</p>
     */
    private ResponseEntity<?> build(HttpStatus status,
                                    String code,
                                    String message,
                                    HttpServletRequest request,
                                    Map<String, String> fields) {
        if (wantsHtml(request)) {
            return html(status);
        }

        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                MDC.get("correlationId"),
                fields
        );

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * Browser navigation обычно отправляет Accept:text/html.
     * Рабочий REST API всегда остаётся JSON.
     */
    private boolean wantsHtml(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return false;
        }

        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }

    /** Отдаёт готовую статическую страницу с исходным HTTP status code. */
    private ResponseEntity<Resource> html(HttpStatus status) {
        int code = status.value();
        String page = DEDICATED_HTML_CODES.contains(code)
                ? String.valueOf(code)
                : code >= 400 && code < 500 ? "4xx" : "5xx";

        Resource resource = new ClassPathResource("static/errors/" + page + ".html");

        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(resource);
    }
}
