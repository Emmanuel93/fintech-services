package com.fintech.salesorg.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientsConfig {

    /** The product catalog: the line's envelope a placement policy is validated against. */
    @Bean
    public RestClient creditProductRestClient(RestClient.Builder builder,
                                              @Value("${fintech.sales-org.clients.credit-product-url}") String url,
                                              @Value("${fintech.sales-org.clients.timeout:PT10S}") Duration timeout) {
        return builder.clone().baseUrl(url)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(3))
                        .withReadTimeout(timeout)))
                .build();
    }
}
