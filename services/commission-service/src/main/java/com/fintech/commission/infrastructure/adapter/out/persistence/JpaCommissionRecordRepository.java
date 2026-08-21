package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaCommissionRecordRepository
        extends JpaRepository<CommissionRecord, UUID>, CommissionRecordRepository {

    @Override
    boolean existsBySourceEventId(String sourceEventId);

    @Override
    List<CommissionRecord> findByCreditAccountId(UUID creditAccountId);

    @Override
    Optional<CommissionRecord> findFirstByCreditAccountIdAndStatusOrderByAccrualDateDesc(
            UUID creditAccountId, CommissionRecordStatus status);

    @Override
    List<CommissionRecord> findByBeneficiaryPartyIdAndStatus(UUID beneficiaryPartyId, CommissionRecordStatus status);

    @Override
    List<CommissionRecord> findByStatusAndPeriod(CommissionRecordStatus status, String period);
}
