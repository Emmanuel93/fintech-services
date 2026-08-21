package com.fintech.charges;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChargesApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChargesApplication.class, args);
    }
}
