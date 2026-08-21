package com.fintech.payments.application.service;

import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.domain.AccountBalanceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BalanceSnapshotServiceTest {

    @Mock AccountBalanceSnapshotRepository snapshotRepo;

    BalanceSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new BalanceSnapshotService(snapshotRepo);
    }

    @Test
    void initSnapshot_savesNewEntry() {
        UUID accountId = UUID.randomUUID();
        when(snapshotRepo.existsByCreditAccountId(accountId)).thenReturn(false);
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initSnapshot(accountId, UUID.randomUUID(), new BigDecimal("10000"));

        ArgumentCaptor<AccountBalanceSnapshot> cap = ArgumentCaptor.forClass(AccountBalanceSnapshot.class);
        verify(snapshotRepo).save(cap.capture());
        assertThat(cap.getValue().getTotalDebt()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cap.getValue().getCreditLimit()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    void initSnapshot_isIdempotent() {
        UUID accountId = UUID.randomUUID();
        when(snapshotRepo.existsByCreditAccountId(accountId)).thenReturn(true);

        service.initSnapshot(accountId, UUID.randomUUID(), BigDecimal.ZERO);

        verify(snapshotRepo, never()).save(any());
    }

    @Test
    void upsert_updatesExistingSnapshot() {
        UUID accountId = UUID.randomUUID();
        AccountBalanceSnapshot existing = AccountBalanceSnapshot.init(accountId, UUID.randomUUID(), BigDecimal.ZERO);
        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(existing));
        when(snapshotRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(accountId, UUID.randomUUID(),
                new BigDecimal("9000"), new BigDecimal("200"), new BigDecimal("50"),
                new BigDecimal("800"), new BigDecimal("9250"), 5L, "ACTIVE");

        assertThat(existing.getTotalDebt()).isEqualByComparingTo(new BigDecimal("9250"));
        assertThat(existing.getBalanceVersion()).isEqualTo(5L);
        assertThat(existing.getAccountStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void canAcceptPayment_allowsOverpayment_rejectsZeroOrNegative() {
        AccountBalanceSnapshot s = AccountBalanceSnapshot.init(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO);
        s.update(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("500"), 1L, "ACTIVE");

        // Overpayments are allowed — handled at service level via OverpaymentStrategy
        assertThat(s.canAcceptPayment(new BigDecimal("501"))).isTrue();
        assertThat(s.canAcceptPayment(new BigDecimal("500"))).isTrue();
        assertThat(s.canAcceptPayment(new BigDecimal("1"))).isTrue();
        // Non-positive amounts are rejected
        assertThat(s.canAcceptPayment(BigDecimal.ZERO)).isFalse();
        assertThat(s.canAcceptPayment(new BigDecimal("-1"))).isFalse();
    }

    @Test
    void isAccountActive_trueForActiveAndSuspended_falseForTerminal() {
        AccountBalanceSnapshot s = AccountBalanceSnapshot.init(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO);

        s.update(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1L, "ACTIVE");
        assertThat(s.isAccountActive()).isTrue();

        s.update(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1L, "SUSPENDED");
        assertThat(s.isAccountActive()).isTrue();

        s.update(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1L, "WRITTEN_OFF");
        assertThat(s.isAccountActive()).isFalse();

        s.update(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1L, "CLOSED");
        assertThat(s.isAccountActive()).isFalse();
    }
}
