package com.fintech.payments.infrastructure.config;

import com.fintech.payments.application.PaymentsProperties;
import com.fintech.payments.domain.allocation.AllocationStrategy;
import com.fintech.payments.domain.allocation.SeniorityAllocationStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentsModuleConfig {

    /** The prelation of a received credit: referenced loan, then the oldest with debt, the rest in favor. */
    @Bean
    public AllocationStrategy allocationStrategy() {
        return new SeniorityAllocationStrategy();
    }
}
