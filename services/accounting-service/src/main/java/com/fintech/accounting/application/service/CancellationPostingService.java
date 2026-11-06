package com.fintech.accounting.application.service;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.port.out.InvoiceableItemRepository;
import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountBalanceShadow;
import com.fintech.accounting.domain.AccountCodes;
import com.fintech.accounting.domain.InvoiceableItem;
import com.fintech.accounting.domain.InvoiceableItemStatus;
import com.fintech.accounting.domain.JournalEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A credit cancelled because its only disbursement could not happen (D63), as accounting books it. The principal came
 * back with the portfolio's {@code DISPOSITION_FAILED} (2103 against 1201) and the opening fee with charges' reversal;
 * what is left is accounting's own:
 *
 * <ul>
 *   <li>the authorized line in memo accounts (7101/7201), booked at activation, is reversed: the line never existed;</li>
 *   <li>the fee and its IVA still waiting to be invoiced are kept out of the CFDI run: they were never charged. One
 *       invoiced already would need a credit note, and is reported.</li>
 * </ul>
 */
@Service
@Transactional
public class CancellationPostingService {

    private static final Logger log = LoggerFactory.getLogger(CancellationPostingService.class);
    static final String CANCELLED = "ACCOUNT_CANCELLED";
    private static final String ACTIVATED = "ACCOUNT_ACTIVATED";

    private final JournalEntryRepository entries;
    private final AccountBalanceShadowRepository shadows;
    private final InvoiceableItemRepository items;
    private final VoucherPostingService ledger;

    public CancellationPostingService(JournalEntryRepository entries, AccountBalanceShadowRepository shadows,
                                      InvoiceableItemRepository items, VoucherPostingService ledger) {
        this.entries = entries;
        this.shadows = shadows;
        this.items = items;
        this.ledger = ledger;
    }

    /** @return the memo amount reversed (zero when there was none, or on a redelivery) */
    public BigDecimal onCreditAccountCancelled(UUID creditAccountId, UUID obligorPartyId, Instant occurredAt) {
        List<JournalEntry> booked = entries.findByCreditAccountId(creditAccountId);
        boolean reversedAlready = booked.stream().anyMatch(e -> CANCELLED.equals(e.getTriggerEvent()));
        BigDecimal authorized = booked.stream()
                .filter(e -> ACTIVATED.equals(e.getTriggerEvent())
                        && AccountCodes.LINEAS_AUTORIZADAS.equals(e.getDebitAccount()))
                .map(JournalEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reversed = BigDecimal.ZERO;
        if (!reversedAlready && authorized.signum() > 0) {
            String unit = shadows.findById(creditAccountId).map(AccountBalanceShadow::getOrgUnitCode).orElse(null);
            ledger.post(new VoucherRequest(CANCELLED + ":" + creditAccountId, CANCELLED, occurredAt, creditAccountId,
                            obligorPartyId, unit, "Cancelación del crédito: el desembolso no se realizó"),
                    List.of(new Pair(AccountCodes.LINEAS_POR_DISPONER, AccountCodes.LINEAS_AUTORIZADAS, authorized,
                            "Línea cancelada sin disponer")));
            reversed = authorized;
        }

        int keptOut = 0;
        for (InvoiceableItem item : items.findByCreditAccountIdAndStatus(creditAccountId, InvoiceableItemStatus.PENDING)) {
            item.cancel();
            items.save(item);
            keptOut++;
        }
        List<InvoiceableItem> invoiced = items.findByCreditAccountIdAndStatus(creditAccountId,
                InvoiceableItemStatus.BILLED);
        if (!invoiced.isEmpty()) {
            log.warn("Crédito cancelado con {} partidas ya facturadas creditAccountId={}: requieren nota de crédito",
                    invoiced.size(), creditAccountId);
        }
        log.info("Crédito cancelado en contabilidad creditAccountId={} cuentas de orden reversadas={} partidas fuera "
                + "de facturación={}", creditAccountId, reversed, keptOut);
        return reversed;
    }
}
