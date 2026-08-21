package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.domain.LiquidationBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaLiquidationBatchRepository
        extends JpaRepository<LiquidationBatch, UUID>, LiquidationBatchRepository {
}
