package com.fintech.notifications;

import com.fintech.notifications.domain.CreditAccountProgress;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreditAccountProgressTest {

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId = UUID.randomUUID();

    @Test
    void tryMarkInstallmentPaid_noCurrentInstallmentLoaded_isNoOp() {
        CreditAccountProgress progress = CreditAccountProgress.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN", 12);

        Optional<Integer> result = progress.tryMarkInstallmentPaid(new BigDecimal("500.00"));

        assertThat(result).isEmpty();
        assertThat(progress.getInstallmentsPaidCount()).isZero();
    }

    @Test
    void tryMarkInstallmentPaid_amountCoversCurrentInstallment_advancesCounter() {
        CreditAccountProgress progress = CreditAccountProgress.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN", 12);
        progress.updateCurrentInstallment(LocalDate.now().plusDays(3), new BigDecimal("500.00"));

        Optional<Integer> result = progress.tryMarkInstallmentPaid(new BigDecimal("500.00"));

        assertThat(result).contains(1);
        assertThat(progress.getInstallmentsPaidCount()).isEqualTo(1);
        assertThat(progress.getCurrentTotalAmount()).isNull(); // cuota vigente consumida
        assertThat(progress.remainingInstallments()).isEqualTo(11);
    }

    @Test
    void tryMarkInstallmentPaid_partialAmount_doesNotCoverInstallment_NT11() {
        CreditAccountProgress progress = CreditAccountProgress.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN", 12);
        progress.updateCurrentInstallment(LocalDate.now().plusDays(3), new BigDecimal("500.00"));

        Optional<Integer> result = progress.tryMarkInstallmentPaid(new BigDecimal("200.00"));

        assertThat(result).isEmpty();
        assertThat(progress.getInstallmentsPaidCount()).isZero();
        assertThat(progress.getCurrentTotalAmount()).isEqualByComparingTo("500.00"); // sigue vigente
    }

    @Test
    void tryMarkInstallmentPaid_amountExceedsInstallment_stillCoversIt() {
        CreditAccountProgress progress = CreditAccountProgress.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN", 12);
        progress.updateCurrentInstallment(LocalDate.now().plusDays(3), new BigDecimal("500.00"));

        Optional<Integer> result = progress.tryMarkInstallmentPaid(new BigDecimal("600.00"));

        assertThat(result).contains(1);
    }

    @Test
    void remainingInstallments_nullWhenTotalUnknown() {
        CreditAccountProgress progress = CreditAccountProgress.init(creditAccountId, obligorPartyId, "SME_LOAN", null);

        assertThat(progress.remainingInstallments()).isNull();
    }
}
