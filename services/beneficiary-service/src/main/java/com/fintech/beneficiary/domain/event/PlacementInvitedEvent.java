package com.fintech.beneficiary.domain.event;

import com.fintech.beneficiary.domain.Placement;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * La colocación se creó y la liga está lista para enviarse.
 *
 * <p>Lo consume notifications para mandarle el WhatsApp a la beneficiaria. Lleva el nombre, el
 * teléfono, el monto y el plazo porque la plantilla los necesita, pero <b>nunca el token</b>: el
 * secreto viaja en la URL que arma notifications a partir del {@code inviteRef}, y un token en un
 * tópico de Kafka es un token en los logs de todos los consumidores.
 */
public class PlacementInvitedEvent extends PlacementEvent {

    private final String beneficiaryFullName;
    private final String beneficiaryPhone;
    private final BigDecimal amount;
    private final int termFortnights;
    private final Instant inviteExpiresAt;

    public PlacementInvitedEvent(Placement placement, Instant inviteExpiresAt, String correlationId) {
        super(placement.getPlacementId(), placement.getDistributorPartyId(), correlationId);
        this.beneficiaryFullName = placement.getBeneficiaryFullName();
        this.beneficiaryPhone    = placement.getBeneficiaryPhone();
        this.amount              = placement.getAmount();
        this.termFortnights      = placement.getTermFortnights();
        this.inviteExpiresAt     = inviteExpiresAt;
    }

    @Override
    public String topic() {
        return "beneficiary.placement-invited";
    }

    public String getBeneficiaryFullName() { return beneficiaryFullName; }
    public String getBeneficiaryPhone()    { return beneficiaryPhone; }
    public BigDecimal getAmount()          { return amount; }
    public int getTermFortnights()         { return termFortnights; }
    public Instant getInviteExpiresAt()    { return inviteExpiresAt; }
}
