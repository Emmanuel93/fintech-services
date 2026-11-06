package com.fintech.creditportfolio.domain.event;

import com.fintech.creditportfolio.domain.PaymentFacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * Published after any balance mutation. Consumed by wallet (projection), collections,
 * charges/payments (their local projections) and T4 accounting.
 */
public class BalanceUpdatedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final UUID creditAccountId;
    private final UUID obligorPartyId;
    private final BigDecimal principalBalance;
    private final BigDecimal accruedInterestBalance;
    private final BigDecimal penaltyBalance;
    private final BigDecimal availableCredit;
    private final BigDecimal totalDebt;
    private final String triggerEvent;
    private final String accountStatus;
    private final long balanceVersion;
    /** La sucursal sellada del crédito. Accounting la copia en cada póliza. */
    private final String originUnitCode;
    /**
     * El importe de <b>este</b> hecho, con signo. Positivo carga la deuda, negativo la abona.
     *
     * <p>Sin él, contabilidad tenía que deducir el importe restando el saldo nuevo contra el que
     * recordaba, y ese cálculo es frágil: basta que dos eventos del mismo crédito se procesen fuera
     * de orden para que un delta arrastre lo que no le toca. Como además se tomaba en valor
     * absoluto, la magnitud de un pago de dos millones acababa asentada como si fuera el devengo de
     * interés de un día.
     *
     * <p>Cartera ya lo tiene calculado —es el mismo número que guarda en {@code balance_events}—, así
     * que publicarlo cuesta un campo y elimina la deducción entera.
     */
    private final java.math.BigDecimal eventAmount;
    /**
     * Cuánto movió este hecho en cada componente, con signo. Es lo que permite desglosar un pago.
     *
     * <p>Un pago no se aplica todo a capital: liquida primero moratorios, luego interés devengado y
     * al final capital. Contabilidad necesita ese reparto para abonar cada cuenta por lo suyo, y
     * antes lo deducía restando los saldos que recordaba — con lo que un evento desordenado le
     * atribuía a capital lo que en realidad relevó intereses, y {@code 1203} no bajaba nunca.
     */
    private final java.math.BigDecimal principalDelta;
    private final java.math.BigDecimal interestDelta;
    private final java.math.BigDecimal penaltyDelta;
    /**
     * El día de negocio al que pertenece el hecho. El devengo del día D se aplica a las 00:05 de D+1 y
     * un pago de las 00:07 es de D+1: con esta fecha, closing sella el saldo <b>al cierre de D</b> sin
     * importar a qué hora corra el corte ni qué haya llegado después.
     */
    private final java.time.LocalDate valueDate;
    /** The fact that moved the balances (a payment order id, a charge id): what payments confirms its order by. */
    private String sourceEventId;
    /**
     * The IVA part of the move, signed. {@code penaltyDelta} keeps carrying it too (accounting books both to 1203);
     * apart, it lets a reversal or a return reach 2110 and not the moratorium income.
     */
    private BigDecimal ivaDelta;
    /** For a reversed or waived charge: the type of the charge, which decides the income or tax account it undoes. */
    private String chargeType;

    public BalanceUpdatedEvent(UUID creditAccountId, UUID obligorPartyId,
                               BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                               BigDecimal penaltyBalance, BigDecimal availableCredit,
                               BigDecimal totalDebt, String triggerEvent, String accountStatus,
                               long balanceVersion, String originUnitCode) {
        this(creditAccountId, obligorPartyId, principalBalance, accruedInterestBalance, penaltyBalance,
                availableCredit, totalDebt, triggerEvent, accountStatus, balanceVersion, originUnitCode,
                null, null);
    }

    public BalanceUpdatedEvent(UUID creditAccountId, UUID obligorPartyId,
                               BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                               BigDecimal penaltyBalance, BigDecimal availableCredit,
                               BigDecimal totalDebt, String triggerEvent, String accountStatus,
                               long balanceVersion, String originUnitCode, Instant occurredOn) {
        this(creditAccountId, obligorPartyId, principalBalance, accruedInterestBalance, penaltyBalance,
                availableCredit, totalDebt, triggerEvent, accountStatus, balanceVersion, originUnitCode,
                occurredOn, null);
    }

    /**
     * @param occurredOn cuándo ocurrió el hecho; {@code null} = ahora.
     *
     * <p>Contabilidad deriva el <b>período</b> de este instante, no de su propio reloj. Mientras se
     * fijaba siempre en {@code now()}, un devengo del 30 de junio procesado hoy caía en el período de
     * hoy: la contabilidad no podía tener historia, y sembrarla obligaba a escribir saldos a mano.
     */
    public BalanceUpdatedEvent(UUID creditAccountId, UUID obligorPartyId,
                               BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                               BigDecimal penaltyBalance, BigDecimal availableCredit,
                               BigDecimal totalDebt, String triggerEvent, String accountStatus,
                               long balanceVersion, String originUnitCode, Instant occurredOn,
                               java.math.BigDecimal eventAmount) {
        this(creditAccountId, obligorPartyId, principalBalance, accruedInterestBalance, penaltyBalance,
                availableCredit, totalDebt, triggerEvent, accountStatus, balanceVersion, originUnitCode,
                occurredOn, eventAmount, null, null, null);
    }

    public BalanceUpdatedEvent(UUID creditAccountId, UUID obligorPartyId,
                               BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                               BigDecimal penaltyBalance, BigDecimal availableCredit,
                               BigDecimal totalDebt, String triggerEvent, String accountStatus,
                               long balanceVersion, String originUnitCode, Instant occurredOn,
                               java.math.BigDecimal eventAmount,
                               java.math.BigDecimal principalDelta,
                               java.math.BigDecimal interestDelta,
                               java.math.BigDecimal penaltyDelta) {
        this(creditAccountId, obligorPartyId, principalBalance, accruedInterestBalance, penaltyBalance,
                availableCredit, totalDebt, triggerEvent, accountStatus, balanceVersion, originUnitCode,
                occurredOn, eventAmount, principalDelta, interestDelta, penaltyDelta, null);
    }

    /**
     * @param valueDate el día de negocio al que pertenece el hecho —el mismo que guarda
     *                  {@code balance_events.value_date}—; {@code null} = hoy.
     */
    public BalanceUpdatedEvent(UUID creditAccountId, UUID obligorPartyId,
                               BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                               BigDecimal penaltyBalance, BigDecimal availableCredit,
                               BigDecimal totalDebt, String triggerEvent, String accountStatus,
                               long balanceVersion, String originUnitCode, Instant occurredOn,
                               java.math.BigDecimal eventAmount,
                               java.math.BigDecimal principalDelta,
                               java.math.BigDecimal interestDelta,
                               java.math.BigDecimal penaltyDelta,
                               java.time.LocalDate valueDate) {
        this.valueDate             = valueDate != null ? valueDate : com.fintech.shared.time.BusinessClock.current().today();
        this.eventAmount           = eventAmount;
        this.principalDelta        = principalDelta;
        this.interestDelta         = interestDelta;
        this.penaltyDelta          = penaltyDelta;
        this.eventId               = UUID.randomUUID().toString();
        this.occurredOn            = occurredOn != null ? occurredOn : BusinessClock.current().now();
        this.creditAccountId       = creditAccountId;
        this.obligorPartyId        = obligorPartyId;
        this.principalBalance      = principalBalance;
        this.accruedInterestBalance = accruedInterestBalance;
        this.penaltyBalance        = penaltyBalance;
        this.availableCredit       = availableCredit;
        this.totalDebt             = totalDebt;
        this.triggerEvent          = triggerEvent;
        this.accountStatus         = accountStatus;
        this.balanceVersion        = balanceVersion;
        this.originUnitCode        = originUnitCode;
    }

    public String getEventId()                   { return eventId; }
    public Instant getOccurredOn()               { return occurredOn; }
    public UUID getCreditAccountId()             { return creditAccountId; }
    public UUID getObligorPartyId()              { return obligorPartyId; }
    public BigDecimal getPrincipalBalance()      { return principalBalance; }
    public BigDecimal getAccruedInterestBalance(){ return accruedInterestBalance; }
    public BigDecimal getPenaltyBalance()        { return penaltyBalance; }
    public BigDecimal getAvailableCredit()       { return availableCredit; }
    public BigDecimal getTotalDebt()             { return totalDebt; }
    public String getTriggerEvent()              { return triggerEvent; }
    public String getAccountStatus()             { return accountStatus; }
    public long getBalanceVersion()              { return balanceVersion; }
    /**
     * Sin este getter el campo no se serializaba y la sucursal <b>nunca viajaba</b> en el evento:
     * contabilidad la daba por nula y sólo la recuperaba después, corriendo la reconciliación de
     * origen a mano. Cada póliza nacía sin sucursal hasta que alguien barría.
     */
    public String getOriginUnitCode()            { return originUnitCode; }
    public java.math.BigDecimal getEventAmount()    { return eventAmount; }
    public java.math.BigDecimal getPrincipalDelta() { return principalDelta; }
    public java.math.BigDecimal getInterestDelta()  { return interestDelta; }
    public java.math.BigDecimal getPenaltyDelta()   { return penaltyDelta; }
    public java.time.LocalDate getValueDate()       { return valueDate; }
    public String getSourceEventId()                { return sourceEventId; }

    public BalanceUpdatedEvent withSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
        return this;
    }

    public BigDecimal getIvaDelta()  { return ivaDelta; }
    public String getChargeType()    { return chargeType; }

    /** The IVA moved and, for a reversal, the type of the charge reversed. */
    private String fundingSource;
    private java.time.LocalDate operationDate;
    private Instant receivedAt;
    private UUID inboundCreditId;
    private String allocationMode;
    private String trackingKey;

    /**
     * For a payment: where its money came from and its two dates, so accounting can book the bank side on the SPEI
     * operation date and the loan side on the day it was received.
     */
    public BalanceUpdatedEvent withPayment(PaymentFacts payment) {
        if (payment != null) {
            this.fundingSource   = payment.fundingSource();
            this.operationDate   = payment.operationDate();
            this.receivedAt      = payment.receivedAt();
            this.inboundCreditId = payment.inboundCreditId();
            this.allocationMode  = payment.allocationMode();
            this.trackingKey     = payment.trackingKey();
        }
        return this;
    }

    public String getFundingSource()                { return fundingSource; }
    public java.time.LocalDate getOperationDate()   { return operationDate; }
    public Instant getReceivedAt()                  { return receivedAt; }
    public UUID getInboundCreditId()                { return inboundCreditId; }
    public String getAllocationMode()               { return allocationMode; }
    public String getTrackingKey()                  { return trackingKey; }

    public BalanceUpdatedEvent withDetail(BigDecimal ivaDelta, String chargeType) {
        this.ivaDelta   = ivaDelta;
        this.chargeType = chargeType;
        return this;
    }

    /**
     * How the distributor's commission on this account is settled ({@code NETTED} or {@code PAYOUT}); null when the
     * product pays no distributor. On a payment it tells commission-service not to accrue a share of the interest
     * collected: the commission travels in its own {@code COMMISSION_NETTED} event.
     */
    private String commissionSettlement;
    /** For a {@code COMMISSION_NETTED} (or its reversal): the commission settled, the statement and the tier. */
    private CommissionNetting commission;

    /**
     * The distributor's commission netted against a statement: her flat share of what the clients paid that statement
     * ({@code basis} at the on-time {@code rate}), scaled to the tier of the day the statement got paid.
     *
     * @param cycleNumber    the statement's cycle
     * @param paymentDueDate the statement's due date
     * @param daysLate       days from that date to the day the payment landed (0 or less: on time)
     * @param rate           the tier's share, what she earned
     * @param onTimeRate     the on-time share, what she would have earned
     * @param basis          what her clients paid that statement, as the share counts it
     * @param amount         the commission settled
     * @param paymentOrder   the payment that completed the statement
     */
    public record CommissionNetting(int cycleNumber, LocalDate paymentDueDate, long daysLate, BigDecimal rate,
                                    BigDecimal onTimeRate, BigDecimal basis, BigDecimal amount, String paymentOrder) {}

    public BalanceUpdatedEvent withCommissionSettlement(String settlement) {
        this.commissionSettlement = settlement;
        return this;
    }

    public BalanceUpdatedEvent withCommission(CommissionNetting commission) {
        this.commission = commission;
        return this;
    }

    public String getCommissionSettlement() { return commissionSettlement; }
    public CommissionNetting getCommission() { return commission; }
}
