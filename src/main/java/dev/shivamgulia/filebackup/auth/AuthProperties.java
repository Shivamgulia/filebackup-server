package dev.shivamgulia.filebackup.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Seeded users and the admin override, from config (personal service — no open registration). */
@ConfigurationProperties(prefix = "filebackup")
public record AuthProperties(List<SeedUser> users, String adminUser) {

    public record SeedUser(String username, String password) {
    }
}
