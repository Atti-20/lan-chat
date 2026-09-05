package com.lanchat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "tunnel.enabled=false",
        "jwt.secret=test-only-signing-key-for-spring-context-tests"
})
class LanChatServerApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void generatePassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches("123456", encoder.encode("123456")));
    }

}
