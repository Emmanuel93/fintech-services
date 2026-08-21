package com.fintech.beneficiary.application.port.in;

import com.fintech.beneficiary.application.CreatePlacementCommand;
import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.VerificationSource;

import java.util.UUID;

/**
 * El ciclo de vida de una colocación, transición por transición.
 *
 * <p>Los métodos son deliberadamente estrechos —uno por arista de la máquina de estados— en vez de
 * un {@code transition(placementId, targetStatus)} genérico. La razón es que cada arista tiene sus
 * propios requisitos: aprobar exige la aceptación del riesgo, cerrar el KYC exige prospecto, Party
 * y CLABE, y desembolsar exige la disposición. Un método genérico movería esas validaciones fuera
 * del dominio, que es justo donde tienen que estar.
 */
public interface PlacementLifecycleUseCase {

    /** Crea la colocación en {@code INVITED}. La liga la acuña quien llama, en la fase 2. */
    Placement create(CreatePlacementCommand command);

    Placement findById(UUID placementId);

    /** La beneficiaria abrió la liga y pasó su OTP. */
    Placement startKyc(UUID placementId);

    /** Los 7 pasos quedaron completos y el expediente ya existe. */
    Placement completeKyc(UUID placementId, UUID prospectId, UUID partyId, String clabe);

    /** Scoring respondió: el reporte ya se le puede mostrar al distribuidor. */
    Placement bureauReady(UUID placementId);

    /** El distribuidor decide colocar. Sin aceptación de riesgo no procede. */
    Placement approve(UUID placementId, UUID distributorPartyId,
                      boolean riskAcknowledged, UUID riskAcknowledgementId);

    /** El distribuidor decide no colocar. */
    Placement reject(UUID placementId, UUID distributorPartyId, String reason);

    /** Disposición pedida; credit-portfolio tiene la última palabra sobre el cupo. */
    Placement markDisbursing(UUID placementId, UUID dispositionId);

    /** El SPEI llegó a su CLABE. */
    /**
     * Ata la colocación a la disposición real cuando el id definitivo llega por evento.
     * Idempotente: si ya coincide, no escribe.
     */
    Placement reconcileDispositionId(UUID placementId, UUID dispositionId);

    Placement markDisbursed(UUID placementId);

    /** Ella terminó de pagar: credit-portfolio reporta el calendario de la disposición saldado. */
    Placement markPaidOff(UUID placementId);

    /** Vencieron los 7 días de la liga. */
    Placement expire(UUID placementId);

    /** El distribuidor revoca la liga, sólo mientras no haya expediente. */
    Placement cancel(UUID placementId, UUID distributorPartyId, String reason);

    /** Algo se rompió. Siempre con motivo. */
    Placement fail(UUID placementId, String reason);

    /**
     * Registra el veredicto de identidad de la mesa de KYC.
     *
     * <p>Es un juicio de Kredius y va aparte del ciclo comercial: no mueve el estado de la
     * colocación, lo habilita. Sin identidad comprobada, {@code approve} no procede.
     */
    Placement reviewIdentity(UUID placementId, IdentityDecision decision, VerificationSource source,
                             String decidedBy, String rejectionReason);
}
