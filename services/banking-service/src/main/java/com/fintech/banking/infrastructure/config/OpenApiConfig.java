package com.fintech.banking.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bankingOpenAPI(@Value("${api.version:1.0.0}") String version) {
        return new OpenAPI().info(new Info()
                .title("Banking Service API")
                .version(version)
                .description("""
                        Tesorería: cuentas propias, por dónde sale cada pago y qué reporta el banco.

                        No conoce el dominio de crédito. Servicio interno: la autenticación es
                        header-trust — el gateway valida el JWT e inyecta X-User-Id / X-Roles.
                        """));
    }
}
