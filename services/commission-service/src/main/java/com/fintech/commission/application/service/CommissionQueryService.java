package com.fintech.commission.application.service;

import com.fintech.commission.application.port.in.GetCommissionUseCase;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import com.fintech.commission.domain.LiquidationBatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CommissionQueryService implements GetCommissionUseCase {

    private final CommissionRecordRepository repository;
    private final LiquidationBatchRepository lots;

    public CommissionQueryService(CommissionRecordRepository repository, LiquidationBatchRepository lots) {
        this.repository = repository;
        this.lots       = lots;
    }

    @Override
    public List<CommissionRecord> getByCreditAccountId(UUID creditAccountId) {
        return repository.findByCreditAccountId(creditAccountId);
    }

    @Override
    public List<CommissionRecord> getPendingByBeneficiary(UUID beneficiaryPartyId) {
        return repository.findByBeneficiaryPartyIdAndStatus(beneficiaryPartyId, CommissionRecordStatus.ACCRUED);
    }

    @Override
    public List<LiquidationBatch> getLots(String period) {
        return lots.findByPeriod(period);
    }
}
