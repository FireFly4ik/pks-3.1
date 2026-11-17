package ru.mirea.hospital.web.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mirea.hospital.web.repository.UserAccountRepository;

import java.io.IOException;
import java.util.UUID;

/**
 * Создаёт SecurityContext из access JWT.
 *
 * <p>Обычный REST API принимает JWT из заголовка Authorization. Для Swagger UI
 * дополнительно поддерживается отдельный HttpOnly cookie, выдаваемый web-администратору.
 * Для остальных API-вызовов этот cookie не используется.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserAccountRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, UserAccountRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    private static final String SWAGGER_COOKIE = "hospital_swagger";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = bearerToken(request);

            /*
             * При обычной навигации браузер не добавляет Authorization Bearer
             * к запросу /swagger-ui.html. Поэтому для Swagger-раздела поддерживается
             * отдельный HttpOnly cookie с тем же подписанным access JWT.
             */
            if (token == null && isSwaggerRequest(request)) {
                token = cookieValue(request, SWAGGER_COOKIE);
            }

            authenticate(token);
        }

        filterChain.doFilter(request, response);
    }

    /** Возвращает access JWT из Authorization, не доверяя другим схемам аутентификации. */
    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ")
                ? header.substring(7)
                : null;
    }

    /** Swagger cookie используется исключительно на Swagger/OpenAPI URL. */
    private boolean isSwaggerRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals("/swagger-ui.html")
                || uri.startsWith("/swagger-ui/")
                || uri.equals("/v3/api-docs")
                || uri.startsWith("/v3/api-docs/");
    }

    /** Безопасно извлекает нужный cookie, если браузер его прислал. */
    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }

        return null;
    }

    /**
     * Проверяет подпись/issuer/expiration JWT, а роли всегда перечитывает из БД.
     *
     * <p>Для Swagger-cookie роль пользователя проверяется при каждом запросе: если администратору снимут роль ADMIN,
     * старый подписанный cookie сам по себе не сохранит административный доступ.</p>
     */
    private void authenticate(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        try {
            Claims claims = jwtService.parse(token);
            UUID userId = UUID.fromString(claims.getSubject());

            users.findById(userId)
                    .filter(user -> user.isEnabled())
                    .ifPresent(user -> {
                        UserPrincipal principal = UserPrincipal.from(user);
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        principal,
                                        null,
                                        principal.getAuthorities()
                                );
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        } catch (RuntimeException ignored) {
            // Единый AuthenticationEntryPoint ниже по цепочке сформирует одинаковый 401.
            SecurityContextHolder.clearContext();
        }
    }
}
