package com.fintech.accounting;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.port.out.InvoiceableItemRepository;
import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.application.service.CancellationPostingService;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountCodes;
import com.fintech.accounting.domain.InvoiceableItem;
import com.fintech.accounting.domain.InvoiceableItemStatus;
import com.fintech.accounting.domain.JournalEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * A credit cancelled because its disbursement never happened (D63), in accounting: the authorized line booked in memo
 * accounts at activation is reversed once, and the fee and IVA waiting to be invoiced never reach a CFDI.
 */
class CancellationPostingServiceTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID OBLIGOR = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-03-02T16:05:00Z");

    private JournalEntryRepository entries;
    private InvoiceableItemRepository items;
    private VoucherPostingService ledger;
    private CancellationPostingService cancellations;

    @BeforeEach
    void setUp() {
        entries = mock(JournalEntryRepository.class);
        AccountBalanceShadowRepository shadows = mock(AccountBalanceShadowRepository.class);
        given(shadows.findById(any())).willReturn(Optional.empty());
        items = mock(InvoiceableItemRepository.class);
        ledger = mock(VoucherPostingService.class);
        cancellations = new CancellationPostingService(entries, shadows, items, ledger);
    }

    private static JournalEntry entry(String trigger, String debit, String credit, String amount) {
        return JournalEntry.post("evt-" + UUID.randomUUID(), trigger, ACCOUNT, OBLIGOR, debit, credit,
                new BigDecimal(amount), "MXN", trigger, UUID.randomUUID(), "SUC-001", "202603", AT, (short) 1);
    }

    @Test
    @DisplayName("la línea autorizada se reversa en cuentas de orden y la comisión pendiente no llega a facturarse")
    void memoReversedAndFeeKeptOutOfTheInvoices() {
        given(entries.findByCreditAccountId(ACCOUNT)).willReturn(List.of(
                entry("ACCOUNT_ACTIVATED", AccountCodes.LINEAS_AUTORIZADAS, AccountCodes.LINEAS_POR_DISPONER, "50000"),
                entry("DISPOSITION_AT_ORIGINATION", AccountCodes.CARTERA_VIGENTE, AccountCodes.SPEI_POR_LIQUIDAR, "50000")));
        InvoiceableItem fee = InvoiceableItem.accrue("fee-1", OBLIGOR, ACCOUNT, "OPENING_FEE", new BigDecimal("1500"), false, "202603");
        InvoiceableItem iva = InvoiceableItem.accrue("iva-1", OBLIGOR, ACCOUNT, "IVA", new BigDecimal("240"), true, "202603");
        given(items.findByCreditAccountIdAndStatus(ACCOUNT, InvoiceableItemStatus.PENDING)).willReturn(List.of(fee, iva));
        given(items.findByCreditAccountIdAndStatus(ACCOUNT, InvoiceableItemStatus.BILLED)).willReturn(List.of());

        BigDecimal reversed = cancellations.onCreditAccountCancelled(ACCOUNT, OBLIGOR, AT);

        assertThat(reversed).isEqualByComparingTo("50000");
        ArgumentCaptor<VoucherRequest> voucher = ArgumentCaptor.forClass(VoucherRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Pair>> lines = ArgumentCaptor.forClass(List.class);
        then(ledger).should().post(voucher.capture(), lines.capture());
        assertThat(voucher.getValue().sourceEventId()).isEqualTo("ACCOUNT_CANCELLED:" + ACCOUNT);
        assertThat(lines.getValue()).singleElement().satisfies(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.LINEAS_POR_DISPONER);
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.LINEAS_AUTORIZADAS);
            assertThat(p.amount()).isEqualByComparingTo("50000");
        });
        assertThat(List.of(fee, iva)).allMatch(i -> i.getStatus() == InvoiceableItemStatus.CANCELLED);
    }

    @Test
    @DisplayName("repetido, no reversa dos veces")
    void redeliveryDoesNothing() {
        given(entries.findByCreditAccountId(ACCOUNT)).willReturn(List.of(
                entry("ACCOUNT_ACTIVATED", AccountCodes.LINEAS_AUTORIZADAS, AccountCodes.LINEAS_POR_DISPONER, "50000"),
                entry("ACCOUNT_CANCELLED", AccountCodes.LINEAS_POR_DISPONER, AccountCodes.LINEAS_AUTORIZADAS, "50000")));
        given(items.findByCreditAccountIdAndStatus(any(), any())).willReturn(List.of());

        assertThat(cancellations.onCreditAccountCancelled(ACCOUNT, OBLIGOR, AT)).isEqualByComparingTo("0");
        then(ledger).should(never()).post(any(), any());
    }

    @Test
    @DisplayName("rendimiento: un crédito con diez mil pólizas y mil partidas pendientes se compensa en menos de un segundo")
    void performance() {
        List<JournalEntry> many = new ArrayList<>();
        many.add(entry("ACCOUNT_ACTIVATED", AccountCodes.LINEAS_AUTORIZADAS, AccountCodes.LINEAS_POR_DISPONER, "50000"));
        for (int i = 0; i < 10_000; i++) {
            many.add(entry("ORDINARY_INTEREST", "1203", "4101", "13.33"));
        }
        List<InvoiceableItem> pending = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            pending.add(InvoiceableItem.accrue("int-" + i, OBLIGOR, ACCOUNT, "ORDINARY_INTEREST", new BigDecimal("13.33"),
                    false, "202603"));
        }
        given(entries.findByCreditAccountId(ACCOUNT)).willReturn(many);
        given(items.findByCreditAccountIdAndStatus(ACCOUNT, InvoiceableItemStatus.PENDING)).willReturn(pending);
        given(items.findByCreditAccountIdAndStatus(ACCOUNT, InvoiceableItemStatus.BILLED)).willReturn(List.of());

        long started = System.nanoTime();
        cancellations.onCreditAccountCancelled(ACCOUNT, OBLIGOR, AT);
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(pending).allMatch(i -> i.getStatus() == InvoiceableItemStatus.CANCELLED);
        assertThat(ms).as("10,000 pólizas y 1,000 partidas en %d ms", ms).isLessThan(1_000);
    }
}
