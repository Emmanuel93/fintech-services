package com.fintech.disbursement.infrastructure.config;

import com.fintech.disbursement.application.DisbursementProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({DisbursementProperties.class, DisbursementTopicProperties.class})
public class DisbursementModuleConfig {

    /**
     * Reloj inyectable. Sin esto no se puede probar la ventana operativa ni el backoff sin dormir el
     * hilo — y una ventana operativa que sólo se puede probar esperando no se prueba.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
