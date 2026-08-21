package com.fintech.invoicing.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "invoicing", version = "1.0",
                description = "Facturación CFDI 4.0 — genera y timbra (stub PAC) la factura del party",
                contact = @Contact(name = "Platform Team")),
        servers = { @Server(url = "http://localhost:8096", description = "Local"),
                    @Server(url = "http://invoicing-service:8080", description = "Docker Compose") })
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {}
