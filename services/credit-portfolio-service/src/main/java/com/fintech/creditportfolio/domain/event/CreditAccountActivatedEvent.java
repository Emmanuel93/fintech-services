package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a CreditAccount transitions to ACTIVE after the first disbursement.
 * contractId = applicationId from origination (loop-close correlation).
 */
public class CreditAccountActivatedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final UUID creditAccountId;
    private final UUID contractId;
    private final UUID obligorPartyId;
    private final String productType;
    private final String productBehavior;
    private final BigDecimal nominalRate;
    private final BigDecimal moratoriumRate;
    private final BigDecimal openingFeeRate;
    private final BigDecimal principalBalance;
    private final BigDecimal creditLimit;
    private final String riskTier;
    private final Instant activatedAt;
    private final String promoterCode;
    /** La sucursal sellada. Es el evento que la da a conocer al resto del sistema. */
    private final String originUnitCode;

    // NUEVO: null cuando no hay dinero que mover (revolvente abre en cero). Presente sólo en la
    // activación con desembolso; disbursement-service es el único de los diez consumidores que
    // actúa sobre él (§8.2). El nombre del campo es literal: el consumidor espera "disbursementInstruction".
    private final DisbursementInstruction disbursementInstruction;

    /**
     * La cadencia de pago del producto y su plazo. <b>Los necesita el cierre para derivar su propio
     * calendario de corte</b> sin consultar el calendario de cartera en línea.
     *
     * <p>Para un producto no revolvente el corte coincide con el vencimiento de la cuota, así que la
     * tentación es que el cierre lea {@code installments.due_date}. No se hace: leer cartera durante
     * la ventana de cierre rompe el aislamiento, el corte es una decisión de política que un plan de
     * pagos no puede expresar —cortar N días antes, correrse si cae inhábil—, y un corte sellado es
     * inmutable, así que una reestructura no puede reescribirlo hacia atrás. Con estos dos campos,
     * el cierre deriva la cadencia y la persiste como suya.
     *
     * <p>Nulos para revolventes, que no tienen plan.
     */
    private final String paymentFrequency;

    /**
     * Desde cuándo devenga interés este crédito. Nulo = desde el alta.
     *
     * <p>La fija BNPL, y {@code charges} la necesita: sin ella devengaría desde el día uno, que es
     * lo contrario de lo que un «compra ahora, paga después» promete.
     */
    private final java.time.LocalDate accrualStartDate;
    private final Integer termPeriods;

    public CreditAccountActivatedEvent(UUID creditAccountId, UUID contractId, UUID obligorPartyId,
                                        String productType, String productBehavior,
                                        BigDecimal nominalRate, BigDecimal moratoriumRate,
                                        BigDecimal openingFeeRate, BigDecimal principalBalance,
                                        BigDecimal creditLimit, String riskTier, Instant activatedAt,
                                        String promoterCode, String originUnitCode,
                                        DisbursementInstruction disbursementInstruction) {
        this(creditAccountId, contractId, obligorPartyId, productType, productBehavior, nominalRate,
                moratoriumRate, openingFeeRate, principalBalance, creditLimit, riskTier, activatedAt,
                promoterCode, originUnitCode, disbursementInstruction, null, null);
    }

    public CreditAccountActivatedEvent(UUID creditAccountId, UUID contractId, UUID obligorPartyId,
                                        String productType, String productBehavior,
                                        BigDecimal nominalRate, BigDecimal moratoriumRate,
                                        BigDecimal openingFeeRate, BigDecimal principalBalance,
                                        BigDecimal creditLimit, String riskTier, Instant activatedAt,
                                        String promoterCode, String originUnitCode,
                                        DisbursementInstruction disbursementInstruction,
                                        String paymentFrequency, Integer termPeriods) {
        this(creditAccountId, contractId, obligorPartyId, productType, productBehavior, nominalRate,
                moratoriumRate, openingFeeRate, principalBalance, creditLimit, riskTier, activatedAt,
                promoterCode, originUnitCode, disbursementInstruction, paymentFrequency, termPeriods,
                null);
    }

    public CreditAccountActivatedEvent(UUID creditAccountId, UUID contractId, UUID obligorPartyId,
                                        String productType, String productBehavior,
                                        BigDecimal nominalRate, BigDecimal moratoriumRate,
                                        BigDecimal openingFeeRate, BigDecimal principalBalance,
                                        BigDecimal creditLimit, String riskTier, Instant activatedAt,
                                        String promoterCode, String originUnitCode,
                                        DisbursementInstruction disbursementInstruction,
                                        String paymentFrequency, Integer termPeriods,
                                        java.time.LocalDate accrualStartDate) {
        this.paymentFrequency = paymentFrequency;
        this.termPeriods      = termPeriods;
        this.accrualStartDate = accrualStartDate;
        this.eventId         = UUID.randomUUID().toString();
        // El hecho ocurrió cuando se activó la cuenta, no cuando se publicó el evento. Contabilidad
        // deriva de aquí el período del alta, así que fijarlo en `now()` ataba la póliza al reloj del
        // productor: un alta procesada tarde caía en el mes equivocado y no había forma de sembrar
        // una cartera con historia.
        this.occurredOn      = activatedAt != null ? activatedAt : Instant.now();
        this.creditAccountId = creditAccountId;
        this.contractId      = contractId;
        this.obligorPartyId  = obligorPartyId;
        this.productType     = productType;
        this.productBehavior = productBehavior;
        this.nominalRate     = nominalRate;
        this.moratoriumRate  = moratoriumRate;
        this.openingFeeRate  = openingFeeRate;
        this.principalBalance = principalBalance;
        this.creditLimit     = creditLimit;
        this.riskTier        = riskTier;
        this.activatedAt     = activatedAt;
        this.promoterCode    = promoterCode;
        this.originUnitCode  = originUnitCode;
        this.disbursementInstruction = disbursementInstruction;
    }

    /**
     * Todo lo que hace falta para pagar, sin que el conector tenga que preguntarle nada a nadie.
     * Los nombres de campo son el contrato con disbursement-service; no se renombran a la ligera.
     */
    public record DisbursementInstruction(
            UUID       dispositionId,          // clave de idempotencia aguas abajo
            UUID       companyId,              // tenant — nullable; disbursement lo resuelve si falta
            String     dispositionType,        // informativo: con el monedero en hold, TODA disposición sale a una cuenta bancaria
            BigDecimal amount,
            String     currency,               // "MXN"
            String     beneficiaryName,
            String     beneficiaryAccount,     // la CLABE congelada en la cuenta
            String     beneficiaryAccountType, // "40" CLABE | "3" tarjeta | "10" celular
            String     beneficiaryTaxId,       // RFC/CURP, nullable
            Integer    beneficiaryInstitution, // nullable — derivable de la CLABE
            Long       numericReference,       // nullable
            String     concept
    ) {}

    public String getEventId()              { return eventId; }
    public Instant getOccurredOn()          { return occurredOn; }
    public UUID getCreditAccountId()        { return creditAccountId; }
    public UUID getContractId()             { return contractId; }
    public UUID getObligorPartyId()         { return obligorPartyId; }
    public String getProductType()          { return productType; }
    public String getProductBehavior()      { return productBehavior; }
    public BigDecimal getNominalRate()      { return nominalRate; }
    public BigDecimal getMoratoriumRate()   { return moratoriumRate; }
    public BigDecimal getOpeningFeeRate()   { return openingFeeRate; }
    public BigDecimal getPrincipalBalance() { return principalBalance; }
    public BigDecimal getCreditLimit()      { return creditLimit; }
    public String getRiskTier()             { return riskTier; }
    public Instant getActivatedAt()         { return activatedAt; }
    public String getPromoterCode()         { return promoterCode; }
    public String getOriginUnitCode()       { return originUnitCode; }
    public DisbursementInstruction getDisbursementInstruction() { return disbursementInstruction; }

    /**
     * Sin estos dos getters los campos <b>no se serializan</b> y el cierre recibe la cadencia nula,
     * cayendo a MONTHLY para todo producto — un calendario de corte quincenal o semanal quedaría
     * mal derivado sin que nada fallara.
     *
     * <p>Es el mismo defecto que ya documenta {@link #getOriginUnitCode()} unas líneas arriba: el
     * campo existe, el constructor lo recibe, y sin getter Jackson no lo escribe. Compila, la suite
     * queda verde, y el dato nunca llega.
     */
    public String getPaymentFrequency()     { return paymentFrequency; }
    public Integer getTermPeriods()         { return termPeriods; }
    /**
     * Sin este getter el campo <b>no se serializa</b> y charges devenga desde el día uno. Es
     * exactamente el defecto que una prueba de contrato cazó con `paymentFrequency` (BK-44).
     */
    public java.time.LocalDate getAccrualStartDate() { return accrualStartDate; }
}
