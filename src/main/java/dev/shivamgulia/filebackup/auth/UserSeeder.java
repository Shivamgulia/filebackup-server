package dev.shivamgulia.filebackup.auth;

import dev.shivamgulia.filebackup.user.UserEntity;
import dev.shivamgulia.filebackup.user.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * Seeds users from config on boot. New users are created; existing users are only updated
 * where config is authoritative: usernames listed in {@code filebackup.admin-user} are
 * forced to the {@code admin} role (role management for everyone else lives in the admin API).
 */
@Component
public class UserSeeder implements ApplicationRunner {

    private final UserRepository users;
    private final AuthProperties properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserSeeder(UserRepository users, AuthProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (AuthProperties.SeedUser seed : new ArrayList<>(properties.users())) {
            users.findByUsername(seed.username()).ifPresentOrElse(existing -> {
                if (properties.isAdmin(seed.username()) && !"admin".equals(existing.getRole())) {
                    existing.setRole("admin");
                    users.save(existing);
                }
            }, () -> users.save(new UserEntity(seed.username(), encoder.encode(seed.password()),
                    properties.isAdmin(seed.username()) ? "admin" : "user")));
        }
    }
}
