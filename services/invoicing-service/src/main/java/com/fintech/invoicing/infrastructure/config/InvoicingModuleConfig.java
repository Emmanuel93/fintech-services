package com.fintech.invoicing.infrastructure.config;

import com.fintech.invoicing.application.InvoicingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InvoicingProperties.class)
public class InvoicingModuleConfig {}
