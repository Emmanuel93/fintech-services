package com.fintech.accounting.application.service;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.port.out.CustomerBalanceLedger;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The payers' balances in favor (2102). The part of a received credit that no loan owed, and a share a loan rejected,
 * leave the collection in transit (1103) for the balance in favor: the money is in the bank and owed to the payer.
 * Applying the balance to a due installment is booked by the portfolio's payment (2102 against the loan), and an
 * application the loan rejected never was booked, so neither posts here; all of them are kept for the reconciliation.
 */
@Service
@Transactional
public class CustomerBalancePostingService {

    static final String TRIGGER = "CREDIT_BALANCE_RECEIVED";
    private static final Set<String> FROM_TRANSIT = Set.of("EXCESS_FROM_CREDIT", "REJECTED_ALLOCATION");

    private final CustomerBalanceLedger movements;
    private final VoucherPostingService ledger;
    private final AccountingProperties properties;

    public CustomerBalancePostingService(CustomerBalanceLedger movements, VoucherPostingService ledger,
                                         AccountingProperties properties) {
        this.movements = movements;
        this.ledger = ledger;
        this.properties = properties;
    }

    public void onMovement(UUID movementId, UUID partyId, String movementType, BigDecimal amount, UUID inboundCreditId,
                           UUID paymentOrderId, UUID creditAccountId, LocalDate businessDate) {
        movements.record(movementId, partyId, movementType, amount, inboundCreditId, paymentOrderId, creditAccountId,
                businessDate);
        if (!FROM_TRANSIT.contains(movementType) || amount == null || amount.signum() <= 0) {
            return;
        }
        String key = "BALANCE:" + movementId;
        if (ledger.isPosted(key)) {
            return;
        }
        Instant at = businessDate.atTime(12, 0).atZone(properties.zoneId()).toInstant();
        String concept = "EXCESS_FROM_CREDIT".equals(movementType)
                ? "Abono sin deuda que cubrir: queda a favor del cliente"
                : "Parte de un abono rechazada por el crédito: queda a favor del cliente";
        // The item of collections in transit is the received credit: this share of it clears there with the bank's.
        String item = inboundCreditId != null ? inboundCreditId.toString() : null;
        ledger.post(new VoucherRequest(key, TRIGGER, at, creditAccountId, partyId, null, concept, item),
                List.of(new Pair(AccountCodes.REMESAS_EN_CAMINO, AccountCodes.SALDOS_A_FAVOR, amount, concept)));
    }
}
