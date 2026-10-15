package com.fintech.banking.application.service;

import com.fintech.banking.application.port.out.MovementTraceRepository;
import com.fintech.banking.domain.MovementTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Enhebra la cadena del dinero conforme van llegando los hechos.
 *
 * <p><b>Cada tramo es idempotente y tolera el desorden.</b> Kafka no garantiza orden entre topics:
 * la conciliación de un pago puede llegar antes que su despacho si el poller del banco corrió
 * primero. Un tramo que llega antes que el anterior <b>no se descarta</b> — se guarda, y el que
 * faltaba lo completa después. Descartarlo dejaría trazas eternamente incompletas por una carrera
 * que no es un error.
 */
@Service
public class MovementTraceService {

    private static final Logger log = LoggerFactory.getLogger(MovementTraceService.class);

    private final MovementTraceRepository repo;

    public MovementTraceService(MovementTraceRepository repo) { this.repo = repo; }

    @Transactional
    public void onRequest(UUID payoutId, String sourceSystem, String sourceReference,
                            UUID creditAccountId, BigDecimal amount, String eventId) {
        repo.findByPayoutId(payoutId).ifPresentOrElse(
                t -> log.debug("La traza del pago {} ya existía — idempotente", payoutId),
                () -> repo.save(MovementTrace.open(payoutId, sourceSystem, sourceReference,
                        creditAccountId, amount, eventId)));
    }

    @Transactional
    public void onRoute(UUID payoutId, UUID bankAccountId, String eventId) {
        trace(payoutId).ifPresent(t -> { t.routed(bankAccountId, eventId); repo.save(t); });
    }

    @Transactional
    public void onDispatch(UUID payoutId, String trackingKey, LocalDate businessDate, String eventId) {
        trace(payoutId).ifPresent(t -> { t.dispatched(trackingKey, businessDate, eventId); repo.save(t); });
    }

    /**
     * El banco reportó el movimiento. Se busca por clave de rastreo porque a esta altura del camino
     * el {@code payoutId} ya no viaja: el estado de cuenta sólo trae lo que la red bancaria conoce.
     */
    @Transactional
    public void onReconcile(String trackingKey, UUID lineId, String eventId) {
        repo.findByTrackingKey(trackingKey).ifPresentOrElse(
                t -> { t.reconciled(lineId, eventId); repo.save(t); },
                () -> log.warn("Movimiento conciliado con clave {} sin traza: el pago no salió de "
                        + "aquí, o su despacho aún no llega", trackingKey));
    }

    @Transactional
    public void onPost(UUID payoutId, String voucherRef, String eventId) {
        trace(payoutId).ifPresent(t -> { t.posted(voucherRef, eventId); repo.save(t); });
    }

    // ── Las cuatro preguntas ────────────────────────────────────────────────

    /** «Este crédito, ¿por dónde salió su dinero?» */
    @Transactional(readOnly = true)
    public List<MovementTrace> byCreditAccount(UUID creditAccountId) {
        return repo.findByCreditAccountId(creditAccountId);
    }

    /** «Esta clave de rastreo, ¿de qué crédito era?» */
    @Transactional(readOnly = true)
    public Optional<MovementTrace> byTrackingKey(String trackingKey) {
        return repo.findByTrackingKey(trackingKey);
    }

    /** «Este movimiento del banco, ¿a qué corresponde?» */
    @Transactional(readOnly = true)
    public Optional<MovementTrace> byStatementLine(UUID lineId) {
        return repo.findByLineId(lineId);
    }

    /** «Esta póliza, ¿qué dinero real la respalda?» */
    @Transactional(readOnly = true)
    public List<MovementTrace> byJournalEntry(String voucherRef) {
        return repo.findByVoucherRef(voucherRef);
    }

    /** Lo que se quedó a medias, con el eslabón en que se detuvo. */
    @Transactional(readOnly = true)
    public List<MovementTrace> incomplete(int limite) {
        return repo.findIncomplete(limite);
    }

    private Optional<MovementTrace> trace(UUID payoutId) {
        Optional<MovementTrace> t = repo.findByPayoutId(payoutId);
        if (t.isEmpty()) {
            log.warn("Hecho sobre el pago {} sin traza abierta — llegó antes que su solicitud", payoutId);
        }
        return t;
    }
}
