package com.fintech.channelmobile.infrastructure.adapter.out.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Reenvía a los endpoints /internal/test-support/* de charges-service y
 * credit-portfolio-service — ambos solo alcanzables dentro de la red Docker.
 * Dev-only: el BFF solo expone /internal/test-support/* cuando
 * fintech.channel-mobile.test-support-enabled=true (ver TestSupportProxyController).
 */
@Component
public class TestSupportClient {

    private final WebClient chargesWebClient;
    private final WebClient creditPortfolioWebClient;

    public TestSupportClient(@Qualifier("chargesWebClient") WebClient chargesWebClient,
                              @Qualifier("creditPortfolioWebClient") WebClient creditPortfolioWebClient) {
        this.chargesWebClient = chargesWebClient;
        this.creditPortfolioWebClient = creditPortfolioWebClient;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> runDailyAccrual() {
        return chargesWebClient.post()
                .uri("/internal/test-support/run-daily-accrual")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> runUpcomingInstallmentJob() {
        return creditPortfolioWebClient.post()
                .uri("/internal/test-support/run-upcoming-installment-job")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> runInstallmentDueJob() {
        return creditPortfolioWebClient.post()
                .uri("/internal/test-support/run-installment-due-job")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    /**
     * Recalcula los días de atraso de las cuentas. Es el que hace que la mora
     * aparezca en el payload de la cuenta: sin él, la mensualidad queda marcada
     * vencida pero `daysDelinquent` sigue en cero y la app la muestra al
     * corriente.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runDelinquencyJob() {
        return creditPortfolioWebClient.post()
                .uri("/internal/test-support/run-delinquency-job")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> shiftInstallmentDueDate(String creditAccountId, int installmentNumber,
                                                         int daysFromToday) {
        return creditPortfolioWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/test-support/accounts/{creditAccountId}/installments/{n}/shift-due-date")
                        .queryParam("daysFromToday", daysFromToday)
                        .build(creditAccountId, installmentNumber))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }
}
