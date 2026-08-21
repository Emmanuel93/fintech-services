package com.fintech.wallet;

import com.fintech.wallet.application.CreatePaymentInstructionCommand;
import com.fintech.wallet.application.WalletProperties;
import com.fintech.wallet.application.port.out.PaymentInstructionRepository;
import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.application.port.out.WalletMovementRepository;
import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.application.service.PaymentInstructionService;
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
class PaymentInstructionServiceTest {

    @Mock WalletViewRepository walletViewRepository;
    @Mock PaymentInstructionRepository instructionRepository;
    @Mock WalletMovementRepository movementRepository;
    @Mock WalletEventPublisher eventPublisher;

    PaymentInstructionService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();
    private WalletView view;

    @BeforeEach
    void setUp() {
        var props = new WalletProperties();
        service = new PaymentInstructionService(
                walletViewRepository, instructionRepository, movementRepository, eventPublisher, props);
        view = WalletView.createFromActivation(creditAccountId, obligorPartyId,
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
        view.applyStatementGenerated(new BigDecimal("2500"), java.time.LocalDate.of(2026, 8, 15));
    }

    @Test
    void create_partialSpei_createsAndPublishes() {
        given(walletViewRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(view));
        given(instructionRepository.existsPendingByAccountAndMethod(creditAccountId, "SPEI")).willReturn(false);
        given(instructionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publishPaymentInstructionCreated(any());

        var cmd = new CreatePaymentInstructionCommand(
                creditAccountId, obligorPartyId,
                PaymentMethod.SPEI, new BigDecimal("5000"), PaymentType.PARTIAL, null);
        var result = service.create(cmd);

        assertThat(result.getPaymentMethod()).isEqualTo("SPEI");
        assertThat(result.getAmount()).isEqualByComparingTo("5000");
        assertThat(result.getStatus()).isEqualTo("PENDING");
        then(instructionRepository).should().save(any(PaymentInstruction.class));
        then(eventPublisher).should().publishPaymentInstructionCreated(any());
    }

    @Test
    void create_minimum_usesMinimumPaymentFromView() {
        given(walletViewRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(view));
        given(instructionRepository.existsPendingByAccountAndMethod(creditAccountId, "SPEI")).willReturn(false);
        given(instructionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publishPaymentInstructionCreated(any());

        var cmd = new CreatePaymentInstructionCommand(
                creditAccountId, obligorPartyId,
                PaymentMethod.SPEI, null, PaymentType.MINIMUM, null);
        var result = service.create(cmd);

        assertThat(result.getAmount()).isEqualByComparingTo("2500"); // from view.minimumPayment
    }

    @Test
    void create_duplicatePending_throwsDuplicateException() {
        given(walletViewRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(view));
        given(instructionRepository.existsPendingByAccountAndMethod(creditAccountId, "SPEI")).willReturn(true);

        var cmd = new CreatePaymentInstructionCommand(
                creditAccountId, obligorPartyId,
                PaymentMethod.SPEI, new BigDecimal("1000"), PaymentType.PARTIAL, null);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(DuplicatePendingInstructionException.class);
        then(instructionRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void create_domiciliacionWithoutClabe_throwsDispositionBlocked() {
        given(walletViewRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(view));

        var cmd = new CreatePaymentInstructionCommand(
                creditAccountId, obligorPartyId,
                PaymentMethod.DOMICILIACION, new BigDecimal("2500"), PaymentType.MINIMUM, null);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(DispositionBlockedException.class);
        then(instructionRepository).shouldHaveNoInteractions();
    }

    @Test
    void create_walletNotFound_throwsNotFoundException() {
        given(walletViewRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.empty());

        var cmd = new CreatePaymentInstructionCommand(
                creditAccountId, obligorPartyId,
                PaymentMethod.SPEI, new BigDecimal("1000"), PaymentType.PARTIAL, null);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(WalletViewNotFoundException.class);
    }
}
