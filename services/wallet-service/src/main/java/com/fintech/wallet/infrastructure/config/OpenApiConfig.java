package com.fintech.wallet.infrastructure.config;

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
                title = "wallet-service",
                version = "1.0",
                description = "Balance projection and payment/disposition orchestration for CreditProduct",
                contact = @Contact(name = "Platform Team")
        ),
        servers = {
                @Server(url = "http://localhost:8092", description = "Local"),
                @Server(url = "http://wallet-service:8080",   description = "Docker Compose")
        }
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {}
