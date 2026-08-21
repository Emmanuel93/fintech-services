package com.fintech.commission.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "commission", version = "1.0",
                description = "Acumulación y liquidación de comisiones — distribuidores B2B2C, promotores, gestores de cobranza",
                contact = @Contact(name = "Platform Team")),
        servers = { @Server(url = "http://localhost:8097", description = "Local"),
                    @Server(url = "http://commission-service:8080", description = "Docker Compose") })
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
