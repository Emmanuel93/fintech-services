package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
        this.eventAmount           = eventAmount;
        this.principalDelta        = principalDelta;
        this.interestDelta         = interestDelta;
        this.penaltyDelta          = penaltyDelta;
        this.eventId               = UUID.randomUUID().toString();
        this.occurredOn            = occurredOn != null ? occurredOn : Instant.now();
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
}
