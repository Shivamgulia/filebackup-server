package dev.shivamgulia.filebackup.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import dev.shivamgulia.filebackup.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Stateless security chain: JWT filter stage, no sessions, no CSRF (token-authenticated API).
 * Login and health are open; everything else needs a valid JWT (else the entry point's 401).
 * Cross-user access stays a 404 by design — enforcement lives in BackupService, not here.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, AuthProperties properties,
                                            UserRepository users) throws Exception {
        var jwtFilter = new JwtAuthFilter(jwt, properties.adminUser(), users);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/health", "/api/blob/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/admin/**").hasRole(JwtAuthFilter.ROLE_ADMIN.substring(5))
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(jsonEntryPoint())
                        .accessDeniedHandler(jsonDeniedHandler()));
        return http.build();
    }

    /** Same JSON error shape as GlobalExceptionHandler. */
    private static AuthenticationEntryPoint jsonEntryPoint() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":401,\"message\":\"Missing or invalid token\",\"fieldErrors\":null}");
        };
    }

    /** JSON 403 for authenticated-but-not-admin requests (avoids the /error dispatch noise). */
    private static AccessDeniedHandler jsonDeniedHandler() {
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":403,\"message\":\"Admin only\",\"fieldErrors\":null}");
        };
    }
}
