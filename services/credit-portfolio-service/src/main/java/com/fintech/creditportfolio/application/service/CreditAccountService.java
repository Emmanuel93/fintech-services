package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.domain.config.SemiMonthlyCalendar;
import com.fintech.creditportfolio.domain.config.BillingCycle;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import com.fintech.creditportfolio.application.port.out.PartyVerificationRepository;
import com.fintech.creditportfolio.application.port.out.BusinessCalendarRepository;
import com.fintech.creditportfolio.application.port.out.PaymentReferenceSequence;
import com.fintech.creditportfolio.application.CreateCreditAccountCommand;
import com.fintech.creditportfolio.application.ProcessAgreementExecutedCommand;
import com.fintech.creditportfolio.application.ProcessDispositionCommand;
import com.fintech.creditportfolio.application.port.in.ActivateCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.in.FindCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.in.ProcessAgreementExecutedUseCase;
import com.fintech.creditportfolio.application.port.in.ProcessDispositionUseCase;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.PaymentReference;
import com.fintech.creditportfolio.domain.CreditAccountNotFoundException;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionStatus;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.PartyVerification;
import com.fintech.creditportfolio.domain.DistributorCommissionSchedule;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.PlacementGate;
import com.fintech.creditportfolio.domain.StatementCoverage;
import java.util.ArrayList;
import com.fintech.creditportfolio.domain.event.AccountRestructuredEvent;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.PaymentOptions;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.creditportfolio.domain.event.DispositionRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.fintech.shared.time.BusinessClock;

/**
 * Config-driven account activation. Behaviour (amortisation, disposition type, credit limit)
 * is resolved from the pinned product config version — not from a hardcoded behavior string.
 */
@Service
@Transactional
public class CreditAccountService implements ActivateCreditAccountUseCase, FindCreditAccountUseCase,
        ProcessDispositionUseCase, ProcessAgreementExecutedUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountService.class);

    private final CreditAccountRepository accountRepository;
    private final DispositionRepository dispositionRepository;
    private final InstallmentRepository installmentRepository;
    private final BalanceEventRepository balanceEventRepository;
    private final com.fintech.creditportfolio.application.port.out.PartyVerificationRepository partyVerificationRepository;
    private final AmortizationEngine amortizationEngine;
    private final BankingCalendar bankingCalendar;
    private final PaymentReferenceSequence paymentReferences;

    /** IVA nacional, para los créditos cuya sucursal todavía no se resuelve al originar. */
    @org.springframework.beans.factory.annotation.Value("${fintech.credit-portfolio.vat-rate:0.16}")
    private BigDecimal ivaPorDefecto = AmortizationEngine.IVA_NACIONAL;
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

    /** Without the published calendar (tests): only weekends are non-business days. */
    public CreditAccountService(CreditAccountRepository accountRepository,
                                 DispositionRepository dispositionRepository,
                                 InstallmentRepository installmentRepository,
                                 BalanceEventRepository balanceEventRepository,
                                 AmortizationEngine amortizationEngine,
                                 CreditPortfolioEventPublisher eventPublisher,
                                 ProductConfigResolver configResolver,
                                 PartyVerificationRepository partyVerificationRepository,
                                 int defaultDispositionTerm) {
        this(accountRepository, dispositionRepository, installmentRepository, balanceEventRepository,
                amortizationEngine, eventPublisher, configResolver, partyVerificationRepository,
                new BankingCalendar(new BusinessCalendarRepository() {
                    @Override public void upsert(String c, LocalDate d, boolean b, String l) { }
                    @Override public Map<LocalDate, Boolean> exceptions(String c) { return Map.of(); }
                }), new InMemoryPaymentReferences(), defaultDispositionTerm);
    }

    /** For tests without a database: consecutive references from the first of the range. */
    static final class InMemoryPaymentReferences implements PaymentReferenceSequence {
        private final AtomicLong next = new AtomicLong(PaymentReference.MIN_BODY);
        @Override public long nextBody() { return next.getAndIncrement(); }
    }

    @Autowired
    public CreditAccountService(CreditAccountRepository accountRepository,
                                 DispositionRepository dispositionRepository,
                                 InstallmentRepository installmentRepository,
                                 BalanceEventRepository balanceEventRepository,
                                 AmortizationEngine amortizationEngine,
                                 CreditPortfolioEventPublisher eventPublisher,
                                 ProductConfigResolver configResolver,
                                 PartyVerificationRepository partyVerificationRepository,
                                 BankingCalendar bankingCalendar,
                                 PaymentReferenceSequence paymentReferences,
                                 @Value("${fintech.credit-portfolio.default-disposition-term:12}")
                                 int defaultDispositionTerm) {
        this.bankingCalendar = bankingCalendar;
        this.paymentReferences = paymentReferences;
        this.defaultDispositionTerm = defaultDispositionTerm;
        this.accountRepository      = accountRepository;
        this.dispositionRepository  = dispositionRepository;
        this.installmentRepository  = installmentRepository;
        this.balanceEventRepository = balanceEventRepository;
        this.amortizationEngine     = amortizationEngine;
        this.eventPublisher         = eventPublisher;
        this.configResolver         = configResolver;
        this.partyVerificationRepository = partyVerificationRepository;
    }

    /**
     * The account, locked for this transaction and read after the lock. Every writer of an account goes through
     * here or through the balance applier: a writer that read it before another committed and saved it after
     * would put back the balances it read. That is how an opening fee reached the ledger and not the balances on
     * the clean run of 2026-11-02.
     */
    private Optional<CreditAccount> lockedAccount(UUID creditAccountId) {
        return accountRepository.lockAndLoad(creditAccountId).or(() -> accountRepository.findById(creditAccountId));
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
        account.sealCat(cmd.cat());

        accountRepository.save(account);
        log.info("CreditAccount created accountId={} contractId={} status=PENDING_ACTIVATION vatRate={}",
                account.getCreditAccountId(), cmd.contractId(), account.getVatRate());

        // BK-29 · BNPL corre el arranque del pago, y con él el PLAN ENTERO.
        //
        // Correr sólo el devengo y dejar el plan quieto es el error obvio y silencioso: el cliente
        // no pagaría interés pero su primera cuota vencería igual, así que el «compra ahora, paga
        // después» le llegaría con una cuota exigible antes de haber empezado a pagar.
        LocalDate arranqueBnpl = bnplStartDate(caps.options(), cmd.bnplDeferralDays());

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
                    AmortizationEngine.firstDueDate(arranqueBnpl, config.getPaymentFrequency()),
                    account.getVatRate());
            // Due on business days: one that falls on a weekend or a bank holiday is due the next business day.
            bankingCalendar.followBusinessDays(schedule);
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
            // Kept with the disposition: a later attempt of the payment sends this same instruction (D63).
            disposition.recordPayee(account.getBeneficiaryName(), account.getClabeAccount(), "40",
                    account.getBeneficiaryTaxId(), "DISPOSICION DE CREDITO");
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
        // The loan's payment reference: the numeric reference its owner types in a SPEI transfer to pay it.
        account.assignPaymentReference(PaymentReference.of(paymentReferences.nextBody()));
        account.activate(disbursementAmount);
        accountRepository.save(account);

        // The debt is born here, so the ledger starts here too. Until 2026-11-02 the activation set the
        // principal without a balance_events row: every loan's ledger began after the money was lent and
        // the sum of its entries could never equal its balances (100 loans, $6.38 M). The subledger has
        // to carry every balance change, or nothing downstream (statements, accounting) can reconcile.
        if (disbursementAmount.signum() > 0) {
            balanceEventRepository.save(BalanceEvent.record(account.getCreditAccountId(),
                    "activation:" + account.getCreditAccountId(), "ACTIVATION_DISBURSEMENT", disbursementAmount,
                    account, BusinessClock.current().today()));
        }

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
                instruction,
                // La cadencia y el plazo viajan para que el cierre derive su calendario de corte
                // sin tener que consultar el calendario de esta cuenta en línea.
                config.getPaymentFrequency(),
                account.getAssignedTerm(),
                // BNPL: charges no devenga antes de esta fecha. Nulo cuando el producto no lo usa.
                arranqueBnpl.isAfter(BusinessClock.current().today()) ? arranqueBnpl : null,
                // The product's billing cycle, pinned to the account the day it opens.
                config.getBillingCycle()).withPaymentReference(account.getPaymentReference()));

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
     * El tipo de disposición del producto de esta cuenta.
     *
     * <p>Un producto de uso propio dispone siempre para su titular; una línea de distribuidor
     * dispone siempre a la beneficiaria. <b>No es una elección de cada petición</b>, y tratarla
     * como tal es lo que permitía desviar el dinero.
     *
     * <p>Si la configuración del producto no se puede resolver, <b>se detiene</b>. Un default aquí
     * sería el mismo agujero con otro disfraz: adivinar el tipo es adivinar a quién se le manda el
     * dinero, y equivocarse no se descubre hasta que el titular reclama.
     */
    /**
     * Desde cuándo empieza a correr el crédito.
     *
     * <p>Sin BNPL es hoy. Con BNPL es hoy más los días que el producto permite correr, acotados por
     * {@code bnplMaxDeferralDays}: un tope que no se aplica no es un tope.
     */
    /** Delega en la configuración, que es quien sabe qué significa cada uno de sus campos. */
    private LocalDate bnplStartDate(PaymentOptions opciones, Integer solicitados) {
        LocalDate arranque = opciones.bnplStartDate(BusinessClock.current().today(), solicitados);
        if (solicitados != null && solicitados > 0 && arranque.equals(BusinessClock.current().today())) {
            log.warn("BNPL pedido ({} días) sobre un producto que no lo admite — se ignora", solicitados);
        }
        return arranque;
    }

    /** Las opciones de pago del producto de esta cuenta. Nunca nulas: sin configuración, ninguna. */
    private PaymentOptions options(CreditAccount account) {
        return configResolver
                .resolveForAccount(account.getProductCode(), account.getProductVersion())
                .map(ProductConfigVersion::getCapabilities)
                .map(Capabilities::options)
                .orElseGet(PaymentOptions::none);
    }

    private DispositionType productType(CreditAccount account) {
        return configResolver
                .resolveForAccount(account.getProductCode(), account.getProductVersion())
                .map(ProductConfigVersion::getCapabilities)
                .map(this::resolveDispositionType)
                .orElseThrow(() -> new IllegalStateException(
                        "Sin configuración del producto " + account.getProductCode() + " v"
                                + account.getProductVersion() + ": no se puede decidir a quién va "
                                + "la disposición de la cuenta " + account.getCreditAccountId()));
    }

    /**
     * A qué cuenta bancaria va el dinero.
     *
     * <p><b>Siempre sale a una cuenta bancaria</b>, también en uso propio: el saldo a favor en
     * monedero está fuera de alcance. En uso propio el destino es el titular —cuya CLABE ya
     * guarda la cuenta— y en una línea de distribuidor, la beneficiaria.
     *
     * <p><b>El destino es la terna (nombre, CLABE, RFC) y sale entera de un mismo lado.</b> Se
     * decidía la CLABE según el tipo pero el nombre y el RFC salían siempre de la cuenta, así que
     * una colocación mandaba el dinero a la CLABE de la beneficiaria <i>a nombre de la
     * distribuidora</i>: la orden STP y el CEP decían «LAURA MÉNDEZ SOTO / MESL930326XXX» sobre
     * una cuenta que es de otra persona. El banco receptor puede devolverlo por no coincidir, y si
     * no lo devuelve es peor: el comprobante regulatorio afirma un pago a quien no lo recibió.
     *
     * <p>Por eso, cuando la CLABE es la del receptor, el nombre y el RFC también lo son. Uso propio
     * conserva los del titular, que es quien recibe. Que una colocación sin nombre de receptor no
     * llegue hasta aquí lo garantiza {@link #validateBeneficiary}.
     */
    private Destino destinationOf(CreditAccount account,
                              ProcessDispositionCommand cmd,
                              DispositionType type) {
        boolean aTercero = type != DispositionType.SELF_USE;
        boolean clabeDelReceptor = aTercero && cmd.payeeAccount() != null;
        String payeeClabe = clabeDelReceptor
                ? cmd.payeeAccount()
                : account.getClabeAccount();
        if (payeeClabe == null || payeeClabe.isBlank()) {
            throw new IllegalStateException("La disposición " + account.getCreditAccountId()
                    + " no tiene cuenta de destino: no hay adónde mandar el dinero");
        }
        // Nómina puede traer CLABE sin nombre —el receptor es el propio titular—; una colocación
        // no, y eso ya lo rechazó la validación.
        String nombreDelReceptor = clabeDelReceptor ? blankToNull(cmd.payeeName()) : null;
        if (nombreDelReceptor != null) {
            return new Destino(nombreDelReceptor, payeeClabe, "40", blankToNull(cmd.payeeTaxId()));
        }
        return new Destino(account.getBeneficiaryName(), payeeClabe, "40",
                account.getBeneficiaryTaxId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Adónde va el dinero. Interno: el evento viaja plano porque así lo declara quien lo consume. */
    private record Destino(String beneficiaryName, String beneficiaryAccount,
                           String beneficiaryAccountType, String beneficiaryTaxId) {}

    /**
     * Disposición pedida sobre una línea ya activa (wallet.disposition-requested).
     *
     * <p><b>Aquí no sale dinero.</b> Se valida, se registra la disposición en PROCESSING y se
     * publica {@code disposition-authorized}. Quien paga es {@code disbursement}, y la disposición
     * se completa cuando hay evidencia del proveedor.
     */
    @Override
    public void process(ProcessDispositionCommand cmd) {
        if (cmd.sourceEventId() != null && balanceEventRepository.existsBySourceEventId(cmd.sourceEventId())) {
            log.info("Duplicate disposition event sourceEventId={} — skipping (idempotent)", cmd.sourceEventId());
            return;
        }

        CreditAccount account = lockedAccount(cmd.creditAccountId())
                .orElseThrow(() -> new CreditAccountNotFoundException(cmd.creditAccountId().toString()));

        String rejectionReason = validateDisposition(account, cmd.amount(), cmd.termPeriods());
        if (rejectionReason == null) {
            rejectionReason = validateBeneficiary(account, cmd);
        }
        // El AML devuelve además el veredicto que LEYÓ del receptor, para que la constancia sea
        // exactamente la fila con la que se decidió y no una segunda lectura que podría diferir.
        AmlCheck aml = rejectionReason == null ? checkAml(account, cmd) : AmlCheck.notRun();
        if (rejectionReason == null) {
            rejectionReason = aml.rejectionReason();
        }
        if (rejectionReason != null) {
            log.warn("Disposition rejected creditAccountId={} amount={} reason={}",
                    cmd.creditAccountId(), cmd.amount(), rejectionReason);
            eventPublisher.publishDispositionRejected(new DispositionRejectedEvent(
                    cmd.sourceEventId(), cmd.creditAccountId(), account.getObligorPartyId(), cmd.amount(), rejectionReason));
            return;
        }

        // BK-13 · El tipo lo decide el PRODUCTO, no quien pide. Cuando venía en la petición, una
        // solicitud sobre una DISTRIBUTOR_LINE que mandara "SELF_USE" —o un valor basura, que caía
        // al mismo default silencioso— acreditaba el dinero a la distribuidora en vez de mandarlo a
        // la beneficiaria. No era un detalle de modelado: era dinero saliendo a la persona
        // equivocada, alcanzable sin siquiera mentir a propósito.
        DispositionType type = productType(account);
        Disposition disposition = Disposition.create(
                cmd.creditAccountId(), type, cmd.amount(), cmd.beneficiaryPartyId(), cmd.sourceEventId());
        disposition.markProcessing();

        // La foto de lo que se sabía de listas en este instante. Se guarda aunque el veredicto sea
        // limpio —sobre todo si es limpio—: es lo que permite decir después «a las 10:06 su
        // veredicto era CLEARED contra la lista del día 3» en vez de tener que reconstruirlo.
        recordAmlSnapshot(disposition, aml);

        // Where the money goes, decided once and kept with the disposition: a later attempt sends the same (D63).
        Destino destino = destinationOf(account, cmd, type);
        disposition.recordPayee(destino.beneficiaryName(), destino.beneficiaryAccount(),
                destino.beneficiaryAccountType(), destino.beneficiaryTaxId(), "DISPOSICION DE CREDITO");

        dispositionRepository.save(disposition);

        // BK-11 · Aquí NO sale dinero. La disposición queda PROCESSING y se publica el hecho:
        // `disposition-authorized`, que `disbursement` ya escuchaba y nadie emitía. Antes esto
        // llamaba a un stub que devolvía "SPEI-STUB-…", marcaba la disposición COMPLETED y hacía
        // que contabilidad asentara 1201 → 1101 —salida de caja— de dinero que nunca salió: el
        // activo crecía y el banco bajaba contra nada.
        //
        // Se completa en `onDisbursementCompleted`, cuando hay evidencia del proveedor.
        eventPublisher.publishDispositionAuthorized(new DispositionAuthorizedEvent(
                disposition.getDispositionId(), account.getCreditAccountId(), null,
                // La unidad de origen es la clave con la que el orquestador resuelve la empresa.
                // Cartera no conoce su catálogo de empresas, pero sí de qué sucursal es la cuenta.
                account.getOriginUnitCode(),
                cmd.amount(), "MXN",
                destino.beneficiaryName(), destino.beneficiaryAccount(),
                destino.beneficiaryAccountType(), destino.beneficiaryTaxId(),
                "DISPOSICION DE CREDITO"));

        // BK-24 · el calendario de ESTA disposición, **si el producto lo genera al disponer**.
        //
        // Una revolvente no tiene un plazo; lo tiene cada disposición. Eso es cierto para el
        // distribuidor, donde el vendedor decide al colocar «a cuántos meses se lo dejas». Para una
        // tarjeta es al revés: la compra nace revolvente pura —exigible entera en la siguiente
        // fecha de pago posterior al corte— y el titular decide diferirla después.
        //
        // Generarlo siempre hacía que una compra con tarjeta naciera ya parcializada al plazo por
        // defecto del producto: ni lo que el cliente pidió ni lo que el corte debía exigirle.
        if (options(account).planPosterior()) {
            disposition.asPureRevolving();
            dispositionRepository.save(disposition);
            log.info("Disposición REVOLVENTE PURA (sin calendario) dispositionId={} — exigible en el corte",
                    disposition.getDispositionId());
        } else {
            generateDispositionSchedule(account, disposition, cmd.termPeriods());
        }

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

        // `disposition-completed` NO se publica aquí. Se publicaba, y era la mentira que sostenía
        // todo lo demás: wallet, notificaciones y contabilidad daban por bueno un pago que aún no
        // había salido. Lo publica `onDisbursementCompleted`, con la evidencia del proveedor.

        log.info("Disposición AUTORIZADA (pendiente de pago) dispositionId={} creditAccountId={} type={} amount={}",
                disposition.getDispositionId(), account.getCreditAccountId(), type, cmd.amount());
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
        if (productType(account) != DispositionType.THIRD_PARTY_CREDIT) {
            return null;
        }
        if (cmd.beneficiaryPartyId() == null) {
            return "BENEFICIARY_REQUIRED";
        }
        if (cmd.beneficiaryPartyId().equals(account.getObligorPartyId())) {
            return "BENEFICIARY_CANNOT_BE_OBLIGOR";
        }
        // La CLABE de la beneficiaria sin su nombre no se completa con el de la distribuidora: eso
        // es exactamente el SPEI a nombre equivocado. Se rechaza antes de tocar la línea.
        if (cmd.payeeAccount() != null && blankToNull(cmd.payeeName()) == null) {
            return "BENEFICIARY_NAME_REQUIRED";
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
    private void generateDispositionSchedule(CreditAccount account, Disposition disposition,
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
        Optional<ProductConfigVersion> config = configResolver
                .resolveForAccount(account.getProductCode(), account.getProductVersion());
        String cadencia = config
                .map(ProductConfigVersion::getPaymentFrequency)
                .filter(f -> f != null && !f.isBlank())
                .orElse("MONTHLY");
        // The fortnight's two cutoff days (the distributor line: the 6th and the 21st): each installment falls on one.
        SemiMonthlyCalendar quincena = BillingCycle.semiMonthlyOf(config.map(ProductConfigVersion::getBillingCycle).orElse(null));

        // The plan starts the day the placement is made. On the fortnight its first installment is the first cutoff
        // after that day, and that first period bears the share of the fortnight the money was held.
        LocalDate hoy = BusinessClock.current().today();
        List<Installment> calendario = amortizationEngine.generate(
                disposition.getDispositionId(),          // el calendario cuelga de la disposición
                disposition.getAmount(),
                account.getNominalRate(),
                plazo,
                account.getAmortizationType() != null ? account.getAmortizationType() : "FRENCH",
                cadencia,
                hoy,
                AmortizationEngine.firstDueDate(hoy, cadencia, quincena),
                account.getVatRate(),
                quincena);
        // A distributor's placement carries her commission on each installment: her flat share of what the client
        // pays, capped at the plan's interest so it never comes out of the capital.
        config.map(ProductConfigVersion::getDistributorCommission)
                .ifPresent(terms -> DistributorCommissionSchedule.plan(calendario, terms));
        installmentRepository.saveAll(calendario);

        log.info("Calendario de disposición generado dispositionId={} creditAccountId={} plazo={} cadencia={} cuotas={}",
                disposition.getDispositionId(), account.getCreditAccountId(), plazo, cadencia, calendario.size());
    }

    private String validateDisposition(CreditAccount account, BigDecimal amount) {
        return validateDisposition(account, amount, null);
    }

    /**
     * CP-04 (terminal/suspended), CP-05 (single disposition for non-revolving), CP-03 (available credit) and, on a
     * line that pays a distributor, the product's ranges and the arrears gate: a placement fits the product's amounts
     * and terms, and the line does not place while a statement of hers is unpaid past its due date (from the first
     * day late until she pays what is overdue, the same day).
     */
    private String validateDisposition(CreditAccount account, BigDecimal amount, Integer termPeriods) {
        if (account.getStatus() == CreditAccountStatus.SUSPENDED) {
            return "ACCOUNT_SUSPENDED";
        }
        if (account.getStatus() == CreditAccountStatus.ACTIVE && account.isRevolving()) {
            Optional<ProductConfigVersion> config = configResolver == null ? Optional.empty()
                    : configResolver.resolveForAccount(account.getProductCode(), account.getProductVersion());
            if (config.map(ProductConfigVersion::getDistributorCommission).isPresent()) {
                String outOfRange = config.get().placementOutOfRange(amount, termPeriods);
                if (outOfRange != null) return outOfRange;
                // Blocked while a statement is unpaid past due; closed for good once collections forgave part of her debt.
                List<BalanceEvent> ledger = balanceEventRepository.paymentsOf(account.getCreditAccountId());
                PlacementGate gate = PlacementGate.evaluate(
                        StatementCoverage.obligationsFrom(installmentRepository.findByScheduleIdOrdered(account.getCreditAccountId())),
                        StatementCoverage.paymentsFrom(ledger), BusinessClock.current().today(), StatementCoverage.quitaOn(ledger));
                if (gate.blocked()) return gate.reason();
            }
        }
        if (account.getStatus() != CreditAccountStatus.ACTIVE) {
            return "ACCOUNT_NOT_ACTIVE: status=" + account.getStatus();
        }
        if (!account.isRevolving()) {
            // D68: in flight counts too. Counting only the completed one let a second disposition through while the
            // first was being paid (or after it failed): two payouts for one loan.
            boolean alreadyDisposed = dispositionRepository.findByCreditAccountId(account.getCreditAccountId())
                    .stream().anyMatch(d -> d.getStatus() == DispositionStatus.COMPLETED
                            || d.getStatus() == DispositionStatus.PROCESSING
                            || d.getStatus() == DispositionStatus.PENDING);
            if (alreadyDisposed) {
                return "SINGLE_DISPOSITION_ONLY: product already has a disposition paid or in flight";
            }
            return null;
        }
        if (account.getAvailableCredit() != null && amount.compareTo(account.getAvailableCredit()) > 0) {
            return "INSUFFICIENT_AVAILABLE_CREDIT: amount=" + amount
                    + " availableCredit=" + account.getAvailableCredit();
        }
        return null;
    }

    /**
     * collections.agreement-executed. QUITA_PARCIAL reduces debt (penalty→interest→principal,
     * same hierarchy as a payment but with no cash). RESTRUCTURE changes rate and term, replaces the
     * unpaid installments of an installment loan with a new plan over the outstanding principal, and
     * publishes {@code account-restructured} so charges accrues at the agreed rate from that day.
     */
    @Override
    public void process(ProcessAgreementExecutedCommand cmd) {
        if (cmd.sourceEventId() != null && balanceEventRepository.existsBySourceEventId(cmd.sourceEventId())) {
            log.info("Duplicate agreement-executed event sourceEventId={} — skipping (idempotent)", cmd.sourceEventId());
            return;
        }

        CreditAccount account = lockedAccount(cmd.creditAccountId())
                .orElseThrow(() -> new CreditAccountNotFoundException(cmd.creditAccountId().toString()));

        if ("QUITA_PARCIAL".equals(cmd.type())) {
            // The forgiveness is measured bucket by bucket (IVA, moratorium, interest, principal, as applyForgiveness
            // allocates it) and travels with its breakdown. Published without it, accounting deduced the split from
            // its own shadow, which does not keep the IVA apart, and credited principal $234.11 more than the
            // portfolio forgave (E27, 2 Nov E2E).
            BigDecimal capAntes = account.getPrincipalBalance();
            BigDecimal intAntes = account.getAccruedInterestBalance();
            BigDecimal morAntes = account.getPenaltyBalance();
            BigDecimal ivaAntes = account.getIvaBalance();
            account.applyForgiveness(cmd.forgivenAmount());
            accountRepository.save(account);
            BigDecimal capDelta = account.getPrincipalBalance().subtract(capAntes);
            BigDecimal intDelta = account.getAccruedInterestBalance().subtract(intAntes);
            BigDecimal morDelta = account.getPenaltyBalance().subtract(morAntes);
            BigDecimal ivaDelta = account.getIvaBalance().subtract(ivaAntes);
            BigDecimal medido = capDelta.add(intDelta).add(morDelta).add(ivaDelta);

            BalanceEvent asiento = BalanceEvent.record(account.getCreditAccountId(), cmd.sourceEventId(),
                    "AGREEMENT_QUITA_PARCIAL", medido, account).withBreakdown(capDelta, intDelta, ivaDelta, morDelta);
            balanceEventRepository.save(asiento);

            eventPublisher.publishBalanceUpdated(new BalanceUpdatedEvent(
                    account.getCreditAccountId(), account.getObligorPartyId(),
                    account.getPrincipalBalance(), account.getAccruedInterestBalance(),
                    account.getPenaltyBalance(), account.getAvailableCredit(), account.getTotalDebt(),
                    "AGREEMENT_QUITA_PARCIAL", account.getStatus().name(), account.getBalanceVersion(),
                    account.getOriginUnitCode(), null,
                    // The IVA travels with the moratorium, as every other movement publishes it (accounting nets both
                    // against 1203), and on its own in the detail.
                    medido, capDelta, intDelta, morDelta.add(ivaDelta), asiento.getValueDate())
                    .withSourceEventId(cmd.sourceEventId()).withDetail(ivaDelta, null));

            log.info("Agreement QUITA_PARCIAL applied creditAccountId={} forgiven={}",
                    cmd.creditAccountId(), cmd.forgivenAmount());
        } else if ("RESTRUCTURE".equals(cmd.type())) {
            BigDecimal tasaAnterior = account.getNominalRate();
            account.applyRestructure(cmd.newNominalRate(), cmd.newTermMonths());
            accountRepository.save(account);
            NewPlan plan = account.isRevolving() ? NewPlan.NONE : replan(account, cmd.newTermMonths());
            // The agreement goes to the ledger with no money moved: it is what a redelivery finds (idempotency) and
            // what the statement shows as the period's restructure.
            balanceEventRepository.save(BalanceEvent.record(account.getCreditAccountId(), cmd.sourceEventId(),
                            "AGREEMENT_RESTRUCTURE", BigDecimal.ZERO, account)
                    .withBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            eventPublisher.publishAccountRestructured(new AccountRestructuredEvent(cmd.sourceEventId(),
                    account.getCreditAccountId(), account.getObligorPartyId(), agreementIdOf(cmd.sourceEventId()), tasaAnterior,
                    account.getNominalRate(), account.getPrincipalBalance(), plan.term(), plan.firstDueDate(),
                    plan.replaced(), BusinessClock.current().today()));
            log.info("Agreement RESTRUCTURE applied creditAccountId={} rate {} → {} newTerm={} firstDue={} replaced={}",
                    cmd.creditAccountId(), tasaAnterior, account.getNominalRate(), plan.term(), plan.firstDueDate(),
                    plan.replaced());
        } else {
            log.warn("Unknown CollectionAgreement type '{}' for creditAccountId={} — ignored",
                    cmd.type(), cmd.creditAccountId());
        }
    }

    /** What a restructure did to an installment loan's plan. */
    record NewPlan(Integer term, LocalDate firstDueDate, int replaced) {
        static final NewPlan NONE = new NewPlan(null, null, 0);
    }

    /**
     * The new plan of a restructured installment loan: its unpaid installments are REPLACED and the principal
     * still owed is amortized at the new rate over the new term, keeping the old plan's day of the month (so the
     * statement cutoffs, derived from the same cadence, stay aligned). Arrears of interest, IVA and penalty stay
     * owed in their buckets: the next payment covers them first, as any payment does. Numbering continues after
     * the last number used, so the record of the old plan is kept.
     */
    NewPlan replan(CreditAccount account, Integer newTerm) {
        UUID id = account.getCreditAccountId();
        List<Installment> pendientes = installmentRepository.findByScheduleIdOrdered(id).stream()
                .filter(i -> i.getStatus() != InstallmentStatus.PAID)
                .toList();
        if (pendientes.isEmpty() || account.getPrincipalBalance().signum() <= 0) {
            return NewPlan.NONE;
        }
        int plazo = newTerm != null && newTerm > 0 ? newTerm : pendientes.size();
        ProductConfigVersion config = configResolver.resolveForAccount(account.getProductCode(), account.getProductVersion())
                .orElse(null);
        String frecuencia = config != null ? config.getPaymentFrequency() : null;
        String metodo = account.getAmortizationType() != null ? account.getAmortizationType()
                : config != null ? config.getAmortizationType() : null;
        LocalDate hoy = BusinessClock.current().today();
        SemiMonthlyCalendar quincena = BillingCycle.semiMonthlyOf(config != null ? config.getBillingCycle() : null);
        LocalDate primera = AmortizationEngine.nextDueAfter(pendientes.get(0).getDueDate(), hoy, frecuencia, quincena);

        int siguiente = installmentRepository.maxInstallmentNumber(id) + 1;
        pendientes.forEach(Installment::replace);
        installmentRepository.saveAll(pendientes);

        List<Installment> generado = amortizationEngine.generate(id, account.getPrincipalBalance(),
                account.getNominalRate(), plazo, metodo, frecuencia, null, primera, account.getVatRate(), quincena);
        List<Installment> nuevo = new ArrayList<>();
        for (int k = 0; k < generado.size(); k++) {
            Installment g = generado.get(k);
            nuevo.add(Installment.of(id, siguiente + k, g.getDueDate(), g.getPrincipalAmount(),
                    g.getInterestAmount(), g.getTaxAmount()));
        }
        bankingCalendar.followBusinessDays(nuevo);
        installmentRepository.saveAll(nuevo);
        return new NewPlan(plazo, primera, pendientes.size());
    }

    private static UUID agreementIdOf(String sourceEventId) {
        try {
            return sourceEventId == null ? null : UUID.fromString(sourceEventId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * AML en <b>cada</b> disposición. No es lo mismo que el control de originación.
     *
     * <h2>Por qué se repite aquí y no basta con haberlo hecho al abrir la línea</h2>
     *
     * Porque el AML no depende de la persona sino de la <b>lista</b>, y la lista cambia. Alguien
     * limpio al abrir su línea puede aparecer publicado seis meses después. Originar ocurre una vez;
     * <b>disponer ocurre decenas de veces durante años</b> —una revolvente dispone cada mes, una de
     * distribuidor en cada colocación—. Así que la originación es un control de punto en el tiempo y
     * ésta es la comprobación recurrente: la última antes de que el dinero salga.
     *
     * <h2>El KYC NO se vuelve a exigir</h2>
     *
     * La identidad se estableció una vez al originar; en cada disposición lo que demuestra que es él
     * es la <b>autenticación</b> —NIP y OTP al teléfono registrado—, no repetir la biometría. Lo
     * único de identidad que bloquea aquí es una revocación posterior, que es un hecho distinto de
     * «no verificada». Ver {@code PartyVerification#allowsDisposition()}.
     *
     * <h2>A quién se comprueba: a los dos papeles, porque son dos</h2>
     *
     * Una disposición tiene <b>quien responde</b> por la línea y <b>quien recibe</b> el dinero, y
     * según el producto son la misma persona o no:
     *
     * <table border="1">
     *   <caption>Los dos papeles por tipo de producto</caption>
     *   <tr><th>Producto</th><th>Responsable de la línea</th><th>Quien recibe</th></tr>
     *   <tr><td>Uso propio / nómina</td><td>el titular</td><td><b>el mismo titular</b></td></tr>
     *   <tr><td>Línea de distribuidor</td><td>la distribuidora</td>
     *       <td><b>la beneficiaria — nunca la distribuidora</b></td></tr>
     * </table>
     *
     * <p>Se comprueban <b>los dos</b>, y por razones distintas: al receptor porque es a quien se le
     * va a pagar, y al responsable porque no tiene sentido mover dinero de la línea de alguien que
     * está en listas aunque el destino sea otro. En uso propio las dos comprobaciones caen sobre la
     * misma persona y se hace una.
     *
     * <h2>Y falla cerrado</h2>
     *
     * Sin veredicto no se dispone. Un party del que no se ha recibido veredicto es uno del que no se
     * sabe, y no saber no es estar limpio — es exactamente el hueco por el que saldría dinero a
     * alguien sin comprobar.
     */
    private AmlCheck checkAml(CreditAccount account, ProcessDispositionCommand cmd) {
        // El RESPONSABLE de la línea: quien debe el dinero y quien autoriza la disposición.
        //
        // Se comprueba siempre, aunque no sea él quien recibe: no tiene sentido mover dinero de la
        // línea de alguien que está en listas, por mucho que el destino sea otra persona.
        UUID lineHolder = account.getObligorPartyId();
        if (lineHolder == null) {
            // La columna es NOT NULL, así que esto no debería ocurrir. Se escribe explícito porque
            // la alternativa —el null callado— era «no hay a quién comprobar, adelante», y eso es
            // un fallo-abierto esperando un dato corrupto.
            return new AmlCheck(
                    "AML_RESPONSABLE: la cuenta no tiene responsable identificado — no se dispone",
                    null);
        }

        PartyVerification holderVerification = verificationOf(lineHolder);
        String motivo = amlReasonFor(holderVerification, lineHolder, "responsable");
        if (motivo != null) return new AmlCheck(motivo, null);

        // Y QUIEN RECIBE el dinero, que no siempre es el mismo.
        UUID recipient = recipientOf(account, cmd);
        if (recipient.equals(lineHolder)) {
            // Uso propio: la misma persona en los dos papeles. Una lectura, un veredicto.
            return new AmlCheck(null, holderVerification);
        }

        PartyVerification recipientVerification = verificationOf(recipient);
        return new AmlCheck(amlReasonFor(recipientVerification, recipient, "receptor"), recipientVerification);
    }

    /**
     * Lo que el AML comprobó y con qué dato.
     *
     * @param rejectionReason        por qué no se dispone, o {@code null} si pasa
     * @param recipientVerification  el veredicto <b>de quien recibe</b>, tal como se leyó para
     *                               decidir. Viaja hasta la constancia para que el expediente diga
     *                               la fila exacta con la que se dejó salir el dinero — leerla otra
     *                               vez podría devolver algo distinto y entonces el expediente
     *                               hablaría de una comprobación que no fue la que ocurrió
     */
    private record AmlCheck(String rejectionReason, PartyVerification recipientVerification) {
        static AmlCheck notRun() { return new AmlCheck(null, null); }
    }

    private PartyVerification verificationOf(UUID partyId) {
        return partyVerificationRepository.findByPartyId(partyId).orElse(null);
    }

    /**
     * Quién <b>recibe</b> el dinero de esta disposición.
     *
     * <h2>La regla, que es una sola y conviene no repetirla</h2>
     *
     * <b>Toda disposición tiene un beneficiario.</b> Lo que cambia con el producto es quién es:
     *
     * <ul>
     *   <li><b>Uso propio</b> ({@code SELF_USE}, {@code PAYROLL}): el beneficiario <b>es</b> el
     *       responsable de la línea. No es que «no haya beneficiario» — es que es la misma persona,
     *       y por eso la petición no lo manda.</li>
     *   <li><b>Línea de distribuidor</b> ({@code THIRD_PARTY_CREDIT}): el beneficiario es la
     *       persona a la que se le colocó, y <b>nunca</b> el responsable de la línea. Lo garantiza
     *       {@link #validateBeneficiary}, que exige beneficiario y prohíbe que sea el obligado — así
     *       que cuando llega aquí, hay receptor y es distinto.</li>
     * </ul>
     *
     * <p>Escrito así una vez, el AML y la foto del veredicto comprueban <b>exactamente</b> a la
     * misma persona. Cuando cada uno lo resolvía por su cuenta, podían discrepar sin que nada
     * fallara: se bloquearía mirando a uno y se guardaría constancia del otro.
     *
     * @return nunca nulo — en el peor caso, el responsable de la línea
     */
    private static UUID recipientOf(CreditAccount account, ProcessDispositionCommand cmd) {
        return cmd.beneficiaryPartyId() != null
                ? cmd.beneficiaryPartyId()
                : account.getObligorPartyId();
    }

    /**
     * El motivo por el que una persona no deja pasar la disposición, o {@code null} si la deja.
     *
     * <p>Recibe la verificación <b>ya leída</b> en vez de leerla: así la compuerta y la constancia
     * comparten la fila, y una lectura doble no puede decidir con un dato y archivar otro.
     *
     * <p>Y sin {@code Optional.map} a propósito: el resultado «todo bien» es {@code null}, y un
     * {@code map} que devuelve null produce un {@code Optional.empty()} indistinguible de «no había
     * fila». Escrito así, toda persona LIMPIA caía en la rama de «sin veredicto» y quedaba
     * bloqueada — un fallo-cerrado que parece prudente y en realidad detiene a todo el mundo.
     */
    private static String amlReasonFor(PartyVerification verification, UUID partyId, String papel) {
        if (partyId == null) return null;   // no hay a quién comprobar; lo cubren otras validaciones
        if (verification == null) {
            return "AML_" + papel.toUpperCase() + ": sin veredicto de verificación — no se dispone";
        }
        return verification.allowsDisposition() ? null
                : "AML_" + papel.toUpperCase() + ": " + verification.dispositionBlockReason();
    }

    /**
     * Copia a la disposición el veredicto de listas que la gobernó.
     *
     * <p>Se toma el de <b>quien recibe el dinero</b> — en una colocación de distribuidor, la
     * beneficiaria—, porque es sobre esa persona sobre la que importa haber comprobado que no está
     * en listas antes de pagarle.
     */
    private void recordAmlSnapshot(Disposition disposition, AmlCheck aml) {
        PartyVerification v = aml.recipientVerification();
        if (v == null) return;

        disposition.recordAmlSnapshot(v.getScreeningStatus(),
                v.getScreenedListVersions(), v.getVerdictAt());
    }
}
