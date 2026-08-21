package com.fintech.stp.infrastructure.adapter.in.api.dto;

import com.fintech.stp.domain.SettlementObservation;

import java.time.Instant;
import java.util.UUID;

/** Evidencia de lo que STP contestó. Sin el payload crudo: eso se consulta en la bitácora. */
public record SettlementObservationResponse(
        UUID observationId,
        String trackingKey,
        String observedStatus,
        String observedAtSource,
        String returnCauseCode,
        String cepUrl,
        String cepBeneficiaryName,
        Boolean signatureOk,
        String observedVia,
        String status,
        String detail,
        Instant observedAt
) {

    public static SettlementObservationResponse from(SettlementObservation observation) {
        return new SettlementObservationResponse(
                observation.getObservationId(), observation.getTrackingKey(),
                observation.getObservedStatus(), observation.getObservedAtSource(),
                observation.getReturnCauseCode(), observation.getCepUrl(),
                observation.getCepBeneficiaryName(), observation.getSignatureOk(),
                observation.getObservedVia().name(), observation.getStatus(),
                observation.getDetail(), observation.getObservedAt());
    }
}
