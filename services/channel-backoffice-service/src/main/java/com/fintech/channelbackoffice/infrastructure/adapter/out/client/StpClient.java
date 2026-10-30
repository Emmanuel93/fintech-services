package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a stp-service para Bancos / Tesorería: la orden SPEI de un pago y su CEP.
 *
 * <p>La orden se busca por {@code paymentRequestId}, que es el id del desembolso: es la clave de
 * idempotencia con la que disbursement se la entregó al conector.
 */
@Component
public class StpClient {

    private static final Logger log = LoggerFactory.getLogger(StpClient.class);

    private final WebClient webClient;

    public StpClient(@Qualifier("stpWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** Vacío si el pago no salió por STP o todavía no llega al conector. */
    public Optional<PaymentOrder> paymentOrder(UUID paymentRequestId) {
        log.info("-> GET stp /payment-orders/{}", paymentRequestId);
        try {
            return Optional.ofNullable(webClient.get()
                    .uri("/api/v1/stp/payment-orders/{id}", paymentRequestId)
                    .headers(DomainClientSupport.staffIdentity())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("stp-service", r))
                    .bodyToMono(PaymentOrder.class)
                    .block());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) return Optional.empty();
            throw e;
        }
    }

    /** Las dos cuentas vienen enmascaradas desde stp. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentOrder(UUID stpPaymentOrderId, UUID paymentRequestId, UUID companyId,
                               String trackingKey, LocalDate businessDate, BigDecimal amount,
                               String beneficiaryName, String beneficiaryAccountMasked,
                               String orderingAccountMasked, String orderingHolderName,
                               String status, String stpOrderId, String cepUrl,
                               Boolean beneficiaryNameMatches, Instant settledAt) {}
}
