package com.fintech.creditportfolio.calendar;

import com.fintech.creditportfolio.application.port.out.BusinessCalendarRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.BankingCalendar;
import com.fintech.creditportfolio.application.service.BusinessCalendarSync;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Business days: Monday to Friday except the bank holidays. A loan's installment that falls due on a weekend or a
 * holiday is due the next business day — the client pays then without being late — and keeps its contractual date.
 */
class BankingCalendarTest {

    static class Memory implements BusinessCalendarRepository {
        final Map<LocalDate, Boolean> days = new HashMap<>();
        public void upsert(String c, LocalDate d, boolean b, String l) { days.put(d, b); }
        public Map<LocalDate, Boolean> exceptions(String c) { return Map.copyOf(days); }
    }

    private final Memory repo = new Memory();
    private final BankingCalendar calendar = new BankingCalendar(repo);

    @AfterEach
    void realClock() {
        BusinessClock.current().resetShift();
    }

    private void holiday(String day) {
        calendar.record("MX", LocalDate.parse(day), false, "feriado");
    }

    @Test
    @DisplayName("Holy Thursday and Friday, then the weekend: an installment due on the Thursday is due on Monday")
    void easter() {
        holiday("2026-04-02");
        holiday("2026-04-03");

        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-04-02"))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-04-04"))).isFalse();   // Saturday
        assertThat(calendar.onOrAfter(LocalDate.parse("2026-04-02"))).isEqualTo(LocalDate.parse("2026-04-06"));
        assertThat(calendar.onOrAfter(LocalDate.parse("2026-04-06"))).isEqualTo(LocalDate.parse("2026-04-06"));
    }

    @Test
    @DisplayName("a plan dated on business days: the moved installments keep their contractual date")
    void planOnBusinessDays() {
        holiday("2026-04-02");
        holiday("2026-04-03");
        UUID loan = UUID.randomUUID();
        List<Installment> plan = new ArrayList<>(List.of(
                Installment.of(loan, 1, LocalDate.parse("2026-04-02"), BigDecimal.TEN, BigDecimal.ONE),   // Holy Thursday
                Installment.of(loan, 2, LocalDate.parse("2026-05-02"), BigDecimal.TEN, BigDecimal.ONE),   // Saturday
                Installment.of(loan, 3, LocalDate.parse("2026-06-02"), BigDecimal.TEN, BigDecimal.ONE))); // Tuesday

        assertThat(calendar.followBusinessDays(plan)).isEqualTo(2);

        assertThat(plan).extracting(Installment::getDueDate).containsExactly(
                LocalDate.parse("2026-04-06"), LocalDate.parse("2026-05-04"), LocalDate.parse("2026-06-02"));
        assertThat(plan).extracting(Installment::getOriginalDueDate).containsExactly(
                LocalDate.parse("2026-04-02"), LocalDate.parse("2026-05-02"), null);
    }

    @Test
    @DisplayName("a holiday published after the loans were dated moves the loans' installments still to fall due on it")
    void aLateHolidayMovesFutureInstallments() {
        BusinessClock.current().shiftTo(LocalDate.parse("2026-12-01"));
        InstallmentRepository installments = mock(InstallmentRepository.class);
        CreditAccountRepository accounts = mock(CreditAccountRepository.class);
        UUID loanId = UUID.randomUUID(), placementId = UUID.randomUUID();
        CreditAccount loan = mock(CreditAccount.class);
        when(loan.isRevolving()).thenReturn(false);
        when(accounts.findById(any())).thenReturn(Optional.empty());
        when(accounts.findById(loanId)).thenReturn(Optional.of(loan));
        Installment ofTheLoan = Installment.of(loanId, 7, LocalDate.parse("2027-02-01"), BigDecimal.TEN, BigDecimal.ONE);
        Installment ofAPlacement = Installment.of(placementId, 2, LocalDate.parse("2027-02-01"), BigDecimal.TEN, BigDecimal.ONE);
        when(installments.findDueOn(LocalDate.parse("2027-02-01"))).thenReturn(List.of(ofTheLoan, ofAPlacement));
        BusinessCalendarSync sync = new BusinessCalendarSync(calendar, installments, accounts);

        int moved = sync.onDay("MX", LocalDate.parse("2027-02-01"), false, "Día de la Constitución");

        assertThat(moved).isEqualTo(1);
        assertThat(ofTheLoan.getDueDate()).isEqualTo(LocalDate.parse("2027-02-02"));
        assertThat(ofAPlacement.getDueDate()).as("a placement is due on its statement's date").isEqualTo(LocalDate.parse("2027-02-01"));
        // A holiday of a day already past changes nothing: what happened on it happened.
        assertThat(sync.onDay("MX", LocalDate.parse("2026-11-16"), false, "Revolución")).isZero();
        verify(installments, never()).findDueOn(LocalDate.parse("2026-11-16"));
    }

    @Test
    @DisplayName("performance: dating a thousand installments on business days takes milliseconds")
    void performance() {
        for (int y = 2026; y <= 2030; y++) {
            for (String md : List.of("01-01", "05-01", "09-16", "11-02", "12-12", "12-25")) holiday(y + "-" + md);
        }
        UUID loan = UUID.randomUUID();
        List<Installment> plan = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            plan.add(Installment.of(loan, i + 1, LocalDate.parse("2026-03-02").plusDays(i), BigDecimal.TEN, BigDecimal.ONE));
        }
        long start = System.nanoTime();
        calendar.followBusinessDays(plan);
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isLessThan(200L);
        assertThat(plan).allSatisfy(i -> assertThat(calendar.isBusinessDay(i.getDueDate())).isTrue());
    }
}
