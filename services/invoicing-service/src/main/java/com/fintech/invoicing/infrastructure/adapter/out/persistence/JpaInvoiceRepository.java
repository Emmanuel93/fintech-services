package com.fintech.invoicing.infrastructure.adapter.out.persistence;

import com.fintech.invoicing.application.port.out.InvoiceRepository;
import com.fintech.invoicing.domain.Invoice;
import com.fintech.invoicing.domain.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JpaInvoiceRepository extends JpaRepository<Invoice, UUID>, InvoiceRepository {

    @Override
    boolean existsByInvoiceRequestId(UUID invoiceRequestId);

    @Override
    List<Invoice> findByObligorPartyId(UUID obligorPartyId);

    /**
     * Guardas {@code :x IS NULL OR …}, el mismo patrón que ya usan cartera y cobranza: un solo plan
     * de consulta para todas las combinaciones de filtros, en vez de una consulta derivada por cada
     * una.
     *
     * <p>{@code EXISTS} y no {@code JOIN} para el crédito: con JOIN, una factura con tres líneas del
     * mismo contrato saldría tres veces y la paginación contaría duplicados.
     */
    @Override
    @Query("""
            SELECT i FROM Invoice i
             WHERE (:period IS NULL OR i.period = :period)
               AND (:status IS NULL OR i.status = :status)
               AND (:partyId IS NULL OR i.obligorPartyId = :partyId)
               AND (:creditAccountId IS NULL OR EXISTS (
                        SELECT 1 FROM InvoiceLine l
                         WHERE l.invoice = i AND l.creditAccountId = :creditAccountId))
            """)
    Page<Invoice> search(@Param("period") String period,
                         @Param("status") InvoiceStatus status,
                         @Param("partyId") UUID partyId,
                         @Param("creditAccountId") UUID creditAccountId,
                         Pageable pageable);
}
