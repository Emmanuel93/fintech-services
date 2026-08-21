package com.fintech.channels.infrastructure.config;

import com.fintech.channels.application.ChannelsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableConfigurationProperties(ChannelsProperties.class)
@EnableMethodSecurity
public class ChannelsModuleConfig {}
