package com.fintech.commission.application.service;

import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import com.fintech.commission.domain.LiquidationBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LB-03: groups ACCRUED records by beneficiary for a period into a single {@link LiquidationBatch}
 * — one SPEI per beneficiary instead of one per credit — only if the total meets the configured
 * minimum. T6 only decides and marks LIQUIDATED; T4 executes the actual payment upon
 * {@code CommissionLiquidated}.
 */
@Service
@Transactional
public class LiquidationService {

    private static final Logger log = LoggerFactory.getLogger(LiquidationService.class);

    private final CommissionRecordRepository recordRepository;
    private final LiquidationBatchRepository batchRepository;
    private final CommissionEventPublisher eventPublisher;
    private final CommissionProperties properties;

    public LiquidationService(CommissionRecordRepository recordRepository,
                               LiquidationBatchRepository batchRepository,
                               CommissionEventPublisher eventPublisher,
                               CommissionProperties properties) {
        this.recordRepository = recordRepository;
        this.batchRepository  = batchRepository;
        this.eventPublisher   = eventPublisher;
        this.properties       = properties;
    }

    public int runLiquidation(String period) {
        List<CommissionRecord> accrued = recordRepository.findByStatusAndPeriod(CommissionRecordStatus.ACCRUED, period);
        Map<UUID, List<CommissionRecord>> byBeneficiary = new LinkedHashMap<>();
        for (CommissionRecord r : accrued) {
            byBeneficiary.computeIfAbsent(r.getBeneficiaryPartyId(), k -> new ArrayList<>()).add(r);
        }

        int batches = 0;
        for (Map.Entry<UUID, List<CommissionRecord>> entry : byBeneficiary.entrySet()) {
            UUID beneficiaryPartyId = entry.getKey();
            List<CommissionRecord> records = entry.getValue();
            BigDecimal total = records.stream().map(CommissionRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

            if (total.compareTo(properties.getMinLiquidationAmount()) < 0) {
                log.info("Skipping liquidation for beneficiary={} period={} total={} — below minimum {}",
                        beneficiaryPartyId, period, total, properties.getMinLiquidationAmount());
                continue;
            }

            LiquidationBatch batch = LiquidationBatch.open(beneficiaryPartyId, period, total);
            batchRepository.save(batch);
            records.forEach(r -> { r.markLiquidated(batch.getBatchId()); recordRepository.save(r); });

            eventPublisher.publishCommissionLiquidated(batch);
            batches++;
            log.info("LiquidationBatch created batchId={} beneficiary={} period={} total={} records={}",
                    batch.getBatchId(), beneficiaryPartyId, period, total, records.size());
        }
        log.info("Liquidation run period={} → {} batches from {} accrued records", period, batches, accrued.size());
        return batches;
    }
}
