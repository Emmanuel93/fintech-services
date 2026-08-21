package com.fintech.beneficiary.infrastructure.adapter.in.api.dto;

import com.fintech.beneficiary.domain.IdentityStatus;
import com.fintech.beneficiary.domain.Placement;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Una colocación como la ve la mesa de KYC, que no es como la ve la distribuidora.
 *
 * <p>Dos diferencias deliberadas contra {@code PlacementResponse}:
 *
 * <ul>
 *   <li><b>El estado va sin colapsar.</b> La app recibe {@code wireName()} —donde
 *       {@code KYC_COMPLETED} y {@code KYC_IN_PROGRESS} son el mismo «verificándose»— porque a la
 *       distribuidora no le sirve la diferencia. A la mesa sí: es la frontera entre «no ha
 *       terminado» y «terminó y estamos esperando al buró», que son dos problemas distintos con
 *       dos responsables distintos.</li>
 *   <li><b>El celular sigue enmascarado.</b> Ser operador no es razón para ver el teléfono
 *       completo de una persona en una lista; si hace falta para contactarla, eso es una acción
 *       con su propio registro de acceso, no un campo de la bandeja.</li>
 * </ul>
 */
public record BackofficePlacementResponse(
        UUID placementId,
        UUID distributorPartyId,
        String beneficiaryName,
        String beneficiaryPhoneMask,
        UUID beneficiaryPartyId,
        String status,
        String statusReason,
        String identityStatus,
        boolean disbursementAllowed,
        /** El veredicto explícito. `PENDING` = nadie lo ha revisado todavía. */
        String identityDecision,
        String identityVerificationSource,
        String identityDecidedBy,
        Instant identityDecidedAt,
        String identityRejectionReason,
        /** Por qué está donde está: umbral, documento o proveedor caído. */
        String identityReviewNotes,
        BigDecimal amount,
        int termFortnights,
        Instant createdAt,
        Instant updatedAt,
        /** Días sin moverse. Es el SLA de la bandeja: cuánto lleva atorada, no cuánto lleva viva. */
        long staleDays) {

    public static BackofficePlacementResponse from(Placement p, Instant now) {
        IdentityStatus identity = IdentityStatus.of(p);
        return new BackofficePlacementResponse(
                p.getPlacementId(),
                p.getDistributorPartyId(),
                p.getBeneficiaryFullName(),
                mask(p.getBeneficiaryPhone()),
                p.getBeneficiaryPartyId(),
                p.getStatus().name(),
                p.getStatusReason(),
                identity.name(),
                identity.allowsDisbursement(),
                p.getIdentityDecision().name(),
                p.getIdentityVerificationSource() == null ? null : p.getIdentityVerificationSource().name(),
                p.getIdentityDecidedBy(),
                p.getIdentityDecidedAt(),
                p.getIdentityRejectionReason(),
                p.getIdentityReviewNotes(),
                p.getAmount(),
                p.getTermFortnights(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                Duration.between(p.getUpdatedAt(), now).toDays());
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() != 10) return "•• •• •• •• ••";
        return phone.substring(0, 2) + " •• •• " + phone.substring(6, 8) + " " + phone.substring(8);
    }
}
