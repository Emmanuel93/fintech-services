package com.fintech.risk.infrastructure.config;

import com.fintech.risk.application.RiskProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RiskProperties.class)
public class RiskModuleConfig {}
