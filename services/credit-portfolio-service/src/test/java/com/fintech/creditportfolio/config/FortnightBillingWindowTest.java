package com.fintech.creditportfolio.config;

import com.fintech.creditportfolio.domain.config.BillingCycle;
import com.fintech.creditportfolio.domain.config.SemiMonthlyCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Up to which day a statement bills its placements' installments. A fortnight's installments fall on its cutoffs, and
 * an installment dated on a cutoff is that cutoff's statement's, even when holidays move the previous statement's due
 * date onto it (Holy Week: the 6 April 2025 statement is paid by the 21st, the day of the next cutoff).
 */
class FortnightBillingWindowTest {

    /** The distributor line: cuts on the 6th and the 21st, paid by the 18th and the 3rd. */
    private static final BillingCycle DISTRIBUTOR = new BillingCycle("SEMI_MONTHLY", null, 0, 2, null, "NEXT",
            "PER_PERIOD", List.of(6, 21), List.of(18, 3));
    private static final SemiMonthlyCalendar FORTNIGHT = DISTRIBUTOR.semiMonthly();

    @Test
    @DisplayName("una quincena normal factura hasta su fecha límite, que queda antes del corte siguiente")
    void anOrdinaryFortnightBillsUpToItsDueDate() {
        // Cut on 6 January 2025, paid by Monday the 20th (the 18th is a Saturday); the next cutoff is the 21st.
        assertThat(BillingCycle.lastBillableDay(DISTRIBUTOR, LocalDate.of(2025, 1, 6), LocalDate.of(2025, 1, 20)))
                .isEqualTo(LocalDate.of(2025, 1, 20));
        // Cut on 21 January, paid by Tuesday 4 February (the 3rd is a holiday); the next cutoff is the 6th.
        assertThat(BillingCycle.lastBillableDay(DISTRIBUTOR, LocalDate.of(2025, 1, 21), LocalDate.of(2025, 2, 4)))
                .isEqualTo(LocalDate.of(2025, 2, 4));
    }

    @Test
    @DisplayName("Semana Santa 2025: el estado del 6 de abril vence el 21, día del corte siguiente, y factura hasta el 20")
    void holyWeek2025StopsTheDayBeforeTheNextCutoff() {
        assertThat(BillingCycle.lastBillableDay(DISTRIBUTOR, LocalDate.of(2025, 4, 6), LocalDate.of(2025, 4, 21)))
                .isEqualTo(LocalDate.of(2025, 4, 20));
    }

    @Test
    @DisplayName("Semana Santa 2026: el estado del 21 de marzo vence el 6 de abril, día del corte siguiente, y factura hasta el 5")
    void holyWeek2026StopsTheDayBeforeTheNextCutoff() {
        assertThat(BillingCycle.lastBillableDay(DISTRIBUTOR, LocalDate.of(2026, 3, 21), LocalDate.of(2026, 4, 6)))
                .isEqualTo(LocalDate.of(2026, 4, 5));
    }

    @Test
    @DisplayName("ninguna cuota fechada en un corte la factura el estado anterior, en cuatro años de cortes")
    void noInstallmentDatedOnACutoffIsBilledByThePreviousStatement() {
        LocalDate corte = LocalDate.of(2025, 1, 6);
        for (int n = 0; n < 96; n++) {
            LocalDate siguiente = FORTNIGHT.nextCutoff(corte);
            // Whatever the shift, a due date never goes past the next cutoff (closing's rule); here it is the worst case.
            LocalDate hasta = BillingCycle.lastBillableDay(DISTRIBUTOR, corte, siguiente);
            assertThat(hasta).as("statement cut on %s", corte).isBefore(siguiente).isAfter(corte);
            corte = siguiente;
        }
    }

    @Test
    @DisplayName("un ciclo mensual, o ninguno, factura hasta la fecha límite: nunca llega a su corte siguiente")
    void aMonthlyCycleOrNoneBillsUpToTheDueDate() {
        BillingCycle mensual = new BillingCycle("FIXED_DAY", 10, 20, 0, null, "NEXT");
        LocalDate corte = LocalDate.of(2026, 4, 10), vence = LocalDate.of(2026, 4, 30);
        assertThat(BillingCycle.lastBillableDay(mensual, corte, vence)).isEqualTo(vence);
        assertThat(BillingCycle.lastBillableDay(null, corte, vence)).isEqualTo(vence);
        assertThat(BillingCycle.lastBillableDay(DISTRIBUTOR, corte, null)).isNull();
    }

    @Test
    @DisplayName("rendimiento: un millón de ventanas de facturación se calculan en menos de un segundo")
    void aMillionWindowsInUnderASecond() {
        LocalDate corte = LocalDate.of(2025, 4, 6), vence = LocalDate.of(2025, 4, 21);
        long start = System.nanoTime();
        long holyWeek = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (BillingCycle.lastBillableDay(DISTRIBUTOR, corte, vence).getDayOfMonth() == 20) holyWeek++;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(holyWeek).isEqualTo(1_000_000);
        assertThat(ms).as("1,000,000 billing windows took %d ms", ms).isLessThan(1_000);
    }
}
