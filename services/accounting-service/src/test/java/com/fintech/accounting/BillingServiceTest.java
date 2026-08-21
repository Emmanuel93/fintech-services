package com.fintech.accounting;

import com.fintech.accounting.application.InvoiceRequest;
import com.fintech.accounting.application.port.out.AccountingEventPublisher;
import com.fintech.accounting.application.port.out.InvoiceableItemRepository;
import com.fintech.accounting.application.service.BillingService;
import com.fintech.accounting.domain.InvoiceableItem;
import com.fintech.accounting.domain.InvoiceableItemStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock InvoiceableItemRepository itemRepository;
    @Mock AccountingEventPublisher eventPublisher;

    BillingService service;
    private final UUID party = UUID.randomUUID();
    private final UUID ca = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BillingService(itemRepository, eventPublisher);
    }

    @Test
    void accrue_isIdempotentBySourceEvent() {
        given(itemRepository.existsBySourceEventId("evt-1")).willReturn(true);

        service.accrue("evt-1", party, ca, "ORDINARY_INTEREST", new BigDecimal("100"), false, "202607");

        then(itemRepository).should(never()).save(any());
    }

    @Test
    void runBilling_consolidatesOneInvoicePerParty_withSubtotalAndIva() {
        InvoiceableItem interest = InvoiceableItem.accrue("e1", party, ca, "ORDINARY_INTEREST", new BigDecimal("100"), false, "202607");
        InvoiceableItem iva      = InvoiceableItem.accrue("e2", party, ca, "IVA", new BigDecimal("16"), true, "202607");
        given(itemRepository.findByStatusAndPeriod(InvoiceableItemStatus.PENDING, "202607"))
                .willReturn(List.of(interest, iva));
        given(itemRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        int invoices = service.runBilling("202607");

        assertThat(invoices).isEqualTo(1);
        ArgumentCaptor<InvoiceRequest> captor = ArgumentCaptor.forClass(InvoiceRequest.class);
        then(eventPublisher).should().publishInvoiceRequested(captor.capture());
        InvoiceRequest req = captor.getValue();
        assertThat(req.obligorPartyId()).isEqualTo(party);
        assertThat(req.subtotal()).isEqualByComparingTo("100");
        assertThat(req.iva()).isEqualByComparingTo("16");
        assertThat(req.total()).isEqualByComparingTo("116");
        assertThat(req.lines()).hasSize(2);
        // ambos ítems marcados BILLED
        assertThat(interest.getStatus()).isEqualTo(InvoiceableItemStatus.BILLED);
        assertThat(iva.getStatus()).isEqualTo(InvoiceableItemStatus.BILLED);
    }
}
