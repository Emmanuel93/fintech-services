package com.fintech.beneficiary.domain.event;

import com.fintech.beneficiary.domain.Placement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * El distribuidor decidió colocar y firmó que asume el riesgo.
 *
 * <p>Es el evento que dispara la disposición {@code THIRD_PARTY_CREDIT} y, con ella, el único
 * momento en que la línea se descuenta. Lleva el {@code riskAcknowledgementId} porque la evidencia
 * de que aceptó el riesgo es parte del asiento regulatorio: Kredius no aprobó nada, y el registro
 * tiene que poder demostrarlo.
 */
public class PlacementApprovedEvent extends PlacementEvent {

    private final UUID beneficiaryPartyId;
    private final UUID distributorCreditAccountId;
    private final UUID riskAcknowledgementId;
    private final BigDecimal amount;
    private final int termFortnights;
    private final String beneficiaryClabe;
    private final Instant decidedAt;

    public PlacementApprovedEvent(Placement placement, UUID riskAcknowledgementId, String correlationId) {
        super(placement.getPlacementId(), placement.getDistributorPartyId(), correlationId);
        this.beneficiaryPartyId         = placement.getBeneficiaryPartyId();
        this.distributorCreditAccountId = placement.getDistributorCreditAccountId();
        this.riskAcknowledgementId      = riskAcknowledgementId;
        this.amount                     = placement.getAmount();
        this.termFortnights             = placement.getTermFortnights();
        this.beneficiaryClabe           = placement.getBeneficiaryClabe();
        this.decidedAt                  = placement.getDecidedAt();
    }

    @Override
    public String topic() {
        return "beneficiary.placement-approved";
    }

    public UUID getBeneficiaryPartyId()         { return beneficiaryPartyId; }
    public UUID getDistributorCreditAccountId() { return distributorCreditAccountId; }
    public UUID getRiskAcknowledgementId()      { return riskAcknowledgementId; }
    public BigDecimal getAmount()               { return amount; }
    public int getTermFortnights()              { return termFortnights; }
    public String getBeneficiaryClabe()         { return beneficiaryClabe; }
    public Instant getDecidedAt()               { return decidedAt; }
}
