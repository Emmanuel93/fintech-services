package com.fintech.origination;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OriginationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OriginationServiceApplication.class, args);
    }
}
