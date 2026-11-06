package com.fintech.receipt.application.service;

import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.application.port.out.ReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Takes again the receipts that failed (under the attempts cap) or stayed GENERATING after their replica died. */
@Service
public class ReceiptRetryService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptRetryService.class);
    private static final int BATCH = 200;

    private final ReceiptRepository receipts;
    private final ReceiptGenerationService generation;
    private final ReceiptProperties properties;

    public ReceiptRetryService(ReceiptRepository receipts, ReceiptGenerationService generation, ReceiptProperties properties) {
        this.receipts = receipts;
        this.generation = generation;
        this.properties = properties;
    }

    public int retry() {
        List<UUID> pending = receipts.retryable(properties.getMaxAttempts(), properties.getStaleAfter(), BATCH);
        int generated = 0;
        for (UUID id : pending) {
            if (generation.generate(id) == ReceiptGenerationService.Outcome.GENERATED) generated++;
        }
        if (!pending.isEmpty()) {
            log.info("Reintento de recibos: {} de {} generados", generated, pending.size());
        }
        return generated;
    }
}
