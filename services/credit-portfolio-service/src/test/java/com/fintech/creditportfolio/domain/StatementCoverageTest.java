package com.fintech.creditportfolio.domain;

import com.fintech.creditportfolio.domain.StatementCoverage.Obligation;
import com.fintech.creditportfolio.domain.StatementCoverage.Payment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StatementCoverageTest {

    private static final LocalDate C1 = LocalDate.of(2026, 4, 2), D1 = LocalDate.of(2026, 4, 22);
    private static final LocalDate C2 = LocalDate.of(2026, 5, 4), D2 = LocalDate.of(2026, 5, 25);
    private static final LocalDate C3 = LocalDate.of(2026, 6, 2), D3 = LocalDate.of(2026, 6, 22);

    private static Obligation ciclo(int n, LocalDate corte, LocalDate vence, String monto) {
        return new Obligation(UUID.nameUUIDFromBytes(("c" + n).getBytes()), n, corte, vence, new BigDecimal(monto));
    }

    private static Payment pago(String clave, LocalDate fecha, String monto) {
        return new Payment(clave, fecha, new BigDecimal(monto));
    }

    @Test
    @DisplayName("pagar el total de un corte no adelanta el mínimo del siguiente (E34)")
    void aFullPaymentDoesNotPayAheadTheNextMinimum() {
        Obligation c1 = ciclo(1, C1, D1, "517.40"), c2 = ciclo(2, C2, D2, "217.97"), c3 = ciclo(3, C3, D3, "379.19");
        // 10,348 paid on the first due date, nothing until 20 June.
        Map<UUID, BigDecimal> alDia = StatementCoverage.cover(List.of(c1, c2, c3), List.of(pago("p1", D1, "10348.00")), D2.plusDays(10));

        assertThat(StatementCoverage.paid(c1, alDia)).isTrue();
        // The second statement was issued after that payment: its minimum is owed, and late.
        assertThat(alDia.get(c2.id())).isEqualByComparingTo("0");
        assertThat(alDia.get(c3.id())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("un pago cubre, del más viejo al más nuevo, sólo los estados emitidos antes que él")
    void aPaymentCoversOnlyTheStatementsIssuedBeforeIt() {
        Obligation c1 = ciclo(1, C1, D1, "517.40"), c2 = ciclo(2, C2, D2, "217.97"), c3 = ciclo(3, C3, D3, "379.19");
        List<Payment> pagos = List.of(pago("p1", D1, "10348.00"), pago("p2", LocalDate.of(2026, 6, 20), "4359.38"));

        Map<UUID, BigDecimal> cubierto = StatementCoverage.cover(List.of(c1, c2, c3), pagos, null);

        // 20 June is after the cutoffs of statements 2 and 3: it covers both, the late one first.
        assertThat(StatementCoverage.paid(c2, cubierto)).isTrue();
        assertThat(StatementCoverage.paid(c3, cubierto)).isTrue();
    }

    @Test
    @DisplayName("un pago el mismo día del corte ya estaba en el saldo de ese corte")
    void aPaymentOnTheCutoffDayBelongsToThatStatementsBalance() {
        Obligation c1 = ciclo(1, C1, D1, "500.00"), c2 = ciclo(2, C2, D2, "300.00");
        Map<UUID, BigDecimal> cubierto = StatementCoverage.cover(List.of(c1, c2), List.of(pago("p", C2, "2000.00")), null);

        assertThat(StatementCoverage.paid(c1, cubierto)).isTrue();
        assertThat(cubierto.get(c2.id())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("dos pagos parciales se suman sobre el mismo estado")
    void partialPaymentsAddUp() {
        Obligation c1 = ciclo(1, C1, D1, "500.00");
        Map<UUID, BigDecimal> cubierto = StatementCoverage.cover(List.of(c1),
                List.of(pago("a", D1.minusDays(5), "200.00"), pago("b", D1, "300.00")), null);

        assertThat(StatementCoverage.paid(c1, cubierto)).isTrue();
    }

    @Test
    @DisplayName("al cierre de un día sólo cuentan los pagos valuados hasta ese día")
    void asOfADayOnlyItsPaymentsCount() {
        Obligation c1 = ciclo(1, C1, D1, "500.00");
        Map<UUID, BigDecimal> antes = StatementCoverage.cover(List.of(c1), List.of(pago("a", D1.plusDays(3), "500.00")), D1.plusDays(2));

        assertThat(antes.get(c1.id())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("un estado sin corte registrado (anterior al cambio) conserva la regla de antes")
    void aStatementWithoutCutoffKeepsTheOldRule() {
        Obligation viejo = ciclo(1, null, D1, "500.00");
        Map<UUID, BigDecimal> cubierto = StatementCoverage.cover(List.of(viejo), List.of(pago("a", C1.minusDays(10), "500.00")), null);

        assertThat(StatementCoverage.paid(viejo, cubierto)).isTrue();
    }

    @Test
    @DisplayName("lo devuelto de un pago deja de cubrir")
    void aReturnedPaymentNoLongerCovers() {
        CreditAccount cuenta = mock(CreditAccount.class);
        BalanceEvent pagado = BalanceEvent.record(UUID.randomUUID(), "orden-1", "PAYMENT_APPLIED", new BigDecimal("-500.00"),
                cuenta, D1);
        BalanceEvent devuelto = BalanceEvent.record(UUID.randomUUID(), "PAYMENT_RETURNED:orden-1", "PAYMENT_RETURNED",
                new BigDecimal("500.00"), cuenta, D1.plusDays(1));

        assertThat(StatementCoverage.paymentsFrom(List.of(pagado, devuelto))).isEmpty();
    }

    @Test
    @DisplayName("una quita cubre, sin dinero, los estados más viejos emitidos antes de ella (D10)")
    void aQuitaCoversTheOldestStatementsLikeAPaymentWithNoCash() {
        CreditAccount cuenta = mock(CreditAccount.class);
        BalanceEvent quita = BalanceEvent.record(UUID.randomUUID(), "convenio-1", "AGREEMENT_QUITA_PARCIAL",
                new BigDecimal("-1500.00"), cuenta, LocalDate.of(2026, 6, 15));
        BalanceEvent liquida = BalanceEvent.record(UUID.randomUUID(), "orden-9", "PAYMENT_APPLIED",
                new BigDecimal("-700.00"), cuenta, LocalDate.of(2026, 6, 22));
        List<Payment> pagos = StatementCoverage.paymentsFrom(List.of(quita, liquida));
        assertThat(pagos).extracting(Payment::key).containsExactly("convenio-1", "orden-9");

        Obligation c1 = ciclo(1, C1, D1, "1000.00"), c2 = ciclo(2, C2, D2, "1000.00"), c3 = ciclo(3, C3, D3, "200.00");
        Map<UUID, BigDecimal> cubierto = StatementCoverage.cover(List.of(c1, c2, c3), pagos, null);
        // The quita (1,500) covers the first statement and half of the second; the payoff covers the rest and the third.
        assertThat(cubierto.get(c1.id())).isEqualByComparingTo("1000.00");
        assertThat(cubierto.get(c2.id())).isEqualByComparingTo("1000.00");
        assertThat(cubierto.get(c3.id())).isEqualByComparingTo("200.00");
        assertThat(StatementCoverage.paid(c3, cubierto)).isTrue();
        assertThat(StatementCoverage.quitaOn(List.of(quita, liquida))).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(StatementCoverage.quitaOn(List.of(liquida))).isNull();
    }
}
