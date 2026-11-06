package com.fintech.closing.billingcycle;

import com.fintech.closing.application.service.BusinessCalendarService;
import com.fintech.closing.application.service.CutoffScheduleDeriver;
import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.BillingCycle;
import com.fintech.closing.domain.ClosePolicy;
import com.fintech.closing.domain.CutoffRule;
import com.fintech.closing.domain.CutoffSchedule;
import com.fintech.closing.domain.NonBusinessDayShift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The cutoff calendar comes from the product's billing cycle pinned to the account; the policy is the fallback. */
class CutoffScheduleDeriverTest {

    // Wednesday 4 Nov 2026.
    private static final LocalDate ACTIVATED = LocalDate.of(2026, 11, 4);

    private final BusinessCalendarService calendar = mock(BusinessCalendarService.class);
    private final ClosePolicy policy = mock(ClosePolicy.class);
    private final CutoffScheduleDeriver deriver = new CutoffScheduleDeriver(calendar);

    CutoffScheduleDeriverTest() {
        // No holidays in these tests: every date stays where it falls.
        when(calendar.shift(anyString(), any(LocalDate.class), any())).thenAnswer(inv -> inv.getArgument(1));
        when(policy.getCalendarCode()).thenReturn("MX");
        when(policy.cutoffRule()).thenReturn(CutoffRule.CYCLE_FROM_ACTIVATION);
        when(policy.getCutoffDay()).thenReturn(null);
        when(policy.getPaymentDueOffsetDays()).thenReturn((short) 20);
        when(policy.shift()).thenReturn(NonBusinessDayShift.NEXT);
        when(policy.minimumPaymentRate()).thenReturn(Optional.of(new BigDecimal("0.05")));
    }

    @Test
    @DisplayName("a weekly product cutting on Fridays: first Friday after activation, then every week, due 3 days later")
    void weeklyOnAFixedWeekday() {
        AccountCloseProfile profile = profile("WEEKLY", null,
                new BillingCycle(CutoffRule.DAY_OF_WEEK, 5, 3, 2, null, NonBusinessDayShift.NEXT));

        CutoffSchedule first = deriver.derive(profile, policy, 1).orElseThrow();
        CutoffSchedule third = deriver.derive(profile, policy, 3).orElseThrow();

        assertThat(first.getCutoffDate()).isEqualTo(LocalDate.of(2026, 11, 6)).matches(d -> d.getDayOfWeek() == DayOfWeek.FRIDAY);
        assertThat(first.getPaymentDueDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(third.getCutoffDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("a biweekly product cutting on Fridays advances fourteen days per cycle")
    void biweeklyOnAFixedWeekday() {
        AccountCloseProfile profile = profile("BIWEEKLY", null,
                new BillingCycle(CutoffRule.DAY_OF_WEEK, 5, 5, 3, new BigDecimal("0.10"), NonBusinessDayShift.NEXT));

        assertThat(deriver.derive(profile, policy, 1).orElseThrow().getCutoffDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(deriver.derive(profile, policy, 2).orElseThrow().getCutoffDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("the account's own cycle wins over the policy: a fixed day of month with its own days to pay")
    void theAccountCycleWinsOverThePolicy() {
        AccountCloseProfile profile = profile("MONTHLY", null,
                new BillingCycle(CutoffRule.DAY_OF_MONTH, 15, 10, 3, null, NonBusinessDayShift.NEXT));

        CutoffSchedule first = deriver.derive(profile, policy, 1).orElseThrow();

        assertThat(first.getCutoffDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(first.getPaymentDueDate()).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    @DisplayName("an account opened before the product declared a cycle keeps deriving from the policy")
    void fallsBackToThePolicy() {
        AccountCloseProfile profile = profile("MONTHLY", null, null);

        CutoffSchedule first = deriver.derive(profile, policy, 1).orElseThrow();

        assertThat(first.getCutoffDate()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(first.getPaymentDueDate()).isEqualTo(LocalDate.of(2026, 12, 24));
    }

    @Test
    @DisplayName("an installment loan stops cutting after its last installment, whatever the cycle says")
    void installmentLoanStopsAtItsTerm() {
        AccountCloseProfile profile = profile("MONTHLY", 3,
                new BillingCycle(CutoffRule.INSTALLMENT_DUE_DATE, null, 3, 3, null, NonBusinessDayShift.NEXT));

        assertThat(deriver.derive(profile, policy, 3)).isPresent();
        assertThat(deriver.derive(profile, policy, 4)).isEmpty();
    }

    private static AccountCloseProfile profile(String frequency, Integer term, BillingCycle cycle) {
        boolean revolving = term == null;
        return AccountCloseProfile.fromActivation(UUID.randomUUID(), UUID.randomUUID(),
                revolving ? "CREDIT_CARD" : "PERSONAL_LOAN", revolving ? "REVOLVING" : "INSTALLMENT",
                null, ACTIVATED, frequency, term, new BigDecimal("0.36"), new BigDecimal("10000"), cycle);
    }
}
