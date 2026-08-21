package com.fintech.notifications.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "notifications", version = "1.0",
                description = "Entrega de comunicaciones al cliente por PUSH/EMAIL/WHATSAPP — alcance v1: 6 notificaciones",
                contact = @Contact(name = "Platform Team")),
        servers = { @Server(url = "http://localhost:8098", description = "Local"),
                    @Server(url = "http://notifications-service:8080", description = "Docker Compose") })
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
