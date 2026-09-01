package com.fintech.disbursement.application.service;

import com.fintech.disbursement.application.DisbursementProperties;
import com.fintech.disbursement.application.port.in.DispatchDisbursementsUseCase;
import com.fintech.disbursement.application.port.out.DisbursementEventPublisher;
import com.fintech.disbursement.application.port.out.DisbursementEventRepository;
import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.application.port.out.PayoutRouteResolverPort;
import com.fintech.disbursement.application.port.out.ProviderDispatchPort;
import com.fintech.disbursement.domain.DisbursementEvent;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.domain.FailureCode;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Despacha lo pendiente al conector del proveedor.
 *
 * <p>El lote se toma con {@code FOR UPDATE SKIP LOCKED}, así que varias réplicas corren el job a la
 * vez sin pisarse. No hace falta un coordinador externo — el legado usaba dos crons y cuatro
 * réplicas descoordinadas para esto mismo.
 *
 * <p><strong>La entrega al conector ocurre fuera de la transacción.</strong> Hacerla dentro
 * mantendría abiertos los locks de hasta 50 filas durante el round-trip al broker, y dejaría al
 * resultado del conector —que llega en milisegundos— compitiendo por escribir la misma fila. El
 * ciclo es: una transacción corta que reclama el lote, la entrega fuera, y una transacción corta por
 * cada fallo.
 *
 * <p><strong>Entrega al menos una vez, a propósito.</strong> Si el proceso muere entre reclamar y
 * entregar, la orden queda en {@code DISPATCHED} y el conector nunca la recibió; el operador la ve
 * en la bitácora. Si muere entre entregar y registrar el fallo, se reenvía. Es correcto porque el
 * conector es idempotente por {@code paymentRequestId} — y lo impone con una restricción única en su
 * propia base, no con una promesa.
 */
@Service
public class DisbursementDispatchService implements DispatchDisbursementsUseCase {

    private static final Logger log = LoggerFactory.getLogger(DisbursementDispatchService.class);
    private static final int MAX_BACKOFF_EXPONENT = 6;

    private final DisbursementOrderRepository orders;
    private final DisbursementEventRepository events;
    private final ProviderDispatchPort providerDispatch;
    private final DisbursementEventPublisher publisher;
    private final RoutingService routing;
    private final DisbursementProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DisbursementDispatchService(DisbursementOrderRepository orders,
                                       DisbursementEventRepository events,
                                       ProviderDispatchPort providerDispatch,
                                       DisbursementEventPublisher publisher,
                                       RoutingService routing,
                                       DisbursementProperties properties,
                                       TransactionTemplate transactionTemplate,
                                       Clock clock) {
        this.orders = orders;
        this.events = events;
        this.providerDispatch = providerDispatch;
        this.publisher = publisher;
        this.routing = routing;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** Orden ya reclamada y comprometida en base, lista para entregar al conector. */
    private record Claim(DisbursementOrder order, PayoutRouteResolverPort.PayoutRoute route) {}

    @Override
    public int dispatchDue() {
        List<Claim> claims = transactionTemplate.execute(status -> claimBatch());
        if (claims == null || claims.isEmpty()) {
            return 0;
        }

        int dispatched = 0;
        for (Claim claim : claims) {
            try {
                providerDispatch.dispatch(claim.order(), claim.route());
                dispatched++;
            } catch (RuntimeException e) {
                log.error("No se pudo despachar disbursementId={} provider={}: {}",
                        claim.order().getDisbursementId(), claim.route().provider(), e.getMessage());
                UUID id = claim.order().getDisbursementId();
                String detail = e.getMessage();
                transactionTemplate.executeWithoutResult(status -> handleDispatchFailure(id, detail));
            }
        }
        return dispatched;
    }

    /** Transacción corta: reclama el lote y lo deja marcado {@code DISPATCHED} en base. */
    private List<Claim> claimBatch() {
        List<DisbursementOrder> batch = orders.lockDueForDispatch(properties.getDispatch().getBatchSize());
        List<Claim> claims = new ArrayList<>(batch.size());
        Instant now = clock.instant();

        for (DisbursementOrder order : batch) {
            Rail rail = order.railValue();

            if (!routing.isRailEnabled(rail)) {
                defer(order, FailureCode.NO_ROUTING_RULE, "El rail " + rail + " está deshabilitado", now);
                continue;
            }
            if (!routing.isOpen(rail, now)) {
                order.scheduleFor(routing.nextOpening(rail, now));   // esperar no gasta intento
                orders.save(order);
                continue;
            }

            Optional<PayoutRouteResolverPort.PayoutRoute> route;
            try {
                route = routing.findRoute(order.getCompanyId(), rail, order.getAmount());
            } catch (PayoutRouteResolverPort.PayoutRoutingUnavailableException e) {
                // Tesorería caída NO es configuración incompleta. Si esto gastara intento, una
                // indisponibilidad de minutos agotaría los seis intentos de órdenes perfectamente
                // válidas y las dejaría FAILED. Se espera, como fuera de ventana.
                log.warn("Tesorería no responde; la orden {} espera sin gastar intento: {}",
                        order.getDisbursementId(), e.getMessage());
                order.scheduleFor(now.plus(properties.getBanking().getUnavailableBackoff()));
                orders.save(order);
                continue;
            }

            if (route.isEmpty()) {
                defer(order, FailureCode.NO_ROUTING_RULE,
                        "Tesorería no tiene ruta para companyId=" + order.getCompanyId()
                                + " rail=" + rail + " monto=" + order.getAmount(), now);
                continue;
            }

            Provider provider = route.get().provider();
            DisbursementStatus from = order.status();
            order.dispatch(provider);
            orders.save(order);
            events.save(DisbursementEvent.record(order, from, DisbursementStatus.DISPATCHED,
                    null, "provider=" + provider + " cuenta=" + route.get().bankAccountId()
                            + " intento=" + order.getAttemptCount(), "SYSTEM"));
            claims.add(new Claim(order, route.get()));
        }
        return claims;
    }

    /** Transacción corta: relee la orden con datos frescos y aplica el fallo de entrega. */
    private void handleDispatchFailure(UUID disbursementId, String detail) {
        orders.findById(disbursementId).ifPresent(order -> {
            if (order.isTerminal() || order.status() != DisbursementStatus.DISPATCHED) {
                // El conector alcanzó a responder antes que nosotros a registrar el fallo. Su
                // palabra vale más que nuestro timeout: se deja como está.
                log.info("Fallo de entrega descartado, la orden ya avanzó a {} disbursementId={}",
                        order.getStatus(), disbursementId);
                return;
            }
            retryOrFail(order, FailureCode.PROVIDER_UNAVAILABLE,
                    "Fallo al entregar al conector: " + detail, clock.instant());
        });
    }

    /** No hubo despacho real, pero sí gasta intento: una configuración incompleta tiene que doler. */
    private void defer(DisbursementOrder order, FailureCode code, String reason, Instant now) {
        DisbursementStatus from = order.status();
        int attempts = order.getAttemptCount() + 1;
        if (attempts >= properties.getDispatch().getMaxAttempts()) {
            terminateAsFailed(order, code, reason);
            return;
        }
        order.deferAttempt(code.name(), reason, nextAttempt(attempts, now));
        orders.save(order);
        // DB-06: también el diferimiento es una transición y deja evidencia. Si no, una orden que
        // rebota cinco veces contra una configuración incompleta se ve idéntica a una recién creada.
        events.save(DisbursementEvent.record(order, from, DisbursementStatus.REQUESTED,
                code.name(), reason + " · intento " + attempts, "SYSTEM"));
        log.warn("Desembolso diferido disbursementId={} motivo={} intento={}",
                order.getDisbursementId(), code, attempts);
    }

    private void retryOrFail(DisbursementOrder order, FailureCode code, String reason, Instant now) {
        // dispatch() ya incrementó el contador: el intento ocurrió, lo que falló fue la entrega.
        int attempts = order.getAttemptCount();
        if (attempts >= properties.getDispatch().getMaxAttempts()) {
            terminateAsFailed(order, FailureCode.MAX_ATTEMPTS_EXCEEDED, reason);
            return;
        }
        DisbursementStatus from = order.status();
        order.scheduleRetry(code.name(), reason, nextAttempt(attempts, now));
        orders.save(order);
        events.save(DisbursementEvent.record(order, from, DisbursementStatus.REQUESTED,
                code.name(), reason + " · intento " + attempts, "SYSTEM"));
    }

    private void terminateAsFailed(DisbursementOrder order, FailureCode code, String reason) {
        DisbursementStatus from = order.status();
        order.fail(code.name(), reason);
        orders.save(order);
        events.save(DisbursementEvent.record(order, from, DisbursementStatus.FAILED,
                code.name(), reason, "SYSTEM"));
        publisher.publishFailed(order);
        log.error("Desembolso agotado disbursementId={} code={} — requiere revisión manual",
                order.getDisbursementId(), code);
    }

    private Instant nextAttempt(int attempts, Instant now) {
        int exponent = Math.min(Math.max(attempts, 1), MAX_BACKOFF_EXPONENT);
        return now.plus(properties.getDispatch().getRetryBackoff().multipliedBy(1L << (exponent - 1)));
    }
}
