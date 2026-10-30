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

    /**
     * La consulta de tesorería: de un crédito, o por beneficiario o clave de rastreo, del más nuevo
     * al más viejo. Los dos filtros son opcionales; vacíos, todas.
     *
     * @param creditAccountId el crédito tal como lo devuelve el eco de {@code sourceMetadata}
     * @param q fragmento del nombre del beneficiario o de la clave de rastreo
     */
    OrderPage search(UUID creditAccountId, String q, int page, int size);

    /** Una página de órdenes con su total, para que la pantalla sepa cuántas hay sin traerlas. */
    record OrderPage(List<DisbursementOrder> content, int page, int size, long totalElements) {
        public int totalPages() {
            return size <= 0 ? 0 : (int) ((totalElements + size - 1) / size);
        }
    }
}
