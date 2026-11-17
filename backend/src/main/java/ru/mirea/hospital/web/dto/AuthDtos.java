package ru.mirea.hospital.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.mirea.hospital.web.domain.Role;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** DTO-контракты аутентификации. */
public final class AuthDtos {
    private AuthDtos() {
    }

    /** Тип клиента определяет способ доставки refresh-токена. */
    public enum ClientType { WEB, NATIVE }

    public record RegisterRequest(
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    /** Запрос входа. */
    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            ClientType clientType) {
        public ClientType effectiveClientType() {
            return clientType == null ? ClientType.NATIVE : clientType;
        }
    }

    /** Запрос ротации native refresh-токена. */
    public record RefreshRequest(@Size(max = 512) String refreshToken) {
    }

    /** Запрос безопасной смены пароля. */
    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {
    }

    /**
     * Запрос первичной смены временного пароля.
     *
     * <p>Текущий пароль повторно не передаётся: пользователь уже прошёл вход с временным
     * паролем и имеет действующий access JWT.</p>
     */
    public record InitialPasswordChangeRequest(
            @NotBlank @Size(max = 128) String newPassword) {
    }

    /** Публичная информация об авторизованном пользователе. */
    public record CurrentUser(
            UUID id,
            String email,
            String displayName,
            Set<Role> roles,
            boolean passwordChangeRequired) {
    }

    /** Результат входа или refresh. */
    public record AuthResponse(
            String accessToken,
            Instant accessExpiresAt,
            String refreshToken,
            Instant refreshExpiresAt,
            CurrentUser user) {
    }
}
