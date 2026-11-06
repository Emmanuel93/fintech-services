package com.fintech.accounting.domain;

import java.util.UUID;

/**
 * Where the money of a payment came from, as payments reports it. It decides the account the payment is booked
 * against and the item of the clearing account it moves:
 *
 * <ul>
 *   <li>{@code SPEI_CREDIT}: a share of a SPEI credit received on a collection CLABE. It waits in collections in
 *       transit (1103) until the bank credits it on its operation date; its item is the received credit.</li>
 *   <li>{@code CREDIT_BALANCE}: the payer's balance in favor (2102), already in the bank.</li>
 *   <li>anything else ({@code DIRECT}, unknown or absent): money brought to Bancos (1101) at once.</li>
 * </ul>
 *
 * @param fundingSource   as payments names it; {@code null} for producers that do not say
 * @param inboundCreditId the received SPEI credit, for a share of one
 */
public record PaymentOrigin(String fundingSource, UUID inboundCreditId) {

    public static final String SPEI_CREDIT = "SPEI_CREDIT";
    public static final String CREDIT_BALANCE = "CREDIT_BALANCE";

    private static final PaymentOrigin DIRECT = new PaymentOrigin(null, null);

    /** Money brought straight to the bank: a direct payment, or a producer that does not say. */
    public static PaymentOrigin direct() {
        return DIRECT;
    }

    /** The account the payment's money comes from. */
    public String debitAccount() {
        if (SPEI_CREDIT.equals(fundingSource)) return AccountCodes.REMESAS_EN_CAMINO;
        if (CREDIT_BALANCE.equals(fundingSource)) return AccountCodes.SALDOS_A_FAVOR;
        return AccountCodes.BANCOS;
    }

    /** The item of collections in transit the payment moves: the received credit, for money still in transit. */
    public String clearingRef() {
        return SPEI_CREDIT.equals(fundingSource) && inboundCreditId != null ? inboundCreditId.toString() : null;
    }
}
