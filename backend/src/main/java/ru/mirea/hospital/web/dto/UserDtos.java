package ru.mirea.hospital.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import ru.mirea.hospital.web.domain.Role;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** DTO-контракты административного управления пользователями. */
public final class UserDtos {
    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 128) String temporaryPassword,
            @NotEmpty Set<Role> roles) {
    }

    public record UpdateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 160) String displayName,
            boolean enabled,
            @NotEmpty Set<Role> roles,
            long version) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 128) String temporaryPassword) {
    }

    public record UserResponse(
            UUID id,
            String email,
            String displayName,
            boolean enabled,
            boolean passwordChangeRequired,
            Set<Role> roles,
            Instant lockedUntil,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }
}
