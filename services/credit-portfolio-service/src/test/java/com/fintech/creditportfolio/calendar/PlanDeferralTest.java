package com.fintech.creditportfolio.calendar;

import com.fintech.creditportfolio.application.service.BankingCalendar;
import com.fintech.creditportfolio.application.service.PlanDeferral;
import com.fintech.creditportfolio.domain.Installment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving installments by periods of their plan (a relief program, a skipped payment): from the plan's date, on a
 * business day. The relief moved the due date it found, already rolled to a business day, and did not roll the result:
 * the demo's relief to personal loans left 336 installments due on Saturdays, Sundays and holidays.
 */
class PlanDeferralTest {

    private static final Logger log = LoggerFactory.getLogger(PlanDeferralTest.class);

    private final BankingCalendar mexico = MexicanBankHolidays.calendar();
    private final PlanDeferral deferral = new PlanDeferral(mexico);

    /** A plan dated as an activation dates it: every period of the cadence, rolled to business days. */
    private List<Installment> plan(LocalDate first, int installments, boolean weekly) {
        UUID loan = UUID.randomUUID();
        List<Installment> plan = new ArrayList<>();
        for (int n = 1; n <= installments; n++) {
            LocalDate due = weekly ? first.plusWeeks(n - 1L) : first.plusMonths(n - 1L);
            plan.add(Installment.of(loan, n, due, BigDecimal.TEN, BigDecimal.ONE));
        }
        mexico.followBusinessDays(plan);
        return plan;
    }

    private static LocalDate d(String day) {
        return LocalDate.parse(day);
    }

    @Test
    @DisplayName("two months of relief to a plan of the 1st: 1 May is a holiday, due on Monday the 4th; moved, it is due "
            + "on Wednesday 1 July, not on Saturday 4 July")
    void movesFromThePlanDateNotFromTheRolledOne() {
        List<Installment> plan = plan(d("2026-05-01"), 36, false);
        assertThat(plan.get(0).getDueDate()).isEqualTo(d("2026-05-04"));

        deferral.defer(plan, "MONTHLY", 2);

        assertThat(plan.get(0).getDueDate()).isEqualTo(d("2026-07-01"));
        // The date before the move stays, to explain it to the client.
        assertThat(plan.get(0).getOriginalDueDate()).isEqualTo(d("2026-05-01"));
    }

    @Test
    @DisplayName("what the move lands on a weekend is due on the next business day")
    void rollsWhatLandsOnAWeekend() {
        List<Installment> plan = plan(d("2026-05-01"), 36, false);

        deferral.defer(plan, "MONTHLY", 2);

        // 1 June + two months is Saturday 1 August: due on Monday the 3rd.
        assertThat(plan.get(1).getDueDate()).isEqualTo(d("2026-08-03"));
        // 1 August (a Saturday, due on the 3rd) + two months is Thursday 1 October; from the 3rd it was Saturday the 3rd.
        assertThat(plan.get(3).getDueDate()).isEqualTo(d("2026-10-01"));
    }

    @Test
    @DisplayName("what the move lands on a holiday is due on the next business day: 16 September is due on the 17th")
    void rollsWhatLandsOnAHoliday() {
        List<Installment> plan = plan(d("2026-07-16"), 6, false);

        deferral.defer(plan, "MONTHLY", 2);

        assertThat(plan.get(0).getDueDate()).isEqualTo(d("2026-09-17"));
    }

    @Test
    @DisplayName("a whole 36-month plan moved two months: every installment on a business day, on its plan's day, "
            + "never two on the same day")
    void aWholePlanKeepsItsDay() {
        LocalDate first = d("2026-05-01");
        List<Installment> plan = plan(first, 36, false);

        deferral.defer(plan, "MONTHLY", 2);

        LocalDate previous = null;
        for (Installment i : plan) {
            LocalDate planDate = first.plusMonths(i.getInstallmentNumber() - 1L + 2);
            assertThat(i.getDueDate()).as("cuota %d", i.getInstallmentNumber()).isEqualTo(mexico.onOrAfter(planDate));
            assertThat(mexico.isBusinessDay(i.getDueDate())).as("cuota %d", i.getInstallmentNumber()).isTrue();
            if (previous != null) assertThat(i.getDueDate()).isAfter(previous);
            previous = i.getDueDate();
        }
    }

    @Test
    @DisplayName("weekly: two periods are two weeks from the plan's Wednesday, not from the Thursday a holiday moved it to")
    void weeklyKeepsTheWeekday() {
        List<Installment> plan = plan(d("2026-09-02"), 8, true);
        assertThat(plan.get(2).getDueDate()).isEqualTo(d("2026-09-17"));   // 16 September, a Wednesday holiday

        deferral.defer(plan, "WEEKLY", 2);

        assertThat(plan.get(0).getDueDate()).isEqualTo(d("2026-09-17"));   // 2 Sep + 2 weeks lands on the holiday
        assertThat(plan.get(2).getDueDate()).isEqualTo(d("2026-09-30"));   // from the 17th it was Thursday 1 October
    }

    @Test
    @DisplayName("an installment a relief or a skip already moved moves from the date it is due now: a second move does "
            + "not undo the first")
    void aMovedInstallmentMovesFromItsDueDate() {
        Installment relieved = Installment.of(UUID.randomUUID(), 1, d("2026-05-01"), BigDecimal.TEN, BigDecimal.ONE);
        relieved.shiftDueDate(d("2026-07-01"));

        deferral.defer(List.of(relieved), "MONTHLY", 2);

        assertThat(relieved.getDueDate()).isEqualTo(d("2026-09-01"));
    }

    @Test
    @DisplayName("what a statement billed moves from that statement's due date")
    void aBilledInstallmentMovesFromItsStatementDueDate() {
        Installment billed = Installment.of(UUID.randomUUID(), 1, d("2026-09-02"), BigDecimal.TEN, BigDecimal.ONE);
        billed.billIn(11, d("2026-09-17"));

        assertThat(deferral.dueDateAfter(billed, "BIWEEKLY", 1)).isEqualTo(d("2026-10-01"));
    }

    @Test
    @DisplayName("performance: 108,000 installments (3,000 loans of 36) moved in under three seconds, all on business days")
    void performance() {
        List<Installment> all = new ArrayList<>();
        for (int loan = 0; loan < 3_000; loan++) {
            all.addAll(plan(d("2026-05-01").plusDays(loan % 28), 36, false));
        }

        long start = System.nanoTime();
        deferral.defer(all, "MONTHLY", 2);
        long millis = (System.nanoTime() - start) / 1_000_000;

        log.info("Diferir {} cuotas tomó {} ms", all.size(), millis);
        assertThat(millis).isLessThan(3_000);
        assertThat(all).allMatch(i -> mexico.isBusinessDay(i.getDueDate()));
    }
}
