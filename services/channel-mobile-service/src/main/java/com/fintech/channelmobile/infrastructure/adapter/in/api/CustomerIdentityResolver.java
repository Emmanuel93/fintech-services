package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.PartyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quién es el cliente que está usando la app: nombre, CURP, correo y teléfono.
 *
 * <p>Este canal no resolvía nada — el interceptor pasaba {@code null} literal en las posiciones del
 * nombre y el correo, así que <b>ningún cliente tenía nombre en la bitácora, nunca</b>. Preguntar
 * «qué consultó esta persona antes de quejarse» devolvía filas de UUIDs.
 *
 * <p>El {@code sub} del token de un cliente es su {@code prospectId}, así que la identidad completa
 * sale de una sola llamada a origination-service, que es donde viven correo y teléfono. El
 * {@code partyId} se resuelve aparte, para que la entrada pueda cruzarse con el resto del sistema,
 * que identifica al cliente por ese id y no por el del prospecto.
 *
 * <p>Nunca lanza y cachea con TTL, como el resolutor de personal del backoffice: la bitácora es
 * fire-and-forget y un servicio caído debe costar el nombre, jamás el hecho ni la operación.
 */
@Component
public class CustomerIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(CustomerIdentityResolver.class);
    private static final Duration TTL     = Duration.ofMinutes(10);
    private static final Duration TTL_NEG = Duration.ofSeconds(30);

    private final OriginationClient originationClient;
    private final PartyClient partyClient;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    public CustomerIdentityResolver(OriginationClient originationClient, PartyClient partyClient) {
        this.originationClient = originationClient;
        this.partyClient = partyClient;
    }

    /** La identidad completa del cliente, congelada al momento del acceso. */
    public record CustomerIdentity(UUID partyId, String fullName, String curp,
                                   String email, String phone) {}

    /** Resuelve por el prospectId que viaja como sujeto del token. Nunca lanza. */
    public Optional<CustomerIdentity> resolve(String actor) {
        UUID prospectId = parseUuid(actor);
        if (prospectId == null) {
            return Optional.empty();
        }
        Cached hit = cache.get(prospectId);
        if (hit != null && !hit.isExpired()) {
            return Optional.ofNullable(hit.identity());
        }
        return Optional.ofNullable(consultar(prospectId));
    }

    private CustomerIdentity consultar(UUID prospectId) {
        OriginationClient.ProspectDetail prospecto = originationClient.getProspect(prospectId);
        if (prospecto == null) {
            cache.put(prospectId, new Cached(null, Instant.now().plus(TTL_NEG)));
            return null;
        }
        var identity = new CustomerIdentity(
                partyId(prospectId),
                nombre(prospecto.firstName(), prospecto.lastName1(), prospecto.lastName2()),
                prospecto.curp(),
                prospecto.email(),
                prospecto.phone());
        cache.put(prospectId, new Cached(identity, Instant.now().plus(TTL)));
        return identity;
    }

    /**
     * El partyId con el que el resto del sistema conoce a este cliente.
     *
     * <p>Va en su propio try: durante el alta el Party todavía no existe, y que falte no puede
     * borrar el nombre y el contacto que origination ya dio.
     */
    private UUID partyId(UUID prospectId) {
        try {
            var party = partyClient.getByProspectId(prospectId);
            return party == null ? null : party.partyId();
        } catch (RuntimeException e) {
            log.debug("sin partyId para el prospecto {}: {}", prospectId, e.toString());
            return null;
        }
    }

    static String nombre(String firstName, String lastName1, String lastName2) {
        String completo = java.util.stream.Stream.of(firstName, lastName1, lastName2)
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse(null);
        return completo == null || completo.isBlank() ? null : completo;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record Cached(CustomerIdentity identity, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
