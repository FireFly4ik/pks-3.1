package ru.mirea.hospital.web.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.mirea.hospital.web.domain.UserAccount;
import ru.mirea.hospital.web.error.AuthenticationFailedException;
import ru.mirea.hospital.web.repository.UserAccountRepository;

/** Получает текущего пользователя в сервисном слое. */
@Component
public class CurrentUserService {
    private final UserAccountRepository users;

    public CurrentUserService(UserAccountRepository users) {
        this.users = users;
    }

    /**
     * Загружает актуальную сущность, а не доверяет устаревшим данным из access JWT.
     */
    public UserAccount requireEntity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new AuthenticationFailedException();
        }
        return users.findById(principal.id()).filter(UserAccount::isEnabled)
                .orElseThrow(AuthenticationFailedException::new);
    }

    public UserPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new AuthenticationFailedException();
        }
        return principal;
    }
}
