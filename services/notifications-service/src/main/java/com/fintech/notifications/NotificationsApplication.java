package com.fintech.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NT-12: este servicio nunca declara {@code @EnableScheduling} — es 100% reactivo a Kafka,
 * nunca escanea/revisa el estado de una cuenta por su cuenta.
 */
@SpringBootApplication
public class NotificationsApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationsApplication.class, args);
    }
}
