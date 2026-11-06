package com.fintech.accounting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What a month-end close found: every check it ran, whether each one reconciled, and the outcome. A close publishes
 * the month only when every blocking check reconciles; otherwise the month stays open and this report says why.
 *
 * @param preview  the reconciliation run without closing (nothing was booked, the deferred fees of the month are not
 *                 amortized yet)
 * @param closedAt when the month was closed; null while it is open
 */
public record CloseReport(String period, String outcome, Instant runAt, Instant closedAt, boolean preview,
                          List<CloseCheck> checks) {

    public static final String RECONCILED = "RECONCILED";
    public static final String DIFFERENCES = "DIFFERENCES";
    /**
     * An open month whose last day has not come: everything that can be checked today is in order, and what is
     * measured on the last day (the reserve, the funding's interest) is pending. It is not a difference: a close run
     * today would be refused for the same reason a close run on the 15th is, the month is not over.
     */
    public static final String IN_PROGRESS = "IN_PROGRESS";

    /**
     * One check of the close.
     *
     * @param blocking a failed blocking check keeps the month open; a non-blocking one is reported only
     * @param items    how many accounts, vouchers or entries are off (or are reported, for a non-blocking one)
     * @param amount   how much is off, in pesos, when it is a matter of amounts
     * @param detail   what was compared, in words, with the figures
     * @param pending  measured on the month's last day, which has not come: not in order yet, and not a difference
     */
    public record CloseCheck(String code, String title, boolean ok, boolean blocking, Integer items,
                             BigDecimal amount, String detail, boolean pending) {

        /** A check measured now (a close stored before the field existed reads as one). */
        public CloseCheck(String code, String title, boolean ok, boolean blocking, Integer items, BigDecimal amount,
                          String detail) {
            this(code, title, ok, blocking, items, amount, detail, false);
        }

        /** The same check under another title: a dry run that measures to date names it for what it measured. */
        public CloseCheck titled(String newTitle) {
            return new CloseCheck(code, newTitle, ok, blocking, items, amount, detail, pending);
        }

        /** This check, waiting for the month's last day: what it found today is said, as what the last day will measure. */
        public CloseCheck pendingUntilMonthEnd(LocalDate lastDay) {
            return new CloseCheck(code, title, false, blocking, items, amount,
                    "Se mide al cierre del " + lastDay.getDayOfMonth() + " de " + MONTHS[lastDay.getMonthValue() - 1]
                            + ". Hoy: " + detail, true);
        }
    }

    private static final String[] MONTHS = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
            "septiembre", "octubre", "noviembre", "diciembre"};

    public static CloseReport of(String period, Instant runAt, Instant closedAt, boolean preview,
                                 List<CloseCheck> checks) {
        boolean differences = checks.stream().anyMatch(c -> c.blocking() && !c.ok() && !c.pending());
        boolean pending = checks.stream().anyMatch(CloseCheck::pending);
        String outcome = differences ? DIFFERENCES : pending ? IN_PROGRESS : RECONCILED;
        return new CloseReport(period, outcome, runAt, closedAt, preview, List.copyOf(checks));
    }

    public boolean reconciled() {
        return RECONCILED.equals(outcome);
    }

    public List<CloseCheck> failures() {
        return checks.stream().filter(c -> c.blocking() && !c.ok() && !c.pending()).toList();
    }

    /** What waits for the month's last day. */
    public List<CloseCheck> pendingChecks() {
        return checks.stream().filter(CloseCheck::pending).toList();
    }

    /** One line for the operator: what keeps the month open. */
    public String summary() {
        if (reconciled()) {
            return "El período " + period + " concilia: " + checks.size() + " comprobaciones en orden.";
        }
        if (IN_PROGRESS.equals(outcome)) {
            List<CloseCheck> waiting = pendingChecks();
            StringBuilder s = new StringBuilder("El período ").append(period).append(" sigue en curso y va en orden: ")
                    .append(checks.size() - waiting.size()).append(" comprobaciones en orden a la fecha; ")
                    .append(waiting.size()).append(waiting.size() == 1 ? " se mide" : " se miden")
                    .append(" al cerrar el mes (");
            for (int i = 0; i < waiting.size(); i++) {
                if (i > 0) s.append(", ");
                s.append(waiting.get(i).title());
            }
            return s.append(").").toString();
        }
        List<CloseCheck> off = failures();
        StringBuilder s = new StringBuilder("No se puede cerrar el período ").append(period).append(": ")
                .append(off.size()).append(off.size() == 1 ? " comprobación no concilia" : " comprobaciones no concilian")
                .append(" — ");
        for (int i = 0; i < off.size(); i++) {
            if (i > 0) s.append("; ");
            s.append(off.get(i).title()).append(" (").append(off.get(i).detail()).append(')');
        }
        return s.append('.').toString();
    }
}
