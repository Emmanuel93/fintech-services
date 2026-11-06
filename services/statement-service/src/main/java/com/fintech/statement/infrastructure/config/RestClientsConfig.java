package com.fintech.statement.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Los clientes de cartera, party y comisiones, con tiempos acotados: un corte de miles de cuentas no puede
 * quedarse colgado de una llamada que no responde.
 */
@Configuration
public class RestClientsConfig {

    @Bean
    public RestClient creditPortfolioRestClient(RestClient.Builder builder,
                                                @Value("${fintech.statement.clients.credit-portfolio-url}") String url,
                                                @Value("${fintech.statement.clients.timeout:PT10S}") Duration timeout) {
        return builder.clone().baseUrl(url).requestFactory(factory(timeout)).build();
    }

    @Bean
    public RestClient partyRestClient(RestClient.Builder builder,
                                      @Value("${fintech.statement.clients.party-url}") String url,
                                      @Value("${fintech.statement.clients.timeout:PT10S}") Duration timeout) {
        return builder.clone().baseUrl(url).requestFactory(factory(timeout)).build();
    }

    /** What a distributor earns: the commission statement shows it next to what she pays. */
    @Bean
    public RestClient commissionRestClient(RestClient.Builder builder,
                                           @Value("${fintech.statement.clients.commission-url}") String url,
                                           @Value("${fintech.statement.clients.timeout:PT10S}") Duration timeout) {
        return builder.clone().baseUrl(url).requestFactory(factory(timeout)).build();
    }

    private static ClientHttpRequestFactory factory(Duration timeout) {
        return ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(timeout));
    }
}
