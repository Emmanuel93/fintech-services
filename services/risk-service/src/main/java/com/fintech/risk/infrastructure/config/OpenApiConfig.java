package com.fintech.risk.infrastructure.config;

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
                title = "risk",
                version = "1.0",
                description = "Clasificación IFRS-9 (etapa) y estimación preventiva de reservas (EPR/ECL) por cuenta",
                contact = @Contact(name = "Platform Team")
        ),
        servers = {
                @Server(url = "http://localhost:8094", description = "Local"),
                @Server(url = "http://risk-service:8080", description = "Docker Compose")
        }
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {}
