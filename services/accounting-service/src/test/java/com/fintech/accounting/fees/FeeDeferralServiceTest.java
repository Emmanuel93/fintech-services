package com.fintech.accounting.fees;

import com.fintech.accounting.application.port.out.DeferredFeeRepository;
import com.fintech.accounting.application.service.FeeDeferralService;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountCodes;
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
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FeeDeferralServiceTest {

    /** A repository in memory: what the service recognized is what the next month starts from. */
    static class Memoria implements DeferredFeeRepository {
        final List<DeferredFee> filas = new ArrayList<>();
        public boolean register(DeferredFee f) {
            if (filas.stream().anyMatch(x -> x.sourceEventId().equals(f.sourceEventId()))) return false;
            return filas.add(f);
        }
        public List<DeferredFee> active() { return filas.stream().filter(f -> f.pending().signum() > 0).toList(); }
        public List<DeferredFee> activeOf(UUID a) { return active().stream().filter(f -> f.creditAccountId().equals(a)).toList(); }
        public void recognize(UUID id, BigDecimal so) {
            filas.replaceAll(f -> f.id().equals(id) ? new DeferredFee(f.id(), f.sourceEventId(), f.creditAccountId(),
                    f.obligorPartyId(), f.orgUnitCode(), f.total(), so, f.months(), f.firstPeriod(), f.kind()) : f);
        }
        BigDecimal recognized() { return filas.get(0).recognized(); }
        public void reduceTotal(UUID id, BigDecimal a) {
            filas.replaceAll(f -> f.id().equals(id) ? new DeferredFee(f.id(), f.sourceEventId(), f.creditAccountId(),
                    f.obligorPartyId(), f.orgUnitCode(), f.total().subtract(a), f.recognized(), f.months(), f.firstPeriod(),
                    f.kind()) : f);
        }
        public Optional<DeferredFee> findBySource(String source) {
            return filas.stream().filter(f -> f.sourceEventId().equals(source)).findFirst();
        }
    }

    final Memoria fees = new Memoria();
    final VoucherPostingService ledger = mock(VoucherPostingService.class);
    final FeeDeferralService service = new FeeDeferralService(fees, ledger);
    final UUID cuenta = UUID.randomUUID();

    @Test
    @DisplayName("el plazo en meses sale de los períodos y la frecuencia del producto")
    void termInMonths() {
        assertThat(FeeDeferralService.termMonths(12, "MONTHLY")).isEqualTo(12);
        assertThat(FeeDeferralService.termMonths(12, "BIWEEKLY")).isEqualTo(6);
        // Twelve calendar fortnights are six months; twenty-four are a year.
        assertThat(FeeDeferralService.termMonths(12, "SEMI_MONTHLY")).isEqualTo(6);
        assertThat(FeeDeferralService.termMonths(24, "SEMI_MONTHLY")).isEqualTo(12);
        assertThat(FeeDeferralService.termMonths(16, "WEEKLY")).isEqualTo(4);
        assertThat(FeeDeferralService.termMonths(null, "MONTHLY")).isNull();
    }

    @Test
    @DisplayName("cada cierre reconoce como interés (tasa efectiva, 4106) la parte de los meses transcurridos; cerrar dos veces no reconoce dos veces")
    void recognizesMonthByMonth() {
        service.defer("fee-1", cuenta, null, "S_CUL", new BigDecimal("1000.00"), 3, "202603");

        service.amortize("202603", Instant.now());
        assertThat(fees.recognized()).isEqualByComparingTo("333.33");
        service.amortize("202603", Instant.now());
        assertThat(fees.recognized()).isEqualByComparingTo("333.33");
        service.amortize("202604", Instant.now());
        assertThat(fees.recognized()).isEqualByComparingTo("666.67");
        service.amortize("202605", Instant.now());
        service.amortize("202606", Instant.now());
        assertThat(fees.recognized()).isEqualByComparingTo("1000.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Pair>> pares = ArgumentCaptor.forClass(List.class);
        verify(ledger, atLeast(3)).post(any(VoucherRequest.class), pares.capture());
        assertThat(pares.getAllValues()).allSatisfy(l -> {
            assertThat(l.get(0).debitAccount()).isEqualTo(AccountCodes.COMISIONES_POR_DEVENGAR);
            assertThat(l.get(0).creditAccount()).isEqualTo(AccountCodes.INGRESOS_TASA_EFECTIVA);
        });
        BigDecimal total = pares.getAllValues().stream().map(l -> l.get(0).amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("un crédito que termina antes reconoce de una vez lo que quedaba")
    void settledLoanRecognizesTheRest() {
        service.defer("fee-2", cuenta, null, "S_CUL", new BigDecimal("1200.00"), 12, "202603");
        service.amortize("202603", Instant.now());

        service.recognizeRemainder(cuenta, "liquidarse el crédito", Instant.now());

        assertThat(fees.recognized()).isEqualByComparingTo("1200.00");
        assertThat(fees.active()).isEmpty();
    }

    @Test
    @DisplayName("un cargo reentregado no difiere la comisión dos veces")
    void redeliveredChargeDefersOnce() {
        service.defer("fee-3", cuenta, null, "S_CUL", new BigDecimal("500.00"), 6, "202603");
        service.defer("fee-3", cuenta, null, "S_CUL", new BigDecimal("500.00"), 6, "202603");
        assertThat(fees.filas).hasSize(1);
    }

    @Test
    @DisplayName("revertir una comisión a medio devengar saca de 2150 sólo lo que sigue diferido")
    void reversingAPartlyDeferredFee() {
        service.defer("fee-4", cuenta, null, "S_CUL", new BigDecimal("1200.00"), 12, "202603");
        service.amortize("202604", Instant.now());   // two months recognized: 200

        BigDecimal sacadoDe2150 = service.withdraw(cuenta, new BigDecimal("1200.00"));

        assertThat(sacadoDe2150).isEqualByComparingTo("1000.00");
        assertThat(fees.active()).isEmpty();
    }

    @Test
    @DisplayName("un costo de originación se amortiza contra el interés de la tasa efectiva (4106 contra 1205)")
    void anOriginationCostLowersInterestRevenue() {
        service.deferCost("COMMISSION:c-1", cuenta, null, "S_CUL", new BigDecimal("600.00"), 6, "202603");

        service.amortize("202604", Instant.now());

        assertThat(fees.recognized()).isEqualByComparingTo("200.00");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Pair>> pares = ArgumentCaptor.forClass(List.class);
        verify(ledger).post(any(VoucherRequest.class), pares.capture());
        Pair par = pares.getValue().get(0);
        assertThat(par.debitAccount()).isEqualTo(AccountCodes.INGRESOS_TASA_EFECTIVA);
        assertThat(par.creditAccount()).isEqualTo(AccountCodes.COSTOS_ORIGINACION);
        assertThat(par.amount()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("revertir un costo de originación separa lo que sigue diferido de lo ya amortizado; una comisión de apertura no se toca")
    void withdrawingAnOriginationCost() {
        service.defer("fee-5", cuenta, null, "S_CUL", new BigDecimal("300.00"), 3, "202603");
        service.deferCost("COMMISSION:c-2", cuenta, null, "S_CUL", new BigDecimal("600.00"), 6, "202603");
        service.amortize("202603", Instant.now());   // cost: 100 recognized

        var withdrawn = service.withdrawCost("COMMISSION:c-2").orElseThrow();

        assertThat(withdrawn.deferred()).isEqualByComparingTo("500.00");
        assertThat(withdrawn.recognized()).isEqualByComparingTo("100.00");
        assertThat(service.withdrawCost("fee-5")).isEmpty();
        assertThat(service.withdraw(cuenta, new BigDecimal("300.00"))).isEqualByComparingTo("200.00");
    }
}
