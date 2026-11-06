package com.fintech.creditportfolio.domain;

import com.fintech.creditportfolio.domain.StatementCoverage.Obligation;
import com.fintech.creditportfolio.domain.StatementCoverage.Payment;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
import com.fintech.creditportfolio.domain.config.DistributorCommission.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a distributor's payment nets of her commission: the statements it completes, at the tier of the day the money
 * landed. The market's rule: a statement is paid by her cash plus her commission; late, less; past the ladder, nothing.
 */
class CommissionNettingTest {

    private static final DistributorCommission MARKET = new DistributorCommission(
            DistributorCommission.COLLECTED_PRINCIPAL_AND_INTEREST, new BigDecimal("0.18"),
            List.of(new Tier(0, new BigDecimal("0.18")), new Tier(1, new BigDecimal("0.14")),
                    new Tier(2, new BigDecimal("0.11")), new Tier(3, new BigDecimal("0.07"))),
            DistributorCommission.NETTED);

    /** Statement 1: cut on 21 February, due 3 March, asks 1,000; its installments scheduled 150 of commission. */
    private static final Obligation FIRST = new Obligation(UUID.randomUUID(), 1, LocalDate.of(2026, 2, 21),
            LocalDate.of(2026, 3, 3), new BigDecimal("1000.00"));
    /** Statement 2: cut on 6 March, due 18 March, asks 1,200; scheduled 180. */
    private static final Obligation SECOND = new Obligation(UUID.randomUUID(), 2, LocalDate.of(2026, 3, 6),
            LocalDate.of(2026, 3, 18), new BigDecimal("1200.00"));
    private static final Map<Integer, BigDecimal> SCHEDULED = Map.of(1, new BigDecimal("150.00"), 2, new BigDecimal("180.00"));
    private static final Map<Integer, BigDecimal> BASIS = Map.of(1, new BigDecimal("833.33"), 2, new BigDecimal("1000.00"));

    private static List<CommissionNetting.Grant> plan(List<Obligation> statements, List<Payment> prior, String cash, LocalDate on) {
        return CommissionNetting.plan(statements, prior, new BigDecimal(cash), on, SCHEDULED, BASIS, Map.of(), MARKET);
    }

    @Test
    @DisplayName("entrega a tiempo lo que pidió el estado menos su comisión: el estado se completa y la comisión es la del 18 %")
    void onTimeTheStatementIsCompletedByCashPlusCommission() {
        List<CommissionNetting.Grant> grants = plan(List.of(FIRST), List.of(), "850.00", LocalDate.of(2026, 3, 3));
        assertThat(grants).hasSize(1);
        CommissionNetting.Grant g = grants.get(0);
        assertThat(g.statement().cycle()).isEqualTo(1);
        assertThat(g.daysLate()).isZero();
        assertThat(g.rate()).isEqualByComparingTo("0.18");
        assertThat(g.amount()).isEqualByComparingTo("150.00");
        assertThat(g.basis()).isEqualByComparingTo("833.33");
    }

    @Test
    @DisplayName("dos días tarde la comisión baja al 11 %: 91.67 de los 150, y entrega 908.33")
    void twoDaysLateTheTierIsEleven() {
        List<CommissionNetting.Grant> grants = plan(List.of(FIRST), List.of(), "908.33", LocalDate.of(2026, 3, 5));
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).daysLate()).isEqualTo(2);
        assertThat(grants.get(0).rate()).isEqualByComparingTo("0.11");
        assertThat(grants.get(0).amount()).isEqualByComparingTo("91.67");
    }

    @Test
    @DisplayName("se pone al día pagando dos estados de una vez: el atrasado ya no da comisión, el vigente la suya")
    void catchingUpPaysTheOldOneWholeAndEarnsOnTheCurrentOne() {
        // 18 March: statement 1 is 15 days late (nothing), statement 2 is on time (180).
        List<CommissionNetting.Grant> grants = plan(List.of(FIRST, SECOND), List.of(), "2020.00", LocalDate.of(2026, 3, 18));
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).statement().cycle()).isEqualTo(2);
        assertThat(grants.get(0).daysLate()).isZero();
        assertThat(grants.get(0).amount()).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("un pago parcial no gana nada; el que completa el estado fija el escalón")
    void aPartialPaymentEarnsNothingUntilTheStatementIsCompleted() {
        assertThat(plan(List.of(FIRST), List.of(), "500.00", LocalDate.of(2026, 3, 3))).isEmpty();
        // The rest arrives a day late: the statement misses 500, the commission at the tier is 116.67, the cash 383.33.
        List<Payment> prior = List.of(new Payment("p1", LocalDate.of(2026, 3, 3), new BigDecimal("500.00")));
        assertThat(plan(List.of(FIRST), prior, "300.00", LocalDate.of(2026, 3, 4))).as("still short").isEmpty();
        List<CommissionNetting.Grant> grants = plan(List.of(FIRST), prior, "383.33", LocalDate.of(2026, 3, 4));
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).daysLate()).isEqualTo(1);
        assertThat(grants.get(0).amount()).isEqualByComparingTo("116.67");
    }

    @Test
    @DisplayName("si entrega el total sin descontarse, la comisión se le reconoce igual y el sobrante baja su saldo")
    void payingTheWholeStatementStillEarnsTheCommission() {
        List<CommissionNetting.Grant> grants = plan(List.of(FIRST), List.of(), "1000.00", LocalDate.of(2026, 3, 3));
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).amount()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("lo que ya se neteó contra un estado no se vuelve a netear")
    void whatWasAlreadyNettedIsNotNettedAgain() {
        List<Payment> prior = List.of(new Payment("COMMISSION_NETTED:p0:1", LocalDate.of(2026, 3, 3), new BigDecimal("150.00")),
                new Payment("p0", LocalDate.of(2026, 3, 3), new BigDecimal("400.00")));
        List<CommissionNetting.Grant> grants = CommissionNetting.plan(List.of(FIRST), prior, new BigDecimal("450.00"),
                LocalDate.of(2026, 3, 3), SCHEDULED, BASIS, Map.of(1, new BigDecimal("150.00")), MARKET);
        assertThat(grants).isEmpty();
    }

    @Test
    @DisplayName("un pago antes del corte del estado no lo completa: no hay estado emitido que netear")
    void aPaymentBeforeTheCutoffNetsNothing() {
        assertThat(plan(List.of(SECOND), List.of(), "1020.00", LocalDate.of(2026, 3, 5))).isEmpty();
    }

    @Test
    @DisplayName("rendimiento: diez mil pagos sobre una línea con 48 estados se planean en menos de un segundo")
    void tenThousandPlansInUnderASecond() {
        List<Obligation> statements = new ArrayList<>();
        LocalDate cutoff = LocalDate.of(2025, 1, 6);
        for (int k = 1; k <= 48; k++) {
            statements.add(new Obligation(UUID.randomUUID(), k, cutoff, cutoff.plusDays(12), new BigDecimal("1000.00")));
            cutoff = cutoff.getDayOfMonth() == 6 ? cutoff.withDayOfMonth(21) : cutoff.plusMonths(1).withDayOfMonth(6);
        }
        List<Payment> prior = new ArrayList<>();
        for (int k = 0; k < 47; k++) {
            prior.add(new Payment("p" + k, statements.get(k).due(), new BigDecimal("1000.00")));
        }
        Map<Integer, BigDecimal> scheduled = new java.util.HashMap<>();
        for (int k = 1; k <= 48; k++) scheduled.put(k, new BigDecimal("150.00"));
        long start = System.nanoTime();
        int granted = 0;
        for (int n = 0; n < 10_000; n++) {
            granted += CommissionNetting.plan(statements, prior, new BigDecimal("850.00"), statements.get(47).due(),
                    scheduled, scheduled, Map.of(), MARKET).size();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(granted).isEqualTo(10_000);
        assertThat(ms).as("10,000 plans took %d ms", ms).isLessThan(1_000);
    }
}
