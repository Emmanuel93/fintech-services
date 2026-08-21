package com.fintech.beneficiary.domain.event;

import com.fintech.beneficiary.domain.Placement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * El dinero llegó a la CLABE de la beneficiaria. La colocación quedó viva.
 *
 * <p>Es el punto donde el distribuidor empieza a devengar: a partir de aquí, cada pago que se cobre
 * a tiempo le genera bonificación. La comisión la calcula commission-service contra los pagos
 * reales; este evento sólo marca el arranque.
 */
public class PlacementDisbursedEvent extends PlacementEvent {

    private final UUID beneficiaryPartyId;
    private final UUID distributorCreditAccountId;
    private final UUID dispositionId;
    private final BigDecimal amount;
    private final int termFortnights;
    private final Instant disbursedAt;

    public PlacementDisbursedEvent(Placement placement, String correlationId) {
        super(placement.getPlacementId(), placement.getDistributorPartyId(), correlationId);
        this.beneficiaryPartyId         = placement.getBeneficiaryPartyId();
        this.distributorCreditAccountId = placement.getDistributorCreditAccountId();
        this.dispositionId              = placement.getDispositionId();
        this.amount                     = placement.getAmount();
        this.termFortnights             = placement.getTermFortnights();
        this.disbursedAt                = placement.getDisbursedAt();
    }

    @Override
    public String topic() {
        return "beneficiary.placement-disbursed";
    }

    public UUID getBeneficiaryPartyId()         { return beneficiaryPartyId; }
    public UUID getDistributorCreditAccountId() { return distributorCreditAccountId; }
    public UUID getDispositionId()              { return dispositionId; }
    public BigDecimal getAmount()               { return amount; }
    public int getTermFortnights()              { return termFortnights; }
    public Instant getDisbursedAt()             { return disbursedAt; }
}
