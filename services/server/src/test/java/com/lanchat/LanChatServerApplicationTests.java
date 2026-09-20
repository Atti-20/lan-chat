package com.lanchat;

import com.lanchat.service.BroadcastNotificationAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "tunnel.enabled=false",
        "jwt.secret=test-only-signing-key-for-spring-context-tests"
})
class LanChatServerApplicationTests {

    // The context smoke test must not require a developer's local MySQL just
    // to execute the production ApplicationRunner. Its behavior is covered
    // by BroadcastNotificationAccountServiceTest with a mocked mapper.
    @MockitoBean
    private BroadcastNotificationAccountService broadcastNotificationAccountService;

    @Test
    void contextLoads() {
    }

    @Test
    void generatePassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches("123456", encoder.encode("123456")));
    }

}
