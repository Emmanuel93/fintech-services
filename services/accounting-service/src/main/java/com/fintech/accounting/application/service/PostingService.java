package com.fintech.accounting.application.service;

import com.fintech.accounting.application.AccountActivation;
import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.port.out.*;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.CommissionMoves;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.PaymentOrigin;
import com.fintech.accounting.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * Traduce eventos económicos a pólizas de partida doble.
 *
 * <p>El monto sale del <strong>delta</strong> de saldos contra el shadow local, porque
 * {@code balance-updated} sólo trae saldos nuevos. WRITE_OFF y AGREEMENT_QUITA_PARCIAL se postean
 * especial (GL-10: consumir reserva antes de P&amp;L), y el pago se desglosa (GL-02).
 */
@Service
@Transactional
public class PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);

    private static final String WRITE_OFF     = "WRITE_OFF";
    private static final String QUITA_PARCIAL = "AGREEMENT_QUITA_PARCIAL";
    private static final String PAYMENT       = "PAYMENT_APPLIED";
    private static final String PAYMENT_RETURNED = "PAYMENT_RETURNED";
    /** The distributor's commission settled against her statement (and its undo when the payment comes back). */
    private static final String COMMISSION_NETTED = "COMMISSION_NETTED";
    private static final String COMMISSION_NETTED_REVERSED = "COMMISSION_NETTED_REVERSED";
    private static final String CHARGE_REVERSED  = "CHARGE_REVERSED";
    private static final String CHARGE_WAIVED    = "CHARGE_WAIVED";
    private static final String ACTIVATED     = "ACCOUNT_ACTIVATED";
    /** The undo of a disposition (D63): it never opens an account's accounting life. */
    private static final Set<String> UNDOES_A_DISPOSITION = Set.of("DISPOSITION_FAILED", "DISPOSITION_RETURNED");
    private static final String OPENING_FEE         = "CHARGE_OPENING_FEE";
    private static final String ORDINARY_INTEREST   = "CHARGE_ORDINARY_INTEREST";
    private static final String MORATORIUM_INTEREST = "CHARGE_MORATORIUM_INTEREST";

    private final AccountBalanceShadowRepository shadowRepository;
    private final PostingRuleRepository postingRuleRepository;
    private final ProvisionLedgerRepository provisionLedgerRepository;
    private final VoucherPostingService ledger;
    private final BillingService billingService;
    private final AccountingProperties properties;

    private final FeeDeferralService feeDeferral;

    public PostingService(AccountBalanceShadowRepository shadowRepository,
                           PostingRuleRepository postingRuleRepository,
                           ProvisionLedgerRepository provisionLedgerRepository,
                           VoucherPostingService ledger,
                           BillingService billingService,
                           AccountingProperties properties,
                           FeeDeferralService feeDeferral) {
        this.feeDeferral               = feeDeferral;
        this.shadowRepository          = shadowRepository;
        this.postingRuleRepository     = postingRuleRepository;
        this.provisionLedgerRepository = provisionLedgerRepository;
        this.ledger                    = ledger;
        this.billingService            = billingService;
        this.properties                = properties;
    }

    /**
     * El alta del préstamo, en cuentas de orden.
     *
     * <p>Antes, la primera huella contable de un crédito era la <em>disposición</em> del dinero: un
     * crédito autorizado y no dispuesto no existía en ninguna parte de la contabilidad, aunque el
     * compromiso de la institución sí fuera real. Va en cuentas de orden y no en cartera porque
     * autorizar una línea no coloca capital: cargar 1201 contra nada infla el activo con dinero que
     * no salió.
     *
     * <p>Es también donde se <b>sella la sucursal</b> del crédito, que a partir de aquí llevan todas
     * sus pólizas.
     */
    public void onAccountActivated(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                    BigDecimal authorizedAmount, BigDecimal disbursedAmount,
                                    String orgUnitCode, Instant occurredAt) {
        onAccountActivated(new AccountActivation(sourceEventId, creditAccountId, obligorPartyId, authorizedAmount,
                disbursedAmount, orgUnitCode, null, occurredAt, null));
    }

    public void onAccountActivated(AccountActivation activation) {
        String sourceEventId = activation.sourceEventId();
        UUID creditAccountId = activation.creditAccountId();
        UUID obligorPartyId = activation.obligorPartyId();
        BigDecimal authorizedAmount = activation.authorizedAmount();
        BigDecimal disbursedAmount = activation.disbursedAmount();
        Instant occurredAt = activation.occurredAt();
        // El shadow se crea SIEMPRE, aunque no haya monto que asentar. Es la señal de que
        // presenciamos el nacimiento de esta cuenta: sin él, el primer cargo que llegue se tomaría
        // por la incorporación de un saldo previo y traería el préstamo entero como importe.
        // Also where the loan's product is sealed: from here every voucher of the loan carries it.
        AccountBalanceShadow shadow = shadowRepository.findById(creditAccountId)
                .orElseGet(() -> AccountBalanceShadow.init(creditAccountId, obligorPartyId));
        shadow.learnOrgUnit(activation.orgUnitCode());
        shadow.learnProduct(activation.productType());
        shadow.learnTerm(activation.termMonths());
        shadow.seedDisbursement(disbursedAmount);
        shadowRepository.save(shadow);
        String unit = shadow.getOrgUnitCode();

        // La línea autorizada, en cuentas de orden.
        if (authorizedAmount != null && authorizedAmount.signum() > 0) {
            ledger.post(
                    new VoucherRequest(sourceEventId, ACTIVATED, occurredAt, creditAccountId,
                            obligorPartyId, unit, "Alta de línea de crédito autorizada"),
                    List.of(new Pair(AccountCodes.LINEAS_AUTORIZADAS, AccountCodes.LINEAS_POR_DISPONER,
                            authorizedAmount, "Línea autorizada, pendiente de disponer")));
        }

        // Y el desembolso, que es lo que de verdad coloca capital.
        //
        // No llega por `balance-updated`: la activación de un crédito simple mueve el saldo y publica
        // únicamente este evento, así que si no se asienta aquí la cartera colocada nunca se carga y
        // el mayor no tiene ni una póliza de disposición.
        if (disbursedAmount != null && disbursedAmount.signum() > 0) {
            ledger.post(
                    new VoucherRequest(sourceEventId + ":disp", "DISPOSITION_AT_ORIGINATION", occurredAt,
                            creditAccountId, obligorPartyId, unit, "Disposición del crédito al activar"),
                    // Against SPEI to settle: the bank reports the order out on its operation date (BankSideRule).
                    List.of(new Pair(AccountCodes.CARTERA_VIGENTE, AccountCodes.SPEI_POR_LIQUIDAR,
                            disbursedAmount, "Capital colocado")));
        }
    }

    public void onBalanceUpdated(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                 BigDecimal principal, BigDecimal interest, BigDecimal penalty,
                                 BigDecimal totalDebt, String triggerEvent, long balanceVersion,
                                 String orgUnitCode, Instant occurredAt) {
        onBalanceUpdated(sourceEventId, creditAccountId, obligorPartyId, principal, interest, penalty,
                totalDebt, triggerEvent, balanceVersion, orgUnitCode, occurredAt, null);
    }

    /**
     * @param eventAmount el importe del hecho tal como lo calculó cartera; {@code null} lo deduce.
     *
     * <p><b>GL-12: el importe se recibe, no se adivina.</b> Antes salía siempre del delta contra el
     * saldo recordado, y ese cálculo se rompe en cuanto dos eventos del mismo crédito se procesan
     * fuera de orden: el delta arrastra lo que no le toca y, tomado en valor absoluto, la magnitud de
     * un pago de dos millones se asienta como el devengo de interés de un día. En la cartera sembrada
     * eso infló la cuenta 4101 de $142,618 a $2,233,339 — quince veces— sin descuadrar la balanza,
     * porque el par seguía cuadrando consigo mismo.
     *
     * <p>El delta se sigue usando para lo que de verdad se deduce de saldos: el desglose de un pago
     * o de un quebranto entre capital y devengado, que el productor no manda desagregado.
     */
    public void onBalanceUpdated(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                 BigDecimal principal, BigDecimal interest, BigDecimal penalty,
                                 BigDecimal totalDebt, String triggerEvent, long balanceVersion,
                                 String orgUnitCode, Instant occurredAt, BigDecimal eventAmount) {
        onBalanceUpdated(sourceEventId, creditAccountId, obligorPartyId, principal, interest, penalty,
                totalDebt, triggerEvent, balanceVersion, orgUnitCode, occurredAt, eventAmount,
                null, null, null);
    }

    /**
     * @param principalDelta cuánto movió el hecho en capital, interés y moratorios; con signo.
     *
     * <p><b>GL-13: el desglose se recibe y el evento no se descarta.</b> Dos problemas del mismo
     * origen —deducir lo que el productor ya sabe—:
     *
     * <p>El reparto de un pago entre capital, interés y moratorios lo decide cartera al aplicarlo.
     * Deducirlo restando saldos sólo funciona si los eventos llegan en orden; con la cartera sembrada
     * todos los pagos se asentaron contra capital y <b>1203 no se abonó nunca</b>.
     *
     * <p>Y la guarda de versión descartaba el evento entero cuando llegaba desordenado: de 17 pagos
     * reales sólo se asentaron 5. La idempotencia no depende de esa guarda —{@code uq_vouchers_source}
     * ya impide asentar dos veces el mismo hecho—, así que ahora un evento fuera de orden se asienta
     * igual y lo único que no retrocede es el saldo recordado.
     */
    public void onBalanceUpdated(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                 BigDecimal principal, BigDecimal interest, BigDecimal penalty,
                                 BigDecimal totalDebt, String triggerEvent, long balanceVersion,
                                 String orgUnitCode, Instant occurredAt, BigDecimal eventAmount,
                                 BigDecimal principalDelta, BigDecimal interestDelta,
                                 BigDecimal penaltyDelta) {
        onBalanceUpdated(sourceEventId, creditAccountId, obligorPartyId, principal, interest, penalty, totalDebt,
                triggerEvent, balanceVersion, orgUnitCode, occurredAt, eventAmount, principalDelta, interestDelta,
                penaltyDelta, null, null);
    }

    /**
     * @param ivaDelta   the IVA part of the move (also inside {@code penaltyDelta}); null in older producers
     * @param chargeType for a reversed or waived charge, the charge's type: it decides which income or tax
     *                   account the reversal undoes
     */
    public void onBalanceUpdated(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                 BigDecimal principal, BigDecimal interest, BigDecimal penalty,
                                 BigDecimal totalDebt, String triggerEvent, long balanceVersion,
                                 String orgUnitCode, Instant occurredAt, BigDecimal eventAmount,
                                 BigDecimal principalDelta, BigDecimal interestDelta,
                                 BigDecimal penaltyDelta, BigDecimal ivaDelta, String chargeType) {
        onBalanceUpdated(sourceEventId, creditAccountId, obligorPartyId, principal, interest, penalty, totalDebt,
                triggerEvent, balanceVersion, orgUnitCode, occurredAt, eventAmount, principalDelta, interestDelta,
                penaltyDelta, ivaDelta, chargeType, PaymentOrigin.direct());
    }

    /** @param origin for a payment, where its money came from: the account it is booked against and its item. */
    public void onBalanceUpdated(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                 BigDecimal principal, BigDecimal interest, BigDecimal penalty,
                                 BigDecimal totalDebt, String triggerEvent, long balanceVersion,
                                 String orgUnitCode, Instant occurredAt, BigDecimal eventAmount,
                                 BigDecimal principalDelta, BigDecimal interestDelta,
                                 BigDecimal penaltyDelta, BigDecimal ivaDelta, String chargeType,
                                 PaymentOrigin origin) {
        // The shadow is the sign that accounting saw this account born: onAccountActivated creates it. A fact that
        // arrives before it (the birth travels on another topic, consumed in parallel) waits for the birth: it is
        // retried until the activation commits, and if it never does it goes to the dead letter, where it can be
        // replayed. Only the disposition of the money opens an account's accounting life by itself.
        //
        // Inferring instead a "mid-life incorporation" booked the opening fee against equity as "moratorios
        // incorporados" when the race was lost (E2E 3 Nov: 245 in 3901, fee income short by the same amount).
        // Every account is born in this platform; taking over an existing portfolio is a migration, not a guess.
        if (shadowRepository.findById(creditAccountId).isEmpty() && !opensAccountingLife(triggerEvent)) {
            throw new AccountBirthNotSeenException(creditAccountId, triggerEvent);
        }

        AccountBalanceShadow shadow = shadowRepository.findById(creditAccountId)
                .orElseGet(() -> AccountBalanceShadow.init(creditAccountId, obligorPartyId));
        shadow.learnOrgUnit(orgUnitCode);

        BalanceDelta deducido = shadow.applyAndComputeDelta(principal, interest, penalty, totalDebt, balanceVersion);
        if (deducido == null) {
            // Evento fuera de orden o redelivery. No se descarta: si trae su propio desglose, se
            // asienta igual —y si es un duplicado de verdad, `uq_vouchers_source` lo rechaza al
            // insertar—. Lo único que no se hace es retroceder el saldo recordado.
            if (principalDelta == null && eventAmount == null) {
                log.debug("balance-updated viejo/duplicado sin importe propio creditAccountId={} v={} — se ignora",
                        creditAccountId, balanceVersion);
                return;
            }
            log.debug("balance-updated fuera de orden creditAccountId={} v={} — se asienta con su propio importe",
                    creditAccountId, balanceVersion);
        }
        shadowRepository.save(shadow);

        // El desglose del productor manda; el deducido es el respaldo para eventos que aún no lo traen.
        BalanceDelta delta = principalDelta != null
                ? new BalanceDelta(principalDelta, nz(interestDelta), nz(penaltyDelta),
                        eventAmount != null ? eventAmount : principalDelta.add(nz(interestDelta)).add(nz(penaltyDelta)))
                : deducido != null ? deducido
                : new BalanceDelta(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, nz(eventAmount));

        String unit = shadow.getOrgUnitCode();
        // El importe del productor manda; el delta es el respaldo para los eventos que todavía no lo
        // traen. Se toma en valor absoluto porque el signo ya lo expresa la pareja de cuentas.
        BigDecimal amount = eventAmount != null ? eventAmount.abs() : delta.absTotal();
        if (amount.signum() == 0) return; // sólo cambió el estatus: no hay nada que asentar

        if (WRITE_OFF.equals(triggerEvent) || QUITA_PARCIAL.equals(triggerEvent)) {
            postWriteOffOrQuita(sourceEventId, triggerEvent, creditAccountId, obligorPartyId, unit, delta, occurredAt);
            return;
        }
        if (PAYMENT.equals(triggerEvent)) {
            postPayment(sourceEventId, creditAccountId, obligorPartyId, unit, delta, occurredAt, origin);
            return;
        }
        if (COMMISSION_NETTED.equals(triggerEvent)) {
            postCommissionNetted(sourceEventId, creditAccountId, obligorPartyId, unit, shadow.getProductType(), delta, occurredAt);
            return;
        }
        if (COMMISSION_NETTED_REVERSED.equals(triggerEvent)) {
            postCommissionNettedReversed(sourceEventId, creditAccountId, obligorPartyId, unit, shadow.getProductType(), delta,
                    occurredAt);
            return;
        }
        if (PAYMENT_RETURNED.equals(triggerEvent)) {
            postPaymentReturned(sourceEventId, creditAccountId, obligorPartyId, unit, delta, occurredAt);
            return;
        }
        if ((CHARGE_REVERSED.equals(triggerEvent) || CHARGE_WAIVED.equals(triggerEvent)) && chargeType != null) {
            postChargeReversal(sourceEventId, triggerEvent, chargeType, creditAccountId, obligorPartyId, unit,
                    amount, occurredAt);
            return;
        }

        PostingRule rule = postingRuleRepository.findByTriggerEvent(triggerEvent).orElse(null);
        if (rule == null) {
            log.warn("Sin regla de posteo para triggerEvent={} (creditAccountId={}) — no se asienta",
                    triggerEvent, creditAccountId);
            return;
        }
        if ((ORDINARY_INTEREST.equals(triggerEvent) || MORATORIUM_INTEREST.equals(triggerEvent))
                && postCreditImpairedInterest(sourceEventId, triggerEvent, rule, creditAccountId, obligorPartyId, unit,
                        amount, occurredAt)) {
            // The client still owes, and is invoiced, the contractual interest: only its revenue recognition changed.
            if (isInvoiceableCharge(triggerEvent)) {
                billingService.accrue(sourceEventId, obligorPartyId, creditAccountId, triggerEvent.substring("CHARGE_".length()),
                        amount, false, periodOfFact(occurredAt));
            }
            return;
        }
        ledger.post(
                new VoucherRequest(sourceEventId, triggerEvent, occurredAt, creditAccountId, obligorPartyId,
                        unit, rule.getDescription()),
                List.of(new Pair(rule.getDebitAccount(), rule.getCreditAccount(), amount, rule.getDescription())));
        if (OPENING_FEE.equals(triggerEvent) && AccountCodes.COMISIONES_POR_DEVENGAR.equals(rule.getCreditAccount())) {
            deferOpeningFee(sourceEventId, shadow, creditAccountId, obligorPartyId, unit, amount, occurredAt);
        }

        if (isInvoiceableCharge(triggerEvent)) {
            String concept = triggerEvent.substring("CHARGE_".length());
            billingService.accrue(sourceEventId, obligorPartyId, creditAccountId, concept, amount,
                    "CHARGE_IVA".equals(triggerEvent), periodOfFact(occurredAt));
        }
    }

    /**
     * The facts an account starts its accounting life with.
     *
     * <p>The disposition of the money is the first thing that really moves a balance. Seeing a disposition on an
     * unknown account is not having missed anything: it is the beginning.
     */
    private static boolean opensAccountingLife(String triggerEvent) {
        // A failed or returned disposition undoes what the birth booked: heard first, it would open the account with
        // nothing to undo and leave the late activation's 1201/2103 standing (D63). It waits for the birth.
        return triggerEvent != null
                && ((triggerEvent.startsWith("DISPOSITION") && !UNDOES_A_DISPOSITION.contains(triggerEvent))
                    || ACTIVATED.equals(triggerEvent));
    }

    /**
     * GL-02: el pago se aplica a lo que de verdad liquidó.
     *
     * <p>La regla anterior era un solo par {@code 1101/1201}: el importe completo se abonaba a
     * capital, incluida la parte que liquida interés devengado, que vive en 1203. El efecto
     * acumulado es que <b>1203 crece indefinidamente y 1201 se subestima</b> — el auxiliar de
     * intereses por cobrar nunca baja aunque los clientes paguen sus intereses, y con unos meses de
     * operación la balanza deja de ser presentable.
     *
     * <p>El desglose ya se calculaba y se tiraba: {@link BalanceDelta} trae capital, interés y
     * moratorio por separado y sólo se usaba {@code absTotal()}.
     */
    private void postPayment(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                             String orgUnitCode, BalanceDelta delta, Instant occurredAt, PaymentOrigin origin) {
        String from = origin.debitAccount();
        List<Pair> pairs = new ArrayList<>();
        // Los deltas de un pago son negativos (el saldo baja); el importe abonado es su valor absoluto.
        BigDecimal principal = negativePart(delta.principalDelta());
        BigDecimal interest  = negativePart(delta.interestDelta());
        BigDecimal penalty   = negativePart(delta.penaltyDelta());

        if (principal.signum() > 0) {
            pairs.add(new Pair(from, AccountCodes.CARTERA_VIGENTE, principal,
                    "Aplicación a capital"));
        }
        if (interest.signum() > 0) {
            pairs.add(new Pair(from, AccountCodes.INTERESES_POR_COBRAR, interest,
                    "Aplicación a interés devengado"));
        }
        if (penalty.signum() > 0) {
            pairs.add(new Pair(from, AccountCodes.INTERESES_POR_COBRAR, penalty,
                    "Aplicación a moratorios"));
        }

        if (pairs.isEmpty()) {
            // Ningún componente bajó pero el total sí: no se puede saber qué liquidó. Se asienta contra
            // cartera con la plantilla, que es el comportamiento anterior, y se avisa — un pago sin
            // desglose es un dato del productor que hay que corregir, no un caso a normalizar.
            log.warn("Pago sin desglose por componente creditAccountId={} total={} — se asienta a capital",
                    creditAccountId, delta.absTotal());
            pairs.add(new Pair(from, AccountCodes.CARTERA_VIGENTE, delta.absTotal(),
                    "Pago sin desglose por componente"));
        }

        ledger.post(new VoucherRequest(sourceEventId, PAYMENT, occurredAt, creditAccountId, obligorPartyId,
                orgUnitCode, "Pago recibido — aplica a interés devengado y capital", origin.clearingRef()), pairs);
    }

    /**
     * The distributor's commission settled against her statement: what Kredius owed her for it (2120, accrued when
     * commission recorded it) pays the statement's interest (1203) and, when a late fortnight's interest is less than
     * her flat commission, principal (1201) that the interest she paid earlier already covered. No cash moves: she
     * delivered the statement less her commission.
     *
     * <p>It leaves 2120 through the same item the accrual entered it by ({@code COM:<beneficiary>:<product>}): the
     * close reconciles 2120 by beneficiary and product, and a debit without the item left every month open with her
     * accrual standing against nothing (dist5, 5 Nov).
     */
    private void postCommissionNetted(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                      String orgUnitCode, String productType, BalanceDelta delta, Instant occurredAt) {
        List<Pair> pairs = new ArrayList<>();
        BigDecimal principal = negativePart(delta.principalDelta());
        BigDecimal interest  = negativePart(delta.interestDelta());
        if (interest.signum() > 0) {
            pairs.add(new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.INTERESES_POR_COBRAR, interest,
                    "Comisión de la distribuidora aplicada a interés"));
        }
        if (principal.signum() > 0) {
            pairs.add(new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.CARTERA_VIGENTE, principal,
                    "Comisión de la distribuidora aplicada a capital"));
        }
        if (pairs.isEmpty()) {
            log.warn("Comisión neteada sin desglose por componente creditAccountId={} total={} — se asienta a interés",
                    creditAccountId, delta.absTotal());
            pairs.add(new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.INTERESES_POR_COBRAR, delta.absTotal(),
                    "Comisión de la distribuidora neteada sin desglose"));
        }
        ledger.post(new VoucherRequest(sourceEventId, COMMISSION_NETTED, occurredAt, creditAccountId, obligorPartyId,
                orgUnitCode, "Comisión de la distribuidora neteada contra su estado de cuenta",
                CommissionMoves.keyOf(obligorPartyId, productType)).withProduct(productType), pairs);
    }

    /** The undo of {@link #postCommissionNetted}: her payment came back, so what her commission had settled is owed again. */
    private void postCommissionNettedReversed(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                              String orgUnitCode, String productType, BalanceDelta delta, Instant occurredAt) {
        List<Pair> pairs = new ArrayList<>();
        BigDecimal principal = positivePart(delta.principalDelta());
        BigDecimal interest  = positivePart(delta.interestDelta());
        if (interest.signum() > 0) {
            pairs.add(new Pair(AccountCodes.INTERESES_POR_COBRAR, AccountCodes.COMISIONES_POR_PAGAR, interest,
                    "Comisión neteada revertida: interés"));
        }
        if (principal.signum() > 0) {
            pairs.add(new Pair(AccountCodes.CARTERA_VIGENTE, AccountCodes.COMISIONES_POR_PAGAR, principal,
                    "Comisión neteada revertida: capital"));
        }
        if (pairs.isEmpty()) {
            pairs.add(new Pair(AccountCodes.INTERESES_POR_COBRAR, AccountCodes.COMISIONES_POR_PAGAR, delta.absTotal(),
                    "Comisión neteada revertida sin desglose"));
        }
        ledger.post(new VoucherRequest(sourceEventId, COMMISSION_NETTED_REVERSED, occurredAt, creditAccountId, obligorPartyId,
                orgUnitCode, "Comisión de la distribuidora neteada, revertida: su pago fue devuelto",
                CommissionMoves.keyOf(obligorPartyId, productType)).withProduct(productType), pairs);
    }

    /**
     * A returned payment (SPEI returned by the payer's bank): the mirror of {@link #postPayment}. What the payment
     * had covered is owed again — principal to 1201, interest, moratorium and IVA to 1203 — and the money leaves the
     * bank. Before, returns never reached the ledger at all (the portfolio dropped them), and the template rule put
     * the whole amount on 1201.
     */
    private void postPaymentReturned(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                     String orgUnitCode, BalanceDelta delta, Instant occurredAt) {
        List<Pair> pairs = new ArrayList<>();
        BigDecimal principal = positivePart(delta.principalDelta());
        BigDecimal interest  = positivePart(delta.interestDelta());
        BigDecimal penalty   = positivePart(delta.penaltyDelta());
        if (principal.signum() > 0) {
            pairs.add(new Pair(AccountCodes.CARTERA_VIGENTE, AccountCodes.BANCOS, principal, "Devolución: capital"));
        }
        if (interest.signum() > 0) {
            pairs.add(new Pair(AccountCodes.INTERESES_POR_COBRAR, AccountCodes.BANCOS, interest, "Devolución: interés devengado"));
        }
        if (penalty.signum() > 0) {
            pairs.add(new Pair(AccountCodes.INTERESES_POR_COBRAR, AccountCodes.BANCOS, penalty, "Devolución: moratorios e IVA"));
        }
        if (pairs.isEmpty()) {
            log.warn("Devolución sin desglose por componente creditAccountId={} total={} — se asienta a capital",
                    creditAccountId, delta.absTotal());
            pairs.add(new Pair(AccountCodes.CARTERA_VIGENTE, AccountCodes.BANCOS, delta.absTotal(),
                    "Devolución sin desglose por componente"));
        }
        ledger.post(new VoucherRequest(sourceEventId, PAYMENT_RETURNED, occurredAt, creditAccountId, obligorPartyId,
                orgUnitCode, "Pago devuelto por el banco del pagador"), pairs);
    }

    /**
     * A reversed or waived charge, undone in the account it was booked to. A reversal (the charge was a mistake)
     * cancels the income — 4101 interest, 4102 moratorium, 4103 fees — or the IVA payable (2110). A waiver (the
     * charge was earned and forgiven) is an expense (5103), except its IVA, which is no longer owed to the tax
     * authority. The template rule debited 4103 for every type.
     */
    private void postChargeReversal(String sourceEventId, String triggerEvent, String chargeType, UUID creditAccountId,
                                    UUID obligorPartyId, String orgUnitCode, BigDecimal amount, Instant occurredAt) {
        boolean condonado = CHARGE_WAIVED.equals(triggerEvent);
        String tipo = chargeType.toUpperCase(Locale.ROOT);
        String debito;
        if ("IVA".equals(tipo)) {
            debito = AccountCodes.IVA_POR_PAGAR;
        } else if (condonado) {
            debito = AccountCodes.GASTO_CONDONACIONES;
        } else if ("ORDINARY_INTEREST".equals(tipo) || "INTEREST".equals(tipo)) {
            debito = AccountCodes.INGRESOS_INTERESES;
        } else if ("MORATORIUM_INTEREST".equals(tipo)) {
            debito = AccountCodes.INGRESOS_MORATORIOS;
        } else {
            debito = AccountCodes.INGRESOS_COMISIONES;
        }
        String concepto = (condonado ? "Condonación de " : "Reversa de ") + tipo.toLowerCase(Locale.ROOT).replace('_', ' ');
        if ("OPENING_FEE".equals(tipo) && !ledger.isPosted(sourceEventId)) {
            // A fee still being deferred was never revenue: that part leaves 2150, and only what was already
            // recognized is reversed from revenue (or expensed, if waived). The recognized part is effective-rate
            // interest revenue (4106), where its amortization went.
            if (!condonado) debito = AccountCodes.INGRESOS_TASA_EFECTIVA;
            BigDecimal diferido = feeDeferral.withdraw(creditAccountId, amount);
            ledger.post(new VoucherRequest(sourceEventId, triggerEvent, occurredAt, creditAccountId, obligorPartyId,
                    orgUnitCode, concepto), List.of(
                    new Pair(AccountCodes.COMISIONES_POR_DEVENGAR, AccountCodes.INTERESES_POR_COBRAR, diferido, concepto + " (por devengar)"),
                    new Pair(debito, AccountCodes.INTERESES_POR_COBRAR, amount.subtract(diferido), concepto)));
            return;
        }
        ledger.post(new VoucherRequest(sourceEventId, triggerEvent, occurredAt, creditAccountId, obligorPartyId,
                orgUnitCode, concepto), List.of(new Pair(debito, AccountCodes.INTERESES_POR_COBRAR, amount, concepto)));
    }

    /**
     * GL-10: consume la reserva ya provisionada; el excedente golpea P&L directo.
     *
     * <p><b>GL-11: se da de baja cada componente por su cuenta.</b> Antes se acreditaba el importe
     * entero a {@code 1201}, pero un quebranto no sólo castiga capital: castiga también el interés y
     * el IVA devengados, que viven en {@code 1203}. Acreditarlo todo a cartera dejaba dos errores
     * simétricos y permanentes —{@code 1201} sub-acreditada y {@code 1203} inflada— que la balanza no
     * delata, porque cada asiento sigue siendo un par cuadrado.
     *
     * <p>Es el mismo error que GL-02 corrigió en el pago: el desglose ya venía en {@link BalanceDelta}
     * y se tiraba llamando a {@code absTotal()}.
     */
    private void postWriteOffOrQuita(String sourceEventId, String triggerEvent, UUID creditAccountId,
                                     UUID obligorPartyId, String orgUnitCode, BalanceDelta delta,
                                     Instant occurredAt) {
        BigDecimal amount = delta.absTotal();

        ProvisionLedgerEntry provision = provisionLedgerRepository.findById(creditAccountId)
                .orElseGet(() -> ProvisionLedgerEntry.init(creditAccountId));
        BigDecimal consumed = provision.consume(amount);
        provisionLedgerRepository.save(provision);
        BigDecimal excess = amount.subtract(consumed);

        // Los deltas de un quebranto son negativos (el saldo se va a cero); lo castigado es su valor
        // absoluto.
        BigDecimal principal = negativePart(delta.principalDelta());
        BigDecimal accrued   = negativePart(delta.interestDelta()).add(negativePart(delta.penaltyDelta()));

        // El desglose tiene que sumar el total castigado: los renglones se reparten sobre estos dos
        // saldos, mientras que el cargo (reserva + P&L) y la cuenta de orden se calculan sobre
        // `amount`. Si no coinciden, la póliza daría de baja menos de lo que castiga y lo de orden
        // no cuadraría contra el balance. El descuadre se lleva a capital, que es donde estaba antes
        // de este arreglo, y se avisa: es un dato del productor a corregir, no un caso a normalizar.
        BigDecimal desglosado = principal.add(accrued);
        if (desglosado.compareTo(amount) != 0) {
            if (desglosado.signum() > 0) {
                log.warn("Quebranto con desglose incompleto creditAccountId={} total={} desglosado={} — la diferencia se castiga a capital",
                        creditAccountId, amount, desglosado);
            }
            principal = principal.add(amount.subtract(desglosado));
            // Un desglose mayor que el total dejaría capital negativo: en ese caso mandan los
            // componentes y el sobrante sale del devengado.
            if (principal.signum() < 0) {
                accrued = accrued.add(principal);
                principal = BigDecimal.ZERO;
            }
        }

        // Dos repartos del mismo importe que hay que cruzar: el cargo se divide entre reserva y P&L,
        // y el abono entre capital (1201) e intereses devengados (1203). Se resuelve en cascada y no
        // por proporción: repartir a prorrata obliga a redondear cuatro veces y deja centavos que no
        // suman el total. La reserva se constituye sobre la exposición de capital, así que se aplica
        // primero contra capital y sólo el sobrante toca el devengado.
        List<Pair> pairs = new ArrayList<>();
        BigDecimal[] buckets = { principal, accrued };
        buckets = drawDown(pairs, AccountCodes.ESTIMACION_PREVENTIVA, consumed, buckets,
                "Consumo de estimación preventiva constituida");
        drawDown(pairs, AccountCodes.GASTO_QUEBRANTO, excess, buckets,
                "Pérdida no provisionada a resultados");
        // Una sola póliza con los dos renglones: consumo de reserva y exceso a P&L son el mismo hecho.
        // Antes eran dos asientos con sourceEventId distinto (`…:pl`) y nada los relacionaba.
        ledger.post(new VoucherRequest(sourceEventId, triggerEvent, occurredAt, creditAccountId,
                obligorPartyId, orgUnitCode,
                WRITE_OFF.equals(triggerEvent) ? "Quebranto de cartera" : "Quita parcial autorizada"), pairs);

        // IFRS 9 §5.4.4: dar de baja el activo no extingue el derecho de cobro. Lo castigado se
        // sigue en cuentas de ORDEN —fuera del balance— para poder contestar cuánto se castigó y,
        // cuando llegue una recuperación, contra qué. Sin esto, lo castigado desaparecía del mayor.
        if (WRITE_OFF.equals(triggerEvent)) {
            ledger.post(
                    new VoucherRequest(sourceEventId + ":orden", "WRITE_OFF_CONTROL", occurredAt,
                            creditAccountId, obligorPartyId, orgUnitCode,
                            "Registro en cuentas de orden de la cartera castigada"),
                    List.of(new Pair(AccountCodes.CARTERA_CASTIGADA, AccountCodes.CONTROL_CASTIGADA,
                            amount, "Cartera dada de baja del balance")));

            // The reserve the write-off did not need is released here, as part of the same fact. Risk no longer
            // publishes a zero for a written-off loan: arriving before the write-off, that zero released the whole
            // reserve and the loss went to expense instead of consuming it.
            BigDecimal sobrante = provision.getLastBookedProvision();
            if (sobrante.signum() > 0) {
                provision.book(BigDecimal.ZERO);
                provisionLedgerRepository.save(provision);
                ledger.post(
                        new VoucherRequest(sourceEventId + ":liberacion", "PROVISION_EPR", occurredAt,
                                creditAccountId, obligorPartyId, orgUnitCode,
                                "Estimación preventiva — liberación del sobrante del quebranto"),
                        List.of(new Pair(AccountCodes.ESTIMACION_PREVENTIVA, AccountCodes.GASTO_ESTIMACION, sobrante,
                                "Reserva no consumida por el quebranto")));
            }
        }
    }

    /** Recuperación post-quebranto (collections.recovery-payment-applied), pagada con dinero directo a Bancos. */
    public void onRecoveryPayment(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                   BigDecimal amount, Instant occurredAt) {
        onRecoveryPayment(sourceEventId, creditAccountId, obligorPartyId, amount, occurredAt, PaymentOrigin.direct());
    }

    /**
     * Recuperación post-quebranto. Its money comes from where any payment's does ({@link PaymentOrigin}): a recovery
     * paid by a SPEI credit waits in transit like every other collection until the bank credits it.
     */
    public void onRecoveryPayment(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                   BigDecimal amount, Instant occurredAt, PaymentOrigin origin) {
        if (amount == null || amount.signum() <= 0) return;
        ledger.post(
                new VoucherRequest(sourceEventId, "RECOVERY_PAYMENT", occurredAt, creditAccountId,
                        obligorPartyId, unitOf(creditAccountId), "Recuperación de cartera castigada",
                        origin.clearingRef()),
                List.of(new Pair(origin.debitAccount(), AccountCodes.RECUPERACION_CASTIGADA, amount,
                        "Recuperación de cartera castigada")));
        // What was recovered is no longer owed: the memo of written-off loans comes down by it.
        ledger.post(
                new VoucherRequest(sourceEventId + ":orden", "WRITE_OFF_CONTROL", occurredAt, creditAccountId,
                        obligorPartyId, unitOf(creditAccountId), "Recuperación: baja de cuentas de orden"),
                List.of(new Pair(AccountCodes.CONTROL_CASTIGADA, AccountCodes.CARTERA_CASTIGADA, amount,
                        "Cartera castigada recuperada")));
    }

    /** Retiro de wallet (wallet.withdrawal-completed) — liquida el pasivo de fondos de clientes. */
    public void onWalletWithdrawal(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                    BigDecimal amount, Instant occurredAt) {
        if (amount == null || amount.signum() <= 0) return;
        ledger.post(
                new VoucherRequest(sourceEventId, "WALLET_WITHDRAWAL", occurredAt, creditAccountId,
                        obligorPartyId, unitOf(creditAccountId), "Retiro de wallet"),
                List.of(new Pair(AccountCodes.FONDOS_CLIENTES, AccountCodes.BANCOS, amount,
                        "Retiro de wallet — liquida fondos de clientes")));
    }

    /** La sucursal ya sellada del crédito. Nula mientras ningún evento la haya traído. */
    private String unitOf(UUID creditAccountId) {
        if (creditAccountId == null) return null;
        return shadowRepository.findById(creditAccountId)
                .map(AccountBalanceShadow::getOrgUnitCode)
                .orElse(null);
    }

    /** Un componente que el productor no mandó vale cero, no rompe la suma. */
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** The positive part of a signed delta (what a returned payment puts back), zero if negative or null. */
    private static BigDecimal positivePart(BigDecimal delta) {
        return delta == null || delta.signum() <= 0 ? BigDecimal.ZERO : delta;
    }

    private static BigDecimal negativePart(BigDecimal delta) {
        return delta != null && delta.signum() < 0 ? delta.negate() : BigDecimal.ZERO;
    }

    /**
     * Consume {@code charge} contra los saldos por dar de baja, en orden, emitiendo un renglón por
     * cada cuenta que alcance a tocar. Devuelve lo que quede de cada saldo para el siguiente cargo.
     *
     * <p>El orden de {@link #WRITEOFF_ACCOUNTS} es el del reparto: primero capital y después el
     * devengado. Cada renglón es un par cuadrado, así que la póliza cuadra sea cual sea el reparto;
     * lo que la cascada garantiza es que los renglones <b>sumen exactamente</b> el importe castigado,
     * sin centavos perdidos al redondear.
     */
    private static BigDecimal[] drawDown(List<Pair> pairs, String debitAccount, BigDecimal charge,
                                          BigDecimal[] buckets, String description) {
        BigDecimal[] left = buckets.clone();
        BigDecimal pending = charge;
        for (int i = 0; i < left.length && pending.signum() > 0; i++) {
            BigDecimal slice = left[i].min(pending);
            if (slice.signum() <= 0) continue;
            pairs.add(new Pair(debitAccount, WRITEOFF_ACCOUNTS[i], slice, description));
            left[i] = left[i].subtract(slice);
            pending = pending.subtract(slice);
        }
        return left;
    }

    /** Las cuentas de activo que un quebranto da de baja, en el orden en que se consumen. */
    /**
     * A Stage 3 (credit-impaired) loan's interest, on a net basis (IFRS 9 §5.4.1(b)): the loan keeps accruing its
     * contractual interest, and the receivable is charged with all of it, but revenue is only the rate on the
     * amortised cost. With c = reserve / exposure, as risk measured them the night before:
     * <ul>
     *   <li>Dr 1203 / Cr 4101 (4102 moratorium) — the charge × (1 − c), the revenue;</li>
     *   <li>Dr 1203 / Cr 1290 — the charge × c, which joins the loss allowance (the reserve booked grows by it, so the
     *       night's assessment books only the rest and 1290 keeps matching the reserves);</li>
     *   <li>Dr 7401 / Cr 7402 — the same amount as a memo of the contractual interest not recognized.</li>
     * </ul>
     * Outside Stage 3, or before risk measured the account, the charge is booked as usual and this returns false.
     * Once the loan cures, its interest is gross again from the next charge, and the reserve released goes to 5101.
     */
    private boolean postCreditImpairedInterest(String sourceEventId, String triggerEvent, PostingRule rule,
                                               UUID creditAccountId, UUID obligorPartyId, String unit,
                                               BigDecimal amount, Instant occurredAt) {
        ProvisionLedgerEntry reserva = provisionLedgerRepository.findById(creditAccountId).orElse(null);
        if (reserva == null) {
            return false;
        }
        BigDecimal noReconocido = reserva.creditImpairedShareOf(amount);
        if (noReconocido.signum() <= 0) {
            return false;
        }
        if (ledger.isPosted(sourceEventId)) {
            return true;   // a redelivery: the voucher and the allowance it added are already there
        }
        BigDecimal ingreso = amount.subtract(noReconocido);
        ledger.post(
                new VoucherRequest(sourceEventId, triggerEvent, occurredAt, creditAccountId, obligorPartyId, unit,
                        rule.getDescription() + " — cartera en etapa 3, base neta"),
                List.of(new Pair(rule.getDebitAccount(), rule.getCreditAccount(), ingreso,
                                "Interés sobre el costo amortizado (etapa 3)"),
                        new Pair(rule.getDebitAccount(), AccountCodes.ESTIMACION_PREVENTIVA, noReconocido,
                                "Interés de etapa 3 no reconocido: se suma a la estimación preventiva"),
                        new Pair(AccountCodes.INTERESES_ETAPA3_ORDEN, AccountCodes.CONTRA_INTERESES_ETAPA3, noReconocido,
                                "Interés contractual de etapa 3 no reconocido (orden)")));
        reserva.addToAllowance(noReconocido);
        provisionLedgerRepository.save(reserva);
        log.info("Interés de etapa 3 en base neta creditAccountId={} cargo={} ingreso={} a la estimación={}",
                creditAccountId, amount, ingreso, noReconocido);
        return true;
    }

    /**
     * The opening fee went to 2150: it is recognized over the loan's life. A loan whose life accounting does not know
     * (a line, or one activated before the term travelled with the activation) recognizes it at once, as before.
     */
    private void deferOpeningFee(String sourceEventId, AccountBalanceShadow shadow, UUID creditAccountId,
                                 UUID obligorPartyId, String unit, BigDecimal amount, Instant occurredAt) {
        Integer months = shadow.getTermMonths();
        if (months != null && months > 1) {
            feeDeferral.defer(sourceEventId, creditAccountId, obligorPartyId, unit, amount, months, periodOfFact(occurredAt));
            return;
        }
        ledger.post(new VoucherRequest(sourceEventId + ":inmediata", FeeDeferralService.TRIGGER, occurredAt, creditAccountId,
                        obligorPartyId, unit, "Comisión de apertura reconocida al cobrarse (sin plazo que la difiera)"),
                List.of(new Pair(AccountCodes.COMISIONES_POR_DEVENGAR, AccountCodes.INGRESOS_TASA_EFECTIVA, amount,
                        "Comisión de apertura")));
    }

    private static final String[] WRITEOFF_ACCOUNTS =
            { AccountCodes.CARTERA_VIGENTE, AccountCodes.INTERESES_POR_COBRAR };

    private static boolean isInvoiceableCharge(String triggerEvent) {
        return triggerEvent.startsWith("CHARGE_")
                && !"CHARGE_REVERSED".equals(triggerEvent)
                && !"CHARGE_WAIVED".equals(triggerEvent);
    }

    /** El período al que pertenece el devengo facturable: el del hecho, no el del reloj. */
    private String periodOfFact(Instant occurredAt) {
        Instant at = occurredAt != null ? occurredAt : BusinessClock.current().now();
        return YearMonth.from(at.atZone(properties.zoneId())).toString().replace("-", "");
    }
}
