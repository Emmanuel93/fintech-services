package com.fintech.stp.infrastructure.config;

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
                title = "stp-service",
                version = "1.0",
                description = """
                        Conector con STP (SPEI). Servicio interno: no recibe tráfico de internet.
                        Firma órdenes de pago con la llave de cada empresa y consulta activamente
                        su liquidación. Multi-empresa con custodia de llaves por envelope encryption.
                        """,
                contact = @Contact(name = "Fintech Platform")),
        servers = {
                @Server(url = "http://localhost:8101", description = "Local"),
                @Server(url = "http://stp-service:8080", description = "Docker Compose")
        })
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {
}
