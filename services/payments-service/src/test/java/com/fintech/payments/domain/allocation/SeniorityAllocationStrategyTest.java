package com.fintech.payments.domain.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** The prelation of a received credit among a payer's loans, edge by edge. */
class SeniorityAllocationStrategyTest {

    private final AllocationStrategy strategy = new SeniorityAllocationStrategy();

    private static final UUID OLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NEW = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static LoanDebt loan(UUID id, long reference, String activated, String debt) {
        return new LoanDebt(id, reference, Instant.parse(activated), new BigDecimal(debt));
    }

    private final List<LoanDebt> three = List.of(
            loan(NEW, 3000003, "2026-09-01T16:00:00Z", "4000.00"),
            loan(OLD, 1000001, "2026-03-02T16:00:00Z", "1500.00"),
            loan(MID, 2000002, "2026-06-15T16:00:00Z", "2500.00"));

    @Test
    @DisplayName("con referencia: paga primero el crédito que nombra, hasta su deuda")
    void referencedLoanFirst() {
        AllocationPlan plan = strategy.allocate(new BigDecimal("2000.00"), 2000002L, three);

        assertThat(plan.referenceStatus()).isEqualTo(ReferenceStatus.MATCHED);
        assertThat(plan.lines()).extracting(AllocationLine::creditAccountId, AllocationLine::amount, AllocationLine::mode)
                .containsExactly(tuple(MID, new BigDecimal("2000.00"), AllocationMode.REFERENCED));
    }

    @Test
    @DisplayName("lo que sobra del referenciado va por antigüedad, y lo que nadie debe queda a favor")
    void remainderBySeniorityThenCreditBalance() {
        AllocationPlan plan = strategy.allocate(new BigDecimal("9000.00"), 2000002L, three);

        assertThat(plan.lines()).extracting(AllocationLine::sequence, AllocationLine::creditAccountId,
                        AllocationLine::amount, AllocationLine::mode)
                .containsExactly(
                        tuple(1, MID, new BigDecimal("2500.00"), AllocationMode.REFERENCED),
                        tuple(2, OLD, new BigDecimal("1500.00"), AllocationMode.BY_RULE),
                        tuple(3, NEW, new BigDecimal("4000.00"), AllocationMode.BY_RULE),
                        tuple(4, null, new BigDecimal("1000.00"), AllocationMode.CREDIT_BALANCE));
        assertThat(plan.toLoans()).isEqualByComparingTo("8000.00");
        assertThat(plan.toCreditBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("sin referencia: el más antiguo con deuda primero, cada uno hasta lo que debe")
    void noReferenceOldestFirst() {
        AllocationPlan plan = strategy.allocate(new BigDecimal("3000.00"), null, three);

        assertThat(plan.referenceStatus()).isEqualTo(ReferenceStatus.ABSENT);
        assertThat(plan.lines()).extracting(AllocationLine::creditAccountId, AllocationLine::amount, AllocationLine::mode)
                .containsExactly(
                        tuple(OLD, new BigDecimal("1500.00"), AllocationMode.BY_RULE),
                        tuple(MID, new BigDecimal("1500.00"), AllocationMode.BY_RULE));
    }

    @Test
    @DisplayName("una referencia que no es de la persona se ignora y se aplica por regla")
    void foreignReferenceAppliesByRule() {
        AllocationPlan plan = strategy.allocate(new BigDecimal("500.00"), 9999999L, three);

        assertThat(plan.referenceStatus()).isEqualTo(ReferenceStatus.NOT_FOUND);
        assertThat(plan.lines()).extracting(AllocationLine::creditAccountId, AllocationLine::mode)
                .containsExactly(tuple(OLD, AllocationMode.BY_RULE));
    }

    @Test
    @DisplayName("el referenciado ya liquidado no recibe nada: el abono sigue por antigüedad")
    void referencedLoanWithoutDebt() {
        List<LoanDebt> loans = List.of(loan(OLD, 1000001, "2026-03-02T16:00:00Z", "0.00"),
                loan(MID, 2000002, "2026-06-15T16:00:00Z", "300.00"));
        AllocationPlan plan = strategy.allocate(new BigDecimal("500.00"), 1000001L, loans);

        assertThat(plan.referenceStatus()).isEqualTo(ReferenceStatus.MATCHED);
        assertThat(plan.lines()).extracting(AllocationLine::creditAccountId, AllocationLine::amount, AllocationLine::mode)
                .containsExactly(tuple(MID, new BigDecimal("300.00"), AllocationMode.BY_RULE),
                        tuple(null, new BigDecimal("200.00"), AllocationMode.CREDIT_BALANCE));
    }

    @Test
    @DisplayName("sin deuda en ningún crédito, todo el abono queda a favor")
    void nothingOwedEverythingInFavor() {
        AllocationPlan plan = strategy.allocate(new BigDecimal("750.25"), null, List.of());

        assertThat(plan.lines()).extracting(AllocationLine::creditAccountId, AllocationLine::amount, AllocationLine::mode)
                .containsExactly(tuple(null, new BigDecimal("750.25"), AllocationMode.CREDIT_BALANCE));
    }

    @Test
    @DisplayName("un abono sin importe no se aplica")
    void rejectsZero() {
        assertThatThrownBy(() -> strategy.allocate(BigDecimal.ZERO, null, three)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cada peso cuenta: en mil abonos al azar las líneas suman el abono y nadie recibe más de lo que debe")
    void everyPesoAccountedFor() {
        Random random = new Random(20261104L);
        for (int i = 0; i < 1000; i++) {
            List<LoanDebt> loans = new ArrayList<>();
            for (int j = 0; j < 1 + random.nextInt(5); j++) {
                loans.add(new LoanDebt(UUID.randomUUID(), (long) j, Instant.parse("2026-03-02T16:00:00Z").plusSeconds(j * 86400L),
                        BigDecimal.valueOf(random.nextInt(500_000), 2)));
            }
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(1_000_000), 2);
            Long reference = random.nextBoolean() ? (long) random.nextInt(6) : null;
            AllocationPlan plan = strategy.allocate(amount, reference, loans);

            assertThat(plan.total()).isEqualByComparingTo(amount);
            for (AllocationLine line : plan.lines()) {
                if (line.toLoan()) {
                    LoanDebt loan = loans.stream().filter(l -> l.creditAccountId().equals(line.creditAccountId())).findFirst().orElseThrow();
                    assertThat(line.amount()).isLessThanOrEqualTo(loan.debt());
                }
            }
        }
    }

    @Test
    @DisplayName("decidir la prelación no cuesta nada: cien mil abonos en menos de un segundo")
    void performance() {
        long began = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            strategy.allocate(new BigDecimal("9000.00"), 2000002L, three);
        }
        assertThat((System.nanoTime() - began) / 1_000_000).isLessThan(1_000);
    }

    @Test
    @DisplayName("el depósito del cliente va a la línea de su distribuidora hasta lo que debe; el resto, a favor de ella")
    void aClientsDepositToHerLine() {
        UUID line = UUID.randomUUID(), client = UUID.randomUUID();
        SeniorityAllocationStrategy s = new SeniorityAllocationStrategy();
        AllocationPlan plan = s.allocateForClient(new BigDecimal("700.00"),
                new LoanDebt(line, 1234566L, null, new BigDecimal("500.00")), client);
        assertThat(plan.lines()).containsExactly(
                new AllocationLine(1, line, new BigDecimal("500.00"), AllocationMode.BENEFICIARY, client),
                new AllocationLine(2, null, new BigDecimal("200.00"), AllocationMode.CREDIT_BALANCE));
        assertThat(plan.referenceStatus()).isEqualTo(ReferenceStatus.MATCHED);

        AllocationPlan nothingOwed = s.allocateForClient(new BigDecimal("300.00"),
                new LoanDebt(line, 1234566L, null, BigDecimal.ZERO), client);
        assertThat(nothingOwed.lines()).containsExactly(
                new AllocationLine(1, null, new BigDecimal("300.00"), AllocationMode.CREDIT_BALANCE));
    }
}
