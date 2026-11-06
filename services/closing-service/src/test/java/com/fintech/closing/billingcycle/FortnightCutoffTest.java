package com.fintech.closing.billingcycle;

import com.fintech.closing.application.port.out.CalendarDayRepository;
import com.fintech.closing.application.service.BusinessCalendarService;
import com.fintech.closing.application.service.CutoffScheduleDeriver;
import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.BillingCycle;
import com.fintech.closing.domain.CalendarDay;
import com.fintech.closing.domain.ClosePolicy;
import com.fintech.closing.domain.CutoffRule;
import com.fintech.closing.domain.CutoffSchedule;
import com.fintech.closing.domain.NonBusinessDayShift;
import com.fintech.closing.domain.PaymentCadence;
import com.fintech.closing.domain.SemiMonthlyCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The distributor line's fortnight: it cuts on the 6th and on the 21st of every month, whatever day the line was
 * activated on, and the cutoff never moves. The statement of the 6th is paid by the 18th and the one of the 21st by the
 * 3rd of the next month, moved to the next business day when it falls on a weekend or a bank holiday.
 */
class FortnightCutoffTest {

    /** The bank holidays of 2025 (migration 020) and of 2026 the calendar carries. */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 3), LocalDate.of(2025, 3, 17), LocalDate.of(2025, 4, 17),
            LocalDate.of(2025, 4, 18), LocalDate.of(2025, 5, 1), LocalDate.of(2025, 9, 16), LocalDate.of(2025, 11, 17),
            LocalDate.of(2025, 12, 12), LocalDate.of(2025, 12, 25),
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 2), LocalDate.of(2026, 3, 16), LocalDate.of(2026, 4, 2),
            LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 11, 16),
            LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 25));

    private static final BillingCycle DISTRIBUTOR_CYCLE = new BillingCycle(CutoffRule.SEMI_MONTHLY, null, 0, 2, null,
            NonBusinessDayShift.NEXT, List.of(6, 21), List.of(18, 3));

    /** The real calendar service over the holidays in memory: weekends and bank holidays move to the next business day. */
    private final BusinessCalendarService calendar = new BusinessCalendarService(new HolidaysInMemory());
    private final ClosePolicy policy = mock(ClosePolicy.class);
    private final CutoffScheduleDeriver deriver = new CutoffScheduleDeriver(calendar);

    /** The calendar table keeps only the exceptions to the weekday rule: here, the year's bank holidays. */
    private static final class HolidaysInMemory implements CalendarDayRepository {
        private final Map<LocalDate, CalendarDay> days = HOLIDAYS.stream()
                .collect(Collectors.toMap(d -> d, d -> CalendarDay.of("MX", d, false, "inhábil")));

        @Override public Optional<CalendarDay> find(String calendarCode, LocalDate day) { return Optional.ofNullable(days.get(day)); }
        @Override public CalendarDay save(CalendarDay day) { return day; }
        @Override public List<CalendarDay> findAll(String calendarCode) { return List.copyOf(days.values()); }
    }

    FortnightCutoffTest() {
        when(policy.getCalendarCode()).thenReturn("MX");
        when(policy.cutoffRule()).thenReturn(CutoffRule.SEMI_MONTHLY);
        when(policy.shift()).thenReturn(NonBusinessDayShift.NEXT);
        when(policy.minimumPaymentRate()).thenReturn(Optional.empty());
    }

    // ── The calendar ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("la quincena de la distribuidora corta el 6 y el 21; se paga el 18 y el 3 del mes siguiente")
    void theFortnightCutsOnTheSixthAndTheTwentyFirst() {
        SemiMonthlyCalendar q = DISTRIBUTOR_CYCLE.semiMonthly();
        assertThat(q.advance(LocalDate.of(2025, 1, 2), 1)).isEqualTo(LocalDate.of(2025, 1, 6));
        assertThat(q.advance(LocalDate.of(2025, 1, 6), 1)).isEqualTo(LocalDate.of(2025, 1, 21));
        assertThat(q.advance(LocalDate.of(2025, 1, 21), 1)).isEqualTo(LocalDate.of(2025, 2, 6));
        assertThat(q.advance(LocalDate.of(2025, 12, 21), 1)).isEqualTo(LocalDate.of(2026, 1, 6));
        assertThat(q.advance(LocalDate.of(2025, 1, 2), 0)).isEqualTo(LocalDate.of(2025, 1, 2));
        // Twenty-four fortnights are a year: from one 6 January to the next.
        assertThat(q.advance(LocalDate.of(2025, 1, 6), 24)).isEqualTo(LocalDate.of(2026, 1, 6));
        assertThat(q.isCutoff(LocalDate.of(2025, 2, 21))).isTrue();
        assertThat(q.isCutoff(LocalDate.of(2025, 2, 20))).isFalse();
        assertThat(q.previousCutoff(LocalDate.of(2025, 1, 6))).isEqualTo(LocalDate.of(2024, 12, 21));
        assertThat(q.nextCutoff(LocalDate.of(2025, 1, 21))).isEqualTo(LocalDate.of(2025, 2, 6));
        // The raw payment days, before the business-day shift.
        assertThat(q.dueDate(LocalDate.of(2025, 1, 6))).contains(LocalDate.of(2025, 1, 18));
        assertThat(q.dueDate(LocalDate.of(2025, 1, 21))).contains(LocalDate.of(2025, 2, 3));
        assertThat(q.dueDate(LocalDate.of(2025, 2, 21))).contains(LocalDate.of(2025, 3, 3));
        assertThat(q.dueDate(LocalDate.of(2025, 12, 21))).contains(LocalDate.of(2026, 1, 3));
    }

    @Test
    @DisplayName("sin días declarados la quincena es la de calendario: el 15 y el último día del mes")
    void withoutDaysTheFortnightIsTheCalendarOne() {
        PaymentCadence q = PaymentCadence.SEMI_MONTHLY;
        assertThat(q.periodsPerYear()).isEqualTo(24);
        assertThat(PaymentCadence.parse("SEMI_MONTHLY")).contains(q);
        assertThat(q.advance(LocalDate.of(2025, 1, 10), 1)).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(q.advance(LocalDate.of(2025, 1, 15), 1)).isEqualTo(LocalDate.of(2025, 1, 31));
        assertThat(q.advance(LocalDate.of(2025, 2, 15), 1)).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(q.advance(LocalDate.of(2028, 2, 15), 1)).isEqualTo(LocalDate.of(2028, 2, 29));   // leap year
        assertThat(SemiMonthlyCalendar.of(null, null)).isEqualTo(SemiMonthlyCalendar.CALENDAR_FORTNIGHT);
        assertThat(SemiMonthlyCalendar.CALENDAR_FORTNIGHT.dueDate(LocalDate.of(2025, 1, 15))).isEmpty();
    }

    // ── The cutoff calendar ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una línea dada de alta el 2 de enero corta el 6 y el 21 de cada mes: dos cortes por mes")
    void twoCutoffsAMonthOnFixedDays() {
        List<CutoffSchedule> year = deriver.deriveAll(profile(LocalDate.of(2025, 1, 2)), policy, 24);

        assertThat(year).hasSize(24);
        assertThat(year.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2025, 1, 6));
        assertThat(year.get(1).getCutoffDate()).isEqualTo(LocalDate.of(2025, 1, 21));
        assertThat(year.get(23).getCutoffDate()).isEqualTo(LocalDate.of(2025, 12, 21));
        Map<YearMonth, List<Integer>> byMonth = year.stream().collect(Collectors.groupingBy(
                c -> YearMonth.from(c.getCutoffDate()), Collectors.mapping(c -> c.getCutoffDate().getDayOfMonth(), Collectors.toList())));
        assertThat(byMonth).hasSize(12);
        byMonth.forEach((month, days) -> assertThat(days).as(month.toString()).containsExactly(6, 21));
    }

    @Test
    @DisplayName("activada el mismo día de un corte, la línea empieza a cortar en la quincena siguiente")
    void activatedOnACutoffDayCutsAtTheNextFortnight() {
        assertThat(deriver.derive(profile(LocalDate.of(2025, 1, 6)), policy, 1).orElseThrow().getCutoffDate())
                .isEqualTo(LocalDate.of(2025, 1, 21));
        assertThat(deriver.derive(profile(LocalDate.of(2025, 1, 21)), policy, 1).orElseThrow().getCutoffDate())
                .isEqualTo(LocalDate.of(2025, 2, 6));
        // Two lines activated on different days of the same fortnight cut on the same days.
        assertThat(deriver.derive(profile(LocalDate.of(2025, 2, 22)), policy, 2).orElseThrow().getCutoffDate())
                .isEqualTo(deriver.derive(profile(LocalDate.of(2025, 3, 5)), policy, 2).orElseThrow().getCutoffDate())
                .isEqualTo(LocalDate.of(2025, 3, 21));
    }

    @Test
    @DisplayName("el corte de un domingo se queda en domingo; la fecha límite se corre al día hábil")
    void theCutoffStaysAndTheDueDateMovesToABusinessDay() {
        AccountCloseProfile line = profile(LocalDate.of(2025, 1, 2));
        // The 18th of January 2025 is a Saturday: the statement of the 6th falls due on Monday the 20th.
        CutoffSchedule first = deriver.derive(line, policy, 1).orElseThrow();
        assertThat(first.getCutoffDate()).isEqualTo(LocalDate.of(2025, 1, 6));
        assertThat(first.getPaymentDueDate()).isEqualTo(LocalDate.of(2025, 1, 20));
        // The 3rd of February 2025 is Constitution Day: the statement of the 21st falls due on Tuesday the 4th.
        CutoffSchedule second = deriver.derive(line, policy, 2).orElseThrow();
        assertThat(second.getCutoffDate()).isEqualTo(LocalDate.of(2025, 1, 21));
        assertThat(second.getPaymentDueDate()).isEqualTo(LocalDate.of(2025, 2, 4));
        // The 6th of April 2025 is a Sunday: the fortnight still ends there.
        CutoffSchedule sunday = deriver.derive(line, policy, 7).orElseThrow();
        assertThat(sunday.getCutoffDate()).isEqualTo(LocalDate.of(2025, 4, 6));
    }

    @Test
    @DisplayName("Semana Santa: el 18 de abril de 2025 es Viernes Santo y el pago pasa al lunes 21, el día del corte siguiente")
    void aDueDateMovedPastTheHolidaysMayMeetTheNextCutoff() {
        AccountCloseProfile line = profile(LocalDate.of(2025, 1, 2));
        CutoffSchedule april = deriver.derive(line, policy, 7).orElseThrow();
        CutoffSchedule next = deriver.derive(line, policy, 8).orElseThrow();
        // Holy Thursday, Good Friday and the weekend: the law lets the payment move to the next business day without
        // charge, and that day is the next cutoff. Never after it.
        assertThat(april.getPaymentDueDate()).isEqualTo(LocalDate.of(2025, 4, 21)).isEqualTo(next.getCutoffDate());
        // The same in 2026: the statement of 21 March falls due on Good Friday the 3rd of April, and moves to Monday the
        // 6th.
        CutoffSchedule march2026 = deriver.derive(line, policy, 30).orElseThrow();
        assertThat(march2026.getCutoffDate()).isEqualTo(LocalDate.of(2026, 3, 21));
        assertThat(march2026.getPaymentDueDate()).isEqualTo(LocalDate.of(2026, 4, 6));
    }

    @Test
    @DisplayName("cada estado de cuenta vence después de su corte y, a más tardar, el día del corte siguiente")
    void everyStatementFallsDueAfterItsCutoffAndNoLaterThanTheNext() {
        List<CutoffSchedule> twoYears = deriver.deriveAll(profile(LocalDate.of(2025, 1, 2)), policy, 48);
        assertThat(twoYears).hasSize(48);
        for (int i = 0; i + 1 < twoYears.size(); i++) {
            CutoffSchedule c = twoYears.get(i);
            int dueDay = c.getCutoffDate().getDayOfMonth() == 6 ? 18 : 3;
            assertThat(c.getPaymentDueDate()).as("corte del " + c.getCutoffDate())
                    .isAfter(c.getCutoffDate())
                    .isBeforeOrEqualTo(twoYears.get(i + 1).getCutoffDate());
            // Due on its day, or on the first business day after it.
            LocalDate raw = DISTRIBUTOR_CYCLE.semiMonthly().dueDate(c.getCutoffDate()).orElseThrow();
            assertThat(raw.getDayOfMonth()).isEqualTo(dueDay);
            assertThat(c.getPaymentDueDate()).isAfterOrEqualTo(raw).isBefore(raw.plusDays(5));
        }
    }

    @Test
    @DisplayName("rendimiento: el calendario de cinco años de 2,000 líneas se deriva en menos de dos segundos")
    void fiveYearsOfTwoThousandLinesInUnderTwoSeconds() {
        long start = System.nanoTime();
        int cutoffs = 0;
        for (int i = 0; i < 2_000; i++) {
            cutoffs += deriver.deriveAll(profile(LocalDate.of(2025, 1, 1).plusDays(i % 365)), policy, 120).size();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(cutoffs).isEqualTo(240_000);
        assertThat(ms).as("2,000 calendarios de 120 cortes tardaron " + ms + " ms").isLessThan(2_000);
    }

    private static AccountCloseProfile profile(LocalDate activatedOn) {
        return AccountCloseProfile.fromActivation(UUID.randomUUID(), UUID.randomUUID(), "DISTRIBUTOR_LINE", "REVOLVING",
                null, activatedOn, "SEMI_MONTHLY", null, new BigDecimal("0.95"), new BigDecimal("50000"), DISTRIBUTOR_CYCLE);
    }
}
