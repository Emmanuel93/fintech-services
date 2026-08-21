package com.fintech.disbursement.application.port.out;

import com.fintech.disbursement.domain.DisbursementOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisbursementOrderRepository {

    DisbursementOrder save(DisbursementOrder order);

    Optional<DisbursementOrder> findById(UUID disbursementId);

    /** DB-02: la guarda de idempotencia. La unicidad real la impone la base de datos. */
    Optional<DisbursementOrder> findBySourceKey(String sourceSystem, String sourceType, String sourceEventId);

    /**
     * Toma un lote de órdenes despachables con {@code FOR UPDATE SKIP LOCKED}: varias réplicas
     * pueden correr el job a la vez sin pisarse y sin traer un coordinador externo al repo.
     */
    List<DisbursementOrder> lockDueForDispatch(int batchSize);

    List<DisbursementOrder> findByCompany(UUID companyId, int page, int size);
}
