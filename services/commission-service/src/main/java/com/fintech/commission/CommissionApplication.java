package com.fintech.commission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CommissionApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommissionApplication.class, args);
    }
}
