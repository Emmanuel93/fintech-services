package com.fintech.collections.infrastructure.config;

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
                title = "collections",
                version = "1.0",
                description = "Cobranza temprana, mora, acuerdos de cobranza (reestructura/quita parcial), quebranto y reporte a buró",
                contact = @Contact(name = "Platform Team")
        ),
        servers = {
                @Server(url = "http://localhost:8093", description = "Local"),
                @Server(url = "http://collections-service:8080", description = "Docker Compose")
        }
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {}
