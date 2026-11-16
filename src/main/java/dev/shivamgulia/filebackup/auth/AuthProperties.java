package dev.shivamgulia.filebackup.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Seeded users and the admin override, from config (personal service — no open registration).
 *  {@code adminUser} accepts a comma-separated list of admin usernames. */
@ConfigurationProperties(prefix = "filebackup")
public record AuthProperties(List<SeedUser> users, String adminUser) {

    public record SeedUser(String username, String password) {
    }

    /** True when {@code username} is one of the configured admins (comma-separated config value). */
    public boolean isAdmin(String username) {
        return adminUser != null && java.util.Arrays.stream(adminUser.split(","))
                .map(String::trim)
                .anyMatch(username::equals);
    }
}
