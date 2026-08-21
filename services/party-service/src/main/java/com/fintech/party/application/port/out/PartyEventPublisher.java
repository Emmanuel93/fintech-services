package com.fintech.party.application.port.out;

import java.util.UUID;

public interface PartyEventPublisher {
    void publishPartyBlacklisted(UUID partyId, String reason, String sourceList);

    /** Perfil fiscal capturado/actualizado — lo consume Facturación para construir el receptor del CFDI. */
    void publishFiscalProfileUpdated(UUID partyId, UUID prospectId, String partyType, String rfc, String taxName,
                                     String taxRegime, String taxZipCode, String cfdiUse);

    /** Rol adicional otorgado (p.ej. DISTRIBUTOR) — sales-org lo usa para su posición en la matriz. */
    void publishRoleGranted(UUID partyId, String roleType, String grantedBy);

    /** Rol adicional revocado — sales-org desactiva el nodo correspondiente. */
    void publishRoleRevoked(UUID partyId, String roleType);
}
