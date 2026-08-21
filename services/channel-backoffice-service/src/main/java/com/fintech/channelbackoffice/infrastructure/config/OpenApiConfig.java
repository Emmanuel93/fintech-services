package com.fintech.channelbackoffice.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Channel Backoffice API — BFF",
        version = "1.0",
        description = "Backend-For-Frontend de la consola operativa de crédito. " +
                      "Agrega y orquesta los servicios de dominio para el personal interno; " +
                      "solo acepta tokens con channel=BACKOFFICE."))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {}
