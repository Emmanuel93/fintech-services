package com.fintech.channels.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI channelsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Channels API")
                .description("D1 — Channel session management, intent routing, and lead capture")
                .version("1.0.0"));
    }
}
