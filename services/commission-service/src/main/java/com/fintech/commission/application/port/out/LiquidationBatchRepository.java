package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.LiquidationBatch;
import com.fintech.commission.domain.LiquidationBatchStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiquidationBatchRepository {

    Optional<LiquidationBatch> findById(UUID batchId);

    List<LiquidationBatch> findByStatus(LiquidationBatchStatus status);

    List<LiquidationBatch> findByPeriod(String period);

    LiquidationBatch save(LiquidationBatch batch);
}
