package com.fintech.beneficiary.infrastructure.adapter.in.api.dto;

import com.fintech.beneficiary.domain.Placement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * El objeto Colocación tal como lo parsea la app (`PlacementDto`).
 *
 * <p>Campo por campo contra `KREDIUS_COLOCACION_API.md` §3. No es una vista cómoda del agregado:
 * es un contrato que ya está en producción del lado del cliente, y cualquier renombre aquí rompe
 * una app que no se puede redesplegar al mismo tiempo que el backend.
 *
 * <p>Tres campos no salen del agregado sino de otros servicios —la comisión de commission, los
 * pagos y la mora de credit-portfolio— y por eso viajan en {@link PlacementMetrics}. Mientras esas
 * fases no existan llegan en cero, que es lo correcto para una colocación que todavía no cobra
 * nada; lo que no sería correcto es omitirlos y que la app tenga que adivinar.
 */
public record PlacementResponse(
        UUID placementId,
        String beneficiaryName,
        String beneficiaryPhoneMask,
        UUID beneficiaryPartyId,
        String relationship,
        BigDecimal amount,
        int termFortnights,
        BigDecimal fortnightlyPayment,
        String status,
        BigDecimal commissionAccrued,
        int paymentsMade,
        int daysPastDue,
        Instant placedOn,
        Instant inviteExpiresAt) {

    public static PlacementResponse from(Placement p) {
        return from(p, PlacementMetrics.none());
    }

    public static PlacementResponse from(Placement p, PlacementMetrics metrics) {
        return new PlacementResponse(
                p.getPlacementId(),
                p.getBeneficiaryFullName(),
                mask(p.getBeneficiaryPhone()),
                p.getBeneficiaryPartyId(),
                p.getBeneficiaryRelationship(),
                p.getAmount(),
                p.getTermFortnights(),
                p.getFortnightlyPayment(),
                p.getStatus().wireName(),
                metrics.commissionAccrued(),
                metrics.paymentsMade(),
                metrics.daysPastDue(),
                // `placedOn` es cuándo quedó colocada de verdad: el día que le llegó el dinero.
                p.getDisbursedAt(),
                p.getInviteExpiresAt());
    }

    /**
     * {@code 5541829037} → {@code 55 •• •• 90 37}.
     *
     * <p>Se enmascara <b>en el servidor</b> y el crudo no se serializa nunca. El distribuidor
     * capturó ese número, pero es de un tercero y la pantalla donde se pinta puede estar a la
     * vista de cualquiera; que el cliente reciba el dato completo y decida taparlo sería confiar
     * la privacidad de alguien a una decisión de UI.
     */
    private static String mask(String phone) {
        if (phone == null || phone.length() != 10) return "•• •• •• •• ••";
        return phone.substring(0, 2) + " •• •• " + phone.substring(6, 8) + " " + phone.substring(8);
    }
}
