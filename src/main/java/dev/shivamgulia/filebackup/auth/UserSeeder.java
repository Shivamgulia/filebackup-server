package dev.shivamgulia.filebackup.auth;

import dev.shivamgulia.filebackup.user.UserEntity;
import dev.shivamgulia.filebackup.user.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * Seeds users from config on boot — existing users are never touched.
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
            if (users.findByUsername(seed.username()).isEmpty()) {
                users.save(new UserEntity(seed.username(), encoder.encode(seed.password())));
            }
        }
    }
}
