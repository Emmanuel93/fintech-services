package com.fintech.beneficiary.domain.event;

import com.fintech.beneficiary.domain.Placement;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * La disposición ya se pidió y el dinero está en camino.
 *
 * <p>Existe como evento propio, y no como un cambio de estado genérico, porque lleva el
 * {@code dispositionId}: es la única llave que ata esta colocación con el movimiento de
 * credit-portfolio, y sin ella nadie río abajo podría correlacionar el desembolso con la persona a
 * la que se le colocó.
 *
 * <p>No es lo mismo que {@code placement-approved}: aprobar es la decisión del distribuidor, esto
 * es la confirmación de que la orden salió. Entre uno y otro puede fallar el cupo.
 */
public class PlacementDisbursingEvent extends PlacementEvent {

    private final UUID dispositionId;
    private final UUID beneficiaryPartyId;
    private final UUID distributorCreditAccountId;
    private final BigDecimal amount;

    public PlacementDisbursingEvent(Placement placement, String correlationId) {
        super(placement.getPlacementId(), placement.getDistributorPartyId(), correlationId);
        this.dispositionId              = placement.getDispositionId();
        this.beneficiaryPartyId         = placement.getBeneficiaryPartyId();
        this.distributorCreditAccountId = placement.getDistributorCreditAccountId();
        this.amount                     = placement.getAmount();
    }

    @Override
    public String topic() {
        return "beneficiary.placement-disbursing";
    }

    public UUID getDispositionId()              { return dispositionId; }
    public UUID getBeneficiaryPartyId()         { return beneficiaryPartyId; }
    public UUID getDistributorCreditAccountId() { return distributorCreditAccountId; }
    public BigDecimal getAmount()               { return amount; }
}
