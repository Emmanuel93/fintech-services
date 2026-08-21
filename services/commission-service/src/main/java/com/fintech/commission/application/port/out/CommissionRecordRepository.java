package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommissionRecordRepository {
    boolean existsBySourceEventId(String sourceEventId);
    List<CommissionRecord> findByCreditAccountId(UUID creditAccountId);
    /** Más reciente primero — para la reversa "más reciente" (CM-05, ver limitación documentada). */
    Optional<CommissionRecord> findFirstByCreditAccountIdAndStatusOrderByAccrualDateDesc(
            UUID creditAccountId, CommissionRecordStatus status);
    /** Pendientes de un beneficiario, sin importar el período (para el endpoint de consulta). */
    List<CommissionRecord> findByBeneficiaryPartyIdAndStatus(UUID beneficiaryPartyId, CommissionRecordStatus status);
    /** Todo lo ACCRUED de un período, para que el job de liquidación agrupe por beneficiario. */
    List<CommissionRecord> findByStatusAndPeriod(CommissionRecordStatus status, String period);
    CommissionRecord save(CommissionRecord record);
}
