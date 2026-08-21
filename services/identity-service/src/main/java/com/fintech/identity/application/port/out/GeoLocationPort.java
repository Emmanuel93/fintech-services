package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.event.GeoLocation;

public interface GeoLocationPort {

    /**
     * Resuelve datos de geolocalización para la IP dada.
     * Nunca lanza excepción; retorna {@link GeoLocation#unknown()} si la resolución falla.
     */
    GeoLocation resolve(String ipAddress);
}
