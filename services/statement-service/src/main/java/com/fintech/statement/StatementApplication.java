package com.fintech.statement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class StatementApplication {

    public static void main(String[] args) {
        // Jasper mide texto con AWT; en un contenedor no hay pantalla.
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(StatementApplication.class, args);
    }
}
