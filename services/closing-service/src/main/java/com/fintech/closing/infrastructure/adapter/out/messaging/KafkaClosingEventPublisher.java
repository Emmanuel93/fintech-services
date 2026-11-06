package com.fintech.closing.infrastructure.adapter.out.messaging;

import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import com.fintech.closing.domain.CloseSeal;
import com.fintech.closing.domain.CutoffSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaClosingEventPublisher implements ClosingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaClosingEventPublisher.class);

    static final String TOPIC_WINDOW_OPENED = "closing.unit-window-opened";
    public static final String TOPIC_CUTOFF_CLOSED = "closing.cutoff-closed";
    static final String TOPIC_DAY_SEALED    = "closing.day-sealed";
    public static final String TOPIC_CUTOFF_DUE           = "closing.cutoff-due";
    static final String TOPIC_CUTOFF_RUN_PLANNED   = "closing.cutoff-run-planned";
    static final String TOPIC_CUTOFF_RUN_COMPLETED = "closing.cutoff-run-completed";

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
        send(TOPIC_WINDOW_OPENED, creditAccountId.toString(), payload);
    }

    @Override
    public void publishCutoffClosed(CutoffSchedule c, AccountCloseProfile profile, UUID runId,
                                    LocalDate periodStart, String holderKind, int daysDelinquent) {
        // Days past due as of the cutoff date, not the projection's current value: a held account sealed
        // days later still prints how it stood on the day it cut.
        var payload = new CutoffClosedPayload(UUID.randomUUID().toString(), runId, c.getCreditAccountId(),
                profile.getObligorPartyId(), profile.getProductType(), profile.getProductBehavior(), holderKind,
                c.getCycleNumber(), periodStart, c.getCutoffDate(), c.getPaymentDueDate(),
                c.getBalanceAtCutoff(), c.getPrincipalAtCutoff(), c.getInterestAtCutoff(),
                c.getAmountDue(), c.getMinimumPayment(), daysDelinquent, Instant.now());
        send(TOPIC_CUTOFF_CLOSED, c.getCreditAccountId().toString(), payload);
    }

    @Override
    public CompletableFuture<Void> publishCutoffDue(CutoffUnitStore.Dispatchable u, LocalDate businessDate) {
        var payload = new CutoffDuePayload(UUID.randomUUID().toString(), u.runId(), u.unitId(),
                u.creditAccountId(), u.obligorPartyId(), u.productType(), u.productBehavior(), u.holderKind(),
                businessDate, Instant.now());
        // La llave es la cuenta: la misma cuenta cae siempre en la misma partición, y el reparto
        // entre pods lo hace Kafka al asignar particiones al grupo.
        return kafka.send(TOPIC_CUTOFF_DUE, u.creditAccountId().toString(), payload).thenApply(r -> null);
    }

    @Override
    public void publishCutoffRunPlanned(CloseRun run, List<CutoffUnitStore.Breakdown> breakdown) {
        var lines = breakdown.stream()
                .map(b -> new CutoffRunPlannedPayload.Line(b.productType(), b.productBehavior(), b.holderKind(), b.units()))
                .toList();
        var payload = new CutoffRunPlannedPayload(UUID.randomUUID().toString(), run.getRunId(),
                run.getBusinessDate(), run.getPlannedUnits(), lines, Instant.now());
        send(TOPIC_CUTOFF_RUN_PLANNED, run.getRunId().toString(), payload);
    }

    @Override
    public void publishCutoffRunCompleted(CloseRun run, CutoffUnitStore.StatusCounts counts) {
        var payload = new CutoffRunCompletedPayload(UUID.randomUUID().toString(), run.getRunId(),
                run.getBusinessDate(), run.status().name(), run.getPlannedUnits(), counts.done(),
                counts.failed(), counts.skipped(), Instant.now(), counts.held());
        // Se espera al broker: cargos no devenga el día siguiente hasta recibir este aviso. Si no sale,
        // lanza, y la reanudación lo vuelve a mandar.
        try {
            kafka.send(TOPIC_CUTOFF_RUN_COMPLETED, run.getRunId().toString(), payload)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido al avisar el fin del corte del " + run.getBusinessDate(), e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("No se pudo avisar el fin del corte del " + run.getBusinessDate(), e);
        }
    }

    @Override
    public void publishDaySealed(CloseSeal s) {
        var payload = new DaySealedPayload(s.getSealId().toString(), s.getBusinessDate(),
                s.phase().name(), s.getScopeKey(), s.getUnitCount(), s.getTotalPrincipal(),
                s.getTotalInterest(), s.getTotalDebt(), s.getContentHash(), s.getSealedAt());
        send(TOPIC_DAY_SEALED, s.getBusinessDate() + ":" + s.phase(), payload);
    }

    private void send(String topic, String key, Object payload) {
        kafka.send(topic, key, payload).whenComplete((r, ex) -> {
            if (ex != null) log.error("Publicación fallida topic={} key={}: {}", topic, key, ex.getMessage());
            else log.debug("Publicado topic={} key={}", topic, key);
        });
    }
}
