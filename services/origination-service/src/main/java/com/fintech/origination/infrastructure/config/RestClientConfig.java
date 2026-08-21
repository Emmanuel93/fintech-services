package com.fintech.origination.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient creditProductRestClient(OriginationProperties props) {
        return RestClient.builder()
                .baseUrl(props.getCreditProductServiceUrl())
                .build();
    }

    @Bean
    public RestClient partyRestClient(OriginationProperties props) {
        return RestClient.builder()
                .baseUrl(props.getPartyServiceUrl())
                .build();
    }

    @Bean
    public RestClient salesOrgRestClient(OriginationProperties props) {
        return RestClient.builder()
                .baseUrl(props.getSalesOrgServiceUrl())
                .build();
    }
}
