package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quién es el <b>sujeto</b> sobre el que actuó un empleado: nombre, CURP, correo y teléfono del
 * cliente cuyo expediente se abrió.
 *
 * <p>«Quién actuó» y «sobre quién» son preguntas distintas, y la bitácora sólo sabía responder la
 * primera: {@code party_id} venía vacío en el 91% de las entradas, así que «quién consultó el
 * expediente de este cliente» sólo se podía responder buscando un UUID a mano en la ruta HTTP.
 *
 * <p>La identidad del cliente vive repartida: nombre y CURP en party-service, correo y teléfono en
 * origination-service (el {@code Prospect} del que nació el Party). Por eso son dos llamadas
 * encadenadas — y por eso se cachean juntas bajo el mismo {@code partyId}: son el mismo hecho, y
 * repetirlas por cada pantalla del expediente multiplicaría el tráfico sin cambiar la respuesta.
 *
 * <p>Nunca lanza. Si party responde pero origination no, se guarda lo que se supo (nombre y CURP) y
 * el contacto queda vacío: media identidad identifica mejor que ninguna, y el hecho auditado no
 * depende de que todos los servicios estén en pie.
 *
 * <p>Es el reflejo de {@link StaffIdentityResolver} para el otro tipo de persona del sistema.
 */
@Component
public class CustomerIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(CustomerIdentityResolver.class);
    private static final Duration TTL     = Duration.ofMinutes(10);
    private static final Duration TTL_NEG = Duration.ofSeconds(30);

    private final PartyClient partyClient;
    private final OriginationClient originationClient;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    public CustomerIdentityResolver(PartyClient partyClient, OriginationClient originationClient) {
        this.partyClient = partyClient;
        this.originationClient = originationClient;
    }

    /** La identidad completa de un cliente, congelada al momento del acceso. */
    public record CustomerIdentity(UUID partyId, String fullName, String curp,
                                   String email, String phone) {}

    /** Resuelve por partyId. Vacío si no es un UUID o si el cliente no se pudo resolver. Nunca lanza. */
    public Optional<CustomerIdentity> resolve(String partyId) {
        UUID id = parseUuid(partyId);
        if (id == null) {
            return Optional.empty();
        }
        Cached hit = cache.get(id);
        if (hit != null && !hit.isExpired()) {
            return Optional.ofNullable(hit.identity());
        }
        return Optional.ofNullable(consultar(id));
    }

    private CustomerIdentity consultar(UUID partyId) {
        PartyClient.PartyResponse party;
        try {
            party = partyClient.getByPartyId(partyId);
        } catch (RuntimeException e) {
            // El UUID de la ruta puede no ser un cliente en absoluto (una solicitud, un crédito):
            // un 404 aquí es lo normal, no una avería. Se cachea negativo para no repetir la
            // llamada en cada pantalla que lleve ese id.
            log.debug("no se pudo resolver el cliente {}: {}", partyId, e.toString());
            cache.put(partyId, new Cached(null, Instant.now().plus(TTL_NEG)));
            return null;
        }
        if (party == null) {
            cache.put(partyId, new Cached(null, Instant.now().plus(TTL_NEG)));
            return null;
        }

        Contacto contacto = contacto(party.prospectId());
        var identity = new CustomerIdentity(
                party.partyId() != null ? party.partyId() : partyId,
                nombre(party.firstName(), party.lastName1(), party.lastName2()),
                party.curp(),
                contacto.email(),
                contacto.phone());
        cache.put(partyId, new Cached(identity, Instant.now().plus(TTL)));
        return identity;
    }

    /**
     * Correo y teléfono, que sólo existen en el prospecto del que nació el Party.
     *
     * <p>Se resuelven aparte y con su propio try: que origination no conteste no debe borrar el
     * nombre y la CURP que party ya dio.
     */
    private Contacto contacto(UUID prospectId) {
        if (prospectId == null) {
            return Contacto.VACIO;
        }
        try {
            var prospecto = originationClient.getProspect(prospectId);
            return prospecto == null ? Contacto.VACIO
                    : new Contacto(prospecto.email(), prospecto.phone());
        } catch (RuntimeException e) {
            log.debug("sin contacto del prospecto {}: {}", prospectId, e.toString());
            return Contacto.VACIO;
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

    private record Contacto(String email, String phone) {
        static final Contacto VACIO = new Contacto(null, null);
    }

    private record Cached(CustomerIdentity identity, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
