package ru.mirea.hospital.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.mirea.hospital.web.dto.ApiError;
import ru.mirea.hospital.web.security.JwtAuthenticationFilter;
import ru.mirea.hospital.web.security.PasswordChangeRequiredFilter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Центральная конфигурация HTTP-безопасности. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost 12 подходит для интерактивного входа; при развёртывании значение стоит проверить на целевом сервере.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   ObjectMapper objectMapper,
                                                   PasswordChangeRequiredFilter passwordChangeFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/app.js",
                                "/styles.css",
                                "/favicon.svg",
                                "/error",
                                "/errors/**"
                        ).permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()

                        /*
                         * Остальные actuator endpoints не должны случайно стать публичными,
                         * когда неизвестные web-адреса передаются дальше в MVC для обычного 404.
                         */
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        /*
                         * Весь прикладной REST API остаётся закрытым.
                         *
                         * Для неизвестного /api/... неавторизованный клиент по-прежнему получает
                         * 401, а авторизованный — нормальный JSON 404 от MVC. Это не раскрывает
                         * структуру защищённого API постороннему пользователю.
                         */
                        .requestMatchers("/api/**").authenticated()

                        /*
                         * Неизвестный обычный web URL, например /kjh, передаётся в DispatcherServlet.
                         * Spring определяет отсутствие маршрута и возвращает HTML 404.
                         *
                         * Раньше anyRequest().authenticated() перехватывал такой URL в Security
                         * ДО MVC, поэтому пользователь видел 401 вместо 404.
                         */
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            if (wantsHtml(request)) {
                                forwardHtmlError(request, response, 401);
                                return;
                            }

                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                                    Instant.now(), 401, "UNAUTHORIZED", "Требуется авторизация",
                                    request.getRequestURI(), response.getHeader("X-Correlation-ID"), Map.of()));
                        })
                        .accessDeniedHandler((request, response, denied) -> {
                            if (wantsHtml(request)) {
                                forwardHtmlError(request, response, 403);
                                return;
                            }

                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                                    Instant.now(), 403, "ACCESS_DENIED", "Недостаточно прав для операции",
                                    request.getRequestURI(), response.getHeader("X-Correlation-ID"), Map.of()));
                        }))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                                "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=()")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(passwordChangeFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Для браузерной навигации выбирает HTML-представление ошибки.
     *
     * <p>REST API всегда возвращает JSON, чтобы JavaFX, Android и web-клиент получали
     * один и тот же формат {@code ApiError}.</p>
     */
    private boolean wantsHtml(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return false;
        }

        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }

    /**
     * Forward сохраняет исходный HTTP-код, в отличие от redirect с промежуточным 302.
     * Статические страницы не используют JavaScript и совместимы с настроенной CSP.
     */
    private void forwardHtmlError(HttpServletRequest request,
                                  HttpServletResponse response,
                                  int status) throws java.io.IOException, jakarta.servlet.ServletException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        request.getRequestDispatcher("/errors/" + status + ".html")
                .forward(request, response);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().asList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID", "X-Confirm-Delete"));
        configuration.setExposedHeaders(List.of("X-Correlation-ID", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
