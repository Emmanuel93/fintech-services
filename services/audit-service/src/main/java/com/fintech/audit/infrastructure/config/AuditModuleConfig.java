package com.fintech.audit.infrastructure.config;

import com.fintech.audit.application.AuditProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditModuleConfig {}
