package ru.mirea.hospital.web.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.hospital.web.domain.Role;
import ru.mirea.hospital.web.domain.UserAccount;
import ru.mirea.hospital.web.dto.UserDtos.CreateUserRequest;
import ru.mirea.hospital.web.dto.UserDtos.ResetPasswordRequest;
import ru.mirea.hospital.web.dto.UserDtos.UpdateUserRequest;
import ru.mirea.hospital.web.dto.UserDtos.UserResponse;
import ru.mirea.hospital.web.error.ConflictException;
import ru.mirea.hospital.web.error.NotFoundException;
import ru.mirea.hospital.web.repository.RefreshSessionRepository;
import ru.mirea.hospital.web.repository.UserAccountRepository;
import ru.mirea.hospital.web.security.CurrentUserService;
import ru.mirea.hospital.web.security.PasswordPolicy;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Административное управление пользователями без раскрытия хешей паролей. */
@Service
public class UserAdminService {
    private final UserAccountRepository users;
    private final RefreshSessionRepository sessions;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final CurrentUserService currentUser;
    private final AuditService audit;

    public UserAdminService(UserAccountRepository users, RefreshSessionRepository sessions,
                            PasswordEncoder encoder, PasswordPolicy policy,
                            CurrentUserService currentUser, AuditService audit) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
        this.policy = policy;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> list(Pageable pageable) {
        return users.findAll(pageable).map(UserAdminService::toResponse);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmailIgnoreCase(email)) throw new ConflictException("Пользователь с таким email уже существует");
        policy.validate(request.temporaryPassword(), email);
        UserAccount user = new UserAccount(UUID.randomUUID(), email, request.displayName().trim(),
                encoder.encode(request.temporaryPassword()), normalizeRoles(request.roles()));
        user.setPasswordChangeRequired(true);
        user = users.saveAndFlush(user);
        audit.record("USER_CREATED", "USER", user.getId(), "Создан пользователь " + email, Map.of("roles", user.getRoles()));
        users.flush();
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        UserAccount user = require(id);
        if (request.version() != user.getVersion()) throw new ConflictException("Пользователь уже изменён. Обновите список");
        String email = normalizeEmail(request.email());
        users.findByEmailIgnoreCase(email).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new ConflictException("Пользователь с таким email уже существует");
        });
        Set<Role> roles = normalizeRoles(request.roles());
        UserAccount actor = currentUser.requireEntity();
        if (actor.getId().equals(id) && (!request.enabled() || !roles.contains(Role.ADMIN))) {
            throw new AccessDeniedException("Администратор не может отключить себя или снять свою последнюю роль ADMIN");
        }
        user.setEmail(email);
        user.setDisplayName(request.displayName().trim());
        user.setEnabled(request.enabled());
        user.setRoles(roles);
        if (!request.enabled()) sessions.revokeAllForUser(id, Instant.now());
        audit.record("USER_UPDATED", "USER", id, "Изменён пользователь " + email, Map.of("roles", roles, "enabled", request.enabled()));
        users.flush();
        return toResponse(user);
    }

    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest request) {
        UserAccount user = require(id);
        policy.validate(request.temporaryPassword(), user.getEmail());
        user.setPasswordHash(encoder.encode(request.temporaryPassword()));
        user.setPasswordChangeRequired(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        sessions.revokeAllForUser(id, Instant.now());
        audit.record("USER_PASSWORD_RESET", "USER", id, "Администратор сбросил пароль пользователя", Map.of());
    }

    private UserAccount require(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("Пользователь не найден"));
    }

    private static Set<Role> normalizeRoles(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) throw new ConflictException("У пользователя должна быть хотя бы одна роль");
        return Set.copyOf(roles);
    }

    private static UserResponse toResponse(UserAccount user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.isEnabled(),
                user.isPasswordChangeRequired(), Set.copyOf(user.getRoles()), user.getLockedUntil(),
                user.getCreatedAt(), user.getUpdatedAt(), user.getVersion());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
