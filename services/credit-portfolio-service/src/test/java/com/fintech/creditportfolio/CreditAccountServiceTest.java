package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.CreateCreditAccountCommand;
import com.fintech.creditportfolio.application.ProcessAgreementExecutedCommand;
import com.fintech.creditportfolio.application.ProcessDispositionCommand;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.application.service.CreditAccountService;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionStatus;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.DistributorCommissionSchedule;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.BillingCycle;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
import com.fintech.creditportfolio.domain.config.DistributorCommission.Tier;
import com.fintech.creditportfolio.domain.event.DispositionRejectedEvent;
import java.math.RoundingMode;
import java.time.LocalDate;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.times;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.shared.time.BusinessClock;
import com.fintech.creditportfolio.domain.event.AccountRestructuredEvent;
import com.fintech.creditportfolio.domain.Installment;
import java.time.DayOfWeek;
import java.time.LocalDate;

@ExtendWith(MockitoExtension.class)
class CreditAccountServiceTest {

    @Mock CreditAccountRepository accountRepository;
    @Mock DispositionRepository dispositionRepository;
    @Mock InstallmentRepository installmentRepository;
    @Mock BalanceEventRepository balanceEventRepository;
    @Mock CreditPortfolioEventPublisher eventPublisher;
    @Mock ProductConfigResolver configResolver;
    @Mock com.fintech.creditportfolio.application.port.out.PartyVerificationRepository partyVerifications;

    CreditAccountService service;

    @BeforeEach
    void setUp() {
        service = new CreditAccountService(accountRepository, dispositionRepository,
                installmentRepository, balanceEventRepository, new AmortizationEngine(),
                eventPublisher, configResolver, partyVerifications, 12);
        // El veredicto de AML llega limpio salvo que la prueba diga lo contrario: estas pruebas
        // examinan el motor de disposiciones, no la compuerta —ésa tiene las suyas en
        // `AmlOnDispositionTest`—. Dejarlo bloqueado por omisión haría fallar a todas por un motivo
        // que no es el que miden.
        lenient().when(partyVerifications.findByPartyId(any())).thenReturn(
                java.util.Optional.of(com.fintech.creditportfolio.domain.PartyVerification.of(
                        java.util.UUID.randomUUID(), "VERIFIED", "CLEARED", true, null)));
        // Default: resolver returns an INSTALLMENT/FRENCH/MONTHLY config (lenient — idempotent test returns early)
        lenient().when(configResolver.resolveForActivation(any())).thenReturn(installmentConfig());
        // BK-13 · el tipo de disposición sale del PRODUCTO. Que las pruebas tengan que declarar la
        // configuración no es ceremonia: es el punto. Antes bastaba con mandar el tipo en la
        // petición, y eso era el agujero por el que el dinero salía a la persona equivocada.
        lenient().when(configResolver.resolveForAccount(eq("RL-001"), any()))
                .thenReturn(Optional.of(revolvingConfig()));
        lenient().when(configResolver.resolveForAccount(eq("PL-001"), any()))
                .thenReturn(Optional.of(installmentConfig()));
        lenient().when(configResolver.resolveForAccount(eq("DL-001"), any()))
                .thenReturn(Optional.of(distributorConfig()));
    }

    private static ProductConfigVersion installmentConfig() {
        return ProductConfigVersion.of(
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT", "B2C",
                new Capabilities(true, false, false, "SELF_USE", false, false, false, false, false),
                "FRENCH", "MONTHLY", 1000,
                new BigDecimal("0.24"), new BigDecimal("0.36"), new BigDecimal("0.03"),
                "ACTIVE", false);
    }

    private CreateCreditAccountCommand personalLoanCmd() {
        return new CreateCreditAccountCommand(
                UUID.randomUUID(), "CTR-202606-ABCD1234", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.03"),
                "032180000118359719", "BAJO", null, null, null, null, null, null);
    }

    private static ProductConfigVersion revolvingConfig() {
        return ProductConfigVersion.of(
                "RL-001", 1, "REVOLVING_CREDIT", "REVOLVING", "B2C",
                new Capabilities(false, true, true, "SELF_USE", true, true, false, false, false),
                null, null, 1000,
                new BigDecimal("0.36"), new BigDecimal("0.54"), new BigDecimal("2.0"),
                "ACTIVE", false);
    }

    private CreateCreditAccountCommand revolvingCmd() {
        return new CreateCreditAccountCommand(
                UUID.randomUUID(), "CTR-202607-REV12345", UUID.randomUUID(),
                "RL-001", 1, "REVOLVING_CREDIT", "REVOLVING",
                null, new BigDecimal("20000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.02"),
                "032180000118359719", "MEDIO", null, null, null, null, null, null);
    }

    @Test
    void activate_revolving_opens_at_zero_with_full_available_credit() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(configResolver.resolveForActivation(any())).willReturn(revolvingConfig());

        CreditAccount result = service.activate(revolvingCmd());

        assertThat(result.getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
        assertThat(result.getPrincipalBalance()).isEqualByComparingTo("0");
        assertThat(result.getAvailableCredit()).isEqualByComparingTo("20000");
        then(dispositionRepository).shouldHaveNoInteractions();
    }

    // ── A19 · la sucursal no es el promotor ───────────────────────────────────
    //
    // Aquí se sellaba `promoterCode` en `originUnitCode`. Como el promoterCode que llega es un UUID
    // de 36 caracteres y la columna admite 40, no fallaba nada: escribía un dato equivocado que
    // contabilidad consume como si fuera una sucursal. Y quedaba así para siempre, porque la
    // reconciliación del backoffice salta toda cuenta que ya tenga sucursal sellada.

    @Test
    void a19_originUnitCode_noSeSellaConElPromoterCode() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        String promotor = UUID.randomUUID().toString();
        CreditAccount result = service.activate(withAgentAndBranch(promotor, null));

        assertThat(result.getOriginUnitCode())
                .as("un crédito sin sucursal resuelta nace sin sellar, para que la reconciliación "
                  + "lo pueda reparar — nunca con el código del promotor dentro")
                .isNull();
    }

    @Test
    void a19_originUnitCode_seSellaConLaSucursalCuandoLlega() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        String promotor = UUID.randomUUID().toString();
        CreditAccount result = service.activate(withAgentAndBranch(promotor, "S_GDL"));

        assertThat(result.getOriginUnitCode()).isEqualTo("S_GDL");
        assertThat(result.getOriginUnitCode()).isNotEqualTo(promotor);
    }

    private CreateCreditAccountCommand withAgentAndBranch(String promoterCode, String originUnitCode) {
        return new CreateCreditAccountCommand(
                UUID.randomUUID(), "CTR-202608-DIST0001", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.03"),
                "032180000118359719", "BAJO", promoterCode, originUnitCode, null, null, null, null);
    }

    @Test
    void activate_creates_account_in_active_status() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CreditAccount result = service.activate(personalLoanCmd());

        assertThat(result.getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
        assertThat(result.getPrincipalBalance()).isEqualByComparingTo("50000");
        assertThat(result.getActivatedAt()).isNotNull();
        // El dinero ya NO sale aquí: lo desembolsa disbursement-service tras el hecho.
    }

    @Test
    void activate_writes_the_ledger_entry_the_debt_is_born_with() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.activate(personalLoanCmd());

        // Without this entry the sum of balance_events could never equal the balances: the loan's ledger
        // would start after the money was lent (what happened to 100 loans before 2026-11-02).
        ArgumentCaptor<BalanceEvent> captor = ArgumentCaptor.forClass(BalanceEvent.class);
        then(balanceEventRepository).should().save(captor.capture());
        BalanceEvent entry = captor.getValue();
        assertThat(entry.getEventType()).isEqualTo("ACTIVATION_DISBURSEMENT");
        assertThat(entry.getDeltaAmount()).isEqualByComparingTo("50000");
        assertThat(entry.getSourceEventId()).startsWith("activation:");
    }

    @Test
    void activate_generates_amortization_schedule() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.activate(personalLoanCmd());

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        then(installmentRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(12);
    }

    @Test
    void activate_publishes_credit_account_activated_event() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.activate(personalLoanCmd());

        ArgumentCaptor<CreditAccountActivatedEvent> captor =
                ArgumentCaptor.forClass(CreditAccountActivatedEvent.class);
        then(eventPublisher).should().publishCreditAccountActivated(captor.capture());
        CreditAccountActivatedEvent event = captor.getValue();
        assertThat(event.getProductType()).isEqualTo("PERSONAL_LOAN");
        assertThat(event.getPrincipalBalance()).isEqualByComparingTo("50000");
        assertThat(event.getContractId()).isNotNull();

        // La conexión con desembolsos: el hecho lleva la orden de pago (monto + CLABE destino).
        var instruction = event.getDisbursementInstruction();
        assertThat(instruction).isNotNull();
        assertThat(instruction.amount()).isEqualByComparingTo("50000");
        assertThat(instruction.beneficiaryAccount()).isEqualTo("032180000118359719");
        assertThat(instruction.beneficiaryAccountType()).isEqualTo("40");
        assertThat(instruction.currency()).isEqualTo("MXN");
        assertThat(instruction.dispositionId()).isNotNull();
    }

    @Test
    void activate_revolving_publishesEventWithoutDisbursementInstruction() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(configResolver.resolveForActivation(any())).willReturn(revolvingConfig());

        service.activate(revolvingCmd());

        ArgumentCaptor<CreditAccountActivatedEvent> captor =
                ArgumentCaptor.forClass(CreditAccountActivatedEvent.class);
        then(eventPublisher).should().publishCreditAccountActivated(captor.capture());
        // Revolvente abre en cero: no hay dinero que mover, así que no viaja instrucción.
        assertThat(captor.getValue().getDisbursementInstruction()).isNull();
    }

    @Test
    void activate_is_idempotent_when_account_already_exists() {
        CreditAccount existing = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-1", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.03"), "032180000118359719", "BAJO", null, null);
        given(accountRepository.findByContractId(any())).willReturn(Optional.of(existing));

        service.activate(personalLoanCmd());

        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void activate_creates_disposition_with_correct_amount() {
        given(accountRepository.findByContractId(any())).willReturn(Optional.empty());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<Disposition> dispositionCaptor = ArgumentCaptor.forClass(Disposition.class);
        given(dispositionRepository.save(dispositionCaptor.capture())).willAnswer(inv -> inv.getArgument(0));

        service.activate(personalLoanCmd());

        // Una sola escritura: markProcessing. Ya NO hay complete() aquí — la disposición queda en
        // vuelo hasta que disbursement.completed confirme la liquidación.
        List<Disposition> saved = dispositionCaptor.getAllValues();
        assertThat(saved).isNotEmpty();
        assertThat(saved.get(0).getAmount()).isEqualByComparingTo("50000");
        assertThat(saved.get(0).getStatus()).isEqualTo(DispositionStatus.PROCESSING);
    }

    // ── ProcessDispositionUseCase (wallet.disposition-requested) ─────────────

    /**
     * Una línea de distribuidor: su producto dispone a la <b>beneficiaria</b>.
     *
     * <p>Existe porque el tipo ya no viaja en la petición. Antes, una prueba de colocación a
     * tercero se hacía sobre una línea de uso propio mandando {@code "THIRD_PARTY_CREDIT"} en el
     * comando — que es justo el agujero: cualquiera podía hacer lo mismo en producción.
     */
    private CreditAccount activeDistributorLine() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-DL-1", UUID.randomUUID(),
                "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING",
                null, new BigDecimal("20000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.02"), "032180000118359719", "MEDIO", null, null);
        account.activate(BigDecimal.ZERO);
        return account;
    }

    private static ProductConfigVersion distributorConfig() {
        return ProductConfigVersion.of(
                "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING", "B2B2C",
                new Capabilities(false, true, true, "THIRD_PARTY_CREDIT", true, true, false, false, false),
                "FRENCH", "MONTHLY", 1000,
                new BigDecimal("0.36"), new BigDecimal("0.54"), new BigDecimal("2.0"),
                "ACTIVE", false);
    }

    // ── La línea de distribuidora que paga comisión: rangos del producto, bloqueo en atraso, comisión en el plan ──

    /** The distributor line as the catalog publishes it now: 95 %, fortnights, $1,000–$10,000, 12–24 fortnights, 18 % netted. */
    private static ProductConfigVersion distributorConfigWithCommission() {
        ProductConfigVersion v = ProductConfigVersion.of(
                "DL-002", 1, "DISTRIBUTOR_LINE", "REVOLVING", "B2B2C",
                new Capabilities(false, true, true, "THIRD_PARTY_CREDIT", true, true, false, false, false),
                "FRENCH", "SEMI_MONTHLY", 500,
                new BigDecimal("0.95"), new BigDecimal("0.36"), BigDecimal.ZERO,
                "ACTIVE", false);
        v.defineBillingCycle(new BillingCycle("SEMI_MONTHLY", null, 0, 2, null, "NEXT",
                "PER_PERIOD", List.of(6, 21), List.of(18, 3)));
        v.defineDistributorCommission(new DistributorCommission(
                DistributorCommission.COLLECTED_TOTAL, new BigDecimal("0.18"),
                List.of(new Tier(0, new BigDecimal("0.18")),
                        new Tier(1, new BigDecimal("0.14")),
                        new Tier(2, new BigDecimal("0.11")),
                        new Tier(3, new BigDecimal("0.07"))),
                "NETTED"));
        v.defineRanges(new BigDecimal("1000"), new BigDecimal("10000"), 12, 24, 2);
        return v;
    }

    private CreditAccount commissionedDistributorLine() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-DL-2", UUID.randomUUID(),
                "DL-002", 1, "DISTRIBUTOR_LINE", "REVOLVING",
                null, new BigDecimal("100000"), null,
                new BigDecimal("0.95"), new BigDecimal("0.36"),
                null, BigDecimal.ZERO, "032180000118359719", "MEDIO", null, null);
        account.activate(BigDecimal.ZERO);
        lenient().when(configResolver.resolveForAccount(eq("DL-002"), any())).thenReturn(Optional.of(distributorConfigWithCommission()));
        lenient().when(installmentRepository.findByScheduleIdOrdered(account.getCreditAccountId())).thenReturn(List.of());
        lenient().when(balanceEventRepository.paymentsOf(account.getCreditAccountId())).thenReturn(List.of());
        return account;
    }

    private ProcessDispositionCommand placementOf(CreditAccount account, String amount, int fortnights) {
        return new ProcessDispositionCommand("evt-" + UUID.randomUUID(), account.getCreditAccountId(), account.getObligorPartyId(),
                new BigDecimal(amount), UUID.randomUUID(), "646180157000000004", "ROSA ELENA IBARRA SALGADO", "IASR900612B0B", fortnights);
    }

    /** A placement that will be refused: the account is read and nothing is saved. */
    private void stubRejectable(CreditAccount account) {
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
    }

    private String rejectionReason() {
        ArgumentCaptor<DispositionRejectedEvent> captor =
                ArgumentCaptor.forClass(DispositionRejectedEvent.class);
        then(eventPublisher).should().publishDispositionRejected(captor.capture());
        then(eventPublisher).should(never()).publishDispositionAuthorized(any());
        return captor.getValue().getReason();
    }

    @Test
    @DisplayName("una colocación fuera de los rangos del producto se rechaza: 8 quincenas, $12,000, $1,250")
    void aPlacementOutsideTheProductsRangesIsRejected() {
        CreditAccount account = commissionedDistributorLine();
        stubRejectable(account);
        service.process(placementOf(account, "5000", 8));
        assertThat(rejectionReason()).startsWith("TERM_BELOW_MINIMUM");
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("el monto se valida contra los límites del producto, no contra el paso de $500 del selector")
    void anAmountOutsideTheProductsRangesIsRejected() {
        CreditAccount account = commissionedDistributorLine();
        stubRejectable(account);
        service.process(placementOf(account, "12000", 12));
        assertThat(rejectionReason()).startsWith("AMOUNT_ABOVE_MAXIMUM");
        assertThat(distributorConfigWithCommission().placementOutOfRange(new BigDecimal("999.99"), 12)).startsWith("AMOUNT_BELOW_MINIMUM");
        // The step belongs to the channel's picker: cents within the limits place (the E2E drives SPEI failures with them).
        assertThat(distributorConfigWithCommission().placementOutOfRange(new BigDecimal("4000.02"), 12)).isNull();
        assertThat(distributorConfigWithCommission().placementOutOfRange(new BigDecimal("1500"), 13)).startsWith("TERM_NOT_IN_STEP");
        assertThat(distributorConfigWithCommission().placementOutOfRange(new BigDecimal("1500"), 14)).isNull();
    }

    @Test
    @DisplayName("con un estado de cuenta vencido sin pagar la línea no coloca; pagado, coloca el mismo día")
    void aLineWithAStatementUnpaidPastDueDoesNotPlace() {
        CreditAccount account = commissionedDistributorLine();
        stubDisposable(account);
        LocalDate today = BusinessClock.current().today();
        Installment statement = Installment.of(
                account.getCreditAccountId(), 3, today.minusDays(1), new BigDecimal("2500.00"), BigDecimal.ZERO);
        statement.billAsStatement(3, today.minusDays(13), today.minusDays(1));
        given(installmentRepository.findByScheduleIdOrdered(account.getCreditAccountId())).willReturn(List.of(statement));

        service.process(placementOf(account, "5000", 12));
        assertThat(rejectionReason()).startsWith("LINE_BLOCKED_IN_ARREARS").contains("2500.00");
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");

        // She pays it this morning: the gate opens with the payment's value date, not at the night's close.
        BalanceEvent paid = BalanceEvent.record(account.getCreditAccountId(), "pay-3", "PAYMENT_APPLIED",
                new BigDecimal("-2500.00"), account, today);
        given(balanceEventRepository.paymentsOf(account.getCreditAccountId())).willReturn(List.of(paid));
        service.process(placementOf(account, "5000", 12));
        then(eventPublisher).should().publishDispositionAuthorized(any());
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("una colocación que el lado del cliente rechaza (lista roja, límites de la sucursal) sale con su motivo y no toca la línea")
    void aPlacementTheClientChecksRefuseIsRejectedWithTheirReason() {
        CreditAccount account = commissionedDistributorLine();
        stubRejectable(account);
        AtomicReference<UUID> asked = new AtomicReference<>();
        CreditAccountService withChecks = new CreditAccountService(accountRepository, dispositionRepository,
                installmentRepository, balanceEventRepository, new AmortizationEngine(),
                eventPublisher, configResolver, partyVerifications,
                (line, beneficiary, amount, term) -> {
                    asked.set(beneficiary);
                    return "CLIENT_RED_LISTED: DISTRIBUTOR_REPORT";
                }, 12);
        ProcessDispositionCommand cmd = placementOf(account, "5000", 12);

        withChecks.process(cmd);

        assertThat(rejectionReason()).isEqualTo("CLIENT_RED_LISTED: DISTRIBUTOR_REPORT");
        assertThat(asked.get()).isEqualTo(cmd.beneficiaryPartyId());
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("un crédito directo nunca consulta los límites de cliente: su monto lo define el score")
    void aDirectCreditNeverAsksTheClientChecks() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(dispositionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreditAccountService withChecks = new CreditAccountService(accountRepository, dispositionRepository,
                installmentRepository, balanceEventRepository, new AmortizationEngine(),
                eventPublisher, configResolver, partyVerifications,
                (line, beneficiary, amount, term) -> { throw new AssertionError("a direct credit must not ask"); }, 12);

        withChecks.process(new ProcessDispositionCommand("evt-" + UUID.randomUUID(), account.getCreditAccountId(),
                account.getObligorPartyId(), new BigDecimal("1000"), null, null, null, null, null));

        then(eventPublisher).should(never()).publishDispositionRejected(any());
    }

    @Test
    @DisplayName("tras una quita la línea ya no coloca, aunque esté al corriente: la quita es su salida")
    void aLineWithAQuitaDoesNotPlaceAgain() {
        CreditAccount account = commissionedDistributorLine();
        stubRejectable(account);
        LocalDate today = BusinessClock.current().today();
        BalanceEvent quita = BalanceEvent.record(account.getCreditAccountId(), "convenio-1", "AGREEMENT_QUITA_PARCIAL",
                new BigDecimal("-1000.00"), account, today.minusDays(7));
        given(balanceEventRepository.paymentsOf(account.getCreditAccountId())).willReturn(List.of(quita));

        service.process(placementOf(account, "5000", 12));

        assertThat(rejectionReason()).startsWith("LINE_CLOSED_AFTER_QUITA").contains(today.minusDays(7).toString());
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("el plan de la colocación nace con la comisión de la distribuidora en cada cuota: 18 % de lo que paga el cliente")
    void thePlacementsPlanCarriesTheCommission() {
        CreditAccount account = commissionedDistributorLine();
        stubDisposable(account);

        service.process(placementOf(account, "5000", 12));

        ArgumentCaptor<List<Installment>> plan = ArgumentCaptor.forClass(List.class);
        then(installmentRepository).should().saveAll(plan.capture());
        List<Installment> cuotas = plan.getValue();
        assertThat(cuotas).hasSize(12);
        assertThat(cuotas).allSatisfy(i -> assertThat(i.getScheduledCommission()).isNotNull().isPositive());
        // Flat from the third installment on (a placement made today may have a short first period: the first two
        // share what the first one's interest cannot carry); the plan adds up to 18 % of what the client pays.
        Installment cuarta = cuotas.get(3);
        assertThat(cuarta.getScheduledCommission()).isEqualByComparingTo(cuarta.getTotalAmount()
                .multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal esperado = cuotas.stream().map(i -> i.getTotalAmount().multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal comision = cuotas.stream().map(Installment::getScheduledCommission).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(comision).isEqualByComparingTo(esperado);
        assertThat(DistributorCommissionSchedule.interestCoversCommission(cuotas)).isTrue();
    }

    private CreditAccount activeRevolvingAccount() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-REV-1", UUID.randomUUID(),
                "RL-001", 1, "REVOLVING_CREDIT", "REVOLVING",
                null, new BigDecimal("20000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.02"), "032180000118359719", "MEDIO", null, null);
        account.activate(BigDecimal.ZERO);
        return account;
    }

    private ProcessDispositionCommand selfUseCmd(
            UUID accountId, BigDecimal amount) {
        return new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), accountId, UUID.randomUUID(), amount, null, null, null, null, null);
    }

    @Test
    void laDisposicionDeUsoPropioTambienSaleAUnaCuentaBancaria() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(selfUseCmd(account.getCreditAccountId(), new BigDecimal("5000")));

        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("5000");
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("15000");
        then(eventPublisher).should().publishDispositionAuthorized(any());
        then(eventPublisher).should().publishBalanceUpdated(any());
        then(eventPublisher).should(never()).publishDispositionRejected(any());
    }

    /**
     * La prueba que fijaba el bug, dada la vuelta.
     *
     * <p>Se llamaba {@code process_thirdPartyCredit_dispatchesSpei} y afirmaba que la colocación
     * <b>despachaba SPEI desde cartera</b>, contra un stub que devolvía {@code "SPEI-STUB-…"},
     * marcaba la disposición completada y hacía que contabilidad asentara {@code 1201 → 1101}
     * —salida de caja— de dinero que nunca salió.
     *
     * <p>Ahora afirma el camino correcto: cartera autoriza y publica el hecho; quien paga es
     * {@code disbursement}, y la disposición se completa cuando hay evidencia del proveedor.
     */
    @Test
    void laColocacionAutorizaYNoDispersa() {
        CreditAccount account = activeDistributorLine();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var cmd = new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), UUID.randomUUID(),
                new BigDecimal("3000"), UUID.randomUUID(), "032180000118399999",
                "ROSA ELENA IBARRA SALGADO", "IASR900612B0B", 12);

        service.process(cmd);

        // El compromiso existe desde que se autoriza: el saldo sube aquí.
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("3000");
        // Pero el dinero NO ha salido: no se completa nada todavía.
        then(eventPublisher).should().publishDispositionAuthorized(any());
        then(eventPublisher).should(never()).publishDispositionCompleted(any());
    }

    // ── A nombre de quién sale el SPEI ────────────────────────────────────────
    //
    // El destino es la terna (nombre, CLABE, RFC). Se decidía la CLABE por tipo pero el nombre y el
    // RFC salían siempre de la cuenta: la colocación de $45,000 a Rosa Elena salió a su CLABE con
    // «LAURA MÉNDEZ SOTO / MESL930326XXX» — la distribuidora — en la orden STP y en el CEP.

    /** La línea de la distribuidora con SU nombre y RFC en la cuenta, como en producción. */
    private CreditAccount distributorLineOfLaura() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-DL-LMS", UUID.randomUUID(),
                "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING",
                null, new BigDecimal("60000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.02"), "032180000118359719", "MEDIO",
                "LAURA MÉNDEZ SOTO", "MESL930326XXX");
        account.activate(BigDecimal.ZERO);
        return account;
    }

    private void stubDisposable(CreditAccount account) {
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("una colocación sale a nombre y RFC de la BENEFICIARIA, no de la distribuidora")
    void laColocacionSaleANombreDeLaBeneficiaria() {
        CreditAccount account = distributorLineOfLaura();
        stubDisposable(account);

        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), account.getObligorPartyId(),
                new BigDecimal("45000"), UUID.randomUUID(), "646180157000000004",
                "ROSA ELENA IBARRA SALGADO", "IASR900612B0B", 24));

        ArgumentCaptor<DispositionAuthorizedEvent> captor =
                ArgumentCaptor.forClass(DispositionAuthorizedEvent.class);
        then(eventPublisher).should().publishDispositionAuthorized(captor.capture());
        DispositionAuthorizedEvent evento = captor.getValue();
        assertThat(evento.beneficiaryAccount()).isEqualTo("646180157000000004");
        assertThat(evento.beneficiaryName()).isEqualTo("ROSA ELENA IBARRA SALGADO");
        assertThat(evento.beneficiaryTaxId()).isEqualTo("IASR900612B0B");
        assertThat(evento.beneficiaryName()).isNotEqualTo("LAURA MÉNDEZ SOTO");
    }

    @Test
    @DisplayName("una colocación con CLABE y sin nombre de quien la recibe se rechaza: no se rellena con la distribuidora")
    void colocacionSinNombreDeBeneficiaria_seRechaza() {
        CreditAccount account = distributorLineOfLaura();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));

        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), account.getObligorPartyId(),
                new BigDecimal("45000"), UUID.randomUUID(), "646180157000000004", "  ", null, 24));

        ArgumentCaptor<DispositionRejectedEvent> captor =
                ArgumentCaptor.forClass(DispositionRejectedEvent.class);
        then(eventPublisher).should().publishDispositionRejected(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("BENEFICIARY_NAME_REQUIRED");
        then(eventPublisher).should(never()).publishDispositionAuthorized(any());
        then(dispositionRepository).should(never()).save(any());
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("uso propio conserva al titular: nombre, CLABE y RFC de la cuenta")
    void elUsoPropioSaleANombreDelTitular() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-REV-LMS", UUID.randomUUID(),
                "RL-001", 1, "REVOLVING_CREDIT", "REVOLVING",
                null, new BigDecimal("20000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.02"), "032180000118359719", "MEDIO",
                "LAURA MÉNDEZ SOTO", "MESL930326XXX");
        account.activate(BigDecimal.ZERO);
        stubDisposable(account);

        // Aunque llegue un nombre ajeno, en uso propio no hay a quién más pagarle.
        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), account.getObligorPartyId(),
                new BigDecimal("5000"), null, null, "ALGUIEN MAS", "XAXX010101000", null));

        ArgumentCaptor<DispositionAuthorizedEvent> captor =
                ArgumentCaptor.forClass(DispositionAuthorizedEvent.class);
        then(eventPublisher).should().publishDispositionAuthorized(captor.capture());
        assertThat(captor.getValue().beneficiaryName()).isEqualTo("LAURA MÉNDEZ SOTO");
        assertThat(captor.getValue().beneficiaryTaxId()).isEqualTo("MESL930326XXX");
        assertThat(captor.getValue().beneficiaryAccount()).isEqualTo("032180000118359719");
    }

    @Test
    @DisplayName("varias colocaciones a la vez: cada SPEI lleva el nombre de SU beneficiaria")
    void colocacionesConcurrentes_cadaUnaConSuBeneficiaria() throws Exception {
        int colocaciones = 8;
        Map<String, CreditAccount> cuentas = new java.util.concurrent.ConcurrentHashMap<>();
        for (int i = 0; i < colocaciones; i++) {
            CreditAccount a = distributorLineOfLaura();
            cuentas.put(a.getCreditAccountId().toString(), a);
        }
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(any())).willAnswer(inv ->
                Optional.ofNullable(cuentas.get(inv.getArgument(0).toString())));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(colocaciones);
        java.util.concurrent.CountDownLatch salida = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> tareas = new java.util.ArrayList<>();
        int i = 0;
        for (CreditAccount a : cuentas.values()) {
            String nombre = "BENEFICIARIA " + (i++);
            tareas.add(pool.submit(() -> {
                salida.await();
                service.process(new ProcessDispositionCommand(
                        "evt-" + UUID.randomUUID(), a.getCreditAccountId(), a.getObligorPartyId(),
                        new BigDecimal("1000"), UUID.randomUUID(), "646180157000000004",
                        nombre, null, 6));
                return null;
            }));
        }
        salida.countDown();
        for (var t : tareas) t.get(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        ArgumentCaptor<DispositionAuthorizedEvent> captor =
                ArgumentCaptor.forClass(DispositionAuthorizedEvent.class);
        then(eventPublisher).should(times(colocaciones)).publishDispositionAuthorized(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DispositionAuthorizedEvent::beneficiaryName)
                .doesNotContain("LAURA MÉNDEZ SOTO")
                .doesNotHaveDuplicates()
                .hasSize(colocaciones);
    }

    // ── El calendario de la colocación ────────────────────────────────────────
    //
    // Una revolvente no se amortiza; cada disposición sí, a su propio plazo. El calendario cuelga
    // de la disposición (`scheduleId = dispositionId`), igual que una compra a meses en una
    // tarjeta. Es lo único que le da a una línea algo que vencer: sin esto salía siempre con cero
    // días de atraso, y ese cero arrastraba a risk, a cobranza y al quebranto detrás.

    @Test
    void colocacion_generaSuPropioCalendario() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), UUID.randomUUID(),
                new BigDecimal("6000"), UUID.randomUUID(),
                "032180000118399999", "ROSA ELENA IBARRA SALGADO", "IASR900612B0B", 6));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        then(installmentRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(6);
    }

    @Test
    void colocacionSinPlazo_caeAlPlazoPorDefecto() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), UUID.randomUUID(),
                new BigDecimal("6000"), null, null, null, null, null));

        // Sin piso, una disposición sin plazo generaría cero cuotas: deuda sin nada que vencer.
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        then(installmentRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(12);
    }

    @Test
    void beneficiarioNoPuedeSerElAcreditado() {
        CreditAccount account = activeDistributorLine();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));

        // Prestarse a sí mismo por la línea de distribuidora no es una colocación: es uso propio, y
        // eso va por otro producto. Permitirlo dejaría a alguien cobrándose comisión por su propio
        // crédito.
        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), account.getObligorPartyId(),
                new BigDecimal("3000"), account.getObligorPartyId(),
                "032180000118399999", "ROSA ELENA IBARRA SALGADO", "IASR900612B0B", 12));

        ArgumentCaptor<DispositionRejectedEvent> captor =
                ArgumentCaptor.forClass(DispositionRejectedEvent.class);
        then(eventPublisher).should().publishDispositionRejected(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("BENEFICIARY_CANNOT_BE_OBLIGOR");
        then(dispositionRepository).should(never()).save(any());
    }

    @Test
    void colocacionATerceroSinBeneficiario_seRechaza() {
        CreditAccount account = activeDistributorLine();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));

        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), UUID.randomUUID(),
                new BigDecimal("3000"), null, "032180000118399999", "ROSA ELENA IBARRA SALGADO", "IASR900612B0B", 12));

        then(eventPublisher).should().publishDispositionRejected(any());
        then(dispositionRepository).should(never()).save(any());
    }

    @Test
    void process_suspendedAccount_rejectsWithoutMutatingBalance() {
        CreditAccount account = activeRevolvingAccount();
        account.executeWriteOff(); // simplest way to force a non-ACTIVE terminal state via existing domain API
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));

        service.process(selfUseCmd(account.getCreditAccountId(), new BigDecimal("1000")));

        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");
        then(dispositionRepository).shouldHaveNoInteractions();
        then(eventPublisher).should().publishDispositionRejected(any());
        then(eventPublisher).should(never()).publishDispositionCompleted(any());
    }

    @Test
    void process_amountExceedsAvailableCredit_rejects() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));

        service.process(selfUseCmd(account.getCreditAccountId(), new BigDecimal("999999")));

        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");
        then(dispositionRepository).shouldHaveNoInteractions();
        then(eventPublisher).should().publishDispositionRejected(any());
    }

    @Test
    void process_nonRevolving_secondDisposition_rejects_singleDispositionGuard() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-PL-1", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.03"), "032180000118359719", "BAJO", null, null);
        account.activate(new BigDecimal("50000"));
        Disposition previous = Disposition.create(
                account.getCreditAccountId(), DispositionType.SELF_USE,
                new BigDecimal("50000"), null);
        previous.complete("SPEI-STUB-ORIGINAL");

        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(dispositionRepository.findByCreditAccountId(account.getCreditAccountId()))
                .willReturn(List.of(previous));

        service.process(selfUseCmd(account.getCreditAccountId(), new BigDecimal("1000")));

        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("50000"); // unchanged
        then(eventPublisher).should().publishDispositionRejected(any());
    }

    @Test
    void process_duplicateSourceEventId_isIdempotent() {
        given(balanceEventRepository.existsBySourceEventId("evt-dup")).willReturn(true);

        var cmd = new ProcessDispositionCommand(
                "evt-dup", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000"), null, null, null, null, null);
        service.process(cmd);

        then(accountRepository).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    // ── ProcessAgreementExecutedUseCase (collections.agreement-executed) ─────

    private CreditAccount activePersonalLoanAccount() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-PL-AG", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.03"), "032180000118359719", "BAJO", null, null);
        account.activate(new BigDecimal("50000"));
        return account;
    }

    @Test
    void process_quitaParcial_appliesForgiveness_andPublishesBalanceUpdated() {
        CreditAccount account = activePersonalLoanAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var cmd = new ProcessAgreementExecutedCommand("evt-ag-1", account.getCreditAccountId(),
                "QUITA_PARCIAL", new BigDecimal("10000"), null, null);
        service.process(cmd);

        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("40000");
        then(eventPublisher).should().publishBalanceUpdated(any());
        then(balanceEventRepository).should().save(any());
    }

    @Test
    void process_restructure_replansTheUnpaidInstallments_atTheNewRate_andTellsCharges() {
        // 2 Nov, E04: the agreement changed the account's record only; the plan stayed and charges kept the old rate.
        CreditAccount account = activePersonalLoanAccount();
        UUID id = account.getCreditAccountId();
        UUID agreement = UUID.randomUUID();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(id)).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        LocalDate hoy = BusinessClock.current().today();
        Installment pagada = Installment.of(id, 1, hoy.minusDays(50), new BigDecimal("4000"), new BigDecimal("1000"));
        pagada.reconcileTo(InstallmentStatus.PAID);
        Installment vencida = Installment.of(id, 2, hoy.minusDays(20), new BigDecimal("4100"), new BigDecimal("900"));
        Installment futura = Installment.of(id, 3, hoy.minusDays(20).plusMonths(1), new BigDecimal("4200"), new BigDecimal("800"));
        given(installmentRepository.findByScheduleIdOrdered(id)).willReturn(List.of(pagada, vencida, futura));
        given(installmentRepository.maxInstallmentNumber(id)).willReturn(12);

        service.process(new ProcessAgreementExecutedCommand(agreement.toString(), id, "RESTRUCTURE", null,
                new BigDecimal("0.24"), 6));

        assertThat(account.getNominalRate()).isEqualByComparingTo("0.24");
        assertThat(pagada.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(vencida.getStatus()).isEqualTo(InstallmentStatus.REPLACED);
        assertThat(futura.getStatus()).isEqualTo(InstallmentStatus.REPLACED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Installment>> guardadas = ArgumentCaptor.forClass(List.class);
        then(installmentRepository).should(times(2)).saveAll(guardadas.capture());
        List<Installment> nuevo = guardadas.getAllValues().get(1);
        assertThat(nuevo).hasSize(6);
        assertThat(nuevo).extracting(Installment::getInstallmentNumber).containsExactly(13, 14, 15, 16, 17, 18);
        // The old plan's day of the month, after today — on a business day: a Saturday or Sunday moves to Monday.
        LocalDate esperada = futura.getDueDate();
        while (esperada.getDayOfWeek() == DayOfWeek.SATURDAY || esperada.getDayOfWeek() == DayOfWeek.SUNDAY) {
            esperada = esperada.plusDays(1);
        }
        assertThat(nuevo.get(0).getDueDate()).as("the old plan's day of the month, after today, on a business day")
                .isEqualTo(esperada);
        assertThat(nuevo).as("no installment of the new plan falls due on a weekend")
                .allSatisfy(i -> assertThat(i.getDueDate().getDayOfWeek().getValue()).isLessThan(6));
        assertThat(nuevo.stream().map(Installment::getPrincipalAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("the new plan amortizes the principal still owed").isEqualByComparingTo(account.getPrincipalBalance());
        ArgumentCaptor<AccountRestructuredEvent> evento =
                ArgumentCaptor.forClass(AccountRestructuredEvent.class);
        then(eventPublisher).should().publishAccountRestructured(evento.capture());
        assertThat(evento.getValue().newNominalRate()).isEqualByComparingTo("0.24");
        assertThat(evento.getValue().agreementId()).isEqualTo(agreement);
        assertThat(evento.getValue().replacedInstallments()).isEqualTo(2);
        // Recorded in the ledger with no money moved: a redelivery of the agreement finds it.
        then(balanceEventRepository).should().save(any());
        then(eventPublisher).should(never()).publishBalanceUpdated(any());
    }

    @Test
    void process_agreementExecuted_duplicateSourceEventId_isIdempotent() {
        given(balanceEventRepository.existsBySourceEventId("evt-ag-dup")).willReturn(true);

        var cmd = new ProcessAgreementExecutedCommand("evt-ag-dup", UUID.randomUUID(),
                "QUITA_PARCIAL", new BigDecimal("10000"), null, null);
        service.process(cmd);

        then(accountRepository).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }
}
