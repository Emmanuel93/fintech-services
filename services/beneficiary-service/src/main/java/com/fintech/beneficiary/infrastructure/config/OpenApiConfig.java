package com.fintech.beneficiary.infrastructure.config;

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
                title = "beneficiary-service",
                version = "1.0",
                description = """
                        Colocación B2B2C: un distribuidor con línea revolvente le presta a sus \
                        clientes, y cada uno queda con su propio expediente. Orquesta el alta \
                        previa, la liga de KYC, la consulta de buró con la autorización del \
                        beneficiario y la disposición THIRD_PARTY_CREDIT contra la línea.

                        La deudora frente a Kredius es la distribuidora: hay una sola cuenta de \
                        crédito y cada colocación es una disposición de esa línea.""",
                contact = @Contact(name = "Fintech Platform Team")),
        servers = {
                @Server(url = "http://localhost:8102", description = "Local"),
                @Server(url = "http://beneficiary-service:8080", description = "Docker Compose")
        })
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT del distribuidor. El flujo público de la beneficiaria no lo usa.")
public class OpenApiConfig {}
