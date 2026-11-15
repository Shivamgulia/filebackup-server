package dev.shivamgulia.filebackup.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * The JWT stage of the Spring Security chain: validates the bearer token (signature + expiry)
 * and turns it into an Authentication with ROLE_ADMIN/ROLE_USER. No password is ever involved
 * here — passwords are verified once, at /api/auth/login. Runs for every request; unprotected
 * routes are exempt via the chain's permitAll rules, and requests without a valid token stay
 * anonymous so the chain's 401 entry point answers them.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final JwtService jwt;
    private final String adminUser;

    public JwtAuthFilter(JwtService jwt, String adminUser) {
        this.jwt = jwt;
        this.adminUser = adminUser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwt.parse(header.substring(7)).ifPresent(username -> {
                var authorities = List.of(new SimpleGrantedAuthority(
                        username.equals(adminUser) ? ROLE_ADMIN : "ROLE_USER"));
                var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
