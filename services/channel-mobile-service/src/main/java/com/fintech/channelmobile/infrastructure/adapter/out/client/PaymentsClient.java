package com.fintech.channelmobile.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * payments-service aplica el pago contra su balance snapshot (proyección de
 * credit-portfolio) y publica payment-applied — el saldo real se actualiza async.
 * El BFF nunca habla con wallet-service para pagos (esa instrucción no tiene
 * consumidor real, ver ADR pendiente); el cobro efectivo vive aquí.
 */
@Component
public class PaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentsClient.class);

    private final WebClient webClient;

    public PaymentsClient(@Qualifier("paymentsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public PaymentOrderResponse submit(UUID creditAccountId, UUID obligorPartyId, String userId,
                                        BigDecimal amount, String paymentMethod) {
        String externalRef = UUID.randomUUID().toString();
        log.info("-> POST payments-service /api/v1/payments creditAccountId={} amount={} externalRef={}",
                creditAccountId, amount, externalRef);
        return webClient.post()
                .uri("/api/v1/payments")
                .header("X-User-Id", userId)
                .bodyValue(new SubmitPaymentPayload(creditAccountId, obligorPartyId, amount, paymentMethod, externalRef))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "payments-service error: " + resp.statusCode())))
                .bodyToMono(PaymentOrderResponse.class)
                .block();
    }

    public record SubmitPaymentPayload(
            UUID creditAccountId, UUID obligorPartyId, BigDecimal amount,
            String paymentMethod, String externalRef) {}

    public record PaymentOrderResponse(
            UUID paymentOrderId,
            UUID creditAccountId,
            BigDecimal amount,
            String paymentMethod,
            String externalRef,
            String status,
            long snapshotVersion,
            String rejectionReason,
            String reversalReason,
            Instant createdAt,
            Instant confirmedAt,
            Instant rejectedAt,
            Instant reversedAt
    ) {}
}
