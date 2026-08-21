package com.fintech.identity.infrastructure.config;

import com.fintech.identity.application.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class IdentityModuleConfig {
}
