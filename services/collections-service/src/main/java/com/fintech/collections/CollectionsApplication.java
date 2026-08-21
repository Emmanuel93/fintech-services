package com.fintech.collections;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CollectionsApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollectionsApplication.class, args);
    }
}
