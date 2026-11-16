package dev.shivamgulia.filebackup.auth;

import dev.shivamgulia.filebackup.auth.AuthProperties;
import dev.shivamgulia.filebackup.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Username/password → JWT (HLD §3.1). 401 on unknown user or wrong password; no user enumeration.
 */
@RestController
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token) {
    }

    public record MeResponse(String username, boolean admin) {
    }

    public record LoginRejected(int status, String message) {
    }

    private record ErrorBody(int status, String message) {
    }

    private final UserRepository users;
    private final JwtService jwt;
    private final AuthProperties properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(UserRepository users, JwtService jwt, AuthProperties properties) {
        this.users = users;
        this.jwt = jwt;
        this.properties = properties;
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null) {
            return unauthorized();
        }
        return users.findByUsername(request.username())
                .filter(u -> encoder.matches(request.password(), u.getPasswordHash()))
                .map(u -> {
                    if (u.isSuspended()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body((Object) new LoginRejected(403, "Account suspended"));
                    }
                    return ResponseEntity.ok((Object) new LoginResponse(jwt.issue(u.getUsername())));
                })
                .orElseGet(this::unauthorized);
    }

    /** Who the token belongs to and whether it is the admin — the app's role source of truth. */
    @GetMapping("/api/auth/me")
    MeResponse me(java.security.Principal principal) {
        boolean admin = users.findByUsername(principal.getName())
                .map(u -> properties.isAdmin(u.getUsername()) || "admin".equals(u.getRole()))
                .orElse(false);
        return new MeResponse(principal.getName(), admin);
    }

    private ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorBody(401, "Invalid credentials"));
    }
}
