package com.fintech.payments.infrastructure.config;

import com.fintech.payments.application.PaymentsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentsModuleConfig {}
