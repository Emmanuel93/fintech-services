package com.fintech.invoicing.application.service;

import com.fintech.invoicing.application.InvoiceRequestCommand;
import com.fintech.invoicing.application.InvoicingProperties;
import com.fintech.invoicing.application.port.in.GetInvoiceUseCase;
import com.fintech.invoicing.application.port.out.FiscalProfileRepository;
import com.fintech.invoicing.application.port.out.InvoiceRepository;
import com.fintech.invoicing.application.port.out.InvoicingEventPublisher;
import com.fintech.invoicing.application.port.out.PacStampingPort;
import com.fintech.invoicing.domain.FiscalProfile;
import com.fintech.invoicing.domain.Invoice;
import com.fintech.invoicing.domain.InvoiceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.fintech.invoicing.domain.InvoiceStatus;

import java.util.List;
import java.util.UUID;

/**
 * Genera la factura (CFDI) al recibir una solicitud consolidada de Accounting. El receptor sale del
 * FiscalProfile local; si el party aún no tiene perfil fiscal, se usa el RFC genérico "público en
 * general". Luego se timbra vía el PAC (stub por ahora) y se persiste.
 */
@Service
@Transactional
public class InvoiceService implements GetInvoiceUseCase {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final FiscalProfileRepository fiscalProfileRepository;
    private final PacStampingPort pacPort;
    private final InvoicingEventPublisher eventPublisher;
    private final InvoicingProperties properties;

    public InvoiceService(InvoiceRepository invoiceRepository,
                           FiscalProfileRepository fiscalProfileRepository,
                           PacStampingPort pacPort,
                           InvoicingEventPublisher eventPublisher,
                           InvoicingProperties properties) {
        this.invoiceRepository       = invoiceRepository;
        this.fiscalProfileRepository = fiscalProfileRepository;
        this.pacPort                 = pacPort;
        this.eventPublisher          = eventPublisher;
        this.properties              = properties;
    }

    public void onInvoiceRequested(InvoiceRequestCommand cmd) {
        if (invoiceRepository.existsByInvoiceRequestId(cmd.invoiceRequestId())) {
            log.debug("Invoice already exists for requestId={} — skipping", cmd.invoiceRequestId());
            return;
        }

        Receptor r = resolveReceptor(cmd.obligorPartyId());
        Invoice invoice = Invoice.draft(cmd.invoiceRequestId(), cmd.obligorPartyId(), cmd.period(),
                r.rfc, r.name, r.regime, r.zip, r.cfdiUse,
                cmd.subtotal(), cmd.iva(), cmd.total(), "MXN");
        for (InvoiceRequestCommand.Line line : cmd.lines()) {
            invoice.addLine(line.concept(), line.creditAccountId(), line.amount(), line.isIva());
        }

        // Timbrado (stub PAC) — el registro queda con folio fiscal simulado
        PacStampingPort.StampResult stamp = pacPort.stamp(invoice);
        invoice.markStamped(stamp.folioFiscal(), stamp.serie(), stamp.folio());

        invoiceRepository.save(invoice);
        eventPublisher.publishInvoiceGenerated(invoice);
        log.info("Invoice generated invoiceId={} partyId={} total={} folioFiscal={}",
                invoice.getInvoiceId(), cmd.obligorPartyId(), cmd.total(), stamp.folioFiscal());
    }

    /**
     * El receptor del CFDI, buscado por los dos ids con los que puede llegar el obligado.
     *
     * <p>El {@code obligorPartyId} que traen las solicitudes de facturación es, en la práctica, el id
     * del <b>prospecto</b>: el crédito nace de una solicitud y arrastra ese id, no el del party que
     * se creó después. Buscando sólo por {@code partyId} no se encontraba ningún perfil y <b>todos</b>
     * los CFDI se timbraban a «público en general» — con folio, sin error y sin más síntoma que ver
     * el mismo RFC genérico repetido en toda la pantalla de facturas.
     *
     * <p>Se prueba primero por party y luego por prospecto porque un party con perfil propio es el
     * caso correcto; el prospecto es el puente mientras los ids no se unifiquen aguas arriba.
     */
    private Receptor resolveReceptor(UUID obligorId) {
        FiscalProfile p = fiscalProfileRepository.findById(obligorId)
                .or(() -> fiscalProfileRepository.findByProspectId(obligorId))
                .orElse(null);
        if (p != null && p.getRfc() != null && p.getTaxName() != null) {
            return new Receptor(p.getRfc(), p.getTaxName(), p.getTaxRegime(), p.getTaxZipCode(),
                    p.getCfdiUse() != null ? p.getCfdiUse() : "G03");
        }
        // Público en general (SAT) — sin perfil fiscal capturado todavía
        return new Receptor(properties.getGenericRfc(), "PUBLICO EN GENERAL", "616", null, "S01");
    }

    @Override
    @Transactional(readOnly = true)
    public Invoice getById(UUID invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invoice> getByPartyId(UUID obligorPartyId) {
        return invoiceRepository.findByObligorPartyId(obligorPartyId);
    }

    private record Receptor(String rfc, String name, String regime, String zip, String cfdiUse) {}

    @Override
    @Transactional(readOnly = true)
    public Page<Invoice> search(String period, InvoiceStatus status, UUID partyId,
                                UUID creditAccountId, Pageable pageable) {
        return invoiceRepository.search(blankToNull(period), status, partyId, creditAccountId, pageable);
    }

    /** Un filtro vacío es «sin filtro», no «período igual a cadena vacía». */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
