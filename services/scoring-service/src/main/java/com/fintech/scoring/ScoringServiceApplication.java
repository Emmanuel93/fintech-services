package com.fintech.scoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@org.springframework.retry.annotation.EnableRetry
@SpringBootApplication
@ConfigurationPropertiesScan
public class ScoringServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScoringServiceApplication.class, args);
    }
}
