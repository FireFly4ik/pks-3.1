package ru.mirea.hospital.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mirea.hospital.web.dto.ApiError;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Запрещает использовать временный пароль как постоянную рабочую сессию. */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED = Set.of(
            "/api/auth/me",
            "/api/auth/change-password",
            "/api/auth/change-initial-password",
            "/api/auth/logout",
            "/api/auth/refresh");
    private final ObjectMapper objectMapper;

    public PasswordChangeRequiredFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (request.getRequestURI().startsWith("/api/") && !ALLOWED.contains(request.getRequestURI())
                && authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.passwordChangeRequired()) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), 403,
                    "PASSWORD_CHANGE_REQUIRED", "Необходимо заменить временный пароль",
                    request.getRequestURI(), response.getHeader("X-Correlation-ID"), Map.of()));
            return;
        }
        chain.doFilter(request, response);
    }
}
