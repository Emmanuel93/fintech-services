package com.fintech.accounting;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.CommissionMoves;
import com.fintech.accounting.application.CommissionMoves.Accrual;
import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.port.out.CommissionFacts;
import com.fintech.accounting.application.port.out.PostingRuleRepository;
import com.fintech.accounting.application.port.out.ProvisionLedgerRepository;
import com.fintech.accounting.application.service.BillingService;
import com.fintech.accounting.application.service.CommissionPostingService;
import com.fintech.accounting.application.service.FeeDeferralService;
import com.fintech.accounting.application.service.PostingService;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountBalanceShadow;
import com.fintech.accounting.domain.AccountCodes;
import com.fintech.accounting.domain.PaymentOrigin;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/**
 * The distributor's commission netted against her statement, in the ledger: the expense is owed to her (5104 → 2120,
 * no IVA, no lot to wait for) and the portfolio's netting settles it against the statement's interest first and its
 * capital after (2120 → 1203 / 1201). A returned payment undoes both.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionNettingPostingTest {

    @Mock AccountBalanceShadowRepository shadowRepository;
    @Mock PostingRuleRepository postingRuleRepository;
    @Mock ProvisionLedgerRepository provisionLedgerRepository;
    @Mock VoucherPostingService ledger;
    @Mock BillingService billingService;
    @Mock FeeDeferralService feeDeferral;
    @Mock CommissionFacts facts;

    PostingService postings;
    CommissionPostingService commissions;
    private final UUID line = UUID.randomUUID();
    private final UUID distributor = UUID.randomUUID();
    private static final Instant MARCH_3 = Instant.parse("2026-03-03T18:00:00Z");
    private static final PaymentOrigin NONE = new PaymentOrigin(null, null);

    @BeforeEach
    void setUp() {
        AccountingProperties properties = new AccountingProperties();
        properties.setZone("America/Mexico_City");
        postings = new PostingService(shadowRepository, postingRuleRepository, provisionLedgerRepository, ledger,
                billingService, properties, feeDeferral);
        commissions = new CommissionPostingService(shadowRepository, ledger, feeDeferral, facts, properties);
        given(feeDeferral.withdrawCost(anyString())).willReturn(Optional.empty());
        AccountBalanceShadow shadow = AccountBalanceShadow.init(line, distributor);
        shadow.learnProduct("DISTRIBUTOR_LINE");
        shadow.applyAndComputeDelta(new BigDecimal("5000"), new BigDecimal("200"), BigDecimal.ZERO, new BigDecimal("5200"), 1L);
        given(shadowRepository.findById(line)).willReturn(Optional.of(shadow));
    }

    @Test
    @DisplayName("el neteo sale de 2120 por la misma partida en que entró el devengo (COM:<beneficiaria>:<producto>)")
    void theNettingLeaves2120ByTheAccrualsItem() {
        netted("COMMISSION_NETTED:pay-8:8", "0", "-180.00");
        ArgumentCaptor<VoucherRequest> voucher = ArgumentCaptor.forClass(VoucherRequest.class);
        then(ledger).should().post(voucher.capture(), any());
        // The close reconciles 2120 by beneficiary and product: a debit without the item left every month open (dist5).
        assertThat(voucher.getValue().clearingRef()).isEqualTo(CommissionMoves.keyOf(distributor, "DISTRIBUTOR_LINE"));
        assertThat(voucher.getValue().productType()).isEqualTo("DISTRIBUTOR_LINE");
    }

    @SuppressWarnings("unchecked")
    private List<Pair> capturedPairs() {
        ArgumentCaptor<List<Pair>> captor = ArgumentCaptor.forClass(List.class);
        then(ledger).should().post(any(VoucherRequest.class), captor.capture());
        return captor.getValue();
    }

    private void netted(String key, String principalDelta, String interestDelta) {
        BigDecimal p = new BigDecimal(principalDelta), i = new BigDecimal(interestDelta);
        postings.onBalanceUpdated(key, line, distributor, new BigDecimal("5000").add(p), new BigDecimal("200").add(i),
                BigDecimal.ZERO, new BigDecimal("5200").add(p).add(i), "COMMISSION_NETTED", 2L, "S_E2E", MARCH_3,
                p.add(i), p, i, BigDecimal.ZERO, BigDecimal.ZERO, null, NONE);
    }

    @Test
    @DisplayName("la comisión neteada paga el interés del estado desde lo que se le debía a la distribuidora: 2120 contra 1203")
    void aNettingOutOfTheInterest() {
        netted("COMMISSION_NETTED:pay-7:7", "0", "-180.00");
        List<Pair> pairs = capturedPairs();
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).debitAccount()).isEqualTo(AccountCodes.COMISIONES_POR_PAGAR);
        assertThat(pairs.get(0).creditAccount()).isEqualTo(AccountCodes.INTERESES_POR_COBRAR);
        assertThat(pairs.get(0).amount()).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("en una quincena tardía del plan la comisión plana rebasa su interés: el resto se aplica a capital que el interés anterior ya cubrió")
    void aNettingThatReachesThePrincipal() {
        netted("COMMISSION_NETTED:pay-11:11", "-75.50", "-20.17");
        List<Pair> pairs = capturedPairs();
        assertThat(pairs).hasSize(2);
        assertThat(pairs).extracting(Pair::creditAccount).containsExactly(AccountCodes.INTERESES_POR_COBRAR, AccountCodes.CARTERA_VIGENTE);
        assertThat(pairs).extracting(Pair::debitAccount).containsOnly(AccountCodes.COMISIONES_POR_PAGAR);
        assertThat(pairs.get(0).amount()).isEqualByComparingTo("20.17");
        assertThat(pairs.get(1).amount()).isEqualByComparingTo("75.50");
    }

    @Test
    @DisplayName("el pago devuelto deshace el neteo: 1203 y 1201 vuelven contra 2120")
    void theUndoIsTheMirror() {
        postings.onBalanceUpdated("COMMISSION_NETTED_REVERSED:COMMISSION_NETTED:pay-7:7", line, distributor,
                new BigDecimal("5075.50"), new BigDecimal("220.17"), BigDecimal.ZERO, new BigDecimal("5295.67"),
                "COMMISSION_NETTED_REVERSED", 3L, "S_E2E", MARCH_3, new BigDecimal("95.67"), new BigDecimal("75.50"),
                new BigDecimal("20.17"), BigDecimal.ZERO, BigDecimal.ZERO, null, NONE);
        List<Pair> pairs = capturedPairs();
        assertThat(pairs).hasSize(2);
        assertThat(pairs).extracting(Pair::debitAccount).containsExactly(AccountCodes.INTERESES_POR_COBRAR, AccountCodes.CARTERA_VIGENTE);
        assertThat(pairs).extracting(Pair::creditAccount).containsOnly(AccountCodes.COMISIONES_POR_PAGAR);
    }

    @Test
    @DisplayName("el devengo de una comisión neteada: gasto contra lo que se le debe, sin IVA y sin hecho que espere un lote")
    void theNettedAccrualHasNoIvaAndWaitsForNoLot() {
        commissions.onCommissionAccrued(new Accrual(UUID.randomUUID(), line, distributor, "DISTRIBUTOR_LINE",
                new BigDecimal("180.00"), BigDecimal.ZERO, "DISTRIBUTOR_INTEREST_SHARE", null, MARCH_3, "NETTED"));
        List<Pair> pairs = capturedPairs();
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).debitAccount()).isEqualTo(AccountCodes.GASTO_COMISIONES);
        assertThat(pairs.get(0).creditAccount()).isEqualTo(AccountCodes.COMISIONES_POR_PAGAR);
        assertThat(pairs.get(0).amount()).isEqualByComparingTo("180.00");
        then(facts).should(never()).record(any());
    }

    @Test
    @DisplayName("una comisión pagada por lote sigue igual: con su IVA pendiente y su hecho para el lote")
    void aPayoutAccrualIsUnchanged() {
        commissions.onCommissionAccrued(new Accrual(UUID.randomUUID(), line, distributor, "DISTRIBUTOR_LINE",
                new BigDecimal("90.00"), new BigDecimal("14.40"), "DISTRIBUTOR_INTEREST_SHARE", null, MARCH_3));
        assertThat(capturedPairs()).hasSize(2);
        then(facts).should().record(any());
    }

    @Test
    @DisplayName("rendimiento: diez mil neteos se asientan en menos de un segundo")
    void tenThousandNettingsInUnderASecond() {
        long start = System.nanoTime();
        for (int n = 0; n < 10_000; n++) {
            netted("COMMISSION_NETTED:pay-" + n + ":" + n, "0", "-180.00");
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        then(ledger).should(times(10_000)).post(any(VoucherRequest.class), any());
        assertThat(ms).as("10,000 nettings took %d ms", ms).isLessThan(1_000);
    }
}
