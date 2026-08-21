package com.fintech.scoring.infrastructure.adapter.out.circulo;

import com.fintech.scoring.infrastructure.config.CirculoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class CirculoConfig {

    @Bean
    RestClient circuloRestClient(CirculoProperties props) {
        return RestClient.builder()
                .baseUrl(props.getUrl())
                .defaultHeader("x-api-key", props.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
