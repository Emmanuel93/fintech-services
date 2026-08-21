package com.fintech.wallet;

import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.application.port.out.WalletMovementRepository;
import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.application.service.WalletProjectionService;
import com.fintech.wallet.domain.WalletView;
import com.fintech.wallet.domain.WalletViewNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class WalletProjectionServiceTest {

    @Mock WalletViewRepository walletViewRepository;
    @Mock WalletMovementRepository movementRepository;
    @Mock WalletEventPublisher eventPublisher;

    WalletProjectionService service;

    @BeforeEach
    void setUp() {
        service = new WalletProjectionService(walletViewRepository, movementRepository, eventPublisher);
    }

    // ── getByCreditAccountId ─────────────────────────────────────────────────

    @Test
    void getBy_existing_returnsView() {
        var accountId = UUID.randomUUID();
        var view = WalletView.createFromActivation(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
        given(walletViewRepository.findByCreditAccountId(accountId)).willReturn(Optional.of(view));

        var result = service.getByCreditAccountId(accountId);

        assertThat(result.getCreditAccountId()).isEqualTo(accountId);
    }

    @Test
    void getBy_notFound_throwsNotFoundException() {
        var accountId = UUID.randomUUID();
        given(walletViewRepository.findByCreditAccountId(accountId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByCreditAccountId(accountId))
                .isInstanceOf(WalletViewNotFoundException.class);
    }

    // ── onCreditAccountActivated ─────────────────────────────────────────────

    @Test
    void onActivated_newAccount_createsViewAndPublishesSnapshot() {
        var accountId = UUID.randomUUID();
        given(walletViewRepository.findByCreditAccountId(accountId)).willReturn(Optional.empty());
        given(walletViewRepository.save(any(WalletView.class))).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publishWalletSnapshotUpdated(any());

        service.onCreditAccountActivated(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", new BigDecimal("50000"), null);

        then(walletViewRepository).should().save(any(WalletView.class));
        then(eventPublisher).should().publishWalletSnapshotUpdated(any());
    }

    @Test
    void onActivated_duplicate_skipsCreationAndDoesNotPublish() {
        var accountId = UUID.randomUUID();
        var existing = WalletView.createFromActivation(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", BigDecimal.TEN, null);
        given(walletViewRepository.findByCreditAccountId(accountId)).willReturn(Optional.of(existing));

        service.onCreditAccountActivated(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", new BigDecimal("50000"), null);

        then(walletViewRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    // ── onBalanceUpdated ─────────────────────────────────────────────────────

    @Test
    void onBalanceUpdated_existing_syncesBalancesAtomicallyAndPublishesSnapshot() {
        var accountId = UUID.randomUUID();
        var view = WalletView.createFromActivation(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
        // Actualización atómica por columna (no toca walletBalance): 1 fila afectada.
        given(walletViewRepository.updateBalanceColumns(eq(accountId), any(), any(), any(),
                any(), any(), eq("ACTIVE"), eq(1L))).willReturn(1);
        given(walletViewRepository.findByCreditAccountId(accountId)).willReturn(Optional.of(view));
        willDoNothing().given(eventPublisher).publishWalletSnapshotUpdated(any());

        service.onBalanceUpdated(accountId, UUID.randomUUID(),
                new BigDecimal("48000"), new BigDecimal("500"), BigDecimal.ZERO,
                null, new BigDecimal("48500"), "ACTIVE", 1L);

        then(walletViewRepository).should().updateBalanceColumns(eq(accountId),
                eq(new BigDecimal("48000")), any(), any(), any(),
                eq(new BigDecimal("48500")), eq("ACTIVE"), eq(1L));
        then(walletViewRepository).should(never()).save(any());
        then(eventPublisher).should().publishWalletSnapshotUpdated(view);
    }

    // ── onInstallmentDue ─────────────────────────────────────────────────────

    @Test
    void onInstallmentDue_existing_updatesInstallmentFields() {
        var accountId = UUID.randomUUID();
        var view = WalletView.createFromActivation(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
        given(walletViewRepository.findByCreditAccountId(accountId)).willReturn(Optional.of(view));
        given(walletViewRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publishWalletSnapshotUpdated(any());

        var dueDate = LocalDate.of(2026, 8, 15);
        service.onInstallmentDue(accountId, new BigDecimal("4500"), dueDate);

        assertThat(view.getNextInstallmentAmount()).isEqualByComparingTo("4500");
        assertThat(view.getPaymentDueDate()).isEqualTo(dueDate);
        then(eventPublisher).should().publishWalletSnapshotUpdated(view);
    }

    // ── onDispositionCompleted ────────────────────────────────────────────────

    @Test
    void onDispositionCompleted_selfUse_creditsWalletBalanceAtomicallyAndRecordsMovement() {
        var accountId = UUID.randomUUID();
        var view = WalletView.createFromActivation(accountId, UUID.randomUUID(),
                "REVOLVING_CREDIT", BigDecimal.ZERO, new BigDecimal("20000"));
        given(walletViewRepository.creditWalletBalance(accountId, new BigDecimal("5000"))).willReturn(1);
        given(walletViewRepository.findByCreditAccountId(accountId)).willReturn(Optional.of(view));

        service.onDispositionCompleted(accountId, new BigDecimal("5000"), "SELF_USE");

        then(walletViewRepository).should().creditWalletBalance(accountId, new BigDecimal("5000"));
        then(movementRepository).should().save(any());
        then(eventPublisher).should().publishWalletSnapshotUpdated(view);
    }

    @Test
    void onDispositionCompleted_thirdPartyCredit_doesNotCreditWalletBalance() {
        var accountId = UUID.randomUUID();

        service.onDispositionCompleted(accountId, new BigDecimal("5000"), "THIRD_PARTY_CREDIT");

        then(walletViewRepository).should(never()).creditWalletBalance(any(), any());
        then(movementRepository).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void onDispositionCompleted_unknownAccount_isIgnored() {
        var accountId = UUID.randomUUID();
        given(walletViewRepository.creditWalletBalance(accountId, new BigDecimal("5000"))).willReturn(0);

        service.onDispositionCompleted(accountId, new BigDecimal("5000"), "SELF_USE");

        then(movementRepository).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    // ── listByPartyId ─────────────────────────────────────────────────────────

    @Test
    void listByPartyId_returnsAllInstrumentsForParty() {
        var partyId = UUID.randomUUID();
        var view1 = WalletView.createFromActivation(UUID.randomUUID(), partyId,
                "REVOLVING_CREDIT", BigDecimal.ZERO, new BigDecimal("20000"));
        var view2 = WalletView.createFromActivation(UUID.randomUUID(), partyId,
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
        given(walletViewRepository.findByObligorPartyId(partyId)).willReturn(java.util.List.of(view1, view2));

        var result = service.listByPartyId(partyId);

        assertThat(result).hasSize(2);
    }
}
