package com.fintech.creditportfolio.domain.beneficiary;

import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BeneficiaryTypingTest {

    static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    static final Instant NOW = Instant.parse("2026-03-10T18:00:00Z");
    final UUID client = UUID.randomUUID();
    final UUID lineA = UUID.randomUUID();
    final UUID lineB = UUID.randomUUID();

    BeneficiaryTyping.LineFacts current(UUID line) {
        return new BeneficiaryTyping.LineFacts(line, UUID.randomUUID(), false, false, false, null);
    }

    BeneficiaryTyping.LineFacts behind(UUID line) {
        return new BeneficiaryTyping.LineFacts(line, UUID.randomUUID(), false, true, true, "LINE_BLOCKED_IN_ARREARS: overdue 1200.00");
    }

    BeneficiaryTyping.LineFacts excluded(UUID line) {
        return new BeneficiaryTyping.LineFacts(line, UUID.randomUUID(), true, false, true, "LINE_CLOSED_AFTER_QUITA: convenio de quita del 2026-01-10");
    }

    /** A completed placement of {@code amount} in {@code n} installments due every 15 days from {@code firstDue}. */
    BeneficiaryTyping.PlacementFacts placement(UUID line, BeneficiaryTyping.LineFacts facts, String amount, int n,
                                               LocalDate firstDue, int paid, int billed) {
        Disposition d = Disposition.create(line, DispositionType.THIRD_PARTY_CREDIT, new BigDecimal(amount), client);
        d.markProcessing();
        d.complete("SPEI-1");
        BigDecimal principal = new BigDecimal(amount).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        List<Installment> plan = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Installment c = Installment.of(d.getDispositionId(), i, firstDue.plusDays(15L * (i - 1)), principal, new BigDecimal("50.00"));
            if (i <= billed) c.billIn(i, c.getDueDate());
            if (i <= paid) c.settleAs(InstallmentStatus.PAID);
            plan.add(c);
        }
        return new BeneficiaryTyping.PlacementFacts(d, plan, facts);
    }

    @Test
    @DisplayName("su primer préstamo vivo lo deja NUEVO; liquidado por completo, REVOLVENTE")
    void nuevoUntilAPlacementIsFullyPaid() {
        var live = placement(lineA, current(lineA), "5000", 12, TODAY.plusDays(5), 0, 0);
        var standing = BeneficiaryTyping.evaluate(client, List.of(live), TODAY, NOW);
        assertThat(standing.type()).isEqualTo(ClientType.NUEVO);
        assertThat(standing.activeLoans()).isEqualTo(1);
        assertThat(standing.outstanding()).isEqualByComparingTo("5000.04");   // 12 × 416.67
        assertThat(standing.derivedFlags()).isEmpty();

        var paidOff = placement(lineA, current(lineA), "3000", 12, TODAY.minusDays(200), 12, 12);
        standing = BeneficiaryTyping.evaluate(client, List.of(paidOff, live), TODAY, NOW);
        assertThat(standing.type()).isEqualTo(ClientType.REVOLVENTE);
        assertThat(standing.loansGranted()).isEqualTo(2);
        assertThat(standing.loansPaid()).isEqualTo(1);
        assertThat(standing.activeLoans()).isEqualTo(1);
    }

    @Test
    @DisplayName("quebranto y quita no cuentan como pagado: el préstamo liquidado así no lo vuelve revolvente")
    void quitaAndWriteOffAreNotPayment() {
        var forgiven = placement(lineB, excluded(lineB), "4000", 12, TODAY.minusDays(200), 12, 12);
        var standing = BeneficiaryTyping.evaluate(client, List.of(forgiven), TODAY, NOW);
        assertThat(standing.type()).isEqualTo(ClientType.NUEVO);
        assertThat(standing.loansPaid()).isZero();
        assertThat(standing.activeLoans()).isZero();
        assertThat(standing.outstanding()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("una colocación fallida o devuelta nunca existió; una en vuelo cuenta como viva por su monto")
    void failedNeverHappened_inFlightCountsByItsAmount() {
        Disposition failed = Disposition.create(lineA, DispositionType.THIRD_PARTY_CREDIT, new BigDecimal("2000"), client);
        failed.markProcessing();
        failed.fail("PLD", "rechazada por PLD");
        Disposition reversed = Disposition.create(lineA, DispositionType.THIRD_PARTY_CREDIT, new BigDecimal("2500"), client);
        reversed.markProcessing();
        reversed.reverse("cuenta cancelada");
        Disposition inFlight = Disposition.create(lineA, DispositionType.THIRD_PARTY_CREDIT, new BigDecimal("3000"), client);
        inFlight.markProcessing();

        var standing = BeneficiaryTyping.evaluate(client, List.of(
                new BeneficiaryTyping.PlacementFacts(failed, List.of(), current(lineA)),
                new BeneficiaryTyping.PlacementFacts(reversed, List.of(), current(lineA)),
                new BeneficiaryTyping.PlacementFacts(inFlight, List.of(), current(lineA))), TODAY, NOW);

        assertThat(standing.loansGranted()).isEqualTo(1);
        assertThat(standing.activeLoans()).isEqualTo(1);
        assertThat(standing.outstanding()).isEqualByComparingTo("3000");
        assertThat(standing.lines()).singleElement().satisfies(l -> {
            assertThat(l.activeLoans()).isEqualTo(1);
            assertThat(l.outstanding()).isEqualByComparingTo("3000");
        });
    }

    @Test
    @DisplayName("su cuota facturada vencida sin pagar: «su distribuidora está en mora» si la línea va atrás, «vale en mora» si va al corriente")
    void anOverdueBilledInstallmentFlagsHim_namingWhoIsBehind() {
        // Billed and due 10 days ago, unpaid.
        var onBehindLine = placement(lineA, behind(lineA), "5000", 12, TODAY.minusDays(10), 0, 1);
        var flags = BeneficiaryTyping.evaluate(client, List.of(onBehindLine), TODAY, NOW).derivedFlags();
        assertThat(flags).singleElement().satisfies(f -> {
            assertThat(f.reason()).isEqualTo(FlagReason.DISTRIBUTOR_IN_ARREARS);
            assertThat(f.source()).isEqualTo(FlagSource.SYSTEM);
            assertThat(f.creditAccountId()).isEqualTo(lineA);
            assertThat(f.comment()).startsWith("LINE_BLOCKED_IN_ARREARS");
        });

        var onCurrentLine = placement(lineB, current(lineB), "5000", 12, TODAY.minusDays(10), 0, 1);
        flags = BeneficiaryTyping.evaluate(client, List.of(onCurrentLine), TODAY, NOW).derivedFlags();
        assertThat(flags).singleElement().satisfies(f -> assertThat(f.reason()).isEqualTo(FlagReason.VOUCHER_IN_ARREARS));
    }

    @Test
    @DisplayName("al ponerse al día no queda marcado; una cuota sin facturar o que vence hoy no marca a nadie")
    void currentClientsAreNotFlagged() {
        var caughtUp = placement(lineA, behind(lineA), "5000", 12, TODAY.minusDays(10), 1, 1);
        var notBilled = placement(lineA, behind(lineA), "5000", 12, TODAY.minusDays(10), 0, 0);
        var dueToday = placement(lineA, behind(lineA), "5000", 12, TODAY, 0, 1);
        assertThat(BeneficiaryTyping.evaluate(client, List.of(caughtUp, notBilled, dueToday), TODAY, NOW).derivedFlags()).isEmpty();
    }

    @Test
    @DisplayName("tipar a un cliente con 300 colocaciones de 24 cuotas cuesta milisegundos")
    void typingIsCheap() {
        List<BeneficiaryTyping.PlacementFacts> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            UUID line = i % 2 == 0 ? lineA : lineB;
            many.add(placement(line, current(line), "5000", 24, TODAY.minusDays(400 - i), i % 3 == 0 ? 24 : 5, 6));
        }
        long start = System.nanoTime();
        BeneficiaryTyping.Standing last = null;
        for (int r = 0; r < 200; r++) last = BeneficiaryTyping.evaluate(client, many, TODAY, NOW);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(last.type()).isEqualTo(ClientType.REVOLVENTE);
        assertThat(last.loansGranted()).isEqualTo(300);
        // 200 typings of a client with 7,200 installments: far under a second, so an authorization never waits on it.
        assertThat(ms).isLessThan(1500);
    }
}
