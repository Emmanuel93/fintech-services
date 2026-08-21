package com.fintech.collections.infrastructure.config;

import com.fintech.collections.application.CollectionsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CollectionsProperties.class)
public class CollectionsModuleConfig {}
