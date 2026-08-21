package com.fintech.accounting.infrastructure.config;

import com.fintech.accounting.application.AccountingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AccountingProperties.class)
public class AccountingModuleConfig {}
