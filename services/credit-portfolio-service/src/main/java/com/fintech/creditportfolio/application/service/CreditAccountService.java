package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.CreateCreditAccountCommand;
import com.fintech.creditportfolio.application.ProcessAgreementExecutedCommand;
import com.fintech.creditportfolio.application.ProcessDispositionCommand;
import com.fintech.creditportfolio.application.port.in.ActivateCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.in.FindCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.in.ProcessAgreementExecutedUseCase;
import com.fintech.creditportfolio.application.port.in.ProcessDispositionUseCase;
import com.fintech.creditportfolio.application.port.in.SettleDisbursementUseCase;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.SpeiDispatchPort;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountNotFoundException;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionStatus;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionCompletedEvent;
import com.fintech.creditportfolio.domain.event.DispositionRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Config-driven account activation. Behaviour (amortisation, disposition type, credit limit)
 * is resolved from the pinned product config version — not from a hardcoded behavior string.
 */
@Service
@Transactional
public class CreditAccountService implements ActivateCreditAccountUseCase, FindCreditAccountUseCase,
        ProcessDispositionUseCase, ProcessAgreementExecutedUseCase, SettleDisbursementUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountService.class);

    private final CreditAccountRepository accountRepository;
    private final DispositionRepository dispositionRepository;
    private final InstallmentRepository installmentRepository;
    private final BalanceEventRepository balanceEventRepository;
    private final AmortizationEngine amortizationEngine;

    /** IVA nacional, para los créditos cuya sucursal todavía no se resuelve al originar. */
    @org.springframework.beans.factory.annotation.Value("${fintech.credit-portfolio.vat-rate:0.16}")
    private BigDecimal ivaPorDefecto = AmortizationEngine.IVA_NACIONAL;
    private final SpeiDispatchPort speiDispatch;
    private final CreditPortfolioEventPublisher eventPublisher;
    private final ProductConfigResolver configResolver;

    /**
     * Plazo de una colocación cuando ni la disposición ni la cuenta lo traen.
     *
     * <p>Una revolvente no tiene plazo propio, así que sin este piso una disposición sin plazo
     * generaría un calendario de cero cuotas — es decir, deuda sin nada que vencer, que es
     * exactamente el agujero que este trabajo cierra.
     */
    private final int defaultDispositionTerm;

    public CreditAccountService(CreditAccountRepository accountRepository,
                                 DispositionRepository dispositionRepository,
                                 InstallmentRepository installmentRepository,
                                 BalanceEventRepository balanceEventRepository,
                                 AmortizationEngine amortizationEngine,
                                 SpeiDispatchPort speiDispatch,
                                 CreditPortfolioEventPublisher eventPublisher,
                                 ProductConfigResolver configResolver,
                                 @org.springframework.beans.factory.annotation.Value(
                                     "${fintech.credit-portfolio.default-disposition-term:12}")
                                 int defaultDispositionTerm) {
        this.defaultDispositionTerm = defaultDispositionTerm;
        this.accountRepository      = accountRepository;
        this.dispositionRepository  = dispositionRepository;
        this.installmentRepository  = installmentRepository;
        this.balanceEventRepository = balanceEventRepository;
        this.amortizationEngine     = amortizationEngine;
        this.speiDispatch           = speiDispatch;
        this.eventPublisher         = eventPublisher;
        this.configResolver         = configResolver;
    }

    /**
     * Activation flow (config-driven):
     * resolve config version → PENDING_ACTIVATION → AmortizationSchedule (if config says so)
     *   → Disposition(config dispositionType) → SPEI → DispositionCompleted → ACTIVE
     *   → CreditAccountActivated published.
     */
    @Override
    public CreditAccount activate(CreateCreditAccountCommand cmd) {

        // Idempotency: skip if already created for this contract
        var existing = accountRepository.findByContractId(cmd.contractId());
        if (existing.isPresent()) {
            log.info("CreditAccount already exists for contractId={} — skipping", cmd.contractId());
            return existing.get();
        }

        // 0. Resolve the pinned product config version (with degraded fallback)
        ProductConfigVersion config = configResolver.resolveForActivation(cmd);
        Capabilities caps = config.getCapabilities();
        log.info("Resolved config code={} version={} degraded={} amortization={} freq={}",
                config.getProductCode(), config.getProductVersion(), config.isDegraded(),
                config.getAmortizationType(), config.getPaymentFrequency());

        // 1. Create account (PENDING_ACTIVATION) pinned to the resolved version
        CreditAccount account = CreditAccount.fromSnapshot(
                cmd.contractId(), cmd.contractNumber(), cmd.obligorPartyId(),
                cmd.productCode(), config.getProductVersion(), cmd.productType(), cmd.productBehavior(),
                cmd.approvedAmount(), cmd.approvedLine(), cmd.assignedTerm(),
                cmd.nominalRate(), cmd.moratoriumRate(), cmd.amortizationType(),
                cmd.openingFeeRate(), cmd.clabeAccount(), cmd.riskTier(),
                cmd.beneficiaryName(), cmd.beneficiaryTaxId());

        // El IVA se congela ANTES de generar el calendario, que es quien lo usa. Sellarlo junto con
        // la sucursal —más abajo, al activar— llegaría tarde: el plan ya estaría escrito con la tasa
        // equivocada y corregirlo después obligaría a regenerarlo, que es reescribir lo que el
        // cliente firmó.
        account.sealVatRate(cmd.vatRate() != null ? cmd.vatRate() : ivaPorDefecto);

        accountRepository.save(account);
        log.info("CreditAccount created accountId={} contractId={} status=PENDING_ACTIVATION vatRate={}",
                account.getCreditAccountId(), cmd.contractId(), account.getVatRate());

        // 2. Generate amortisation schedule when the config enables it (AE-01)
        if (caps.hasAmortizationSchedule() && cmd.assignedTerm() != null) {
            UUID scheduleId = account.getCreditAccountId();
            List<Installment> schedule = amortizationEngine.generate(
                    scheduleId,
                    cmd.approvedAmount(),
                    cmd.nominalRate(),
                    cmd.assignedTerm(),
                    config.getAmortizationType() != null ? config.getAmortizationType() : cmd.amortizationType(),
                    config.getPaymentFrequency(),
                    LocalDate.now().plusMonths(1),
                    account.getVatRate());
            installmentRepository.saveAll(schedule);
            log.info("AmortizationSchedule generated scheduleId={} method={} freq={} installments={}",
                    scheduleId, config.getAmortizationType(), config.getPaymentFrequency(), schedule.size());
        }

        // 3-4. Initial disposition — skipped for revolving (PL-01: opens at zero balance,
        // real dispositions come later via ProcessDispositionUseCase, wallet-initiated).
        // Non-revolving disburses in full: la disposición se crea y queda en PROCESSING. El dinero
        // NO sale aquí — antes se despachaba SPEI dentro de la transacción y se marcaba COMPLETED
        // sin que saliera un peso (§6.4). Ahora la orden de pago viaja en el hecho hacia
        // disbursement-service, y la disposición se completa cuando vuelve disbursement.completed.
        BigDecimal disbursementAmount = resolveAmount(cmd, caps);
        CreditAccountActivatedEvent.DisbursementInstruction instruction = null;
        if (disbursementAmount.signum() > 0) {
            Disposition disposition = Disposition.create(
                    account.getCreditAccountId(), resolveDispositionType(caps), disbursementAmount, null);
            disposition.markProcessing();
            dispositionRepository.save(disposition);

            instruction = new CreditAccountActivatedEvent.DisbursementInstruction(
                    disposition.getDispositionId(),
                    null,                                   // companyId: lo resuelve disbursement (routing)
                    disposition.getDispositionType().name(),
                    disbursementAmount,
                    "MXN",
                    account.getBeneficiaryName(),
                    account.getClabeAccount(),
                    "40",                                   // CLABE
                    account.getBeneficiaryTaxId(),
                    null,                                   // institución: derivable de la CLABE
                    null,                                   // referencia numérica: la asigna el conector
                    "DISPOSICION DE CREDITO");
            log.info("Disposition PROCESSING (awaiting disbursement) dispositionId={} type={} amount={}",
                    disposition.getDispositionId(), disposition.getDispositionType(), disbursementAmount);
        }

        // 5. Activate (PL-02 — la deuda nace aquí; el desembolso confirma después)
        //
        // La sucursal se sella JUSTO ANTES de activar, para que el evento de alta ya la lleve: es ese
        // evento el que da de alta el crédito en contabilidad, y una póliza de alta sin sucursal no
        // se puede corregir después sin reescribir el folio de la serie.
        //
        // Aquí se sellaba `cmd.promoterCode()`, que es otra cosa: el distribuidor que respalda el
        // crédito, no la sucursal que lo colocó. Como el promoterCode que llega es un UUID de 36
        // caracteres y la columna admite 40, no fallaba nada — escribía un dato equivocado que
        // contabilidad consume como si fuera una sucursal. Y el daño se volvía permanente: la
        // reconciliación del backoffice, que resuelve la sucursal contra sales-org, salta toda
        // cuenta que YA tenga originUnitCode, así que el único mecanismo capaz de repararlo daba
        // por bueno el UUID y le atribuía pólizas a una sucursal inexistente.
        //
        // Sin `originUnitCode` la cuenta nace sin sellar —igual que el resto de la cartera, que
        // nunca lo traía porque este sello sólo se disparaba cuando había promotor— y vuelve a ser
        // reparable por esa reconciliación. Sellarla de verdad en el alta exige resolver la
        // sucursal antes de publicar, y eso es trabajo aparte.
        account.sealOriginUnit(cmd.originUnitCode());
        account.activate(disbursementAmount);
        accountRepository.save(account);

        // 6. Publish CreditAccountActivated (con la instrucción de desembolso si hay dinero que mover)
        eventPublisher.publishCreditAccountActivated(new CreditAccountActivatedEvent(
                account.getCreditAccountId(),
                account.getContractId(),
                account.getObligorPartyId(),
                account.getProductType(),
                account.getProductBehavior(),
                account.getNominalRate(),
                account.getMoratoriumRate(),
                account.getOpeningFeeRate(),
                account.getPrincipalBalance(),
                account.getCreditLimit(),
                account.getRiskTier(),
                account.getActivatedAt(),
                cmd.promoterCode(),
                account.getOriginUnitCode(),
                instruction));

        log.info("CreditAccount activated and event published accountId={}", account.getCreditAccountId());
        return account;
    }

    @Override
    @Transactional(readOnly = true)
    public CreditAccount getById(UUID creditAccountId) {
        return accountRepository.findById(creditAccountId)
                .orElseThrow(() -> new CreditAccountNotFoundException(creditAccountId.toString()));
    }

    /**
     * Disbursement amount at origination time.
     * PL-01: revolving accounts open with zero balance — the full credit limit stays
     * available, and principal only grows later via real dispositions (wallet-initiated,
     * see ProcessDispositionUseCase). Non-revolving (installment / single-disposition
     * products) still disburse the full approved amount immediately — that is correct
     * for a term loan, which has no later "disposition" concept.
     */
    private BigDecimal resolveAmount(CreateCreditAccountCommand cmd, Capabilities caps) {
        if (caps.hasCreditLimit()) {
            return BigDecimal.ZERO;
        }
        return cmd.approvedAmount() != null ? cmd.approvedAmount() : BigDecimal.ZERO;
    }

    private DispositionType resolveDispositionType(Capabilities caps) {
        String dt = caps.dispositionType();
        if (dt == null) return DispositionType.SELF_USE;
        try {
            return DispositionType.valueOf(dt.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown dispositionType '{}' in config — defaulting to SELF_USE", dt);
            return DispositionType.SELF_USE;
        }
    }

    /**
     * Wallet-initiated disposition (wallet.disposition-requested). SELF_USE stays on the
     * platform (no SPEI — Wallet credits its own walletBalance from DispositionCompleted);
     * THIRD_PARTY_CREDIT/PAYROLL dispatch a real SPEI to payeeAccount, same as the
     * origination-time flow.
     */
    @Override
    public void process(ProcessDispositionCommand cmd) {
        if (cmd.sourceEventId() != null && balanceEventRepository.existsBySourceEventId(cmd.sourceEventId())) {
            log.info("Duplicate disposition event sourceEventId={} — skipping (idempotent)", cmd.sourceEventId());
            return;
        }

        CreditAccount account = accountRepository.findById(cmd.creditAccountId())
                .orElseThrow(() -> new CreditAccountNotFoundException(cmd.creditAccountId().toString()));

        String rejectionReason = validateDisposition(account, cmd.amount());
        if (rejectionReason == null) {
            rejectionReason = validateBeneficiary(account, cmd);
        }
        if (rejectionReason != null) {
            log.warn("Disposition rejected creditAccountId={} amount={} reason={}",
                    cmd.creditAccountId(), cmd.amount(), rejectionReason);
            eventPublisher.publishDispositionRejected(new DispositionRejectedEvent(
                    cmd.sourceEventId(), cmd.creditAccountId(), cmd.amount(), rejectionReason));
            return;
        }

        DispositionType type = parseDispositionType(cmd.dispositionType());
        Disposition disposition = Disposition.create(
                cmd.creditAccountId(), type, cmd.amount(), cmd.beneficiaryPartyId(), cmd.sourceEventId());
        disposition.markProcessing();
        dispositionRepository.save(disposition);

        String externalRef = type == DispositionType.SELF_USE
                ? "WALLET-CREDIT"
                : speiDispatch.dispatch(disposition.getDispositionId(), cmd.amount(), cmd.payeeAccount());
        disposition.complete(externalRef);
        dispositionRepository.save(disposition);

        // El calendario de ESTA disposición.
        //
        // Una revolvente no tiene un plazo; lo tiene cada disposición, y por eso el calendario
        // cuelga de la disposición y no de la cuenta: `scheduleId = dispositionId`. Es la mecánica
        // de una tarjeta con compras a meses —la línea vive, cada compra se amortiza por su
        // cuenta— y es lo que le da a una revolvente algo que vencer. Sin esto, una línea no podía
        // caer en mora nunca: el envejecido busca cuotas vencidas y no había ninguna que buscar.
        generarCalendarioDeDisposicion(account, disposition, cmd.termPeriods());

        account.applyDisposition(cmd.amount());
        accountRepository.save(account);

        balanceEventRepository.save(BalanceEvent.record(
                account.getCreditAccountId(), cmd.sourceEventId(), "DISPOSITION_" + type, cmd.amount(), account));

        eventPublisher.publishBalanceUpdated(new BalanceUpdatedEvent(
                account.getCreditAccountId(), account.getObligorPartyId(),
                account.getPrincipalBalance(), account.getAccruedInterestBalance(),
                account.getPenaltyBalance(), account.getAvailableCredit(), account.getTotalDebt(),
                "DISPOSITION_" + type, account.getStatus().name(), account.getBalanceVersion(),
                account.getOriginUnitCode()));

        eventPublisher.publishDispositionCompleted(new DispositionCompletedEvent(
                disposition.getDispositionId(), account.getCreditAccountId(), account.getObligorPartyId(),
                cmd.amount(), type.name(), account.getAvailableCredit(), account.getBalanceVersion()));

        log.info("Disposition processed dispositionId={} creditAccountId={} type={} amount={} externalRef={}",
                disposition.getDispositionId(), account.getCreditAccountId(), type, cmd.amount(), externalRef);
    }

    /**
     * disbursement.completed — el pago salió y hay evidencia. La disposición pasa de PROCESSING a
     * COMPLETED con el externalRef real y se publica disposition-completed: el mismo hecho que wallet
     * y notifications ya consumen, ahora honesto (antes se publicaba con "SPEI-STUB-…" antes de que
     * saliera un peso, §8.5). Idempotente: un evento repetido sobre una disposición ya terminal se ignora.
     */
    @Override
    public void onDisbursementCompleted(UUID dispositionId, String externalRef) {
        Disposition disposition = dispositionRepository.findById(dispositionId).orElse(null);
        if (disposition == null) {
            log.warn("disbursement.completed sin disposición conocida dispositionId={} — ignorado", dispositionId);
            return;
        }
        if (disposition.getStatus() != DispositionStatus.PROCESSING) {
            log.info("disbursement.completed sobre disposición no-PROCESSING dispositionId={} status={} — idempotente",
                    dispositionId, disposition.getStatus());
            return;
        }

        disposition.complete(externalRef);
        dispositionRepository.save(disposition);

        CreditAccount account = accountRepository.findById(disposition.getCreditAccountId()).orElse(null);
        if (account == null) {
            log.warn("disbursement.completed: cuenta ausente creditAccountId={} — disposición completada sin evento",
                    disposition.getCreditAccountId());
            return;
        }

        eventPublisher.publishDispositionCompleted(new DispositionCompletedEvent(
                disposition.getDispositionId(), account.getCreditAccountId(), account.getObligorPartyId(),
                disposition.getAmount(), disposition.getDispositionType().name(),
                account.getAvailableCredit(), account.getBalanceVersion()));

        log.info("Disposition COMPLETED por disbursement dispositionId={} externalRef={}",
                dispositionId, externalRef);
    }

    /**
     * disbursement.failed — el dinero no va a llegar. La disposición pasa a FAILED. La reversa del
     * principal contabilizado en la activación (la deuda nació al activar) queda como seguimiento:
     * exige una decisión de dominio sobre el estado de la cuenta y no se improvisa aquí.
     */
    @Override
    public void onDisbursementFailed(UUID dispositionId, String failureCode, String failureReason) {
        Disposition disposition = dispositionRepository.findById(dispositionId).orElse(null);
        if (disposition == null) {
            log.warn("disbursement.failed sin disposición conocida dispositionId={} — ignorado", dispositionId);
            return;
        }
        if (disposition.getStatus() != DispositionStatus.PROCESSING) {
            log.info("disbursement.failed sobre disposición no-PROCESSING dispositionId={} status={} — idempotente",
                    dispositionId, disposition.getStatus());
            return;
        }
        disposition.fail();
        dispositionRepository.save(disposition);
        log.warn("Disposition FAILED por disbursement dispositionId={} code={} reason={}",
                dispositionId, failureCode, failureReason);
    }

    /** CP-04 (terminal/suspended), CP-05 (single disposition for non-revolving), CP-03 (available credit). */
    /**
     * Quién recibe la colocación.
     *
     * <p>Dos reglas, y la segunda es la que evita que el modelo se deshaga:
     *
     * <ul>
     *   <li>Una colocación a tercero <b>necesita</b> beneficiario. Sin él no se sabe a quién se le
     *       colocó, y la disposición sería indistinguible de un retiro de la distribuidora.</li>
     *   <li>El beneficiario <b>no puede ser la propia distribuidora</b>. Si lo fuera, no habría
     *       colocación: sería la distribuidora usando su línea para sí misma, y eso es un crédito
     *       de uso propio, con su producto, su expediente y su riesgo — no una venta a un cliente
     *       final. Permitirlo dejaría a alguien cobrándose comisión por prestarse a sí mismo.</li>
     * </ul>
     */
    private String validateBeneficiary(CreditAccount account, ProcessDispositionCommand cmd) {
        if (parseDispositionType(cmd.dispositionType()) != DispositionType.THIRD_PARTY_CREDIT) {
            return null;
        }
        if (cmd.beneficiaryPartyId() == null) {
            return "BENEFICIARY_REQUIRED";
        }
        if (cmd.beneficiaryPartyId().equals(account.getObligorPartyId())) {
            return "BENEFICIARY_CANNOT_BE_OBLIGOR";
        }
        return null;
    }

    /**
     * Genera el calendario de una disposición de línea revolvente.
     *
     * <p>El plazo lo trae la colocación; si no viene, cae al del producto. La tasa y el método de
     * amortización son los de la cuenta —una disposición no renegocia el precio de la línea—.
     *
     * <p>No aplica a los amortizables: ésos generan su calendario una vez, al activarse, y su
     * disposición es el desembolso inicial. Generarles otro aquí duplicaría la deuda.
     */
    private void generarCalendarioDeDisposicion(CreditAccount account, Disposition disposition,
                                                 Integer termPeriods) {
        if (!account.isRevolving()) return;

        int plazo = termPeriods != null && termPeriods > 0
                ? termPeriods
                : (account.getAssignedTerm() != null && account.getAssignedTerm() > 0
                        ? account.getAssignedTerm()
                        : defaultDispositionTerm);

        // La cadencia sale de la configuración del producto, no de una constante. Una línea de
        // distribuidora se cobra por quincenas y una revolvente de nómina no tiene por qué usar la
        // misma: con "MONTHLY" fijo, un plazo de 24 quincenas producía 24 cuotas mensuales —el
        // doble de tiempo— y los vencimientos caían donde ningún contrato los puso.
        String cadencia = configResolver
                .resolveForAccount(account.getProductCode(), account.getProductVersion())
                .map(ProductConfigVersion::getPaymentFrequency)
                .filter(f -> f != null && !f.isBlank())
                .orElse("MONTHLY");

        List<Installment> calendario = amortizationEngine.generate(
                disposition.getDispositionId(),          // el calendario cuelga de la disposición
                disposition.getAmount(),
                account.getNominalRate(),
                plazo,
                account.getAmortizationType() != null ? account.getAmortizationType() : "FRENCH",
                cadencia,
                AmortizationEngine.firstDueDate(LocalDate.now(), cadencia),
                account.getVatRate());
        installmentRepository.saveAll(calendario);

        log.info("Calendario de disposición generado dispositionId={} creditAccountId={} plazo={} cadencia={} cuotas={}",
                disposition.getDispositionId(), account.getCreditAccountId(), plazo, cadencia, calendario.size());
    }

    private String validateDisposition(CreditAccount account, BigDecimal amount) {
        if (account.getStatus() == CreditAccountStatus.SUSPENDED) {
            return "ACCOUNT_SUSPENDED";
        }
        if (account.getStatus() != CreditAccountStatus.ACTIVE) {
            return "ACCOUNT_NOT_ACTIVE: status=" + account.getStatus();
        }
        if (!account.isRevolving()) {
            boolean alreadyDisposed = dispositionRepository.findByCreditAccountId(account.getCreditAccountId())
                    .stream().anyMatch(d -> d.getStatus() == DispositionStatus.COMPLETED);
            if (alreadyDisposed) {
                return "SINGLE_DISPOSITION_ONLY: product already has a completed disposition";
            }
            return null;
        }
        if (account.getAvailableCredit() != null && amount.compareTo(account.getAvailableCredit()) > 0) {
            return "INSUFFICIENT_AVAILABLE_CREDIT: amount=" + amount
                    + " availableCredit=" + account.getAvailableCredit();
        }
        return null;
    }

    private DispositionType parseDispositionType(String dispositionType) {
        try {
            return DispositionType.valueOf(dispositionType.toUpperCase());
        } catch (Exception e) {
            log.warn("Unknown dispositionType '{}' from wallet request — defaulting to SELF_USE", dispositionType);
            return DispositionType.SELF_USE;
        }
    }

    /**
     * collections.agreement-executed. QUITA_PARCIAL reduces debt (penalty→interest→principal,
     * same hierarchy as a payment but with no cash). RESTRUCTURE only updates the account's own
     * rate/term — see CreditAccount.applyRestructure for the documented scope limit (no schedule
     * regeneration, no Charges rate sync).
     */
    @Override
    public void process(ProcessAgreementExecutedCommand cmd) {
        if (cmd.sourceEventId() != null && balanceEventRepository.existsBySourceEventId(cmd.sourceEventId())) {
            log.info("Duplicate agreement-executed event sourceEventId={} — skipping (idempotent)", cmd.sourceEventId());
            return;
        }

        CreditAccount account = accountRepository.findById(cmd.creditAccountId())
                .orElseThrow(() -> new CreditAccountNotFoundException(cmd.creditAccountId().toString()));

        if ("QUITA_PARCIAL".equals(cmd.type())) {
            account.applyForgiveness(cmd.forgivenAmount());
            accountRepository.save(account);

            balanceEventRepository.save(BalanceEvent.record(account.getCreditAccountId(), cmd.sourceEventId(),
                    "AGREEMENT_QUITA_PARCIAL", cmd.forgivenAmount().negate(), account));

            eventPublisher.publishBalanceUpdated(new BalanceUpdatedEvent(
                    account.getCreditAccountId(), account.getObligorPartyId(),
                    account.getPrincipalBalance(), account.getAccruedInterestBalance(),
                    account.getPenaltyBalance(), account.getAvailableCredit(), account.getTotalDebt(),
                    "AGREEMENT_QUITA_PARCIAL", account.getStatus().name(), account.getBalanceVersion(),
                    account.getOriginUnitCode()));

            log.info("Agreement QUITA_PARCIAL applied creditAccountId={} forgiven={}",
                    cmd.creditAccountId(), cmd.forgivenAmount());
        } else if ("RESTRUCTURE".equals(cmd.type())) {
            account.applyRestructure(cmd.newNominalRate(), cmd.newTermMonths());
            accountRepository.save(account);
            log.info("Agreement RESTRUCTURE applied creditAccountId={} newRate={} newTerm={}",
                    cmd.creditAccountId(), cmd.newNominalRate(), cmd.newTermMonths());
        } else {
            log.warn("Unknown CollectionAgreement type '{}' for creditAccountId={} — ignored",
                    cmd.type(), cmd.creditAccountId());
        }
    }
}
