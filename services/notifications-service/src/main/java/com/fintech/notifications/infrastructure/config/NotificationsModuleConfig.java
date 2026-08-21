package com.fintech.notifications.infrastructure.config;

import com.fintech.notifications.application.NotificationsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotificationsProperties.class)
public class NotificationsModuleConfig {}
