package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;

import java.util.List;
import java.util.UUID;

public interface CommissionRecordRepository {

    boolean existsBySourceEventId(String sourceEventId);

    List<CommissionRecord> findByCreditAccountId(UUID creditAccountId);

    /** Pendientes de un beneficiario, sin importar el período (para el endpoint de consulta). */
    List<CommissionRecord> findByBeneficiaryPartyIdAndStatus(UUID beneficiaryPartyId, CommissionRecordStatus status);

    /** Everything accrued up to a period and not in a lot yet: what a run takes, the carry-over included. */
    List<CommissionRecord> findOpenUpTo(String period);

    /** The commissions (and their reversals) a payment order produced. */
    List<CommissionRecord> findByPaymentRef(String paymentRef);

    List<CommissionRecord> findByLiquidationBatchId(UUID batchId);

    CommissionRecord save(CommissionRecord record);
}
