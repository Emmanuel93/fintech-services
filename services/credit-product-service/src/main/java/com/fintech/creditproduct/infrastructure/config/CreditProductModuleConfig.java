package com.fintech.creditproduct.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CreditProductProperties.class)
public class CreditProductModuleConfig {}
