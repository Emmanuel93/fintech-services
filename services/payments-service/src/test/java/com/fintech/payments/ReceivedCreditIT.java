package com.fintech.payments;

import com.fintech.payments.application.port.in.ApplyReceivedCreditUseCase;
import com.fintech.payments.application.port.in.ApplyReceivedCreditUseCase.ReceivedCreditCommand;
import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.application.port.out.PaymentOrderRepository;
import com.fintech.payments.application.port.out.ReceivedCreditRepository;
import com.fintech.payments.application.service.BalanceSnapshotService;
import com.fintech.payments.application.service.CustomerBalanceService;
import com.fintech.payments.application.service.PaymentService;
import com.fintech.payments.domain.FundingSource;
import com.fintech.payments.domain.PaymentOrder;
import com.fintech.payments.domain.credit.CreditAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A credit received on a payer's CLABE, applied against a real Postgres: the referenced loan first, the rest by
 * seniority, what no loan owed in favor; the two dates on every payment order; redelivery; a loan's rejection going
 * back to the balance in favor; and the balance paying the next due installment.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers", topics = {
        "stp.credit-received", "stp.collection-account-assigned", "stp.collection-account-requested",
        "credit-portfolio.installment-due", "credit-portfolio.credit-account-activated",
        "credit-portfolio.balance-updated", "credit-portfolio.payment-rejected",
        "payments.payment-applied", "payments.customer-balance-moved", "payments.payment-returned"})
@DirtiesContext
class ReceivedCreditIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApplyReceivedCreditUseCase applyCredit;
    @Autowired BalanceSnapshotService snapshots;
    @Autowired AccountBalanceSnapshotRepository snapshotRepository;
    @Autowired ReceivedCreditRepository credits;
    @Autowired PaymentOrderRepository orders;
    @Autowired CustomerBalanceService balances;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;

    /** A payer with two loans: an old one (ref 1000001, owes 1,500) and a newer one (ref 2000002, owes 2,500). */
    private record Payer(UUID party, UUID oldLoan, UUID newLoan) {}

    private Payer payerWithTwoLoans() {
        UUID party = UUID.randomUUID();
        UUID oldLoan = UUID.randomUUID();
        UUID newLoan = UUID.randomUUID();
        long base = Math.abs(party.getMostSignificantBits() % 1_000_000L) * 10;
        snapshots.initSnapshot(oldLoan, party, null, Instant.parse("2026-03-02T16:00:00Z"), base + 1, "PERSONAL_LOAN");
        snapshots.initSnapshot(newLoan, party, null, Instant.parse("2026-06-15T16:00:00Z"), base + 2, "PERSONAL_LOAN");
        snapshots.upsert(oldLoan, party, new BigDecimal("1400"), new BigDecimal("100"), BigDecimal.ZERO, null,
                new BigDecimal("1500.00"), 4, "ACTIVE");
        snapshots.upsert(newLoan, party, new BigDecimal("2300"), new BigDecimal("200"), BigDecimal.ZERO, null,
                new BigDecimal("2500.00"), 7, "ACTIVE");
        return new Payer(party, oldLoan, newLoan);
    }

    private long referenceOf(UUID loan) {
        return snapshotRepository.findByCreditAccountId(loan).orElseThrow().getPaymentReference();
    }

    private ReceivedCreditCommand credit(Payer p, String amount, Long reference) {
        return new ReceivedCreditCommand(UUID.randomUUID(), p.party(), new BigDecimal(amount),
                Instant.parse("2026-12-01T01:00:00Z"), LocalDate.of(2026, 12, 1), "SIMSPEI20261201000001", reference);
    }

    @Test
    @DisplayName("abono de 5,000 con referencia al nuevo: 2,500 referenciado, 1,500 por regla al viejo, 1,000 a favor")
    void prelationAndCreditBalance() {
        Payer p = payerWithTwoLoans();
        ReceivedCreditCommand c = credit(p, "5000.00", referenceOf(p.newLoan()));

        applyCredit.apply(c);

        List<CreditAllocation> lines = credits.allocationsOf(c.inboundCreditId());
        assertThat(lines).extracting(CreditAllocation::getSeq, CreditAllocation::getCreditAccountId,
                        CreditAllocation::getAmount, a -> a.mode().name())
                .containsExactly(tuple(1, p.newLoan(), new BigDecimal("2500.00"), "REFERENCED"),
                        tuple(2, p.oldLoan(), new BigDecimal("1500.00"), "BY_RULE"),
                        tuple(3, null, new BigDecimal("1000.00"), "CREDIT_BALANCE"));
        assertThat(credits.findById(c.inboundCreditId()).orElseThrow().referenceStatus().name()).isEqualTo("MATCHED");

        PaymentOrder toNew = orders.findById(lines.get(0).getPaymentOrderId()).orElseThrow();
        assertThat(toNew.fundingSource()).isEqualTo(FundingSource.SPEI_CREDIT);
        assertThat(toNew.getReceivedAt()).isEqualTo(Instant.parse("2026-12-01T01:00:00Z"));
        assertThat(toNew.getOperationDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(toNew.getTrackingKey()).isEqualTo("SIMSPEI20261201000001");
        assertThat(toNew.getExternalRef()).isEqualTo("CREDIT:" + c.inboundCreditId() + ":1");

        assertThat(balances.balanceOf(p.party())).isEqualByComparingTo("1000.00");
        assertThat(balances.movementsOf(p.party())).extracting(m -> m.type().name(), m -> m.getAmount())
                .containsExactly(tuple("EXCESS_FROM_CREDIT", new BigDecimal("1000.00")));
    }

    @Test
    @DisplayName("la reentrega del mismo abono no aplica nada dos veces")
    void redeliveryIsIdempotent() {
        Payer p = payerWithTwoLoans();
        ReceivedCreditCommand c = credit(p, "5000.00", null);

        applyCredit.apply(c);
        applyCredit.apply(c);

        assertThat(credits.allocationsOf(c.inboundCreditId())).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payment_orders WHERE inbound_credit_id = ?",
                Integer.class, c.inboundCreditId())).isEqualTo(2);
        assertThat(balances.balanceOf(p.party())).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("cartera rechaza una parte del abono: el dinero sigue en el banco y pasa al saldo a favor")
    void rejectionGoesToCreditBalance() {
        Payer p = payerWithTwoLoans();
        ReceivedCreditCommand c = credit(p, "1500.00", null);
        applyCredit.apply(c);
        UUID order = credits.allocationsOf(c.inboundCreditId()).get(0).getPaymentOrderId();

        payments.rejectByEventId(order.toString(), "OVERPAYMENT: amount exceeds totalDebt");
        payments.rejectByEventId(order.toString(), "OVERPAYMENT: amount exceeds totalDebt");

        assertThat(balances.balanceOf(p.party())).isEqualByComparingTo("1500.00");
        assertThat(balances.movementsOf(p.party())).extracting(m -> m.type().name())
                .containsExactly("REJECTED_ALLOCATION");
    }

    @Test
    @DisplayName("el saldo a favor paga la cuota que vence, hasta lo que pide, y no dos veces el mismo día")
    void creditBalancePaysTheDueInstallment() {
        Payer p = payerWithTwoLoans();
        applyCredit.apply(credit(p, "5000.00", null));          // 1,000 stays in favor
        UUID installment = UUID.randomUUID();

        balances.applyToDueInstallment(p.newLoan(), installment, new BigDecimal("800.00"));
        balances.applyToDueInstallment(p.newLoan(), installment, new BigDecimal("800.00"));

        assertThat(balances.balanceOf(p.party())).isEqualByComparingTo("200.00");
        List<PaymentOrder> fromBalance = orders.findAllByCreditAccountIdOrderByCreatedAtDesc(p.newLoan()).stream()
                .filter(o -> o.fundingSource() == FundingSource.CREDIT_BALANCE).toList();
        assertThat(fromBalance).hasSize(1);
        assertThat(fromBalance.get(0).getAmount()).isEqualByComparingTo("800.00");
        assertThat(fromBalance.get(0).getAllocationMode()).isEqualTo("CREDIT_BALANCE");
    }

    @Test
    @DisplayName("aplicar abonos no se vuelve lento: 200 abonos con prelación en menos de 20 segundos")
    void performance() {
        Payer p = payerWithTwoLoans();
        long began = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            applyCredit.apply(credit(p, "10.00", null));
        }
        long ms = (System.nanoTime() - began) / 1_000_000;
        assertThat(ms).as("200 abonos en %d ms", ms).isLessThan(20_000);
        assertThat(credits.findByParty(p.party())).hasSize(200);
    }
}
