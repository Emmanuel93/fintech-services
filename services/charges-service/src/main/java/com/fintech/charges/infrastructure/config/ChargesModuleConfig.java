package com.fintech.charges.infrastructure.config;

import com.fintech.charges.application.ChargesProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChargesProperties.class)
public class ChargesModuleConfig {}
