package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.LiquidationBatch;

import java.util.Optional;
import java.util.UUID;

public interface LiquidationBatchRepository {
    Optional<LiquidationBatch> findById(UUID batchId);
    LiquidationBatch save(LiquidationBatch batch);
}
