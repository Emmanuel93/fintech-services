package com.fintech.stp.infrastructure.config;

import com.fintech.stp.application.StpProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(StpProperties.class)
public class StpModuleConfig {

    /**
     * Reloj inyectable. Sin esto no se puede probar la ventana de gracia del poller ni el cálculo
     * del día Banxico sin dormir el hilo.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
