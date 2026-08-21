package com.fintech.disbursement.application.service;

import com.fintech.disbursement.application.DisbursementProperties;
import com.fintech.disbursement.application.ProviderOutcome;
import com.fintech.disbursement.application.port.in.ApplyProviderOutcomeUseCase;
import com.fintech.disbursement.application.port.out.DisbursementEventPublisher;
import com.fintech.disbursement.application.port.out.DisbursementEventRepository;
import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.domain.DisbursementEvent;
import com.fintech.disbursement.domain.DisbursementNotFoundException;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.domain.FailureCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Aplica el resultado que reporta un conector.
 *
 * <p>Todo lo que entra aquí ya viene traducido a {@link ProviderOutcome}: ni un código de Banxico ni
 * una clave de rastreo. Si mañana entra otro proveedor, este archivo no cambia.
 *
 * <p>Idempotente: un resultado repetido sobre una orden que ya está en ese estado se registra en el
 * log y se ignora. Los conectores entregan al menos una vez; darlo por hecho es más barato que
 * fingir que no pasa.
 */
@Service
public class ProviderOutcomeService implements ApplyProviderOutcomeUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProviderOutcomeService.class);
    private static final int MAX_BACKOFF_EXPONENT = 6;

    private final DisbursementOrderRepository orders;
    private final DisbursementEventRepository events;
    private final DisbursementEventPublisher publisher;
    private final DisbursementProperties properties;
    private final Clock clock;

    public ProviderOutcomeService(DisbursementOrderRepository orders,
                                  DisbursementEventRepository events,
                                  DisbursementEventPublisher publisher,
                                  DisbursementProperties properties,
                                  Clock clock) {
        this.orders = orders;
        this.events = events;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void apply(ProviderOutcome outcome) {
        DisbursementOrder order = orders.findById(outcome.disbursementId())
                .orElseThrow(() -> new DisbursementNotFoundException(
                        "El conector reportó un resultado de una orden que no existe: "
                                + outcome.disbursementId()));

        switch (outcome) {
            case ProviderOutcome.Accepted accepted -> applyAccepted(order, accepted);
            case ProviderOutcome.Settled settled -> applySettled(order, settled);
            case ProviderOutcome.Rejected rejected -> applyRejected(order, rejected);
            case ProviderOutcome.Returned returned -> applyReturned(order, returned);
        }
    }

    private void applyAccepted(DisbursementOrder order, ProviderOutcome.Accepted accepted) {
        if (order.isTerminal() || order.status() == DisbursementStatus.ACCEPTED) {
            log.debug("Aceptación ignorada, orden en {} disbursementId={}",
                    order.getStatus(), order.getDisbursementId());
            return;
        }
        DisbursementStatus from = order.status();
        order.accept(accepted.externalRef());
        orders.save(order);
        events.save(DisbursementEvent.record(order, from, DisbursementStatus.ACCEPTED,
                null, "externalRef=" + accepted.externalRef(), "PROVIDER"));
        publisher.publishAccepted(order);
    }

    /** DB-03: el único camino a {@code SETTLED}, y sólo con evidencia del proveedor. */
    private void applySettled(DisbursementOrder order, ProviderOutcome.Settled settled) {
        if (order.status() == DisbursementStatus.SETTLED) {
            return;
        }
        if (order.isTerminal()) {
            log.warn("Liquidación sobre orden terminal {} disbursementId={} — se ignora y se alerta",
                    order.getStatus(), order.getDisbursementId());
            return;
        }
        DisbursementStatus from = order.status();
        order.settle(settled.externalRef(), settled.receiptUrl(), settled.settledAt());
        orders.save(order);
        events.save(DisbursementEvent.record(order, from, DisbursementStatus.SETTLED,
                null, "receipt=" + settled.receiptUrl()
                        + " nombreCoincide=" + settled.beneficiaryNameMatches(), "PROVIDER"));
        publisher.publishCompleted(order, settled.beneficiaryNameMatches());

        if (!settled.beneficiaryNameMatches()) {
            // No cambia el resultado — el dinero llegó — pero sí es materia de revisión.
            log.warn("Liquidado con nombre de beneficiario distinto disbursementId={}",
                    order.getDisbursementId());
        }
    }

    private void applyRejected(DisbursementOrder order, ProviderOutcome.Rejected rejected) {
        if (order.isTerminal()) {
            log.debug("Rechazo ignorado, orden ya terminal {} disbursementId={}",
                    order.getStatus(), order.getDisbursementId());
            return;
        }
        DisbursementStatus from = order.status();

        // DB-08: transitorio vuelve a la cola; sólo lo terminal termina.
        if (rejected.retryable() && order.getAttemptCount() < properties.getDispatch().getMaxAttempts()) {
            order.scheduleRetry(rejected.providerCode(), rejected.reason(),
                    nextAttempt(order.getAttemptCount()));
            orders.save(order);
            events.save(DisbursementEvent.record(order, from, DisbursementStatus.REQUESTED,
                    rejected.providerCode(), "Rechazo transitorio: " + rejected.reason(), "PROVIDER"));
            return;
        }

        order.reject(FailureCode.REJECTED_BY_PROVIDER.name(),
                rejected.providerCode() + " " + rejected.reason());
        orders.save(order);
        events.save(DisbursementEvent.record(order, from, DisbursementStatus.REJECTED,
                rejected.providerCode(), rejected.reason(), "PROVIDER"));
        publisher.publishFailed(order);
    }

    /**
     * Backoff exponencial sobre la base configurada. Plano no sirve: una ventana de mantenimiento
     * del proveedor de media hora agotaría los seis intentos en minuto y medio y rechazaría en firme
     * un pago que sí se podía hacer.
     */
    private Instant nextAttempt(int attempts) {
        int exponent = Math.min(Math.max(attempts, 1), MAX_BACKOFF_EXPONENT);
        return clock.instant().plus(
                properties.getDispatch().getRetryBackoff().multipliedBy(1L << (exponent - 1)));
    }

    private void applyReturned(DisbursementOrder order, ProviderOutcome.Returned returned) {
        if (order.status() == DisbursementStatus.RETURNED) {
            return;
        }
        DisbursementStatus from = order.status();
        order.markReturned(returned.causeCode());
        orders.save(order);
        events.save(DisbursementEvent.record(order, from, DisbursementStatus.RETURNED,
                returned.causeCode(), "Devuelto por el banco receptor", "PROVIDER"));
        publisher.publishReturned(order);
    }
}
