package com.fintech.beneficiary.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * El reloj de la superficie pública.
 *
 * <p>Todo lo de la liga es tiempo —vigencia de 7 días, OTP de 5 minutos, ventana de 15, sesión
 * deslizante, congelamiento de una hora— y una prueba que tuviera que esperar una hora para ver
 * descongelarse una liga no se escribiría nunca. Con el reloj inyectado, se adelanta.
 */
@Configuration
class PublicKycConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
