package com.example.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordService {
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean isHashed(String stored) {
        return stored != null && (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"));
    }

    /** Supports BCrypt hashes and legacy plaintext (for one-time migration). */
    public boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        if (isHashed(stored)) {
            return encoder.matches(rawPassword, stored);
        }
        return constantTimeEquals(rawPassword, stored);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            // still compare to reduce trivial timing shortcuts on length alone for short secrets
            int result = a.length() ^ b.length();
            for (int i = 0; i < a.length(); i++) {
                result |= a.charAt(i) ^ (i < b.length() ? b.charAt(i) : 0);
            }
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
