package com.fintech.creditportfolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CreditPortfolioApplication {
    public static void main(String[] args) {
        SpringApplication.run(CreditPortfolioApplication.class, args);
    }
}
