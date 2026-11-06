package com.fintech.commission.application.service;

import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.domain.LiquidationBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * What became of a lot's SPEI, as disbursement reports it: settled, the lot is paid; failed or came back, the lot waits
 * for the next run, and accounting is told so it undoes the attempt's move to the SPEI in flight.
 */
@Service
@Transactional
public class LotPayoutOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(LotPayoutOutcomeService.class);

    private final LiquidationBatchRepository batches;
    private final CommissionEventPublisher eventPublisher;

    public LotPayoutOutcomeService(LiquidationBatchRepository batches, CommissionEventPublisher eventPublisher) {
        this.batches        = batches;
        this.eventPublisher = eventPublisher;
    }

    public void onPaid(UUID batchId, String trackingKey, Instant settledAt) {
        batches.findById(batchId).ifPresentOrElse(lot -> {
            if (lot.confirmPaid(trackingKey, settledAt)) {
                batches.save(lot);
                log.info("Lote de comisiones {} pagado: neto {} clave {}", batchId, lot.getNetAmount(), trackingKey);
            }
        }, () -> log.warn("Pago de un lote de comisiones que no existe batchId={}", batchId));
    }

    /** The order of attempt {@code attempt} failed; an outcome of an earlier attempt changes nothing. */
    public void onFailed(UUID batchId, int attempt, String reason) {
        LiquidationBatch lot = batches.findById(batchId).orElse(null);
        if (lot == null) {
            log.warn("Falla del pago de un lote de comisiones que no existe batchId={}", batchId);
            return;
        }
        if (attempt != lot.getPayoutAttempts() || !lot.payoutFailed(reason)) {
            log.info("Falla del intento {} del lote {} ignorada: el lote va en el intento {} ({})", attempt, batchId,
                    lot.getPayoutAttempts(), lot.getStatus());
            return;
        }
        batches.save(lot);
        eventPublisher.publishLotPayoutFailed(lot, attempt, reason);
        log.warn("El SPEI del lote de comisiones {} (intento {}) no se pagó: {} — se pide otra vez en la siguiente corrida",
                batchId, attempt, reason);
    }
}
