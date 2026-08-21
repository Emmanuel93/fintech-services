package com.fintech.accounting;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.port.out.*;
import com.fintech.accounting.application.service.BillingService;
import com.fintech.accounting.application.service.PostingService;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

/**
 * Las pruebas se hacen contra los <b>pares</b> que se le entregan al mayor, no contra las filas que
 * llegan a la base. Es la frontera correcta: lo que este servicio decide es qué cuentas se mueven y
 * por cuánto; cómo se numera la póliza y en qué período cae es del mayor y se prueba allá.
 */
@ExtendWith(MockitoExtension.class)
class PostingServiceTest {

    @Mock AccountBalanceShadowRepository shadowRepository;
    @Mock PostingRuleRepository postingRuleRepository;
    @Mock ProvisionLedgerRepository provisionLedgerRepository;
    @Mock VoucherPostingService ledger;
    @Mock BillingService billingService;

    PostingService service;
    final AccountingProperties properties = new AccountingProperties();

    private final UUID ca = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PostingService(shadowRepository, postingRuleRepository,
                provisionLedgerRepository, ledger, billingService, properties);
    }

    @SuppressWarnings("unchecked")
    private List<Pair> capturedPairs() {
        ArgumentCaptor<List<Pair>> captor = ArgumentCaptor.forClass(List.class);
        then(ledger).should().post(any(VoucherRequest.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void charge_postsEntry_andAccruesIncome() {
        // Shadow presente y en cero: es lo que deja `onAccountActivated` en un crédito recién nacido.
        given(shadowRepository.findById(ca)).willReturn(Optional.of(AccountBalanceShadow.init(ca, party)));
        PostingRule rule = mock(PostingRule.class);
        given(rule.getDebitAccount()).willReturn("1203");
        given(rule.getCreditAccount()).willReturn("4101");
        given(postingRuleRepository.findByTriggerEvent("CHARGE_ORDINARY_INTEREST")).willReturn(Optional.of(rule));

        service.onBalanceUpdated("evt-1", ca, party, BigDecimal.ZERO, new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("100"), "CHARGE_ORDINARY_INTEREST", 1L,
                "S_CUL", Instant.parse("2026-08-13T06:00:00Z"));

        List<Pair> pairs = capturedPairs();
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).debitAccount()).isEqualTo("1203");
        assertThat(pairs.get(0).creditAccount()).isEqualTo("4101");
        assertThat(pairs.get(0).amount()).isEqualByComparingTo("100");
        then(billingService).should().accrue(eq("evt-1"), eq(party), eq(ca), eq("ORDINARY_INTEREST"),
                eq(new BigDecimal("100")), eq(false), eq("202608"));
    }

    /**
     * GL-02, el defecto que este trabajo corrige: el pago se aplica a lo que de verdad liquidó.
     *
     * <p>La regla anterior abonaba el importe completo a capital, incluida la parte que liquida
     * interés devengado. El efecto acumulado era que 1203 crecía indefinidamente y 1201 se
     * subestimaba, aunque los clientes pagaran sus intereses.
     */
    @Test
    void payment_splitsBetweenPrincipalInterestAndPenalty() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(ca, party);
        shadow.applyAndComputeDelta(new BigDecimal("1000"), new BigDecimal("120"),
                new BigDecimal("30"), new BigDecimal("1150"), 1L);
        given(shadowRepository.findById(ca)).willReturn(Optional.of(shadow));

        // Paga 200 de capital, los 120 de interés y los 30 de moratorios.
        service.onBalanceUpdated("pay-1", ca, party, new BigDecimal("800"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("800"), "PAYMENT_APPLIED", 2L,
                "S_CUL", Instant.now());

        List<Pair> pairs = capturedPairs();
        assertThat(pairs).hasSize(3);
        assertThat(pairs).allSatisfy(p -> assertThat(p.debitAccount()).isEqualTo(AccountCodes.BANCOS));
        assertThat(pairs).anySatisfy(p -> {
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.CARTERA_VIGENTE);
            assertThat(p.amount()).isEqualByComparingTo("200");
        });
        assertThat(pairs).anySatisfy(p -> {
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.INTERESES_POR_COBRAR);
            assertThat(p.amount()).isEqualByComparingTo("120");
        });
        assertThat(pairs).anySatisfy(p -> {
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.INTERESES_POR_COBRAR);
            assertThat(p.amount()).isEqualByComparingTo("30");
        });
        // Y el total sigue cuadrando contra lo que entró a caja.
        assertThat(pairs.stream().map(Pair::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("350");
    }

    /**
     * GL-12: el importe del cargo es el que manda cartera, no el que se deduzca de los saldos.
     *
     * <p>El shadow llega desfasado a propósito: es lo que pasa cuando dos eventos del mismo crédito
     * se procesan fuera de orden. Deduciendo, este devengo se habría asentado por la diferencia
     * entera contra el saldo recordado —aquí 900,000— en vez de por los 2,436.67 que de verdad se
     * devengaron. Es exactamente cómo la cuenta 4101 acabó quince veces inflada sin que la balanza
     * lo delatara: el par seguía cuadrando consigo mismo.
     */
    @Test
    void charge_usesTheAmountFromPortfolio_notTheBalanceDelta() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(ca, party);
        shadow.applyAndComputeDelta(new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100000"), 1L);
        given(shadowRepository.findById(ca)).willReturn(Optional.of(shadow));
        PostingRule rule = mock(PostingRule.class);
        given(rule.getDebitAccount()).willReturn("1203");
        given(rule.getCreditAccount()).willReturn("4101");
        given(postingRuleRepository.findByTriggerEvent("CHARGE_ORDINARY_INTEREST"))
                .willReturn(Optional.of(rule));

        // Saldos que implican un delta de 900,000 — pero el hecho vale 2,436.67.
        service.onBalanceUpdated("evt-desfasado", ca, party, new BigDecimal("1000000"),
                new BigDecimal("2436.67"), BigDecimal.ZERO, new BigDecimal("1000000"),
                "CHARGE_ORDINARY_INTEREST", 2L, "S_CUL", Instant.now(), new BigDecimal("2436.67"));

        assertThat(capturedPairs()).singleElement().satisfies(p ->
                assertThat(p.amount()).isEqualByComparingTo("2436.67"));
    }

    /**
     * GL-13: un evento fuera de orden se asienta igual, y con el desglose que manda cartera.
     *
     * <p>El shadow ya va en una versión posterior, así que la guarda descartaba el evento entero: de
     * diecisiete pagos reales sólo llegaron cinco al mayor, y los cinco íntegros contra capital
     * porque el reparto también se deducía. Con el desglose recibido, el pago abona cada cuenta por
     * lo suyo aunque llegue tarde.
     */
    @Test
    void outOfOrderPayment_isStillPosted_andSplitByComponent() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(ca, party);
        shadow.applyAndComputeDelta(new BigDecimal("10000"), new BigDecimal("409"), BigDecimal.ZERO,
                new BigDecimal("10409"), 9L);
        given(shadowRepository.findById(ca)).willReturn(Optional.of(shadow));

        // Versión 5 contra un shadow en 9: llegó tarde. Trae su propio desglose.
        service.onBalanceUpdated("pay-tarde", ca, party, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "PAYMENT_APPLIED", 5L, "S_CUL", Instant.now(),
                new BigDecimal("-10590.26"), new BigDecimal("-10000"), new BigDecimal("-409"),
                new BigDecimal("-181.26"));

        List<Pair> pairs = capturedPairs();
        assertThat(sumCredits(pairs, AccountCodes.CARTERA_VIGENTE)).isEqualByComparingTo("10000");
        // Interés y moratorios comparten el auxiliar 1203: 409 + 181.26.
        assertThat(sumCredits(pairs, AccountCodes.INTERESES_POR_COBRAR)).isEqualByComparingTo("590.26");
        // Y llegan como DOS pares al mayor: fundirlos en un renglón es responsabilidad de
        // `VoucherPostingService`, que es quien conoce la restricción de unicidad de la base.
        assertThat(pairs.stream().filter(p -> p.creditAccount().equals(AccountCodes.INTERESES_POR_COBRAR)))
                .hasSize(2);
    }

    /** GL-10: primero se consume la reserva; sólo el excedente golpea resultados. */
    @Test
    void writeOff_consumesReserveFirst_thenPnL() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(ca, party);
        shadow.applyAndComputeDelta(new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000"), 1L);
        given(shadowRepository.findById(ca)).willReturn(Optional.of(shadow));

        ProvisionLedgerEntry provision = ProvisionLedgerEntry.init(ca);
        provision.book(new BigDecimal("600"));
        given(provisionLedgerRepository.findById(ca)).willReturn(Optional.of(provision));

        service.onBalanceUpdated("wo-1", ca, party, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "WRITE_OFF", 2L, "S_CUL", Instant.now());

        // Dos pólizas: la baja del balance y su registro en cuentas de orden.
        ArgumentCaptor<VoucherRequest> req = ArgumentCaptor.forClass(VoucherRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Pair>> captor = ArgumentCaptor.forClass(List.class);
        then(ledger).should(times(2)).post(req.capture(), captor.capture());

        List<Pair> pairs = captor.getAllValues().get(0);
        // Consumo de reserva y exceso son el mismo hecho, así que van en la misma póliza.
        assertThat(pairs).hasSize(2);
        assertThat(pairs).anySatisfy(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.ESTIMACION_PREVENTIVA);
            assertThat(p.amount()).isEqualByComparingTo("600");
        });
        assertThat(pairs).anySatisfy(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.GASTO_QUEBRANTO);
            assertThat(p.amount()).isEqualByComparingTo("400");
        });

        // IFRS 9 §5.4.4: lo castigado sale del balance y se sigue en orden, por el monto completo.
        assertThat(req.getAllValues().get(1).triggerEvent()).isEqualTo("WRITE_OFF_CONTROL");
        assertThat(captor.getAllValues().get(1)).singleElement().satisfies(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.CARTERA_CASTIGADA);
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.CONTROL_CASTIGADA);
            assertThat(p.amount()).isEqualByComparingTo("1000");
        });
    }

    /**
     * GL-11: el quebranto da de baja el devengado por su propia cuenta.
     *
     * <p>El caso real que lo destapó: un crédito con 250,000 de capital y 161.11 entre interés e IVA
     * se castigaba acreditando los 250,161.11 completos a 1201. Cartera quedaba sub-acreditada por
     * 161.11 y 1203 se quedaba con un saldo que ya no era cobrable — y la balanza no lo delataba,
     * porque el asiento seguía cuadrando consigo mismo.
     */
    @Test
    void writeOff_derecognisesAccruedInterest_onItsOwnAccount() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(ca, party);
        shadow.applyAndComputeDelta(new BigDecimal("250000"), new BigDecimal("138.89"),
                new BigDecimal("22.22"), new BigDecimal("250161.11"), 1L);
        given(shadowRepository.findById(ca)).willReturn(Optional.of(shadow));
        // Sin reserva constituida: todo el castigo golpea resultados.
        given(provisionLedgerRepository.findById(ca)).willReturn(Optional.of(ProvisionLedgerEntry.init(ca)));

        service.onBalanceUpdated("wo-2", ca, party, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "WRITE_OFF", 2L, "S_CUL", Instant.now());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Pair>> captor = ArgumentCaptor.forClass(List.class);
        then(ledger).should(times(2)).post(any(VoucherRequest.class), captor.capture());
        List<Pair> pairs = captor.getAllValues().get(0);

        BigDecimal aCartera = sumCredits(pairs, AccountCodes.CARTERA_VIGENTE);
        BigDecimal aDevengado = sumCredits(pairs, AccountCodes.INTERESES_POR_COBRAR);

        // Cada cuenta se da de baja por lo que de verdad tenía.
        assertThat(aCartera).isEqualByComparingTo("250000");
        assertThat(aDevengado).isEqualByComparingTo("161.11");
        // Y entre las dos suman exactamente lo castigado: ni un centavo se queda sin dar de baja.
        assertThat(aCartera.add(aDevengado)).isEqualByComparingTo("250161.11");
    }

    private static BigDecimal sumCredits(List<Pair> pairs, String account) {
        return pairs.stream()
                .filter(p -> p.creditAccount().equals(account))
                .map(Pair::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** El alta que antes no existía: un crédito autorizado ya deja huella contable. */
    @Test
    void accountActivated_postsToOrderAccounts_andSealsTheBranch() {
        given(shadowRepository.findById(ca)).willReturn(Optional.empty());

        service.onAccountActivated("act-1", ca, party, new BigDecimal("250000"),
                new BigDecimal("250000"), "S_CUL", Instant.parse("2026-08-04T15:10:00Z"));

        ArgumentCaptor<VoucherRequest> req = ArgumentCaptor.forClass(VoucherRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Pair>> pairs = ArgumentCaptor.forClass(List.class);
        // Dos pólizas: la línea autorizada en orden y el capital que sale a la calle.
        then(ledger).should(times(2)).post(req.capture(), pairs.capture());

        assertThat(req.getAllValues()).allSatisfy(r -> assertThat(r.orgUnitCode()).isEqualTo("S_CUL"));
        assertThat(pairs.getAllValues().get(0)).singleElement().satisfies(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.LINEAS_AUTORIZADAS);
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.LINEAS_POR_DISPONER);
            assertThat(p.amount()).isEqualByComparingTo("250000");
        });
        assertThat(pairs.getAllValues().get(1)).singleElement().satisfies(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.CARTERA_VIGENTE);
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.BANCOS);
            assertThat(p.amount()).isEqualByComparingTo("250000");
        });

        ArgumentCaptor<AccountBalanceShadow> shadow = ArgumentCaptor.forClass(AccountBalanceShadow.class);
        then(shadowRepository).should().save(shadow.capture());
        assertThat(shadow.getValue().getOrgUnitCode()).isEqualTo("S_CUL");
    }

    /**
     * La sucursal se sella una vez.
     *
     * <p>Si un evento posterior trajera otra, reescribirla haría que reasignar la cartera cambiara la
     * atribución contable de los meses anteriores.
     */
    @Test
    void branch_isSealedOnce_andLaterEventsDoNotOverrideIt() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(ca, party);
        shadow.learnOrgUnit("S_CUL");
        given(shadowRepository.findById(ca)).willReturn(Optional.of(shadow));

        PostingRule rule = mock(PostingRule.class);
        given(rule.getDebitAccount()).willReturn("1203");
        given(rule.getCreditAccount()).willReturn("4101");
        given(postingRuleRepository.findByTriggerEvent(any())).willReturn(Optional.of(rule));

        service.onBalanceUpdated("evt-2", ca, party, BigDecimal.ZERO, new BigDecimal("50"),
                BigDecimal.ZERO, new BigDecimal("50"), "CHARGE_ORDINARY_INTEREST", 1L,
                "S_MAZ", Instant.now());

        ArgumentCaptor<VoucherRequest> req = ArgumentCaptor.forClass(VoucherRequest.class);
        then(ledger).should().post(req.capture(), any());
        assertThat(req.getValue().orgUnitCode()).isEqualTo("S_CUL");
    }

    /**
     * El defecto que producía «comisiones» del tamaño del préstamo.
     *
     * <p>Contabilidad conoce la cuenta a media vida: su shadow está en cero pero cartera ya va por la
     * versión 7. El delta contra cero es el saldo acumulado, no el importe del hecho — y antes se le
     * atribuía al hecho que casualmente llegó primero.
     */
    @Test
    void accountSeenMidLife_postsOpeningBalance_notTheTriggerAmount() {
        given(shadowRepository.findById(ca)).willReturn(Optional.empty());

        // Un crédito de 20 000 de capital y 200 de interés, del que sólo vemos la comisión.
        service.onBalanceUpdated("evt-tarde", ca, party, new BigDecimal("20000"), new BigDecimal("200"),
                BigDecimal.ZERO, new BigDecimal("20200"), "CHARGE_OPENING_FEE", 7L,
                "S_CUL", Instant.now());

        ArgumentCaptor<VoucherRequest> req = ArgumentCaptor.forClass(VoucherRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Pair>> pairs = ArgumentCaptor.forClass(List.class);
        then(ledger).should().post(req.capture(), pairs.capture());

        // No se etiqueta como comisión, y no toca ingresos.
        assertThat(req.getValue().triggerEvent()).isEqualTo("OPENING_BALANCE");
        assertThat(pairs.getValue()).hasSize(2);
        assertThat(pairs.getValue()).allSatisfy(p ->
                assertThat(p.creditAccount()).isEqualTo(AccountCodes.SALDO_INICIAL));
        assertThat(pairs.getValue()).anySatisfy(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.CARTERA_VIGENTE);
            assertThat(p.amount()).isEqualByComparingTo("20000");
        });
        assertThat(pairs.getValue()).anySatisfy(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.INTERESES_POR_COBRAR);
            assertThat(p.amount()).isEqualByComparingTo("200");
        });
        // Y no se factura: incorporar un saldo no devenga ingreso.
        then(billingService).shouldHaveNoInteractions();
    }

    /** Una cuenta que nace con contabilidad escuchando NO es una incorporación. */
    @Test
    void accountSeenFromItsFirstEvent_postsNormally() {
        given(shadowRepository.findById(ca)).willReturn(Optional.of(AccountBalanceShadow.init(ca, party)));
        PostingRule rule = mock(PostingRule.class);
        given(rule.getDebitAccount()).willReturn("1203");
        given(rule.getCreditAccount()).willReturn("4103");
        given(postingRuleRepository.findByTriggerEvent("CHARGE_OPENING_FEE")).willReturn(Optional.of(rule));

        service.onBalanceUpdated("evt-1", ca, party, BigDecimal.ZERO, new BigDecimal("200"),
                BigDecimal.ZERO, new BigDecimal("200"), "CHARGE_OPENING_FEE", 1L,
                "S_CUL", Instant.now());

        ArgumentCaptor<VoucherRequest> req = ArgumentCaptor.forClass(VoucherRequest.class);
        then(ledger).should().post(req.capture(), any());
        assertThat(req.getValue().triggerEvent()).isEqualTo("CHARGE_OPENING_FEE");
    }

    @Test
    void staleBalanceVersion_isSkipped() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(ca, party);
        shadow.applyAndComputeDelta(new BigDecimal("500"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500"), 5L);
        given(shadowRepository.findById(ca)).willReturn(Optional.of(shadow));

        service.onBalanceUpdated("evt-old", ca, party, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("500"), "PAYMENT_APPLIED", 3L, "S_CUL", Instant.now());

        then(ledger).shouldHaveNoInteractions();
    }

    @Test
    void recoveryPayment_postsBancosToRecovery() {
        service.onRecoveryPayment("RECOVERY-1", ca, party, new BigDecimal("250"), Instant.now());

        List<Pair> pairs = capturedPairs();
        assertThat(pairs).singleElement().satisfies(p -> {
            assertThat(p.debitAccount()).isEqualTo(AccountCodes.BANCOS);
            assertThat(p.creditAccount()).isEqualTo(AccountCodes.RECUPERACION_CASTIGADA);
        });
    }
}
