package com.fintech.invoicing.application.port.out;

import com.fintech.invoicing.domain.Invoice;
import com.fintech.invoicing.domain.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository {
    boolean existsByInvoiceRequestId(UUID invoiceRequestId);
    Optional<Invoice> findById(UUID invoiceId);
    List<Invoice> findByObligorPartyId(UUID obligorPartyId);
    Invoice save(Invoice invoice);

    /**
     * Búsqueda paginada por período, estatus, cliente y <b>crédito</b>.
     *
     * <p>El filtro por crédito cruza contra las líneas, que es donde vive {@code creditAccountId}: la
     * factura consolida varios préstamos de un mismo cliente, así que la relación es de la línea, no
     * del encabezado. Sin este cruce, «qué se le facturó a este contrato» obliga a traer todas las
     * facturas del cliente y filtrarlas en el consumidor.
     */
    Page<Invoice> search(String period, InvoiceStatus status, UUID partyId,
                         UUID creditAccountId, Pageable pageable);
}
