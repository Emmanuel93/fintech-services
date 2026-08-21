package com.fintech.accounting;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.port.out.AccountingEventPublisher;
import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.application.port.out.ProvisionLedgerRepository;
import com.fintech.accounting.application.service.ProvisionPostingService;
import com.fintech.accounting.domain.JournalEntry;
import com.fintech.accounting.domain.ProvisionLedgerEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ProvisionPostingServiceTest {

    @Mock ProvisionLedgerRepository provisionLedgerRepository;
    @Mock AccountBalanceShadowRepository shadowRepository;
    @Mock VoucherPostingService ledger;

    ProvisionPostingService service;
    private final UUID ca = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProvisionPostingService(provisionLedgerRepository, shadowRepository, ledger);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<VoucherPostingService.Pair> capturedPairs() {
        ArgumentCaptor<java.util.List<VoucherPostingService.Pair>> captor =
                ArgumentCaptor.forClass(java.util.List.class);
        then(ledger).should().post(any(VoucherPostingService.VoucherRequest.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void deterioration_booksExpenseForTheDelta() {
        given(provisionLedgerRepository.findById(ca)).willReturn(Optional.empty()); // nada asentado aún

        service.onRiskAssessment(ca, UUID.randomUUID(), new BigDecimal("150"), Instant.now());

        var pair = capturedPairs().get(0);
        assertThat(pair.debitAccount()).isEqualTo("5101"); // gasto estimación
        assertThat(pair.creditAccount()).isEqualTo("1290");
        assertThat(pair.amount()).isEqualByComparingTo("150");
    }

    @Test
    void cure_reversesTheDelta() {
        ProvisionLedgerEntry provision = ProvisionLedgerEntry.init(ca);
        provision.book(new BigDecimal("150")); // ya provisionado 150
        given(provisionLedgerRepository.findById(ca)).willReturn(Optional.of(provision));

        service.onRiskAssessment(ca, UUID.randomUUID(), new BigDecimal("50"), Instant.now()); // baja a 50 → delta -100

        var pair = capturedPairs().get(0);
        assertThat(pair.debitAccount()).isEqualTo("1290"); // reversión
        assertThat(pair.creditAccount()).isEqualTo("5101");
        assertThat(pair.amount()).isEqualByComparingTo("100");
    }

    @Test
    void noChange_postsNothing() {
        ProvisionLedgerEntry provision = ProvisionLedgerEntry.init(ca);
        provision.book(new BigDecimal("150"));
        given(provisionLedgerRepository.findById(ca)).willReturn(Optional.of(provision));

        service.onRiskAssessment(ca, UUID.randomUUID(), new BigDecimal("150"), Instant.now());

        then(ledger).shouldHaveNoInteractions();
    }
}
