package com.lanchat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.lanchat.mapper")
public class LanChatServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LanChatServerApplication.class, args);
    }
}
