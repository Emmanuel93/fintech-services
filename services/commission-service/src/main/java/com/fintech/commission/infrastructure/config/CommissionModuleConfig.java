package com.fintech.commission.infrastructure.config;

import com.fintech.commission.application.CommissionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CommissionProperties.class)
public class CommissionModuleConfig {}
