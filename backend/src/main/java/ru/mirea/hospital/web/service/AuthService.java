package ru.mirea.hospital.web.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mirea.hospital.web.config.AppProperties;
import ru.mirea.hospital.web.domain.RefreshSession;
import ru.mirea.hospital.web.domain.UserAccount;
import ru.mirea.hospital.web.dto.AuthDtos.AuthResponse;
import ru.mirea.hospital.web.dto.AuthDtos.ChangePasswordRequest;
import ru.mirea.hospital.web.dto.AuthDtos.InitialPasswordChangeRequest;
import ru.mirea.hospital.web.dto.AuthDtos.CurrentUser;
import ru.mirea.hospital.web.dto.AuthDtos.LoginRequest;
import ru.mirea.hospital.web.error.AuthenticationFailedException;
import ru.mirea.hospital.web.repository.RefreshSessionRepository;
import ru.mirea.hospital.web.repository.UserAccountRepository;
import ru.mirea.hospital.web.security.CurrentUserService;
import ru.mirea.hospital.web.security.JwtService;
import ru.mirea.hospital.web.security.PasswordPolicy;
import ru.mirea.hospital.web.web.RequestMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import ru.mirea.hospital.web.dto.AuthDtos.RegisterRequest;
import ru.mirea.hospital.web.error.ConflictException;

/**
 * Выполняет вход, ротацию refresh-токена, выход и смену пароля.
 */
@Service
public class AuthService {
    private final UserAccountRepository users;
    private final RefreshSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final LoginAttemptService attempts;
    private final JwtService jwtService;
    private final CurrentUserService currentUser;
    private final AppProperties properties;
    private final RequestMetadata metadata;
    private final AuditService audit;
    private final String dummyHash;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserAccountRepository users, RefreshSessionRepository sessions,
                       PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                       LoginAttemptService attempts, JwtService jwtService,
                       CurrentUserService currentUser, AppProperties properties,
                       RequestMetadata metadata, AuditService audit) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.attempts = attempts;
        this.jwtService = jwtService;
        this.currentUser = currentUser;
        this.properties = properties;
        this.metadata = metadata;
        this.audit = audit;
        // Валидный BCrypt-хеш используется и для неизвестного email, чтобы время проверки было сопоставимым.
        this.dummyHash = passwordEncoder.encode("constant-dummy-password-never-used");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        passwordPolicy.validate(request.password(), email);
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Этот email уже зарегистрирован. Войдите в аккаунт.");
        }

        UserAccount account = new UserAccount(UUID.randomUUID(), email, request.displayName().trim(),
                passwordEncoder.encode(request.password()), Set.of(ru.mirea.hospital.web.domain.Role.PATIENT));
        account.setPasswordChangeRequired(false);
        account = users.saveAndFlush(account);
        audit.record("USER_REGISTERED", "USER", account.getId(), "Самостоятельная регистрация", Map.of());
        return issueSession(account);
    }

    /** Проверяет пароль и создаёт новую серверную сессию. */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        UserAccount user = users.findByEmailIgnoreCase(email).orElse(null);
        // BCrypt-проверка выполняется и для неизвестного email, чтобы уменьшить различия по времени ответа.
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !user.isEnabled() || attempts.isLocked(user) || !passwordMatches) {
            if (user != null && user.isEnabled()) attempts.recordFailure(user.getId());
            throw new AuthenticationFailedException();
        }
        attempts.recordSuccess(user.getId());
        AuthResponse response = issueSession(user);
        audit.record("LOGIN_SUCCESS", "USER", user.getId(), "Успешный вход", Map.of("clientType", request.effectiveClientType().name()));
        return response;
    }

    /**
     * Одноразово использует refresh-токен и немедленно заменяет его новым.
     */
    @Transactional
    public AuthResponse refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) {
            throw new AuthenticationFailedException();
        }
        RefreshSession old = sessions.findByTokenHash(hash(rawToken)).orElseThrow(AuthenticationFailedException::new);
        Instant now = Instant.now();
        if (old.getRevokedAt() != null) {
            // Повторное предъявление уже заменённого токена считается подозрительным; активные refresh-сессии отзываются.
            if (old.getReplacedBy() != null) {
                sessions.revokeAllForUser(old.getUser().getId(), now);
                audit.record("REFRESH_REUSE_DETECTED", "USER", old.getUser().getId(),
                        "Обнаружено повторное использование refresh-токена", Map.of());
            }
            throw new AuthenticationFailedException();
        }
        if (!old.getExpiresAt().isAfter(now) || !old.getUser().isEnabled()) {
            old.setRevokedAt(now);
            throw new AuthenticationFailedException();
        }

        String nextRaw = randomToken();
        Instant nextExpiry = now.plus(properties.jwt().refreshDays(), ChronoUnit.DAYS);
        RefreshSession next = new RefreshSession(UUID.randomUUID(), old.getUser(), hash(nextRaw), nextExpiry,
                metadata.userAgent(), metadata.ipAddress());
        sessions.save(next);
        old.setRevokedAt(now);
        old.setLastUsedAt(now);
        old.setReplacedBy(next.getId());

        JwtService.IssuedAccessToken access = jwtService.issue(old.getUser());
        return new AuthResponse(access.token(), access.expiresAt(), nextRaw, nextExpiry, toCurrentUser(old.getUser()));
    }

    /** Отзывает предъявленный refresh-токен; операция идемпотентна. */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) return;
        sessions.findByTokenHash(hash(rawToken)).ifPresent(session -> {
            if (session.getRevokedAt() == null) session.setRevokedAt(Instant.now());
        });
    }

    /** Возвращает актуальные права текущего пользователя. */
    @Transactional(readOnly = true)
    public CurrentUser me() {
        return toCurrentUser(currentUser.requireEntity());
    }

    /**
     * Меняет постоянный пароль по явному подтверждению текущего пароля.
     *
     * <p>Пользователь сохраняется через {@code saveAndFlush} до отзыва refresh-сессий.
     * Такой порядок явно фиксирует изменение пароля перед инвалидированием сессий.</p>
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        UserAccount user = currentUser.requireEntity();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new AuthenticationFailedException();
        }

        applyNewPassword(user, request.newPassword());

        // После смены пароля сбрасываются прежние признаки блокировки.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        users.saveAndFlush(user);

        // После смены постоянного пароля все прежние refresh-сессии отзываются.
        sessions.revokeAllForUser(user.getId(), Instant.now());
        audit.record("PASSWORD_CHANGED", "USER", user.getId(), "Пользователь изменил пароль", Map.of());
    }

    /**
     * Заменяет одноразовый bootstrap-пароль сразу после успешного первого входа.
     *
     * <p>Повторно спрашивать текущий временный пароль здесь не нужно: пользователь уже
     * аутентифицирован им. Текущая сессия сохраняется, поэтому после первичной смены
     * пароля повторный вход не требуется.</p>
     */
    @Transactional
    public void changeInitialPassword(InitialPasswordChangeRequest request) {
        UserAccount user = currentUser.requireEntity();

        if (!user.isPasswordChangeRequired()) {
            throw new ru.mirea.hospital.web.error.BadRequestException(
                    "Первичная смена пароля для этой учётной записи уже выполнена");
        }

        applyNewPassword(user, request.newPassword());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);

        // Новый BCrypt-хеш и флаг сохраняются до возврата HTTP 204.
        users.saveAndFlush(user);

        audit.record("INITIAL_PASSWORD_CHANGED", "USER", user.getId(),
                "Пользователь заменил временный пароль", Map.of());
    }

    /** Общая проверка и установка нового BCrypt-пароля. */
    private void applyNewPassword(UserAccount user, String newPassword) {
        passwordPolicy.validate(newPassword, user.getEmail());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ru.mirea.hospital.web.error.BadRequestException(
                    "Новый пароль должен отличаться от текущего");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
    }

    private AuthResponse issueSession(UserAccount user) {
        JwtService.IssuedAccessToken access = jwtService.issue(user);
        String rawRefresh = randomToken();
        Instant refreshExpiry = Instant.now().plus(properties.jwt().refreshDays(), ChronoUnit.DAYS);
        sessions.save(new RefreshSession(UUID.randomUUID(), user, hash(rawRefresh), refreshExpiry,
                metadata.userAgent(), metadata.ipAddress()));
        return new AuthResponse(access.token(), access.expiresAt(), rawRefresh, refreshExpiry, toCurrentUser(user));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK", ex);
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static CurrentUser toCurrentUser(UserAccount user) {
        return new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName(), SetCopy.copy(user.getRoles()),
                user.isPasswordChangeRequired());
    }

    /** Маленький helper не позволяет случайно вернуть изменяемую Hibernate-коллекцию. */
    private static final class SetCopy {
        private SetCopy() { }
        private static <T> java.util.Set<T> copy(java.util.Set<T> value) { return java.util.Set.copyOf(value); }
    }
}
