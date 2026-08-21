package com.fintech.creditportfolio;

import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Balance engine math: accrual, penalty, payment hierarchy, reversal, write-off, revolving cupo. */
class CreditAccountBalanceTest {

    private CreditAccount installmentActive(String principal) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-1", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal(principal), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", new BigDecimal("1.0"), "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal(principal));   // ACTIVE, principal = amount
        return a;
    }

    private CreditAccount revolvingActive(String line) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-2", UUID.randomUUID(),
                "RL-001", 1, "REVOLVING_LINE", "REVOLVING",
                null, new BigDecimal(line), null,
                new BigDecimal("38.0"), new BigDecimal("60.0"),
                null, BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal(line));   // disburses full line; principal=line, available=0
        return a;
    }

    // ── accrual + penalty ───────────────────────────────────────────────────

    @Test
    void interestAccrual_addsToInterestBucket() {
        CreditAccount a = installmentActive("50000");
        a.applyInterestAccrual(new BigDecimal("1000.00"));
        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("1000.00");
        assertThat(a.getTotalDebt()).isEqualByComparingTo("51000.00");
    }

    @Test
    void penaltyCharge_addsToPenaltyBucket() {
        CreditAccount a = installmentActive("50000");
        a.applyPenaltyCharge(new BigDecimal("250.00"));
        assertThat(a.getPenaltyBalance()).isEqualByComparingTo("250.00");
    }

    // ── payment hierarchy: penalty → interest → principal ───────────────────

    @Test
    void payment_appliesInHierarchy_penaltyInterestPrincipal() {
        CreditAccount a = installmentActive("50000");
        a.applyPenaltyCharge(new BigDecimal("300"));
        a.applyInterestAccrual(new BigDecimal("700"));
        // Pay 1500: 300 penalty, 700 interest, 500 principal
        BigDecimal toPrincipal = a.applyPayment(new BigDecimal("1500"));
        assertThat(a.getPenaltyBalance()).isEqualByComparingTo("0");
        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("0");
        assertThat(a.getPrincipalBalance()).isEqualByComparingTo("49500");
        assertThat(toPrincipal).isEqualByComparingTo("500");
    }

    @Test
    void payment_partial_onlyCoversPenaltyFirst() {
        CreditAccount a = installmentActive("50000");
        a.applyPenaltyCharge(new BigDecimal("300"));
        a.applyInterestAccrual(new BigDecimal("700"));
        a.applyPayment(new BigDecimal("200"));   // less than penalty
        assertThat(a.getPenaltyBalance()).isEqualByComparingTo("100");
        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("700");
        assertThat(a.getPrincipalBalance()).isEqualByComparingTo("50000");
    }

    @Test
    void fullPayment_clearsDebt_andSettles() {
        CreditAccount a = installmentActive("1000");
        a.applyPayment(new BigDecimal("1000"));
        assertThat(a.getTotalDebt()).isEqualByComparingTo("0");
        a.settleIfClear();
        assertThat(a.getStatus()).isEqualTo(CreditAccountStatus.SETTLED);
    }

    // ── revolving: payment to principal liberates cupo ──────────────────────

    @Test
    void revolvingPayment_liberatesAvailableCredit() {
        CreditAccount a = revolvingActive("10000");   // principal=10000, available=0
        a.applyPayment(new BigDecimal("3000"));         // all to principal (no penalty/interest)
        assertThat(a.getPrincipalBalance()).isEqualByComparingTo("7000");
        assertThat(a.getAvailableCredit()).isEqualByComparingTo("3000");
    }

    @Test
    void revolvingPayment_availableNeverExceedsLimit() {
        CreditAccount a = revolvingActive("10000");
        a.applyPayment(new BigDecimal("10000"));        // fully repaid
        assertThat(a.getAvailableCredit()).isEqualByComparingTo("10000");
        assertThat(a.getAvailableCredit()).isLessThanOrEqualTo(a.getCreditLimit());
    }

    @Test
    void revolvingFullyRepaid_staysActive_becauseCupoIsReusable() {
        // Una línea revolvente pagada por completo NO se liquida: queda disponible con su límite
        // intacto. Cerrarla dejaría a la distribuidora sin línea por haber pagado puntual —y la
        // app, que filtra por ACTIVE, la mandaría al home B2C.
        CreditAccount a = revolvingActive("10000");
        a.applyPayment(new BigDecimal("10000"));
        a.settleIfClear();
        assertThat(a.getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
        assertThat(a.getAvailableCredit()).isEqualByComparingTo(a.getCreditLimit());
    }

    // ── reversal ────────────────────────────────────────────────────────────

    @Test
    void reverseInterestCharge_reducesInterestBucket() {
        CreditAccount a = installmentActive("50000");
        a.applyInterestAccrual(new BigDecimal("1000"));
        a.reverseCharge("ORDINARY_INTEREST", new BigDecimal("400"));
        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("600");
    }

    @Test
    void reversePenaltyCharge_reducesPenaltyBucket_neverNegative() {
        CreditAccount a = installmentActive("50000");
        a.applyPenaltyCharge(new BigDecimal("250"));
        a.reverseCharge("ADMIN_FEE", new BigDecimal("400"));   // more than balance
        assertThat(a.getPenaltyBalance()).isEqualByComparingTo("0");   // clamped, CP-02
    }

    // ── write-off ───────────────────────────────────────────────────────────

    @Test
    void writeOff_zeroesAllBalances_terminal() {
        CreditAccount a = installmentActive("50000");
        a.applyInterestAccrual(new BigDecimal("1000"));
        a.applyPenaltyCharge(new BigDecimal("500"));
        a.executeWriteOff();
        assertThat(a.getTotalDebt()).isEqualByComparingTo("0");
        assertThat(a.getStatus()).isEqualTo(CreditAccountStatus.WRITTEN_OFF);
    }

    @Test
    void writeOff_isIdempotent() {
        CreditAccount a = installmentActive("50000");
        a.executeWriteOff();
        a.executeWriteOff();   // no exception, stays WRITTEN_OFF
        assertThat(a.getStatus()).isEqualTo(CreditAccountStatus.WRITTEN_OFF);
    }

    @Test
    void mutatingWrittenOffAccount_throws() {
        CreditAccount a = installmentActive("50000");
        a.executeWriteOff();
        try {
            a.applyInterestAccrual(new BigDecimal("100"));
            assertThat(false).as("should have thrown").isTrue();
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("terminal");
        }
    }
}
