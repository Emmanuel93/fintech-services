package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.SemiMonthlyCalendar;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;

/**
 * The one way to move installments by periods of their plan: a relief program defers every pending one, a skipped
 * payment pushes the skipped one and the ones behind it.
 *
 * <p>An installment moves from its plan's date, and the calendar then rolls the new date to a business day (the
 * «following» convention the plan was built with). The relief program moved the due date it found, already rolled, and
 * did not roll the result: 1 May 2026 is a bank holiday, so a plan of the 1st is due on Monday the 4th; two months after
 * the 4th is Saturday 4 July, where the demo's relief left 336 installments, and a client who paid on Monday was late.
 * Moving from the rolled date also drifts the plan's day of the month (two months after the 1st is 1 July), and the
 * statement cutoffs, derived from the plan's cadence, stop matching the due dates.
 */
@Component
public class PlanDeferral {

    private final BankingCalendar calendar;

    public PlanDeferral(BankingCalendar calendar) {
        this.calendar = calendar;
    }

    /** The due date {@code periods} periods of {@code cadence} after the installment's plan date, on a business day. */
    public LocalDate dueDateAfter(Installment installment, String cadence, int periods) {
        return dueDateAfter(installment, cadence, periods, SemiMonthlyCalendar.CALENDAR_FORTNIGHT);
    }

    /** The same on the product's fortnight: a distributor's installment moves from one of its cutoff days to the next. */
    public LocalDate dueDateAfter(Installment installment, String cadence, int periods, SemiMonthlyCalendar fortnight) {
        return calendar.onOrAfter(AmortizationEngine.shift(planDateOf(installment), cadence, periods, fortnight));
    }

    /** Moves every installment {@code periods} periods of {@code cadence}; the caller saves them. */
    public void defer(Collection<Installment> installments, String cadence, int periods) {
        defer(installments, cadence, periods, SemiMonthlyCalendar.CALENDAR_FORTNIGHT);
    }

    /** Moves every installment {@code periods} periods of {@code cadence} on the product's fortnight. */
    public void defer(Collection<Installment> installments, String cadence, int periods, SemiMonthlyCalendar fortnight) {
        for (Installment installment : installments) {
            installment.shiftDueDate(dueDateAfter(installment, cadence, periods, fortnight));
        }
    }

    /**
     * The date the plan sets for the installment: its original due date when only the roll to a business day stands
     * between that date and the due date; otherwise its due date. A skip or a relief that already moved it, or a
     * statement that billed it, keeps in the original due date the date before that move, to explain the move, and the
     * date it is due now is the plan's.
     */
    LocalDate planDateOf(Installment installment) {
        LocalDate due = installment.getDueDate();
        LocalDate original = installment.getOriginalDueDate();
        return original != null && original.isBefore(due) && calendar.onOrAfter(original).equals(due) ? original : due;
    }
}
