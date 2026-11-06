package com.fintech.accounting.application.service;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.BankSideRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The bank side of treasury's cash book: Bancos (1101) moves here, and only here for SPEI money, on the SPEI
 * operation date, clearing the transit account the business side used on the portfolio's date. A payment received at
 * 19:00 on 30 November was booked to 1103 on the 30th; the bank's credit of 1 December clears it on the 1st.
 */
@Service
@Transactional
public class TreasuryPostingService {

    private final VoucherPostingService ledger;
    private final AccountingProperties properties;

    public TreasuryPostingService(VoucherPostingService ledger, AccountingProperties properties) {
        this.ledger = ledger;
        this.properties = properties;
    }

    /** Posts the bank side of a cash book movement, once, when its type has one (see {@link BankSideRule}). */
    public void onCashMovement(UUID movementId, String movementType, String sourceEventId, BigDecimal amount,
                               LocalDate operationDate, String reference) {
        BankSideRule.of(movementType, sourceEventId).ifPresent(rule -> {
            String key = "BANK:" + movementId;
            if (ledger.isPosted(key)) {
                return;
            }
            Instant at = operationDate.atTime(12, 0).atZone(properties.zoneId()).toInstant();
            String concept = rule.description() + (reference != null ? " — " + reference : "");
            ledger.post(new VoucherRequest(key, rule.trigger(), at, null, null, null, concept,
                            rule.clearingRef(sourceEventId, reference)),
                    List.of(new Pair(rule.debit(), rule.credit(), amount, rule.description())));
        });
    }
}
