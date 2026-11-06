package com.fintech.receipt.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientsConfig {

    @Bean
    public RestClient creditPortfolioRestClient(RestClient.Builder builder,
                                                @Value("${fintech.receipt.clients.credit-portfolio-url}") String url,
                                                @Value("${fintech.receipt.clients.timeout:PT10S}") Duration timeout) {
        return builder.clone().baseUrl(url).requestFactory(factory(timeout)).build();
    }

    @Bean
    public RestClient partyRestClient(RestClient.Builder builder,
                                      @Value("${fintech.receipt.clients.party-url}") String url,
                                      @Value("${fintech.receipt.clients.timeout:PT10S}") Duration timeout) {
        return builder.clone().baseUrl(url).requestFactory(factory(timeout)).build();
    }

    private static ClientHttpRequestFactory factory(Duration timeout) {
        return ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(timeout));
    }
}
