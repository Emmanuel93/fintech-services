package com.fintech.invoicing.infrastructure.adapter.out.pac;

import com.fintech.invoicing.application.InvoicingProperties;
import com.fintech.invoicing.application.port.out.PacStampingPort;
import com.fintech.invoicing.domain.Invoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stub del timbrado ante el PAC — simula la certificación asignando un folio fiscal (UUID) sin
 * llamar a ningún proveedor real. Reemplazar por el adaptador real (WSDL/REST del PAC + certificado
 * y llave del emisor) sin tocar el dominio.
 */
@Component
public class NoopPacAdapter implements PacStampingPort {

    private static final Logger log = LoggerFactory.getLogger(NoopPacAdapter.class);
    private final InvoicingProperties properties;

    public NoopPacAdapter(InvoicingProperties properties) { this.properties = properties; }

    @Override
    public StampResult stamp(Invoice invoice) {
        UUID folioFiscal = UUID.randomUUID();
        long folio = ThreadLocalRandom.current().nextLong(1, 1_000_000);
        log.info("[STUB PAC] timbrado simulado invoiceId={} folioFiscal={} total={}",
                invoice.getInvoiceId(), folioFiscal, invoice.getTotal());
        return new StampResult(folioFiscal, properties.getSerie(), folio);
    }
}
