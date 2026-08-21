package com.fintech.commission.application.service;

import com.fintech.commission.application.port.in.GetCommissionUseCase;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CommissionQueryService implements GetCommissionUseCase {

    private final CommissionRecordRepository repository;

    public CommissionQueryService(CommissionRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CommissionRecord> getByCreditAccountId(UUID creditAccountId) {
        return repository.findByCreditAccountId(creditAccountId);
    }

    @Override
    public List<CommissionRecord> getPendingByBeneficiary(UUID beneficiaryPartyId) {
        return repository.findByBeneficiaryPartyIdAndStatus(beneficiaryPartyId, CommissionRecordStatus.ACCRUED);
    }
}
