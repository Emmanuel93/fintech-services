package com.fintech.stp.infrastructure.config;

import com.fintech.stp.application.StpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Clientes HTTP hacia STP. Es el único egress del monorepo (EG-01).
 *
 * <p>Timeouts por request, no globales: el legado los fijaba con {@code Unirest.setTimeouts(...)},
 * estático y mutado desde código concurrente.
 */
@Configuration
@ConditionalOnProperty(name = "fintech.stp.gateway.mode", havingValue = "real", matchIfMissing = true)
public class RestClientConfig {

    @Bean("stpDispersionClient")
    public RestClient stpDispersionClient(StpProperties properties) {
        return build(properties.getBaseUrl(), properties);
    }

    @Bean("stpConsultaClient")
    public RestClient stpConsultaClient(StpProperties properties) {
        return build(properties.getConsultaBaseUrl(), properties);
    }

    private RestClient build(String baseUrl, StpProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getHttp().getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getHttp().getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
