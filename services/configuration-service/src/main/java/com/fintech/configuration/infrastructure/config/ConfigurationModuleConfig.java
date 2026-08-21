package com.fintech.configuration.infrastructure.config;

import com.fintech.configuration.application.ConfigServiceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConfigServiceProperties.class)
public class ConfigurationModuleConfig {}
