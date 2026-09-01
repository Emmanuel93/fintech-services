package com.fintech.closing.infrastructure.adapter.out.messaging;

import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseSeal;
import com.fintech.closing.domain.CutoffSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class KafkaClosingEventPublisher implements ClosingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaClosingEventPublisher.class);

    static final String TOPIC_WINDOW_OPENED = "closing.unit-window-opened";
    static final String TOPIC_CUTOFF_CLOSED = "closing.cutoff-closed";
    static final String TOPIC_DAY_SEALED    = "closing.day-sealed";

    private final KafkaTemplate<String, Object> kafka;

    public KafkaClosingEventPublisher(KafkaTemplate<String, Object> kafka) { this.kafka = kafka; }

    /**
     * La ventana abierta de una unidad. <b>Es un hecho, no una orden</b>: dice que el momento llegó,
     * no cómo devengar. La clave es la cuenta, así que el orden por crédito queda garantizado dentro
     * de su partición — que es lo que permite escalar el ejecutor particionando.
     */
    @Override
    public void publishUnitWindowOpened(UUID runId, UUID creditAccountId, ClosePhase phase,
                                         LocalDate businessDate, String productType) {
        var payload = new UnitWindowOpenedPayload(UUID.randomUUID().toString(), runId, creditAccountId,
                phase.name(), businessDate, productType, Instant.now());
        enviar(TOPIC_WINDOW_OPENED, creditAccountId.toString(), payload);
    }

    @Override
    public void publishCutoffClosed(CutoffSchedule c, UUID obligorPartyId, String productType) {
        var payload = new CutoffClosedPayload(UUID.randomUUID().toString(), c.getCreditAccountId(),
                obligorPartyId, productType, c.getCycleNumber(), c.getCutoffDate(), c.getPaymentDueDate(),
                c.getBalanceAtCutoff(), c.getAmountDue(), c.getMinimumPayment(), Instant.now());
        enviar(TOPIC_CUTOFF_CLOSED, c.getCreditAccountId().toString(), payload);
    }

    @Override
    public void publishDaySealed(CloseSeal s) {
        var payload = new DaySealedPayload(s.getSealId().toString(), s.getBusinessDate(),
                s.phase().name(), s.getScopeKey(), s.getUnitCount(), s.getTotalPrincipal(),
                s.getTotalInterest(), s.getTotalDebt(), s.getContentHash(), s.getSealedAt());
        enviar(TOPIC_DAY_SEALED, s.getBusinessDate() + ":" + s.phase(), payload);
    }

    private void enviar(String topic, String key, Object payload) {
        kafka.send(topic, key, payload).whenComplete((r, ex) -> {
            if (ex != null) log.error("Publicación fallida topic={} key={}: {}", topic, key, ex.getMessage());
            else log.debug("Publicado topic={} key={}", topic, key);
        });
    }
}
