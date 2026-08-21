package com.fintech.identity.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface MfaPendingRepository {

    /**
     * Almacena un token MFA temporal asociado al partyId y username del usuario.
     *
     * @param partyId  identificador del party
     * @param deviceId identificador de dispositivo (puede ser null)
     * @param username nombre de usuario, necesario para el evento de auditoría en la verificación
     * @return token opaco de un solo uso
     */
    String store(UUID partyId, String deviceId, String username);

    /**
     * Consume el token MFA (one-time use) y retorna los datos asociados.
     * Elimina la entrada de Redis en la misma operación atómica.
     */
    Optional<MfaPendingData> consume(String mfaToken);

    record MfaPendingData(UUID partyId, String username) {}
}
