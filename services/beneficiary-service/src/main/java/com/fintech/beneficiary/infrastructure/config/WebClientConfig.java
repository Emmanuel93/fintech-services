package com.fintech.beneficiary.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Los clientes hacia los servicios de dominio que este servicio compone.
 *
 * <p>Uno por destino y con {@code @Qualifier} explícito: un solo {@code WebClient} genérico
 * obligaría a repetir la URL base en cada llamada, que es justo donde se cuela el error de apuntar
 * un endpoint al servicio equivocado.
 */
@Configuration
class WebClientConfig {

    @Bean
    WebClient creditPortfolioWebClient(BeneficiaryProperties props) {
        return WebClient.builder().baseUrl(props.getCreditPortfolioServiceUrl()).build();
    }

    @Bean
    WebClient creditProductWebClient(BeneficiaryProperties props) {
        return WebClient.builder().baseUrl(props.getCreditProductServiceUrl()).build();
    }

    @Bean
    WebClient scoringWebClient(BeneficiaryProperties props) {
        return WebClient.builder().baseUrl(props.getScoringServiceUrl()).build();
    }

    @Bean
    WebClient walletWebClient(BeneficiaryProperties props) {
        return WebClient.builder().baseUrl(props.getWalletServiceUrl()).build();
    }

    @Bean
    WebClient originationWebClient(BeneficiaryProperties props) {
        return WebClient.builder().baseUrl(props.getOriginationServiceUrl()).build();
    }

    @Bean
    WebClient partyWebClient(BeneficiaryProperties props) {
        return WebClient.builder().baseUrl(props.getPartyServiceUrl()).build();
    }
}
