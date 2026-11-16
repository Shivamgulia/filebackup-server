package dev.shivamgulia.filebackup.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Login user. Access control is the username-path-prefix convention (see HLD §4) —
 * this table only holds credentials. Seeded from config; no open registration.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** bcrypt hash; hashing lives in the auth step, not here. */
    @Column(name = "password_hash", nullable = false, columnDefinition = "text")
    private String passwordHash;

    /** Suspended users fail login and their existing tokens are rejected at the filter. */
    @Column(nullable = false)
    private boolean suspended = false;

    /** "user" or "admin" — configurable admins (filebackup.admin-user) are always admin too. */
    @Column(nullable = false)
    private String role = "user";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UserEntity(String username, String passwordHash) {
        this(username, passwordHash, "user");
    }

    public UserEntity(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }
}
