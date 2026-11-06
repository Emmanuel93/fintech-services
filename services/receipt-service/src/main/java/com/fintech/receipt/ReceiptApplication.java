package com.fintech.receipt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment receipts of a distributor's clients: one per client and statement, generated when the line's cutoff
 * closes, alongside (never inside) the distributor's statement.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ReceiptApplication {

    public static void main(String[] args) {
        // Jasper measures text with AWT; a container has no display.
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(ReceiptApplication.class, args);
    }
}
