package com.fintech.beneficiary.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * La constancia: la beneficiaria autorizó la consulta de su buró, con fecha, hora e IP.
 *
 * <p>Es el evento más importante del servicio desde el lado regulatorio, y el que hace verdadera la
 * regla del diseño: <i>«Consultamos su buró con la autorización que ella firmó»</i> — nunca la del
 * distribuidor, que por eso jamás ve la pantalla de consentimiento de su clienta.
 *
 * <p>Lo consume audit-service, que es suscriptor global, y ahí queda el asiento inmutable. Lleva la
 * versión del texto que ella aceptó porque una autorización sin saber a qué texto corresponde no
 * prueba nada, y el {@code otpVerificationId} porque es lo que ata el consentimiento a un teléfono
 * demostrablemente suyo.
 */
public class BureauConsentGrantedEvent extends PlacementEvent {

    private final UUID consentRecordId;
    private final UUID beneficiaryPartyId;
    private final UUID beneficiaryProspectId;
    private final String consentTextVersion;
    private final String ipAddress;
    private final String userAgent;
    private final UUID otpVerificationId;
    private final Instant acceptedAt;

    public BureauConsentGrantedEvent(UUID placementId, UUID distributorPartyId, UUID consentRecordId,
                                     UUID beneficiaryPartyId, UUID beneficiaryProspectId,
                                     String consentTextVersion, String ipAddress, String userAgent,
                                     UUID otpVerificationId, Instant acceptedAt, String correlationId) {
        super(placementId, distributorPartyId, correlationId);
        this.consentRecordId       = consentRecordId;
        this.beneficiaryPartyId    = beneficiaryPartyId;
        this.beneficiaryProspectId = beneficiaryProspectId;
        this.consentTextVersion    = consentTextVersion;
        this.ipAddress             = ipAddress;
        this.userAgent             = userAgent;
        this.otpVerificationId     = otpVerificationId;
        this.acceptedAt            = acceptedAt;
    }

    @Override
    public String topic() {
        return "beneficiary.bureau-consent-granted";
    }

    public UUID getConsentRecordId()       { return consentRecordId; }
    public UUID getBeneficiaryPartyId()    { return beneficiaryPartyId; }
    public UUID getBeneficiaryProspectId() { return beneficiaryProspectId; }
    public String getConsentTextVersion()  { return consentTextVersion; }
    public String getIpAddress()           { return ipAddress; }
    public String getUserAgent()           { return userAgent; }
    public UUID getOtpVerificationId()     { return otpVerificationId; }
    public Instant getAcceptedAt()         { return acceptedAt; }
}
