package com.fintech.creditportfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a distributor line may place today. It may not while a statement of hers is unpaid past its due date: from
 * the first day late until she pays what is overdue, the same day she pays it. It is read from the statements and
 * the payments as they stand, not from a status, so a payment this morning unblocks the line this morning.
 *
 * @param blocked             whether a placement is refused today
 * @param reason              why, in the portfolio's own words; null when it is not blocked
 * @param overdueStatements   statements unpaid past their due date
 * @param overdueAmount       what they still ask for
 * @param oldestDueDate       the due date of the oldest of them
 */
public record PlacementGate(boolean blocked, String reason, int overdueStatements, BigDecimal overdueAmount,
                            LocalDate oldestDueDate) {

    public static final PlacementGate OPEN = new PlacementGate(false, null, 0, BigDecimal.ZERO, null);
    private static final BigDecimal CENT = new BigDecimal("0.01");

    /** The reason a line closed by a quita gives: it places no more, whatever she pays. */
    public static final String CLOSED_AFTER_QUITA = "LINE_CLOSED_AFTER_QUITA";

    /** The gate at {@code today} from the statements asked and the payments made, each payment covering the oldest first. */
    public static PlacementGate evaluate(List<StatementCoverage.Obligation> obligations, List<StatementCoverage.Payment> payments,
                                         LocalDate today) {
        return evaluate(obligations, payments, today, null);
    }

    /**
     * The gate at {@code today}; {@code quitaOn} is the day collections forgave part of her debt, if it did. A quita is
     * her exit: the line places no more from that day, paid up or not, and closes once she settles what is left
     * (the business's rule: "no vuelve a ingresar, se va y se cierra la línea").
     */
    public static PlacementGate evaluate(List<StatementCoverage.Obligation> obligations, List<StatementCoverage.Payment> payments,
                                         LocalDate today, LocalDate quitaOn) {
        PlacementGate arrears = arrears(obligations, payments, today);
        if (quitaOn == null) return arrears;
        return new PlacementGate(true, CLOSED_AFTER_QUITA + ": convenio de quita del " + quitaOn
                + (arrears.blocked() ? ", overdue " + arrears.overdueAmount() : ""),
                arrears.overdueStatements(), arrears.overdueAmount(), arrears.oldestDueDate());
    }

    private static PlacementGate arrears(List<StatementCoverage.Obligation> obligations, List<StatementCoverage.Payment> payments,
                                         LocalDate today) {
        Map<UUID, BigDecimal> covered = StatementCoverage.cover(obligations, payments, null);
        int overdue = 0;
        BigDecimal amount = BigDecimal.ZERO;
        LocalDate oldest = null;
        for (StatementCoverage.Obligation o : obligations) {
            if (o.due() == null || !o.due().isBefore(today)) continue;
            BigDecimal missing = o.amount().subtract(covered.getOrDefault(o.id(), BigDecimal.ZERO));
            if (missing.compareTo(CENT) < 0) continue;
            overdue++;
            amount = amount.add(missing);
            if (oldest == null || o.due().isBefore(oldest)) oldest = o.due();
        }
        if (overdue == 0) return OPEN;
        return new PlacementGate(true, "LINE_BLOCKED_IN_ARREARS: " + overdue + " statement(s) unpaid past due, oldest due "
                + oldest + ", overdue " + amount.setScale(2, RoundingMode.HALF_UP), overdue,
                amount.setScale(2, RoundingMode.HALF_UP), oldest);
    }
}
