package com.fintech.identity.infrastructure.adapter.out.geo;

import com.fintech.identity.application.port.out.GeoLocationPort;
import com.fintech.identity.domain.event.GeoLocation;
import org.springframework.stereotype.Component;

/**
 * Implementación stub de geolocalización.
 * Reemplazar por un adaptador real (MaxMind GeoIP2, ip-api.com, IPinfo.io, etc.)
 * configurando el bean correspondiente y desactivando este perfil.
 */
@Component
public class StubGeoLocationAdapter implements GeoLocationPort {

    @Override
    public GeoLocation resolve(String ipAddress) {
        return GeoLocation.unknown();
    }
}
