package com.fintech.payments.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "payments-service",
                version = "1.0",
                description = "Recepción y aplicación de pagos con doble validación de saldo. " +
                              "Pre-check contra snapshot local (eventual); post-check autoritativo en credit-portfolio."
        ),
        servers = {
                @Server(url = "http://localhost:8089", description = "Local"),
                @Server(url = "http://payments-service:8080", description = "Docker Compose")
        }
)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
