package com.fintech.accounting;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.CommissionMoves.Accrual;
import com.fintech.accounting.application.CommissionMoves.LotPayout;
import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.port.out.CommissionFacts;
import com.fintech.accounting.application.port.out.CommissionFacts.Fact;
import com.fintech.accounting.application.port.out.CommissionFacts.Kind;
import com.fintech.accounting.application.service.CommissionPostingService;
import com.fintech.accounting.application.service.FeeDeferralService;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountBalanceShadow;
import com.fintech.accounting.domain.AccountCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * The accounting side of commissions: the expense and its IVA owed to the beneficiary (2120), its reversal, a lot's SPEI
 * requested (2120 to the SPEI in flight and to the SAT's withholdings, IVA creditable) and failed. What is decided here
 * is which accounts move and by how much; the ledger guarantees idempotency.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionPostingServiceTest {

    @Mock AccountBalanceShadowRepository shadowRepository;
    @Mock VoucherPostingService ledger;
    @Mock FeeDeferralService deferrals;
    @Mock CommissionFacts facts;

    CommissionPostingService service;
    private final UUID distributor = UUID.randomUUID();
    private static final Instant APRIL_10 = Instant.parse("2026-04-10T18:00:00Z");

    @BeforeEach
    void setUp() {
        AccountingProperties properties = new AccountingProperties();
        properties.setZone("America/Mexico_City");
        service = new CommissionPostingService(shadowRepository, ledger, deferrals, facts, properties);
        given(deferrals.withdrawCost(anyString())).willReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private List<Pair> capturedPairs() {
        ArgumentCaptor<List<Pair>> captor = ArgumentCaptor.forClass(List.class);
        then(ledger).should().post(any(VoucherRequest.class), captor.capture());
        return captor.getValue();
    }

    private VoucherRequest capturedRequest() {
        ArgumentCaptor<VoucherRequest> captor = ArgumentCaptor.forClass(VoucherRequest.class);
        then(ledger).should().post(captor.capture(), any());
        return captor.getValue();
    }

    private Fact capturedFact() {
        ArgumentCaptor<Fact> captor = ArgumentCaptor.forClass(Fact.class);
        then(facts).should().record(captor.capture());
        return captor.getValue();
    }

    private Accrual accrual(String amount, String iva) {
        return new Accrual(UUID.randomUUID(), UUID.randomUUID(), distributor, "DISTRIBUTOR_LINE",
                new BigDecimal(amount), iva == null ? null : new BigDecimal(iva), "DISTRIBUTOR_INTEREST_SHARE", null,
                APRIL_10);
    }

    private LotPayout lot(int attempt) {
        // A natural person's lot: 1,000 + 160 IVA; 100 ISR and 106.67 IVA withheld; 953.33 by SPEI.
        return new LotPayout(UUID.fromString("0f0f0f0f-0000-4000-8000-000000000001"), attempt, distributor,
                "DISTRIBUTOR_LINE", new BigDecimal("1000.00"), new BigDecimal("160.00"), new BigDecimal("100.00"),
                new BigDecimal("106.67"), new BigDecimal("1160.00"), new BigDecimal("953.33"),
                Instant.parse("2026-05-05T17:00:00Z"));
    }

    @Test
    @DisplayName("an accrual: the expense and its pending IVA, owed to the beneficiary for the product")
    void accrual() {
        service.onCommissionAccrued(accrual("90.00", "14.40"));

        assertThat(capturedPairs()).extracting(Pair::debitAccount, Pair::creditAccount, Pair::amount).containsExactly(
                tuple(AccountCodes.GASTO_COMISIONES, AccountCodes.COMISIONES_POR_PAGAR, new BigDecimal("90.00")),
                tuple(AccountCodes.IVA_PENDIENTE_ACREDITAR, AccountCodes.COMISIONES_POR_PAGAR, new BigDecimal("14.40")));
        assertThat(capturedRequest().clearingRef()).isEqualTo("COM:" + distributor + ":DISTRIBUTOR_LINE");
        Fact f = capturedFact();
        assertThat(f.kind()).isEqualTo(Kind.ACCRUED);
        assertThat(f.gross()).isEqualByComparingTo("104.40");
        assertThat(f.businessDate()).isEqualTo(LocalDate.of(2026, 4, 10));
    }

    @Test
    @DisplayName("a reversal mirrors the accrual: 2120 down by the gross, the expense and the pending IVA back")
    void reversal() {
        Accrual a = accrual("90.00", "14.40");
        service.onCommissionReversed(new Accrual(UUID.randomUUID(), a.creditAccountId(), distributor, "DISTRIBUTOR_LINE",
                new BigDecimal("90.00"), new BigDecimal("14.40"), null, a.commissionId(), APRIL_10));

        assertThat(capturedPairs()).extracting(Pair::debitAccount, Pair::creditAccount).containsExactly(
                tuple(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.GASTO_COMISIONES),
                tuple(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.IVA_PENDIENTE_ACREDITAR));
        assertThat(capturedFact().gross()).isEqualByComparingTo("-104.40");
    }

    @Test
    @DisplayName("a lot's SPEI requested: the gross leaves 2120 for the net in flight (2103) and the SAT's withholdings; "
            + "the IVA paid becomes creditable")
    void lotPayoutRequested() {
        service.onLotPayoutRequested(lot(1));

        assertThat(capturedPairs()).extracting(Pair::debitAccount, Pair::creditAccount, Pair::amount).containsExactly(
                tuple(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.SPEI_POR_LIQUIDAR, new BigDecimal("953.33")),
                tuple(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.ISR_RETENIDO_POR_ENTERAR, new BigDecimal("100.00")),
                tuple(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.IVA_RETENIDO_POR_ENTERAR, new BigDecimal("106.67")),
                tuple(AccountCodes.IVA_ACREDITABLE_PAGADO, AccountCodes.IVA_PENDIENTE_ACREDITAR, new BigDecimal("53.33")));
        VoucherRequest req = capturedRequest();
        assertThat(req.sourceEventId()).isEqualTo("LOTE:0f0f0f0f-0000-4000-8000-000000000001:1");
        assertThat(req.clearingRef()).isEqualTo("LOTE:0f0f0f0f-0000-4000-8000-000000000001");
        assertThat(req.creditAccountId()).isNull();
        Fact f = capturedFact();
        assertThat(f.kind()).isEqualTo(Kind.LOT_SENT);
        assertThat(f.gross()).isEqualByComparingTo("-1160.00");
        assertThat(f.attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the debits and credits of a lot's SPEI balance: the gross out of 2120 is the net, the withholdings and "
            + "nothing else")
    void lotPayoutBalances() {
        service.onLotPayoutRequested(lot(1));

        BigDecimal outOf2120 = capturedPairs().stream().filter(p -> p.debitAccount().equals(AccountCodes.COMISIONES_POR_PAGAR))
                .map(Pair::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(outOf2120).isEqualByComparingTo("1160.00");
    }

    @Test
    @DisplayName("a failed SPEI undoes its attempt: the gross is owed again, the IVA pending again")
    void lotPayoutFailed() {
        given(ledger.isPosted("LOTE:0f0f0f0f-0000-4000-8000-000000000001:1")).willReturn(true);

        service.onLotPayoutFailed(lot(1));

        assertThat(capturedPairs()).extracting(Pair::debitAccount, Pair::creditAccount).containsExactly(
                tuple(AccountCodes.SPEI_POR_LIQUIDAR, AccountCodes.COMISIONES_POR_PAGAR),
                tuple(AccountCodes.ISR_RETENIDO_POR_ENTERAR, AccountCodes.COMISIONES_POR_PAGAR),
                tuple(AccountCodes.IVA_RETENIDO_POR_ENTERAR, AccountCodes.COMISIONES_POR_PAGAR),
                tuple(AccountCodes.IVA_PENDIENTE_ACREDITAR, AccountCodes.IVA_ACREDITABLE_PAGADO));
        assertThat(capturedRequest().sourceEventId()).isEqualTo("LOTE:0f0f0f0f-0000-4000-8000-000000000001:1:failed");
        assertThat(capturedFact().gross()).isEqualByComparingTo("1160.00");
    }

    @Test
    @DisplayName("the failure of an attempt never posted undoes nothing")
    void failureOfAnUnpostedAttempt() {
        given(ledger.isPosted(anyString())).willReturn(false);

        service.onLotPayoutFailed(lot(2));

        then(ledger).should(never()).post(any(), any());
        then(facts).should(never()).record(any());
    }

    @Test
    @DisplayName("a zero or missing amount is not an empty voucher: nothing happened")
    void zeroIsNoOp() {
        service.onCommissionAccrued(accrual("0.00", "0.00"));
        service.onCommissionReversed(new Accrual(UUID.randomUUID(), null, distributor, null, null, null, null, null, APRIL_10));

        then(ledger).should(never()).post(any(), any());
    }

    @Test
    @DisplayName("an origination commission is a cost of the loan, deferred over its life")
    void anOriginationCommission_isACostOfTheLoan_deferredOverItsLife() {
        UUID ca = UUID.randomUUID();
        UUID commissionId = UUID.randomUUID();
        AccountBalanceShadow shadow = AccountBalanceShadow.init(ca, UUID.randomUUID());
        shadow.learnTerm(12);
        given(shadowRepository.findById(ca)).willReturn(Optional.of(shadow));

        service.onCommissionAccrued(new Accrual(commissionId, ca, distributor, "PERSONAL_LOAN", new BigDecimal("1200.00"),
                null, "ORIGINATION_FEE", null, Instant.parse("2026-10-15T18:00:00Z")));

        assertThat(capturedPairs()).singleElement().satisfies(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.COSTOS_ORIGINACION);
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.COMISIONES_POR_PAGAR);
        });
        then(deferrals).should().deferCost("COMMISSION:" + commissionId, ca, shadow.getObligorPartyId(), null,
                new BigDecimal("1200.00"), 12, "202610");
    }

    @Test
    @DisplayName("reversing an origination commission takes back the deferred part and the amortized one")
    void reversingAnOriginationCommission() {
        UUID original = UUID.randomUUID();
        given(deferrals.withdrawCost("COMMISSION:" + original)).willReturn(Optional.of(
                new FeeDeferralService.Withdrawn(new BigDecimal("1000.00"), new BigDecimal("200.00"))));

        service.onCommissionReversed(new Accrual(UUID.randomUUID(), null, distributor, "PERSONAL_LOAN",
                new BigDecimal("1200.00"), null, null, original, APRIL_10));

        assertThat(capturedPairs()).extracting(Pair::creditAccount)
                .containsExactly(AccountCodes.COSTOS_ORIGINACION, AccountCodes.INGRESOS_TASA_EFECTIVA);
    }
}
