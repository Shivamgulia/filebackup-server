package dev.shivamgulia.filebackup.user;

import dev.shivamgulia.filebackup.auth.AuthProperties;
import dev.shivamgulia.filebackup.config.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only user management (HLD §4 extension): list, create, password change, suspension.
 * Route security is ROLE_ADMIN via the security chain; enforcement here is business rules only.
 * Admin identification is the configured {@code filebackup.admin-user} — same rule as the JWT filter.
 */
@RestController
public class AdminUserController {

    public record CreateUserRequest(String username, String password) {
    }

    public record ChangePasswordRequest(String password) {
    }

    public record SuspensionRequest(boolean suspended) {
    }

    public record UserView(String username, boolean suspended, java.time.Instant createdAt) {
    }

    public record CreatedResponse(String username) {
    }

    /** Duplicate username at creation; mapped to 409 in {@code GlobalExceptionHandler}. */
    public static class DuplicateUserException extends RuntimeException {
        public DuplicateUserException(String message) {
            super(message);
        }
    }

    private final UserRepository users;
    private final AuthProperties properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminUserController(UserRepository users, AuthProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    @GetMapping("/api/admin/users")
    List<UserView> list() {
        return users.findAll().stream()
                .map(u -> new UserView(u.getUsername(), u.isSuspended(), u.getCreatedAt()))
                .toList();
    }

    @PostMapping("/api/admin/users")
    ResponseEntity<CreatedResponse> create(@RequestBody CreateUserRequest request) {
        requireUsername(request.username());
        requirePassword(request.password());
        if (users.findByUsername(request.username()).isPresent()) {
            throw new DuplicateUserException("User already exists: " + request.username());
        }
        users.save(new UserEntity(request.username(), encoder.encode(request.password())));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(request.username()));
    }

    @PostMapping("/api/admin/users/{username}/password")
    ResponseEntity<Void> changePassword(@PathVariable String username, @RequestBody ChangePasswordRequest request) {
        UserEntity user = users.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("No user: " + username));
        requirePassword(request.password());
        user.setPasswordHash(encoder.encode(request.password()));
        users.save(user);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/admin/users/{username}/suspension")
    ResponseEntity<Void> suspend(@PathVariable String username, @RequestBody SuspensionRequest request) {
        if (properties.isAdmin(username)) {
            throw new IllegalArgumentException("Admin accounts cannot be suspended");
        }
        UserEntity user = users.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("No user: " + username));
        user.setSuspended(request.suspended());
        users.save(user);
        return ResponseEntity.noContent().build();
    }

    /** Usernames become path prefixes (and JWT subjects) — keep them safe characters only. */
    private static void requireUsername(String username) {
        if (username == null || !username.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Username must be 1-64 chars of [A-Za-z0-9._-]");
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
    }
}
