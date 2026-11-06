package com.fintech.closing.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Two cutoffs a month on fixed days, the same for every account of the product: the distributor's fortnight.
 *
 * <p>The reference model cuts on the 6th and the 21st and its statements are paid by the 18th and by the 3rd of the
 * next month, after the clients' paydays. The calendar fortnight cuts on the 15th and the last day ({@code [15, 31]}: a
 * day past the month's end is its last day). The days are product configuration ({@code cutoffDays},
 * {@code paymentDueDays}); this class only does the arithmetic.
 *
 * <p>Cutoffs are numbered from year 0, two per month: {@code 2·m} is the first day of month {@code m} and
 * {@code 2·m + 1} the second, so moving any number of fortnights is O(1).
 *
 * <p>Deliberate replica of the one the portfolio amortizes with: closing does not depend on the portfolio's classes,
 * and a difference between the two shows in the end-to-end run instead of spreading quietly.
 *
 * @param firstDay      the first cutoff day of the month, 1–31
 * @param secondDay     the second one, after the first; past the month's end it is the month's last day
 * @param firstDueDay   the day the first cutoff's statement is paid by (the first such day after it); null when the
 *                      product pays a number of days after the cutoff instead
 * @param secondDueDay  the same for the second cutoff
 */
public record SemiMonthlyCalendar(int firstDay, int secondDay, Integer firstDueDay, Integer secondDueDay) {

    /** The 15th and the last day of every month, with no fixed payment days. */
    public static final SemiMonthlyCalendar CALENDAR_FORTNIGHT = new SemiMonthlyCalendar(15, 31, null, null);

    public SemiMonthlyCalendar {
        if (firstDay < 1 || secondDay > 31 || firstDay >= secondDay) {
            throw new IllegalArgumentException("Dos días de corte ascendentes entre 1 y 31: " + firstDay + ", " + secondDay);
        }
        if ((firstDueDay == null) != (secondDueDay == null)) {
            throw new IllegalArgumentException("Los días de pago van los dos o ninguno");
        }
    }

    /** From the product's lists; the calendar fortnight when it declares no days. */
    public static SemiMonthlyCalendar of(List<Integer> cutoffDays, List<Integer> paymentDueDays) {
        if (cutoffDays == null || cutoffDays.size() != 2) {
            return CALENDAR_FORTNIGHT;
        }
        boolean dueDays = paymentDueDays != null && paymentDueDays.size() == 2;
        return new SemiMonthlyCalendar(cutoffDays.get(0), cutoffDays.get(1),
                dueDays ? paymentDueDays.get(0) : null, dueDays ? paymentDueDays.get(1) : null);
    }

    /** The cutoff {@code periods} cutoffs after {@code from}: for one, the first one strictly after it. */
    public LocalDate advance(LocalDate from, int periods) {
        return periods <= 0 ? from : cutoff(indexAfter(from) + periods - 1);
    }

    /** Whether the day is one of the two cutoffs of its month. */
    public boolean isCutoff(LocalDate day) {
        return cutoff(indexOnOrAfter(day)).equals(day);
    }

    /** The cutoff before {@code cutoff}: its period starts the day after it. */
    public LocalDate previousCutoff(LocalDate cutoff) {
        return cutoff(indexOnOrAfter(cutoff) - 1);
    }

    /** The cutoff after {@code cutoff}. */
    public LocalDate nextCutoff(LocalDate cutoff) {
        return cutoff(indexAfter(cutoff));
    }

    /**
     * The day a cutoff's statement is paid by, before any business-day shift: the first {@code firstDueDay} after a
     * first cutoff, or {@code secondDueDay} after a second one, this month or the next. Empty when the product pays a
     * number of days after the cutoff.
     */
    public Optional<LocalDate> dueDate(LocalDate cutoff) {
        if (firstDueDay == null) return Optional.empty();
        int day = Math.floorMod(indexOnOrAfter(cutoff), 2) == 0 ? firstDueDay : secondDueDay;
        LocalDate same = cutoff.withDayOfMonth(Math.min(day, cutoff.lengthOfMonth()));
        if (same.isAfter(cutoff)) return Optional.of(same);
        LocalDate next = cutoff.plusMonths(1).withDayOfMonth(1);
        return Optional.of(next.withDayOfMonth(Math.min(day, next.lengthOfMonth())));
    }

    /** The index of the first cutoff on or after the day. */
    private long indexOnOrAfter(LocalDate day) {
        long month = day.getYear() * 12L + day.getMonthValue() - 1;
        int length = day.lengthOfMonth();
        if (day.getDayOfMonth() <= Math.min(firstDay, length)) return 2 * month;
        if (day.getDayOfMonth() <= Math.min(secondDay, length)) return 2 * month + 1;
        return 2 * (month + 1);
    }

    /** The index of the first cutoff strictly after the day. */
    private long indexAfter(LocalDate day) {
        long index = indexOnOrAfter(day);
        return cutoff(index).equals(day) ? index + 1 : index;
    }

    /** The cutoff with that index. */
    private LocalDate cutoff(long index) {
        long month = Math.floorDiv(index, 2);
        LocalDate first = LocalDate.of((int) Math.floorDiv(month, 12), (int) Math.floorMod(month, 12) + 1, 1);
        int day = Math.floorMod(index, 2) == 0 ? firstDay : secondDay;
        return first.withDayOfMonth(Math.min(day, first.lengthOfMonth()));
    }
}
