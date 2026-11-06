package com.fintech.creditportfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How much of what each statement asked a statement-billed account (card, line, distributor line) has paid.
 *
 * <p>A payment can only cover statements that were already issued when it was made, oldest first. A payment made
 * before a statement's cutoff was already in that statement's balance: it lowered the balance the statement asked
 * on, and it cannot also count toward what the statement asks. What a payment leaves over after covering the
 * statements issued before it lowered the balance; it does not pay ahead the minimums of statements to come.
 *
 * <p>Before, payments and obligations were compared cumulatively: paying a statement's whole balance once (10,348
 * against a minimum of 517.40) "covered" the next statements' minimums, so a card could skip the minimum of the
 * following cycle for 26 days with no delinquency and no moratorium (2 Nov E2E, E34).
 *
 * <p>A statement with no cutoff on record (issued before it was kept) is treated as issued before any payment: it
 * keeps the old rule.
 */
public final class StatementCoverage {

    private static final BigDecimal CENTAVO = new BigDecimal("0.01");

    public static final String PAYMENT_APPLIED = "PAYMENT_APPLIED";
    public static final String PAYMENT_RETURNED = "PAYMENT_RETURNED";
    /** The distributor's commission settled against a statement, next to the cash that paid it. */
    public static final String COMMISSION_NETTED = "COMMISSION_NETTED";
    /** A netted commission undone because its payment came back. */
    public static final String COMMISSION_NETTED_REVERSED = "COMMISSION_NETTED_REVERSED";
    /**
     * What collections forgave of the debt (a partial quita). It covers the statements as a payment with no cash:
     * what the statements asked and was forgiven is no longer owed, so it no longer keeps the line blocked, no longer
     * bears a moratorium, and no longer stands in the way of the statement the holder does pay. It earns no
     * commission: the netting only grants on cash ({@link CommissionNetting}). Before, a distributor who settled her
     * whole debt a week after a 25 % quita kept $532.98 of principal, the commission of the one statement she paid
     * within the ladder, because the forgiven part of her older statements still "missed" (D10, 5 Nov E2E).
     */
    public static final String AGREEMENT_QUITA_PARCIAL = "AGREEMENT_QUITA_PARCIAL";

    /** What one statement asked: by its cutoff, due by its payment due date. */
    public record Obligation(UUID id, int cycle, LocalDate cutoff, LocalDate due, BigDecimal amount) {}

    /** A payment, net of what was returned of it, valued on its value date. */
    public record Payment(String key, LocalDate valueDate, BigDecimal amount) {}

    private StatementCoverage() {}

    /** How much of each obligation the payments valued up to {@code asOf} cover. */
    public static Map<UUID, BigDecimal> cover(List<Obligation> obligations, List<Payment> payments, LocalDate asOf) {
        List<Obligation> ordered = obligations.stream()
                .sorted(Comparator.comparing((Obligation o) -> o.cutoff() == null ? LocalDate.MIN : o.cutoff())
                        .thenComparing(Obligation::due).thenComparingInt(Obligation::cycle))
                .toList();
        Map<UUID, BigDecimal> covered = new LinkedHashMap<>();
        ordered.forEach(o -> covered.put(o.id(), BigDecimal.ZERO));
        List<Payment> applicable = payments.stream()
                .filter(p -> p.amount().signum() > 0 && (asOf == null || !p.valueDate().isAfter(asOf)))
                .sorted(Comparator.comparing(Payment::valueDate))
                .toList();

        int issued = 0;
        List<Obligation> queue = new ArrayList<>();
        for (Payment payment : applicable) {
            // Statements issued before this payment join the queue: cutoff strictly before the payment's value date.
            while (issued < ordered.size() && issuedBefore(ordered.get(issued), payment.valueDate())) {
                queue.add(ordered.get(issued++));
            }
            BigDecimal remaining = payment.amount();
            for (Obligation o : queue) {
                if (remaining.signum() <= 0) break;
                BigDecimal missing = o.amount().subtract(covered.get(o.id()));
                if (missing.signum() <= 0) continue;
                BigDecimal taken = remaining.min(missing);
                covered.put(o.id(), covered.get(o.id()).add(taken));
                remaining = remaining.subtract(taken);
            }
            // What is left lowered the balance: it does not pay ahead the statements still to come.
        }
        return covered;
    }

    /**
     * The principal overdue of a statement that still misses {@code missing}: the share of the principal it billed
     * that is still unpaid, when the plans' principal billed in it is known ({@code billedPrincipal}); the missing
     * amount itself when it is not (a statement that bills principal only). The moratorium runs on this, never on the
     * interest, the IVA or the moratorium the statement carried.
     */
    public static BigDecimal overduePrincipal(Obligation o, BigDecimal missing, BigDecimal billedPrincipal) {
        if (billedPrincipal == null || o.amount() == null || o.amount().signum() <= 0) return missing;
        BigDecimal share = missing.min(o.amount()).divide(o.amount(), 12, RoundingMode.HALF_UP);
        return billedPrincipal.multiply(share).setScale(2, RoundingMode.HALF_UP).min(billedPrincipal).max(BigDecimal.ZERO);
    }

    /** Whether the obligation is fully covered. */
    public static boolean paid(Obligation o, Map<UUID, BigDecimal> covered) {
        return o.amount().subtract(covered.getOrDefault(o.id(), BigDecimal.ZERO)).compareTo(CENTAVO) < 0;
    }

    private static boolean issuedBefore(Obligation o, LocalDate paymentDate) {
        return o.cutoff() == null || o.cutoff().isBefore(paymentDate);
    }

    /**
     * The account's payments from its ledger, net of returns: PAYMENT_APPLIED entries (the payment order id is their
     * key) less the PAYMENT_RETURNED entries that undo them ({@code PAYMENT_RETURNED:<order id>}), plus what covers a
     * statement without cash: a netted commission and a quita.
     */
    public static List<Payment> paymentsFrom(List<BalanceEvent> ledger) {
        Map<String, Payment> byOrder = new LinkedHashMap<>();
        Map<String, BigDecimal> returned = new HashMap<>();
        for (BalanceEvent e : ledger) {
            // A distributor's commission netted against a statement covers it as her cash does (COMMISSION_NETTED,
            // keyed by its own id); the reversal of one, when its payment comes back, uncovers it. A quita covers the
            // oldest statements too: forgiven, that part of them is no longer owed.
            if (PAYMENT_APPLIED.equals(e.getEventType()) || COMMISSION_NETTED.equals(e.getEventType())
                    || AGREEMENT_QUITA_PARCIAL.equals(e.getEventType())) {
                String key = e.getSourceEventId() != null ? e.getSourceEventId() : String.valueOf(e.getBalanceEventId());
                byOrder.put(key, new Payment(key, e.getValueDate(), e.getDeltaAmount().negate()));
            } else if (PAYMENT_RETURNED.equals(e.getEventType()) && e.getSourceEventId() != null) {
                String order = e.getSourceEventId().replaceFirst("^PAYMENT_RETURNED:", "");
                returned.merge(order, e.getDeltaAmount().abs(), BigDecimal::add);
            } else if (COMMISSION_NETTED_REVERSED.equals(e.getEventType()) && e.getSourceEventId() != null) {
                String netted = e.getSourceEventId().replaceFirst("^" + COMMISSION_NETTED_REVERSED + ":", "");
                returned.merge(netted, e.getDeltaAmount().abs(), BigDecimal::add);
            }
        }
        List<Payment> net = new ArrayList<>();
        for (Payment p : byOrder.values()) {
            BigDecimal netAmount = p.amount().subtract(returned.getOrDefault(p.key(), BigDecimal.ZERO));
            if (netAmount.signum() > 0) {
                net.add(new Payment(p.key(), p.valueDate(), netAmount));
            }
        }
        return net;
    }

    /**
     * The day collections forgave part of the debt (the first quita in the ledger), or null when it never did. A quita
     * is the exit of a distributor: her line places no more and closes once she settles what is left.
     */
    public static LocalDate quitaOn(List<BalanceEvent> ledger) {
        return ledger.stream()
                .filter(e -> AGREEMENT_QUITA_PARCIAL.equals(e.getEventType()) && e.getValueDate() != null)
                .map(BalanceEvent::getValueDate)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** The statements' obligations: the account's cycle installments (scheduleId = account), not its plans'. */
    public static List<Obligation> obligationsFrom(List<Installment> cycleInstallments) {
        return cycleInstallments.stream()
                .filter(i -> !i.getStatus().isVoid())
                .map(i -> new Obligation(i.getInstallmentId(), i.getInstallmentNumber(), i.getCutoffDate(), i.getDueDate(),
                        i.getTotalAmount()))
                .toList();
    }
}
