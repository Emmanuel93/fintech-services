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

    /**
     * El hecho: este cliente pasó a ser de este ejecutivo.
     *
     * <p>Es un <b>hecho de dominio</b>, no una petición de aviso. Party no sabe que alguien lo va a
     * notificar, ni menciona claves de evento, tipos de destinatario ni canales — ese vocabulario
     * es del notificador y meterlo aquí acopla el núcleo del negocio a un canal.
     *
     * <p>Quien quiera reaccionar se suscribe: hoy notifications, para avisarle al ejecutivo; mañana
     * auditoría, la estructura comercial o comisiones, sin que party se entere ni cambie.
     */
    void publishExecutiveAssigned(UUID partyId, UUID executiveId, String executiveName, String clientName);
}
