package com.fintech.accounting.application.service;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.port.out.*;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private static final String ACTIVATED     = "ACCOUNT_ACTIVATED";

    private final AccountBalanceShadowRepository shadowRepository;
    private final PostingRuleRepository postingRuleRepository;
    private final ProvisionLedgerRepository provisionLedgerRepository;
    private final VoucherPostingService ledger;
    private final BillingService billingService;
    private final AccountingProperties properties;

    public PostingService(AccountBalanceShadowRepository shadowRepository,
                           PostingRuleRepository postingRuleRepository,
                           ProvisionLedgerRepository provisionLedgerRepository,
                           VoucherPostingService ledger,
                           BillingService billingService,
                           AccountingProperties properties) {
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
        // El shadow se crea SIEMPRE, aunque no haya monto que asentar. Es la señal de que
        // presenciamos el nacimiento de esta cuenta: sin él, el primer cargo que llegue se tomaría
        // por la incorporación de un saldo previo y traería el préstamo entero como importe.
        AccountBalanceShadow shadow = shadowRepository.findById(creditAccountId)
                .orElseGet(() -> AccountBalanceShadow.init(creditAccountId, obligorPartyId));
        shadow.learnOrgUnit(orgUnitCode);
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
                    List.of(new Pair(AccountCodes.CARTERA_VIGENTE, AccountCodes.BANCOS,
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
        // Que exista el shadow es la señal de que presenciamos el nacimiento de esta cuenta: lo crea
        // `onAccountActivated`. Si no existe, o el hecho que llega abre la vida contable del crédito
        // —la disposición del dinero—, o nos perdimos la historia y el delta contra cero es el saldo
        // acumulado, no el importe del hecho.
        //
        // No sirve mirar `balanceVersion`: cartera la incrementa al activar, así que el primer evento
        // de un crédito recién nacido ya llega en 1 y una guarda por versión marcaría como
        // incorporación a todos los créditos nuevos.
        boolean shadowConocido = shadowRepository.findById(creditAccountId).isPresent();
        boolean incorporacion = !shadowConocido && !abreLaVidaContable(triggerEvent);

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

        if (incorporacion) {
            postOpeningBalance(sourceEventId, creditAccountId, obligorPartyId, unit, delta, occurredAt);
            return;
        }

        if (WRITE_OFF.equals(triggerEvent) || QUITA_PARCIAL.equals(triggerEvent)) {
            postWriteOffOrQuita(sourceEventId, triggerEvent, creditAccountId, obligorPartyId, unit, delta, occurredAt);
            return;
        }
        if (PAYMENT.equals(triggerEvent)) {
            postPayment(sourceEventId, creditAccountId, obligorPartyId, unit, delta, occurredAt);
            return;
        }

        PostingRule rule = postingRuleRepository.findByTriggerEvent(triggerEvent).orElse(null);
        if (rule == null) {
            log.warn("Sin regla de posteo para triggerEvent={} (creditAccountId={}) — no se asienta",
                    triggerEvent, creditAccountId);
            return;
        }
        ledger.post(
                new VoucherRequest(sourceEventId, triggerEvent, occurredAt, creditAccountId, obligorPartyId,
                        unit, rule.getDescription()),
                List.of(new Pair(rule.getDebitAccount(), rule.getCreditAccount(), amount, rule.getDescription())));

        if (isInvoiceableCharge(triggerEvent)) {
            String concept = triggerEvent.substring("CHARGE_".length());
            billingService.accrue(sourceEventId, obligorPartyId, creditAccountId, concept, amount,
                    "CHARGE_IVA".equals(triggerEvent), periodOfFact(occurredAt));
        }
    }

    /**
     * Incorpora a la contabilidad un crédito que ya venía con saldo.
     *
     * <p>Ocurre cuando contabilidad conoce la cuenta a media vida: la cuenta existía antes de que el
     * servicio consumiera, o el tópico se reprocesó desde un offset posterior al desembolso. El
     * delta contra cero es entonces el <b>saldo acumulado</b>, no el importe del hecho que llegó.
     *
     * <p>Sin esto, ese saldo se le atribuía al primer hecho observado y el resultado cuadraba
     * mintiendo: un préstamo de $20,000 con 1% de comisión producía una póliza de «comisión de
     * apertura» de $20,200, la cartera colocada salía en cero y el ingreso por comisiones del mes
     * era cien veces el real.
     *
     * <p>Se asienta con la <b>composición real</b> del saldo —capital a cartera, intereses y
     * moratorios a su auxiliar— contra capital, porque incorporar un saldo preexistente no es
     * ganarlo. Es el asiento de apertura de toda la vida.
     */
    private void postOpeningBalance(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                    String orgUnitCode, BalanceDelta delta, Instant occurredAt) {
        List<Pair> pairs = new ArrayList<>();
        addIfPositive(pairs, AccountCodes.CARTERA_VIGENTE, delta.principalDelta(),
                "Capital incorporado");
        addIfPositive(pairs, AccountCodes.INTERESES_POR_COBRAR, delta.interestDelta(),
                "Interés devengado incorporado");
        addIfPositive(pairs, AccountCodes.INTERESES_POR_COBRAR, delta.penaltyDelta(),
                "Moratorios incorporados");

        if (pairs.isEmpty()) {
            log.warn("Incorporación sin composición creditAccountId={} total={} — no se asienta",
                    creditAccountId, delta.absTotal());
            return;
        }
        log.info("Crédito {} incorporado con saldo previo de {} — asiento de apertura",
                creditAccountId, delta.absTotal());

        ledger.post(new VoucherRequest(sourceEventId, "OPENING_BALANCE", occurredAt, creditAccountId,
                obligorPartyId, orgUnitCode, "Incorporación de crédito con saldo previo"), pairs);
    }

    /**
     * Los hechos con los que un crédito empieza a existir contablemente.
     *
     * <p>La disposición del dinero es lo primero que mueve saldo de verdad. Ver una disposición sobre
     * una cuenta desconocida no es haberse perdido nada: es el principio.
     */
    private static boolean abreLaVidaContable(String triggerEvent) {
        return triggerEvent != null
                && (triggerEvent.startsWith("DISPOSITION") || ACTIVATED.equals(triggerEvent));
    }

    /** Cada componente del saldo entra por su cuenta; lo que venga en cero o negativo, no entra. */
    private static void addIfPositive(List<Pair> pairs, String debitAccount,
                                      BigDecimal amount, String description) {
        if (amount != null && amount.signum() > 0) {
            pairs.add(new Pair(debitAccount, AccountCodes.SALDO_INICIAL, amount, description));
        }
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
                             String orgUnitCode, BalanceDelta delta, Instant occurredAt) {
        List<Pair> pairs = new ArrayList<>();
        // Los deltas de un pago son negativos (el saldo baja); el importe abonado es su valor absoluto.
        BigDecimal principal = negativePart(delta.principalDelta());
        BigDecimal interest  = negativePart(delta.interestDelta());
        BigDecimal penalty   = negativePart(delta.penaltyDelta());

        if (principal.signum() > 0) {
            pairs.add(new Pair(AccountCodes.BANCOS, AccountCodes.CARTERA_VIGENTE, principal,
                    "Aplicación a capital"));
        }
        if (interest.signum() > 0) {
            pairs.add(new Pair(AccountCodes.BANCOS, AccountCodes.INTERESES_POR_COBRAR, interest,
                    "Aplicación a interés devengado"));
        }
        if (penalty.signum() > 0) {
            pairs.add(new Pair(AccountCodes.BANCOS, AccountCodes.INTERESES_POR_COBRAR, penalty,
                    "Aplicación a moratorios"));
        }

        if (pairs.isEmpty()) {
            // Ningún componente bajó pero el total sí: no se puede saber qué liquidó. Se asienta contra
            // cartera con la plantilla, que es el comportamiento anterior, y se avisa — un pago sin
            // desglose es un dato del productor que hay que corregir, no un caso a normalizar.
            log.warn("Pago sin desglose por componente creditAccountId={} total={} — se asienta a capital",
                    creditAccountId, delta.absTotal());
            pairs.add(new Pair(AccountCodes.BANCOS, AccountCodes.CARTERA_VIGENTE, delta.absTotal(),
                    "Pago sin desglose por componente"));
        }

        ledger.post(new VoucherRequest(sourceEventId, PAYMENT, occurredAt, creditAccountId, obligorPartyId,
                orgUnitCode, "Pago recibido — aplica a interés devengado y capital"), pairs);
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
        }
    }

    /** Recuperación post-quebranto (collections.recovery-payment-applied). */
    public void onRecoveryPayment(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                   BigDecimal amount, Instant occurredAt) {
        if (amount == null || amount.signum() <= 0) return;
        ledger.post(
                new VoucherRequest(sourceEventId, "RECOVERY_PAYMENT", occurredAt, creditAccountId,
                        obligorPartyId, unitOf(creditAccountId), "Recuperación de cartera castigada"),
                List.of(new Pair(AccountCodes.BANCOS, AccountCodes.RECUPERACION_CASTIGADA, amount,
                        "Recuperación de cartera castigada")));
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
    private static final String[] WRITEOFF_ACCOUNTS =
            { AccountCodes.CARTERA_VIGENTE, AccountCodes.INTERESES_POR_COBRAR };

    private static boolean isInvoiceableCharge(String triggerEvent) {
        return triggerEvent.startsWith("CHARGE_")
                && !"CHARGE_REVERSED".equals(triggerEvent)
                && !"CHARGE_WAIVED".equals(triggerEvent);
    }

    /** El período al que pertenece el devengo facturable: el del hecho, no el del reloj. */
    private String periodOfFact(Instant occurredAt) {
        Instant at = occurredAt != null ? occurredAt : Instant.now();
        return java.time.YearMonth.from(at.atZone(properties.zoneId())).toString().replace("-", "");
    }
}
