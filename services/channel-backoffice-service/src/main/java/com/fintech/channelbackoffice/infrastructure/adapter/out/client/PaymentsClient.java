package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Acceso a payments-service.
 *
 * <p>El calendario dice qué mensualidades figuran como pagadas; esto dice <b>con qué</b> se pagaron.
 * Son cosas distintas y la consola necesita las dos: un pago confirmado que todavía no movió el
 * calendario es exactamente el caso que se va a investigar desde el backoffice, y con solo el
 * calendario a la vista ese pago no existe.
 */
@Component
public class PaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentsClient.class);

    private final WebClient webClient;

    public PaymentsClient(@Qualifier("paymentsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** Órdenes de pago de una cuenta, más reciente primero. Es una consulta por cuenta, no por fila. */
    public List<PaymentOrderResponse> listByAccount(UUID creditAccountId) {
        log.info("-> GET payments-service /api/v1/payments/accounts/{}", creditAccountId);
        return webClient.get()
                .uri("/api/v1/payments/accounts/{id}", creditAccountId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("payments-service", r))
                .bodyToFlux(PaymentOrderResponse.class)
                .collectList()
                .block();
    }

    /** Espejo del DTO de payments-service. Los campos que no se muestran se ignoran al deserializar. */
    public record PaymentOrderResponse(
            UUID       paymentOrderId,
            UUID       creditAccountId,
            BigDecimal amount,
            String     paymentMethod,
            String     externalRef,
            String     status,
            String     rejectionReason,
            String     reversalReason,
            Instant    createdAt,
            Instant    confirmedAt,
            Instant    rejectedAt,
            Instant    reversedAt
    ) {}
}
