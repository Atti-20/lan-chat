package com.lanchat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest
class LanChatServerApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void generatePassword() {
        System.out.println("123456 的哈希值：" + new BCryptPasswordEncoder().encode("123456"));
    }

}
