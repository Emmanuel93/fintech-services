package com.fintech.stp.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.shared.testing.EventContract;
import com.fintech.stp.domain.BanxicoResponseCode;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.domain.TrackingKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contract of {@code stp.order-rejected} with disbursement: every rejection STP publishes is final.
 *
 * <p><b>D58.</b> Once the relay exhausted its attempts on a transient code (-30, the link in query mode) the rejection
 * went out as retryable. Disbursement retried the same request, STP's idempotency guard dropped it without a word, and
 * the order waited for an answer that never came: the commission lot stayed SENT for months. <b>D59.</b> An order STP
 * never registered (no answer to any attempt, absent from its closed day) had no event at all.
 */
class OrderRejectedContractTest {

    /** What disbursement's {@code ProviderOutcomePayloads.Rejected} declares, with its aliases. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RechazoComoLoVeDisbursement(@JsonAlias("paymentRequestId") UUID disbursementId,
                                       @JsonAlias({"banxicoCode", "providerCode"}) String providerCode,
                                       @JsonAlias({"banxicoReason", "providerReason"}) String providerReason,
                                       String detail, boolean retryable, boolean technical,
                                       Instant occurredOn) {}

    private static StpPaymentOrder orden(UUID paymentRequestId) {
        return StpPaymentOrder.create(paymentRequestId, UUID.randomUUID(), UUID.randomUUID(),
                new TrackingKey("KRD2026112400000000000003"), LocalDate.of(2026, 11, 24), new BigDecimal("1131.03"),
                "ROSA ELENA IBARRA SALGADO", "646180157000000004", "hash", "40", "IASR900612B0B", 646,
                "PAGO DE COMISIONES", null, null, null,
                "646180000000000012", "FINTECH SERVICES SA DE CV SOFOM ENR", "FSE000101AAA", "40", "C1");
    }

    /** What the real publisher sends to Kafka. */
    @SuppressWarnings("unchecked")
    private static StpOrderRejectedPayload publicado(Consumer<KafkaStpEventPublisher> publica) {
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        publica.accept(new KafkaStpEventPublisher(kafka));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(KafkaStpEventPublisher.TOPIC_ORDER_REJECTED), anyString(), payload.capture());
        return (StpOrderRejectedPayload) payload.getValue();
    }

    @Test
    @DisplayName("un rechazo transitorio agotado llega definitivo: la dispersión no reintenta contra el candado")
    void exhaustedTransientIsFinal() {
        UUID id = UUID.randomUUID();
        var p = EventContract.between(publicado(pub -> pub.publishOrderRejected(orden(id),
                        BanxicoResponseCode.ENLACE_FINANCIERO_MODO_CONSULTAS, "Agotados los intentos")),
                        RechazoComoLoVeDisbursement.class)
                .requires("paymentRequestId", RechazoComoLoVeDisbursement::disbursementId)
                .requires("banxicoCode", RechazoComoLoVeDisbursement::providerCode)
                .requires("banxicoReason", RechazoComoLoVeDisbursement::providerReason)
                .verify();

        assertThat(p.disbursementId()).isEqualTo(id);
        assertThat(p.providerCode()).isEqualTo("-30");
        assertThat(p.retryable()).as("STP ya reintentó lo transitorio").isFalse();
        assertThat(p.technical()).as("falló el enlace, no una regla: otra solicitud puede pasar").isTrue();
    }

    @Test
    @DisplayName("un rechazo por una regla del banco (PLD) es definitivo y no técnico: otra solicitud no lo arregla")
    void ruleRejectionIsNotTechnical() {
        var p = EventContract.between(publicado(pub -> pub.publishOrderRejected(orden(UUID.randomUUID()),
                        BanxicoResponseCode.RECHAZO_POR_PLD, "Se rechaza por PLD")), RechazoComoLoVeDisbursement.class)
                .requires("banxicoCode", RechazoComoLoVeDisbursement::providerCode)
                .verify();

        assertThat(p.providerCode()).isEqualTo("-200");
        assertThat(p.retryable()).isFalse();
        assertThat(p.technical()).isFalse();
    }

    @Test
    @DisplayName("una orden que STP nunca registró llega como rechazo definitivo, sin código de Banxico")
    void notRegisteredIsFinal() {
        UUID id = UUID.randomUUID();
        var p = EventContract.between(publicado(pub -> pub.publishOrderNotRegistered(orden(id),
                        "STP no la registró")), RechazoComoLoVeDisbursement.class)
                .requires("paymentRequestId", RechazoComoLoVeDisbursement::disbursementId)
                .requires("banxicoReason", RechazoComoLoVeDisbursement::providerReason)
                .requires("detail", RechazoComoLoVeDisbursement::detail)
                .verify();

        assertThat(p.providerCode()).as("no es una respuesta de Banxico").isNull();
        assertThat(p.providerReason()).isEqualTo(KafkaStpEventPublisher.NOT_REGISTERED);
        assertThat(p.retryable()).isFalse();
        assertThat(p.technical()).as("nada llegó a STP").isTrue();
    }
}
