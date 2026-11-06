package com.fintech.disbursement.application;

import com.fintech.disbursement.application.port.out.DisbursementEventPublisher;
import com.fintech.disbursement.application.port.out.DisbursementEventRepository;
import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.application.service.ProviderOutcomeService;
import com.fintech.disbursement.domain.Beneficiary;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.domain.FailureCode;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;
import com.fintech.disbursement.infrastructure.adapter.out.messaging.DisbursementFailedPayload;
import com.fintech.disbursement.infrastructure.adapter.out.messaging.KafkaDisbursementEventPublisher;
import com.fintech.disbursement.infrastructure.config.DisbursementTopicProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a caller hears when its payment does not happen (D63, D67): a technical rejection is told apart from a rule,
 * {@code disbursement.failed} says whether another attempt may work and which attempt this was, and a request the
 * instruction made impossible is answered with no order instead of going to the dead letters.
 */
class FailedDisbursementContractTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-05T06:05:00Z"), ZoneId.of("America/Mexico_City"));

    private static DisbursementOrder dispatched(Map<String, String> metadata) {
        DisbursementOrder order = DisbursementOrder.request(UUID.randomUUID(), "credit-portfolio",
                DisbursementSource.DISPOSITION, "disp-1", "disp-1:2", metadata,
                Beneficiary.of("JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646),
                new BigDecimal("15000.00"), "MXN", "DISPOSICION", 1L, Rail.SPEI, "cuenta-1");
        order.dispatch(Provider.STP);
        return order;
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, Object> kafka() {
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        return kafka;
    }

    private static DisbursementFailedPayload sent(KafkaTemplate<String, Object> kafka, String key) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("disbursement.failed"), eq(key), payload.capture());
        return (DisbursementFailedPayload) payload.getValue();
    }

    @Test
    @DisplayName("sólo una falla técnica o de configuración admite otro intento; una regla no")
    void whichFailuresMayWorkAgain() {
        Set<FailureCode> anotherAttempt = EnumSet.of(FailureCode.PROVIDER_TECHNICAL_FAILURE,
                FailureCode.PROVIDER_UNAVAILABLE, FailureCode.MAX_ATTEMPTS_EXCEEDED, FailureCode.NO_ROUTING_RULE,
                FailureCode.UNRESOLVED_COMPANY);
        for (FailureCode code : FailureCode.values()) {
            assertThat(code.anotherAttemptMayWork()).as(code.name()).isEqualTo(anotherAttempt.contains(code));
        }
    }

    @Test
    @DisplayName("un rechazo técnico del conector termina la orden con su propio código; uno por regla, con el del proveedor")
    void technicalRejectionHasItsOwnCode() {
        DisbursementOrderRepository orders = mock(DisbursementOrderRepository.class);
        DisbursementEventPublisher publisher = mock(DisbursementEventPublisher.class);
        ProviderOutcomeService service = new ProviderOutcomeService(orders, mock(DisbursementEventRepository.class),
                publisher, new DisbursementProperties(), CLOCK);

        DisbursementOrder link = dispatched(Map.of("attempt", "1"));
        when(orders.findById(link.getDisbursementId())).thenReturn(Optional.of(link));
        service.apply(new ProviderOutcome.Rejected(link.getDisbursementId(), "-30", "Enlace en modo consultas",
                false, true, CLOCK.instant()));

        DisbursementOrder pld = dispatched(Map.of("attempt", "1"));
        when(orders.findById(pld.getDisbursementId())).thenReturn(Optional.of(pld));
        service.apply(new ProviderOutcome.Rejected(pld.getDisbursementId(), "-200", "Se rechaza por PLD",
                false, false, CLOCK.instant()));

        assertThat(link.status()).isEqualTo(DisbursementStatus.REJECTED);
        assertThat(link.getFailureCode()).isEqualTo(FailureCode.PROVIDER_TECHNICAL_FAILURE.name());
        assertThat(pld.getFailureCode()).isEqualTo(FailureCode.REJECTED_BY_PROVIDER.name());
        verify(publisher).publishFailed(link);
        verify(publisher).publishFailed(pld);
    }

    @Test
    @DisplayName("disbursement.failed dice si otro intento puede servir y qué intento fue")
    void failedSaysRetryableAndAttempt() {
        KafkaTemplate<String, Object> kafka = kafka();
        KafkaDisbursementEventPublisher publisher = new KafkaDisbursementEventPublisher(kafka,
                new DisbursementTopicProperties(), CLOCK);
        DisbursementOrder order = dispatched(Map.of("dispositionId", "disp-1", "attempt", "2"));
        order.reject(FailureCode.PROVIDER_TECHNICAL_FAILURE.name(), "NO_REGISTRADA");

        publisher.publishFailed(order);

        DisbursementFailedPayload p = sent(kafka, order.getDisbursementId().toString());
        assertThat(p.failureCode()).isEqualTo("PROVIDER_TECHNICAL_FAILURE");
        assertThat(p.retryable()).isTrue();
        assertThat(p.attempt()).isEqualTo(2);
        assertThat(p.sourceMetadata()).containsEntry("dispositionId", "disp-1");
    }

    @Test
    @DisplayName("D67 · una solicitud imposible se responde sin orden, con las llaves del que la pidió y sin reintento")
    void rejectedRequestIsAnswered() {
        KafkaTemplate<String, Object> kafka = kafka();
        KafkaDisbursementEventPublisher publisher = new KafkaDisbursementEventPublisher(kafka,
                new DisbursementTopicProperties(), CLOCK);
        RequestDisbursementCommand command = new RequestDisbursementCommand("credit-portfolio",
                DisbursementSource.DISPOSITION, "disp-9", "disp-9", null, null,
                Map.of("dispositionId", "disp-9", "attempt", "1"), "JUAN PEREZ", "646180157000000005", "40",
                null, 646, new BigDecimal("5000.00"), "MXN", "X", 1L, Rail.SPEI, "cuenta-9");

        publisher.publishRequestRejected(command, FailureCode.INVALID_BENEFICIARY_ACCOUNT, "CLABE inválida");

        DisbursementFailedPayload p = sent(kafka, "disp-9");
        assertThat(p.disbursementId()).as("nunca fue orden").isNull();
        assertThat(p.status()).isEqualTo("REJECTED");
        assertThat(p.failureCode()).isEqualTo("INVALID_BENEFICIARY_ACCOUNT");
        assertThat(p.retryable()).isFalse();
        assertThat(p.attempt()).isEqualTo(1);
        assertThat(p.sourceReference()).isEqualTo("disp-9");
        assertThat(p.sourceMetadata()).containsEntry("dispositionId", "disp-9");
    }
}
