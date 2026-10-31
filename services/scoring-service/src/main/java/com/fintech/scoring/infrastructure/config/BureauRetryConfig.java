package com.fintech.scoring.infrastructure.config;

import com.fintech.scoring.application.service.BureauRetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** La política de reintento del buró, desde {@code fintech.circulo.retry.*}. */
@Configuration
class BureauRetryConfig {

    @Bean
    BureauRetryPolicy bureauRetryPolicy(CirculoProperties properties) {
        CirculoProperties.Retry r = properties.getRetry();
        return new BureauRetryPolicy(r.getMaxRetries(), r.getInitialBackoff(), r.getMultiplier(), r.getMaxBackoff());
    }
}
