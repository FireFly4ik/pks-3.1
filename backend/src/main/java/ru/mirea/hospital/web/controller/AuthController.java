package ru.mirea.hospital.web.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mirea.hospital.web.config.AppProperties;
import ru.mirea.hospital.web.dto.AuthDtos.AuthResponse;
import ru.mirea.hospital.web.dto.AuthDtos.ChangePasswordRequest;
import ru.mirea.hospital.web.dto.AuthDtos.InitialPasswordChangeRequest;
import ru.mirea.hospital.web.dto.AuthDtos.ClientType;
import ru.mirea.hospital.web.dto.AuthDtos.CurrentUser;
import ru.mirea.hospital.web.dto.AuthDtos.LoginRequest;
import ru.mirea.hospital.web.dto.AuthDtos.RefreshRequest;
import ru.mirea.hospital.web.domain.Role;
import ru.mirea.hospital.web.service.AuthService;

import java.time.Duration;
import java.time.Instant;

/** REST endpoints аутентификации для web и native-клиентов. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "hospital_refresh";
    private static final String SWAGGER_COOKIE = "hospital_swagger";
    private final AuthService auth;
    private final AppProperties properties;

    public AuthController(AuthService auth, AppProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    /** В web-режиме помещает refresh-токен только в HttpOnly cookie. */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthResponse issued = auth.login(request);
        if (request.effectiveClientType() == ClientType.WEB) {
            setRefreshCookie(response, issued.refreshToken(), issued.refreshExpiresAt());
            syncSwaggerCookie(response, issued);
            return withoutRefreshToken(issued);
        }
        return issued;
    }

    /** Ротирует refresh-токен из cookie либо native JSON-body. */
    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody(required = false) RefreshRequest request,
                                @CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
                                HttpServletResponse response) {
        boolean web = cookie != null && !cookie.isBlank();
        String raw = web ? cookie : request == null ? null : request.refreshToken();
        AuthResponse issued = auth.refresh(raw);
        if (web) {
            setRefreshCookie(response, issued.refreshToken(), issued.refreshExpiresAt());
            syncSwaggerCookie(response, issued);
            return withoutRefreshToken(issued);
        }
        return issued;
    }

    /** Отзывает серверную сессию и очищает cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request,
                                       @CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
                                       HttpServletResponse response) {
        auth.logout(cookie != null ? cookie : request == null ? null : request.refreshToken());
        clearRefreshCookie(response);
        clearSwaggerCookie(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public CurrentUser me() {
        return auth.me();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Первичная замена bootstrap-пароля после уже успешной аутентификации.
     * Текущий пароль повторно не передаётся и не хранится клиентом.
     */
    @PostMapping("/change-initial-password")
    public ResponseEntity<Void> changeInitialPassword(
            @Valid @RequestBody InitialPasswordChangeRequest request) {
        auth.changeInitialPassword(request);
        return ResponseEntity.noContent().build();
    }

    private void setRefreshCookie(HttpServletResponse response, String value, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Strict")
                .path("/api/auth").maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * Выдаёт отдельный Swagger-cookie только web-администратору с уже заменённым
     * временным паролем.
     *
     * <p>В cookie помещается короткоживущий подписанный access JWT с флагом HttpOnly.
     * JavaScript его прочитать не может. JwtAuthenticationFilter использует этот cookie
     * только для /swagger-ui/** и /v3/api-docs/**, но не для рабочих REST API.</p>
     */
    private void syncSwaggerCookie(HttpServletResponse response, AuthResponse issued) {
        boolean admin = issued.user().roles().contains(Role.ADMIN);
        boolean permanentPassword = !issued.user().passwordChangeRequired();

        if (!admin || !permanentPassword) {
            clearSwaggerCookie(response);
            return;
        }

        Duration maxAge = Duration.between(Instant.now(), issued.accessExpiresAt());

        ResponseCookie cookie = ResponseCookie.from(SWAGGER_COOKIE, issued.accessToken())
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** Очищает административный Swagger-cookie при выходе или смене роли/контекста. */
    private void clearSwaggerCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(SWAGGER_COOKIE, "")
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static AuthResponse withoutRefreshToken(AuthResponse source) {
        return new AuthResponse(source.accessToken(), source.accessExpiresAt(), null,
                source.refreshExpiresAt(), source.user());
    }
}
