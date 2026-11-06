package com.fintech.accounting.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The bank side of a movement of treasury's cash book: what it posts to Bancos (1101) on the SPEI operation date,
 * against the transit account its business side used on the portfolio's date. A movement with no rule posts nothing
 * here, because its business fact moves 1101 itself (funding, commissions paid, legacy direct payments).
 */
public enum BankSideRule {

    /** A credit received on a collection CLABE, credited by the bank: it clears the collection in transit. */
    SPEI_CREDIT("CLIENT_PAYMENT", "CREDIT:", "TREASURY_SPEI_CREDIT",
            AccountCodes.BANCOS, AccountCodes.REMESAS_EN_CAMINO, "Abono SPEI acreditado por el banco"),
    /** A credit returned to its payer the same operation day: it undoes the line above. */
    SPEI_CREDIT_RETURNED("PAYMENT_RETURN", "CREDIT-RETURNED:", "TREASURY_SPEI_CREDIT_RETURNED",
            AccountCodes.REMESAS_EN_CAMINO, AccountCodes.BANCOS, "Abono SPEI devuelto al ordenante"),
    /**
     * An order of the platform reported out by the bank: it clears the SPEI to settle. An order the receiving bank
     * returns or STP cancels never settled (the connector only returns an order in flight), so it never reaches the
     * cash book: the portfolio's reversal of its disposition is what clears 2103.
     */
    STP_ORDER("STP_ORDER", null, "TREASURY_STP_ORDER",
            AccountCodes.SPEI_POR_LIQUIDAR, AccountCodes.BANCOS, "Dispersión SPEI liquidada por el banco");

    private final String movementType;
    private final String sourcePrefix;
    private final String trigger;
    private final String debit;
    private final String credit;
    private final String description;

    BankSideRule(String movementType, String sourcePrefix, String trigger, String debit, String credit,
                 String description) {
        this.movementType = movementType;
        this.sourcePrefix = sourcePrefix;
        this.trigger = trigger;
        this.debit = debit;
        this.credit = credit;
        this.description = description;
    }

    /** The rule of a cash book movement, if its bank side posts here. */
    public static Optional<BankSideRule> of(String movementType, String sourceEventId) {
        return Arrays.stream(values())
                .filter(r -> r.movementType.equals(movementType))
                .filter(r -> r.sourcePrefix == null || (sourceEventId != null && sourceEventId.startsWith(r.sourcePrefix)))
                .findFirst();
    }

    /**
     * The item of the transit account this movement clears: the received credit for a collection (its fact is
     * {@code CREDIT:<id>} or {@code CREDIT-RETURNED:<id>}), the payment request for an order (its reference).
     */
    public String clearingRef(String sourceEventId, String reference) {
        if (sourcePrefix != null && sourceEventId != null && sourceEventId.startsWith(sourcePrefix)) {
            return sourceEventId.substring(sourcePrefix.length());
        }
        return reference;
    }

    public String trigger() { return trigger; }
    public String debit() { return debit; }
    public String credit() { return credit; }
    public String description() { return description; }
}
