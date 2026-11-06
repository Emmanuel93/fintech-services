package com.fintech.accounting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The commission facts accounting books, as commission reports them. */
public final class CommissionMoves {

    private CommissionMoves() { }

    /** The item of 2120 a commission belongs to: what is owed to one beneficiary for one product. */
    public static String keyOf(UUID beneficiaryPartyId, String productType) {
        return "COM:" + beneficiaryPartyId + ":" + (productType != null ? productType : "");
    }

    /**
     * A commission accrued, or the share of one a returned payment took back, as positive amounts.
     *
     * @param amount               the commission, before IVA
     * @param iva                  the IVA the beneficiary invoices on it
     * @param reversesCommissionId for a reversal, the commission it takes back; null for an accrual
     */
    public record Accrual(UUID commissionId, UUID creditAccountId, UUID beneficiaryPartyId, String productType,
                          BigDecimal amount, BigDecimal iva, String commissionType, UUID reversesCommissionId,
                          Instant occurredAt,
                          /** {@code NETTED}: settled against the distributor's statement, never by a lot; null: payout. */
                          String settlement) {

        public Accrual(UUID commissionId, UUID creditAccountId, UUID beneficiaryPartyId, String productType,
                       BigDecimal amount, BigDecimal iva, String commissionType, UUID reversesCommissionId,
                       Instant occurredAt) {
            this(commissionId, creditAccountId, beneficiaryPartyId, productType, amount, iva, commissionType,
                    reversesCommissionId, occurredAt, null);
        }

        public boolean isNetted() { return "NETTED".equalsIgnoreCase(settlement); }


        public BigDecimal ivaOrZero() { return iva != null ? iva : BigDecimal.ZERO; }

        public BigDecimal gross() { return (amount != null ? amount : BigDecimal.ZERO).add(ivaOrZero()); }

        public String key() { return beneficiaryPartyId == null ? null : keyOf(beneficiaryPartyId, productType); }
    }

    /**
     * A lot's SPEI requested (or failed): its gross leaves what is owed to the beneficiary (2120) for the SPEI in flight
     * (the net, 2103) and what is withheld for the SAT; the IVA it pays becomes creditable.
     */
    public record LotPayout(UUID batchId, int attempt, UUID beneficiaryPartyId, String productType,
                            BigDecimal subtotal, BigDecimal iva, BigDecimal isrWithheld, BigDecimal ivaWithheld,
                            BigDecimal gross, BigDecimal net, Instant occurredAt) {

        /** The item every service names the lot's payment by (2103's clearing item). */
        public String itemRef() { return "LOTE:" + batchId; }

        /** The IVA the SPEI pays to the beneficiary: what is not withheld. */
        public BigDecimal ivaPaid() { return nz(iva).subtract(nz(ivaWithheld)); }

        public String key() { return keyOf(beneficiaryPartyId, productType); }

        private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    }
}
