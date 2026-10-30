package com.fintech.compliance.infrastructure.config;

import com.fintech.compliance.domain.WatchlistKind;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Las listas que se sincronizan, tal como las declara {@code fintech.compliance.watchlists}.
 *
 * <p>La lista estaba declarada en la configuración y nadie la leía: {@code WatchlistLoadService}
 * no tenía un solo llamador, así que en ningún ambiente había listas vigentes y todo screening
 * quedaba {@code PENDING}. Leerla aquí es lo que convierte esa declaración en un control encendido.
 */
@ConfigurationProperties(prefix = "fintech.compliance")
public record WatchlistSources(List<Source> watchlists) {

    public WatchlistSources {
        watchlists = watchlists == null ? List.of() : List.copyOf(watchlists);
    }

    public record Source(String sourceCode, WatchlistKind kind) {}
}
