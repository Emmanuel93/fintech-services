package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.CreateCreditAccountCommand;
import com.fintech.creditportfolio.application.ProcessAgreementExecutedCommand;
import com.fintech.creditportfolio.application.ProcessDispositionCommand;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.SpeiDispatchPort;
import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.application.service.CreditAccountService;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionStatus;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CreditAccountServiceTest {

    @Mock CreditAccountRepository accountRepository;
    @Mock DispositionRepository dispositionRepository;
    @Mock InstallmentRepository installmentRepository;
    @Mock BalanceEventRepository balanceEventRepository;
    @Mock SpeiDispatchPort speiDispatch;
    @Mock CreditPortfolioEventPublisher eventPublisher;
    @Mock ProductConfigResolver configResolver;

    CreditAccountService service;

    @BeforeEach
    void setUp() {
        service = new CreditAccountService(accountRepository, dispositionRepository,
                installmentRepository, balanceEventRepository, new AmortizationEngine(), speiDispatch,
                eventPublisher, configResolver, 12);
        // Default: resolver returns an INSTALLMENT/FRENCH/MONTHLY config (lenient — idempotent test returns early)
        lenient().when(configResolver.resolveForActivation(any())).thenReturn(installmentConfig());
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
                "032180000118359719", "BAJO", null, null, null, null, null);
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
                "032180000118359719", "MEDIO", null, null, null, null, null);
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
        then(speiDispatch).shouldHaveNoInteractions();
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
        CreditAccount result = service.activate(conPromotorYSucursal(promotor, null));

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
        CreditAccount result = service.activate(conPromotorYSucursal(promotor, "S_GDL"));

        assertThat(result.getOriginUnitCode()).isEqualTo("S_GDL");
        assertThat(result.getOriginUnitCode()).isNotEqualTo(promotor);
    }

    private CreateCreditAccountCommand conPromotorYSucursal(String promoterCode, String originUnitCode) {
        return new CreateCreditAccountCommand(
                UUID.randomUUID(), "CTR-202608-DIST0001", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.03"),
                "032180000118359719", "BAJO", promoterCode, originUnitCode, null, null, null);
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
        then(speiDispatch).shouldHaveNoInteractions();
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

        then(speiDispatch).shouldHaveNoInteractions();
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
        then(speiDispatch).shouldHaveNoInteractions();
    }

    // ── ProcessDispositionUseCase (wallet.disposition-requested) ─────────────

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
                "evt-" + UUID.randomUUID(), accountId, UUID.randomUUID(), amount, "SELF_USE", null, null, null);
    }

    @Test
    void process_selfUse_creditsAccountWithoutSpei() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.process(selfUseCmd(account.getCreditAccountId(), new BigDecimal("5000")));

        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("5000");
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("15000");
        then(speiDispatch).shouldHaveNoInteractions();
        then(eventPublisher).should().publishDispositionCompleted(any());
        then(eventPublisher).should().publishBalanceUpdated(any());
        then(eventPublisher).should(never()).publishDispositionRejected(any());
    }

    @Test
    void process_thirdPartyCredit_dispatchesSpei() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(speiDispatch.dispatch(any(), any(), any())).willReturn("SPEI-REF-XYZ");

        var cmd = new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), UUID.randomUUID(),
                new BigDecimal("3000"), "THIRD_PARTY_CREDIT", UUID.randomUUID(), "032180000118399999", 12);

        service.process(cmd);

        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("3000");
        then(speiDispatch).should().dispatch(any(), eq(new BigDecimal("3000")), eq("032180000118399999"));
        then(eventPublisher).should().publishDispositionCompleted(any());
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
        given(speiDispatch.dispatch(any(), any(), any())).willReturn("SPEI-REF");

        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), UUID.randomUUID(),
                new BigDecimal("6000"), "THIRD_PARTY_CREDIT", UUID.randomUUID(),
                "032180000118399999", 6));

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
                new BigDecimal("6000"), "SELF_USE", null, null, null));

        // Sin piso, una disposición sin plazo generaría cero cuotas: deuda sin nada que vencer.
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        then(installmentRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(12);
    }

    @Test
    void beneficiarioNoPuedeSerElAcreditado() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));

        // Prestarse a sí mismo por la línea de distribuidora no es una colocación: es uso propio, y
        // eso va por otro producto. Permitirlo dejaría a alguien cobrándose comisión por su propio
        // crédito.
        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), account.getObligorPartyId(),
                new BigDecimal("3000"), "THIRD_PARTY_CREDIT", account.getObligorPartyId(),
                "032180000118399999", 12));

        ArgumentCaptor<com.fintech.creditportfolio.domain.event.DispositionRejectedEvent> captor =
                ArgumentCaptor.forClass(com.fintech.creditportfolio.domain.event.DispositionRejectedEvent.class);
        then(eventPublisher).should().publishDispositionRejected(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("BENEFICIARY_CANNOT_BE_OBLIGOR");
        then(dispositionRepository).should(never()).save(any());
    }

    @Test
    void colocacionATerceroSinBeneficiario_seRechaza() {
        CreditAccount account = activeRevolvingAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));

        service.process(new ProcessDispositionCommand(
                "evt-" + UUID.randomUUID(), account.getCreditAccountId(), UUID.randomUUID(),
                new BigDecimal("3000"), "THIRD_PARTY_CREDIT", null, "032180000118399999", 12));

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
                "evt-dup", UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000"), "SELF_USE", null, null, null);
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
    void process_restructure_updatesRateAndTerm_withoutPublishingBalanceUpdated() {
        CreditAccount account = activePersonalLoanAccount();
        given(balanceEventRepository.existsBySourceEventId(any())).willReturn(false);
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var cmd = new ProcessAgreementExecutedCommand("evt-ag-2", account.getCreditAccountId(),
                "RESTRUCTURE", null, new BigDecimal("18.0"), 24);
        service.process(cmd);

        assertThat(account.getNominalRate()).isEqualByComparingTo("18.0");
        assertThat(account.getAssignedTerm()).isEqualTo(24);
        then(eventPublisher).shouldHaveNoInteractions();
        then(balanceEventRepository).should(never()).save(any());
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

    // ── SettleDisbursementUseCase (cierre del ciclo, disbursement.completed/failed) ───

    private Disposition processingDisposition(UUID creditAccountId) {
        Disposition d = Disposition.create(creditAccountId, DispositionType.THIRD_PARTY_CREDIT,
                new BigDecimal("50000"), null);
        d.markProcessing();
        return d;
    }

    @Test
    void onDisbursementCompleted_completesDisposition_andPublishesCompleted() {
        CreditAccount account = activeRevolvingAccount();
        Disposition disposition = processingDisposition(account.getCreditAccountId());
        given(dispositionRepository.findById(disposition.getDispositionId()))
                .willReturn(Optional.of(disposition));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(accountRepository.findById(account.getCreditAccountId())).willReturn(Optional.of(account));

        service.onDisbursementCompleted(disposition.getDispositionId(), "STP-REF-REAL-123");

        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        then(eventPublisher).should().publishDispositionCompleted(any());
    }

    @Test
    void onDisbursementCompleted_isIdempotent_whenDispositionNotProcessing() {
        CreditAccount account = activeRevolvingAccount();
        Disposition disposition = processingDisposition(account.getCreditAccountId());
        disposition.complete("ALREADY");
        given(dispositionRepository.findById(disposition.getDispositionId()))
                .willReturn(Optional.of(disposition));

        service.onDisbursementCompleted(disposition.getDispositionId(), "STP-REF-DUP");

        then(dispositionRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void onDisbursementFailed_marksDispositionFailed() {
        UUID accountId = UUID.randomUUID();
        Disposition disposition = processingDisposition(accountId);
        given(dispositionRepository.findById(disposition.getDispositionId()))
                .willReturn(Optional.of(disposition));
        given(dispositionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onDisbursementFailed(disposition.getDispositionId(), "INVALID_BENEFICIARY_ACCOUNT", "CLABE inválida");

        assertThat(disposition.getStatus()).isEqualTo(DispositionStatus.FAILED);
        then(eventPublisher).should(never()).publishDispositionCompleted(any());
    }
}
