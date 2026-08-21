package com.fintech.accounting;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.port.out.AccountingEventPublisher;
import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.application.service.CommissionPostingService;
import com.fintech.accounting.domain.AccountCodes;
import com.fintech.accounting.domain.JournalEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CommissionPostingServiceTest {

    @Mock AccountBalanceShadowRepository shadowRepository;
    @Mock VoucherPostingService ledger;

    CommissionPostingService service;

    @BeforeEach
    void setUp() {
        service = new CommissionPostingService(shadowRepository, ledger);
    }

    /** Los pares que se le entregan al mayor: lo que este servicio decide es qué cuentas mueve. */
    @SuppressWarnings("unchecked")
    private java.util.List<VoucherPostingService.Pair> capturedPairs() {
        ArgumentCaptor<java.util.List<VoucherPostingService.Pair>> captor =
                ArgumentCaptor.forClass(java.util.List.class);
        then(ledger).should().post(any(VoucherPostingService.VoucherRequest.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void onCommissionAccrued_postsExpenseAgainstPayable() {
        UUID commissionId = UUID.randomUUID();
        UUID ca = UUID.randomUUID();

        service.onCommissionAccrued(commissionId, ca, new BigDecimal("90.00"), java.time.Instant.now());

        var pair = capturedPairs().get(0);
        assertThat(pair.debitAccount()).isEqualTo(AccountCodes.GASTO_COMISIONES);
        assertThat(pair.creditAccount()).isEqualTo(AccountCodes.COMISIONES_POR_PAGAR);
        assertThat(pair.amount()).isEqualByComparingTo("90.00");
    }

    @Test
    void onCommissionReversed_reversesTheAccrual() {

        service.onCommissionReversed(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("90.00"), java.time.Instant.now());

        var pair = capturedPairs().get(0);
        assertThat(pair.debitAccount()).isEqualTo(AccountCodes.COMISIONES_POR_PAGAR);
        assertThat(pair.creditAccount()).isEqualTo(AccountCodes.GASTO_COMISIONES);
    }

    @Test
    void onCommissionLiquidated_settlesPayableAgainstBanks() {

        service.onCommissionLiquidated(UUID.randomUUID(), new BigDecimal("500.00"), java.time.Instant.now());

        var pair = capturedPairs().get(0);
        assertThat(pair.debitAccount()).isEqualTo(AccountCodes.COMISIONES_POR_PAGAR);
        assertThat(pair.creditAccount()).isEqualTo(AccountCodes.BANCOS);
        assertThat(pair.amount()).isEqualByComparingTo("500.00");
    }

    /**
     * Un importe en cero no es una póliza vacía: es que no pasó nada.
     *
     * <p>La idempotencia dejó de probarse aquí porque dejó de ser de este servicio: la garantiza el
     * mayor por {@code sourceEventId}, y ahí es donde tiene su prueba.
     */
    @Test
    void zeroOrNullAmount_isNoOp() {
        service.onCommissionAccrued(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO, java.time.Instant.now());
        service.onCommissionLiquidated(UUID.randomUUID(), null, java.time.Instant.now());

        then(ledger).shouldHaveNoInteractions();
    }
}
