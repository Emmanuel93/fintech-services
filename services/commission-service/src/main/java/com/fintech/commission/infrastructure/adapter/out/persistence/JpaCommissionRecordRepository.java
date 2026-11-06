package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JpaCommissionRecordRepository
        extends JpaRepository<CommissionRecord, UUID>, CommissionRecordRepository {

    @Override
    boolean existsBySourceEventId(String sourceEventId);

    @Override
    List<CommissionRecord> findByCreditAccountId(UUID creditAccountId);

    @Override
    List<CommissionRecord> findByBeneficiaryPartyIdAndStatus(UUID beneficiaryPartyId, CommissionRecordStatus status);

    @Override
    @Query("SELECT r FROM CommissionRecord r WHERE r.status = com.fintech.commission.domain.CommissionRecordStatus.ACCRUED "
            + "AND r.period <= :period ORDER BY r.beneficiaryPartyId, r.productType, r.accrualDate")
    List<CommissionRecord> findOpenUpTo(@Param("period") String period);

    @Override
    List<CommissionRecord> findByPaymentRef(String paymentRef);

    @Override
    List<CommissionRecord> findByLiquidationBatchId(UUID batchId);
}
