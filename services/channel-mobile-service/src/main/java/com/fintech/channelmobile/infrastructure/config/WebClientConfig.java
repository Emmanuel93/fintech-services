package com.fintech.channelmobile.infrastructure.config;

import com.fintech.channelmobile.infrastructure.adapter.in.api.MdcCorrelationFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
class WebClientConfig {

    @Bean
    WebClient identityWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getIdentityServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient originationWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getOriginationServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient creditProductWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getCreditProductServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient partyWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getPartyServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient creditPortfolioWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getCreditPortfolioServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient walletWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getWalletServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient notificationsWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getNotificationsServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient paymentsWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getPaymentsServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient chargesWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getChargesServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    @Bean
    WebClient scoringWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getScoringServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    /** Bitácora regulatoria: a dónde manda el canal móvil sus registros de acceso. */
    @Bean
    WebClient auditWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getAuditServiceUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .filter((req, next) -> {
                    String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
                    if (corrId != null) {
                        return next.exchange(ClientRequest.from(req)
                                .header(MdcCorrelationFilter.HEADER, corrId)
                                .build());
                    }
                    return next.exchange(req);
                })
                .build();
    }

    /**
     * La colocación B2B2C. El BFF sólo republica: el contrato que la app consume lo define
     * beneficiary-service, y traducirlo aquí agregaría un punto de ruptura sin dueño.
     */
    @Bean
    WebClient beneficiaryWebClient(ChannelMobileProperties props) {
        return WebClient.builder()
                .baseUrl(props.getBeneficiaryServiceUrl())
                .build();
    }
}
