package com.fintech.disbursement.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI disbursementOpenAPI(@Value("${api.version:1.0.0}") String version) {
        return new OpenAPI().info(new Info()
                .title("Disbursement Service API")
                .version(version)
                .description("""
                        Orquestación de pagos salientes multi-rail y multi-empresa.

                        Servicio interno: no recibe tráfico de internet. La autenticación es
                        header-trust — el gateway valida el JWT e inyecta X-User-Id / X-Roles.
                        """));
    }
}
