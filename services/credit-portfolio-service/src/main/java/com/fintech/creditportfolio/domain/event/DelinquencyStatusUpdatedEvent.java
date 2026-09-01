package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Los días de atraso de una cuenta, y <b>sobre cuánto</b>.
 *
 * <p>Llevaba sólo los días, y por eso quien lo consumiera no podía calcular un moratorio correcto:
 * tendría que adivinar la base. {@code overduePrincipal} y {@code oldestDueDate} son lo que
 * convierte este evento en suficiente — cartera ya los tiene calculados cuando publica, porque de
 * las mismas cuotas vencidas sale el DPD.
 *
 * <p><b>Capital vencido, no importe vencido.</b> El moratorio se cobra sobre el capital en mora; si
 * se cobrara sobre el importe total de la cuota, se estaría cobrando interés sobre interés — que es
 * anatocismo, y en crédito al consumo no procede sin pacto expreso. La diferencia no es teórica:
 * en una cuota francesa temprana, el interés es la mayor parte del importe.
 */
public class DelinquencyStatusUpdatedEvent {

    private final UUID creditAccountId;
    private final UUID obligorPartyId;
    private final String contractNumber;
    private final int daysDelinquent;
    private final BigDecimal overduePrincipal;
    private final LocalDate oldestDueDate;
    private final Instant occurredAt;

    public DelinquencyStatusUpdatedEvent(UUID creditAccountId, UUID obligorPartyId,
                                         String contractNumber, int daysDelinquent,
                                         BigDecimal overduePrincipal, LocalDate oldestDueDate) {
        this.creditAccountId  = creditAccountId;
        this.obligorPartyId   = obligorPartyId;
        this.contractNumber   = contractNumber;
        this.daysDelinquent   = daysDelinquent;
        this.overduePrincipal = overduePrincipal != null ? overduePrincipal : BigDecimal.ZERO;
        this.oldestDueDate    = oldestDueDate;
        this.occurredAt       = Instant.now();
    }

    public UUID getCreditAccountId()        { return creditAccountId; }
    public UUID getObligorPartyId()         { return obligorPartyId; }
    public String getContractNumber()       { return contractNumber; }
    public int getDaysDelinquent()          { return daysDelinquent; }
    /** Capital de las cuotas vencidas sin cubrir. Es la base del moratorio. */
    public BigDecimal getOverduePrincipal() { return overduePrincipal; }
    /** Vencimiento de la cuota vencida más antigua. Nulo si la cuenta está al corriente. */
    public LocalDate getOldestDueDate()     { return oldestDueDate; }
    public Instant getOccurredAt()          { return occurredAt; }
}
