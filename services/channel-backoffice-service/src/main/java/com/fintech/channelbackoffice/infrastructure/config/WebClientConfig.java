package com.fintech.channelbackoffice.infrastructure.config;

import com.fintech.channelbackoffice.infrastructure.adapter.in.api.MdcCorrelationFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Un {@link WebClient} por servicio de dominio, todos con el mismo filtro de correlación para que
 * una traza del backoffice se siga hasta el último servicio que tocó.
 */
@Configuration
class WebClientConfig {

    /** Respuestas del dominio: los listados agregados pueden ser grandes. */
    private static final int MAX_IN_MEMORY_BYTES = 4 * 1024 * 1024;

    @Bean WebClient identityWebClient(ChannelBackofficeProperties p)        { return client(p.getIdentityServiceUrl()); }
    @Bean WebClient partyWebClient(ChannelBackofficeProperties p)           { return client(p.getPartyServiceUrl()); }
    @Bean WebClient creditPortfolioWebClient(ChannelBackofficeProperties p) { return client(p.getCreditPortfolioServiceUrl()); }
    @Bean WebClient creditProductWebClient(ChannelBackofficeProperties p)   { return client(p.getCreditProductServiceUrl()); }
    @Bean WebClient originationWebClient(ChannelBackofficeProperties p)     { return client(p.getOriginationServiceUrl()); }
    @Bean WebClient scoringWebClient(ChannelBackofficeProperties p)         { return client(p.getScoringServiceUrl()); }
    @Bean WebClient riskWebClient(ChannelBackofficeProperties p)            { return client(p.getRiskServiceUrl()); }
    @Bean WebClient collectionsWebClient(ChannelBackofficeProperties p)     { return client(p.getCollectionsServiceUrl()); }
    @Bean WebClient commissionWebClient(ChannelBackofficeProperties p)      { return client(p.getCommissionServiceUrl()); }
    @Bean WebClient configurationWebClient(ChannelBackofficeProperties p)   { return client(p.getConfigurationServiceUrl()); }
    @Bean WebClient paymentsWebClient(ChannelBackofficeProperties p)        { return client(p.getPaymentsServiceUrl()); }
    @Bean WebClient chargesWebClient(ChannelBackofficeProperties p)         { return client(p.getChargesServiceUrl()); }
    @Bean WebClient walletWebClient(ChannelBackofficeProperties p)          { return client(p.getWalletServiceUrl()); }
    @Bean WebClient notificationsWebClient(ChannelBackofficeProperties p)   { return client(p.getNotificationsServiceUrl()); }
    @Bean WebClient accountingWebClient(ChannelBackofficeProperties p)      { return client(p.getAccountingServiceUrl()); }
    @Bean WebClient invoicingWebClient(ChannelBackofficeProperties p)       { return client(p.getInvoicingServiceUrl()); }
    @Bean WebClient auditWebClient(ChannelBackofficeProperties p)           { return client(p.getAuditServiceUrl()); }
    @Bean WebClient salesOrgWebClient(ChannelBackofficeProperties p)        { return client(p.getSalesOrgServiceUrl()); }
    @Bean WebClient beneficiaryWebClient(ChannelBackofficeProperties p)     { return client(p.getBeneficiaryServiceUrl()); }

    private static WebClient client(String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .filter(propagateCorrelationId())
                .build();
    }

    private static ExchangeFilterFunction propagateCorrelationId() {
        return (request, next) -> {
            String corrId = MDC.get(MdcCorrelationFilter.MDC_KEY);
            if (corrId == null) {
                return next.exchange(request);
            }
            return next.exchange(ClientRequest.from(request)
                    .header(MdcCorrelationFilter.HEADER, corrId)
                    .build());
        };
    }
}
