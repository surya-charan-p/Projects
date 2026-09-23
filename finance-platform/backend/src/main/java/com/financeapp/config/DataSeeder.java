package com.financeapp.config;

import com.financeapp.entity.Role;
import com.financeapp.entity.User;
import com.financeapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final String ADMIN_EMAIL = "admin@financeapp.com";
    private static final String ADMIN_DEFAULT_PASSWORD = "Admin@12345";

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(ADMIN_EMAIL)) {
            return;
        }

        User admin = User.builder()
                .firstName("Platform")
                .lastName("Admin")
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(ADMIN_DEFAULT_PASSWORD))
                .role(Role.ADMIN)
                .enabled(true)
                .build();

        userRepository.save(admin);
        log.info("Seeded default admin account: {} (password: {}) - CHANGE THIS IN PRODUCTION",
                ADMIN_EMAIL, ADMIN_DEFAULT_PASSWORD);
    }
}
