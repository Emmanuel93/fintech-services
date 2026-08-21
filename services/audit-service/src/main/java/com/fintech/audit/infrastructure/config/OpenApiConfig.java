package com.fintech.audit.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "audit-service",
                version = "1.0",
                description = "Log inmutable regulatorio — suscriptor global Kafka; cumplimiento CNBV/CONDUSEF/UIF",
                contact = @Contact(name = "Platform Team")
        ),
        servers = {
                @Server(url = "http://localhost:8090", description = "Local"),
                @Server(url = "http://audit-service:8080", description = "Docker Compose")
        }
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {}
