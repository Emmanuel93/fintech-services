package com.fintech.beneficiary.domain.event;

import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;

import java.util.UUID;

/**
 * Las transiciones que no cargan datos propios: inicio y fin del KYC, buró listo, rechazo,
 * vencimiento, revocación y falla.
 *
 * <p>Comparten forma pero no tópico: {@link #topic()} lo deriva del estado destino, de modo que
 * cada consumidor se suscribe sólo a lo que le toca.
 */
public class PlacementStatusChangedEvent extends PlacementEvent {

    private final PlacementStatus fromStatus;
    private final PlacementStatus toStatus;
    private final UUID beneficiaryPartyId;
    private final String reason;

    public PlacementStatusChangedEvent(Placement placement, PlacementStatus fromStatus, String correlationId) {
        super(placement.getPlacementId(), placement.getDistributorPartyId(), correlationId);
        this.fromStatus         = fromStatus;
        this.toStatus           = placement.getStatus();
        this.beneficiaryPartyId = placement.getBeneficiaryPartyId();
        this.reason             = placement.getStatusReason();
    }

    @Override
    public String topic() {
        return switch (toStatus) {
            case KYC_IN_PROGRESS -> "beneficiary.kyc-started";
            case KYC_COMPLETED   -> "beneficiary.kyc-completed";
            case BUREAU_READY    -> "beneficiary.bureau-ready";
            case PAID_OFF        -> "beneficiary.placement-paid-off";
            case REJECTED        -> "beneficiary.placement-rejected";
            case EXPIRED         -> "beneficiary.placement-expired";
            case CANCELLED       -> "beneficiary.placement-cancelled";
            case FAILED          -> "beneficiary.placement-failed";
            // INVITED, APPROVED, DISBURSING y DISBURSED tienen evento propio con carga útil.
            default -> throw new IllegalStateException(
                    "El estado " + toStatus + " tiene un evento propio y no debe publicarse como cambio genérico");
        };
    }

    public PlacementStatus getFromStatus()  { return fromStatus; }
    public PlacementStatus getToStatus()    { return toStatus; }
    public UUID getBeneficiaryPartyId()     { return beneficiaryPartyId; }
    public String getReason()               { return reason; }
}
