package com.fintech.accounting.application.service;

import com.fintech.accounting.application.InvoiceRequest;
import com.fintech.accounting.application.port.out.AccountingEventPublisher;
import com.fintech.accounting.application.port.out.InvoiceableItemRepository;
import com.fintech.accounting.domain.InvoiceableItem;
import com.fintech.accounting.domain.InvoiceableItemStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconocimiento de ingresos y consolidación de facturación: acumula intereses/comisiones/IVA como
 * {@link InvoiceableItem} y, al correr el período, emite <strong>un CFDI por party</strong> con las
 * líneas desglosadas (decisión de negocio confirmada) hacia el servicio de Facturación.
 */
@Service
@Transactional
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final InvoiceableItemRepository itemRepository;
    private final AccountingEventPublisher eventPublisher;

    public BillingService(InvoiceableItemRepository itemRepository, AccountingEventPublisher eventPublisher) {
        this.itemRepository = itemRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Acumula un ingreso facturable. Idempotente por sourceEventId. */
    public void accrue(String sourceEventId, UUID obligorPartyId, UUID creditAccountId,
                       String concept, BigDecimal amount, boolean isIva, String period) {
        if (itemRepository.existsBySourceEventId(sourceEventId)) return;
        itemRepository.save(InvoiceableItem.accrue(sourceEventId, obligorPartyId, creditAccountId,
                concept, amount, isIva, period));
    }

    /** Consolida los PENDING del período en un CFDI por party y los marca BILLED. */
    public int runBilling(String period) {
        List<InvoiceableItem> pending = itemRepository.findByStatusAndPeriod(InvoiceableItemStatus.PENDING, period);
        Map<UUID, List<InvoiceableItem>> byParty = new LinkedHashMap<>();
        for (InvoiceableItem item : pending) {
            byParty.computeIfAbsent(item.getObligorPartyId(), k -> new ArrayList<>()).add(item);
        }

        int invoices = 0;
        for (Map.Entry<UUID, List<InvoiceableItem>> e : byParty.entrySet()) {
            UUID partyId = e.getKey();
            List<InvoiceableItem> items = e.getValue();
            UUID invoiceRequestId = UUID.randomUUID();

            List<InvoiceRequest.Line> lines = new ArrayList<>();
            BigDecimal subtotal = BigDecimal.ZERO;
            BigDecimal iva = BigDecimal.ZERO;
            for (InvoiceableItem item : items) {
                lines.add(new InvoiceRequest.Line(item.getConcept(), item.getCreditAccountId(),
                        item.getAmount(), item.isIva()));
                if (item.isIva()) iva = iva.add(item.getAmount());
                else subtotal = subtotal.add(item.getAmount());
            }
            BigDecimal total = subtotal.add(iva);

            eventPublisher.publishInvoiceRequested(new InvoiceRequest(
                    invoiceRequestId, partyId, period, lines, subtotal, iva, total));

            items.forEach(item -> { item.markBilled(invoiceRequestId); itemRepository.save(item); });
            invoices++;
            log.info("Invoice requested invoiceRequestId={} partyId={} period={} total={}",
                    invoiceRequestId, partyId, period, total);
        }
        log.info("Billing run period={} → {} invoices from {} items", period, invoices, pending.size());
        return invoices;
    }
}
