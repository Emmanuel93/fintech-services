package com.fintech.identity.application;

import java.util.UUID;

/**
 * Resultado del registro/actualización de un dispositivo durante el flujo de autenticación.
 * Incluido en el evento Kafka para que el módulo audit pueda clasificar accesos
 * desde dispositivos nuevos vs. conocidos.
 */
public record DeviceTrackingResult(

        /** UUID del registro en identity.devices. null si no se proporcionó deviceId. */
        UUID deviceRegistryId,

        /** true si es la primera vez que este dispositivo autentica para este party. */
        boolean isNewDevice
) {
    /** Resultado vacío cuando el cliente no envía deviceId o no hay partyId disponible. */
    public static DeviceTrackingResult none() {
        return new DeviceTrackingResult(null, false);
    }
}
