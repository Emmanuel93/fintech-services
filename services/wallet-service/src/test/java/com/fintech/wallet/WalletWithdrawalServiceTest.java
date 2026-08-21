package com.fintech.wallet;

import com.fintech.wallet.application.WithdrawFromWalletCommand;
import com.fintech.wallet.application.port.out.WalletDispatchPort;
import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.application.port.out.WalletMovementRepository;
import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.application.port.out.WalletWithdrawalRepository;
import com.fintech.wallet.application.service.WalletWithdrawalService;
import com.fintech.wallet.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class WalletWithdrawalServiceTest {

    @Mock WalletViewRepository walletViewRepository;
    @Mock WalletWithdrawalRepository withdrawalRepository;
    @Mock WalletMovementRepository movementRepository;
    @Mock WalletDispatchPort dispatchPort;
    @Mock WalletEventPublisher eventPublisher;

    WalletWithdrawalService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();
    private WalletView view;

    @BeforeEach
    void setUp() {
        service = new WalletWithdrawalService(
                walletViewRepository, withdrawalRepository, movementRepository, dispatchPort, eventPublisher);
        view = WalletView.createFromActivation(creditAccountId, obligorPartyId,
                "REVOLVING_CREDIT", BigDecimal.ZERO, new BigDecimal("20000"));
        view.credit(new BigDecimal("5000")); // disposed and sitting in the wallet
    }

    @Test
    void withdraw_sufficientBalance_debitsAtomicallyAndDispatches() {
        given(walletViewRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(view));
        // Débito atómico exitoso: 1 fila afectada.
        given(walletViewRepository.debitWalletBalanceIfEnough(creditAccountId, new BigDecimal("2000")))
                .willReturn(1);
        given(withdrawalRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispatchPort.dispatch(any(), any(), any())).willReturn("WALLET-WD-STUB-ABCD1234");

        var cmd = new WithdrawFromWalletCommand(
                creditAccountId, obligorPartyId, PaymentMethod.SPEI,
                new BigDecimal("2000"), "032180000118359719");
        var result = service.withdraw(cmd);

        then(walletViewRepository).should().debitWalletBalanceIfEnough(creditAccountId, new BigDecimal("2000"));
        then(movementRepository).should().save(any());
        assertThat(result.getStatus()).isEqualTo("SENT");
        assertThat(result.getExternalRef()).isEqualTo("WALLET-WD-STUB-ABCD1234");
        then(eventPublisher).should().publishWithdrawalCompleted(any());
        then(eventPublisher).should().publishWalletSnapshotUpdated(view);
    }

    @Test
    void withdraw_amountExceedsWalletBalance_throwsAndDoesNotDispatch() {
        given(walletViewRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(view));
        // Débito atómico rechazado por saldo insuficiente: 0 filas.
        given(walletViewRepository.debitWalletBalanceIfEnough(creditAccountId, new BigDecimal("10000")))
                .willReturn(0);

        var cmd = new WithdrawFromWalletCommand(
                creditAccountId, obligorPartyId, PaymentMethod.SPEI,
                new BigDecimal("10000"), "032180000118359719");

        assertThatThrownBy(() -> service.withdraw(cmd))
                .isInstanceOf(InsufficientWalletBalanceException.class);

        then(withdrawalRepository).shouldHaveNoInteractions();
        then(movementRepository).shouldHaveNoInteractions();
        then(dispatchPort).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void withdraw_unknownWallet_throwsNotFound() {
        given(walletViewRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.empty());

        var cmd = new WithdrawFromWalletCommand(
                creditAccountId, obligorPartyId, PaymentMethod.SPEI,
                new BigDecimal("1000"), "032180000118359719");

        assertThatThrownBy(() -> service.withdraw(cmd))
                .isInstanceOf(WalletViewNotFoundException.class);
    }
}
