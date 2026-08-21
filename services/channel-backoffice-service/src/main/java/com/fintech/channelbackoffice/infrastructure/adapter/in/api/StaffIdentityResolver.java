package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ServiceTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traduce el {@code staffUserId} (un UUID, lo único que trae el token) a la identidad completa del
 * empleado —nombre, CURP y correo— para que la bitácora diga <b>quién</b> y no un identificador
 * ilegible.
 *
 * <p>Pregunta con la <b>credencial del canal</b>, no con la del empleado. Antes usaba el token de
 * quien estaba navegando contra {@code GET /api/v1/staff/{id}}, que es de ADMIN: un ejecutivo
 * pidiendo su propio nombre recibía 403 y su entrada quedaba anónima. Que la identificación de
 * quien actúa dependiera de los permisos de quien actúa es exactamente lo contrario de lo que la
 * bitácora necesita — significaba que tener pocos permisos bastaba para navegar sin dejar nombre.
 *
 * <p>Cachea el resultado por empleado: la plantilla es pequeña y estable, así que una consulta cada
 * pocos minutos basta y no se golpea a identity-service en cada request. Ante fallo, cachea negativo
 * corto y degrada a sólo el UUID — auditar nunca debe volverse frágil.
 */
@Component
public class StaffIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(StaffIdentityResolver.class);
    private static final Duration TTL     = Duration.ofMinutes(10);
    private static final Duration TTL_NEG = Duration.ofSeconds(30);

    private final IdentityClient identityClient;
    private final ServiceTokenProvider serviceTokens;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    public StaffIdentityResolver(IdentityClient identityClient, ServiceTokenProvider serviceTokens) {
        this.identityClient = identityClient;
        this.serviceTokens = serviceTokens;
    }

    /** El "quién" completo de un colaborador, congelado al momento del acceso. */
    public record StaffIdentity(String email, String fullName, String curp) {}

    /** Resuelve nombre+CURP+correo; vacío si no es un UUID de staff resoluble. Nunca lanza. */
    public Optional<StaffIdentity> resolve(String actor) {
        UUID id = parseUuid(actor);
        if (id == null) {
            return Optional.empty();   // "SYSTEM" u otros sujetos no-UUID: no hay a quién resolver
        }
        Cached hit = cache.get(id);
        if (hit != null && !hit.isExpired()) {
            return Optional.ofNullable(hit.identity());
        }
        Optional<String> token = serviceTokens.token();
        if (token.isEmpty()) {
            // Sin credencial de servicio no se puede preguntar. Se conserva lo último que se supo
            // aunque haya caducado: un nombre de hace un rato identifica mejor que ninguno.
            return hit != null ? Optional.ofNullable(hit.identity()) : Optional.empty();
        }
        return Optional.ofNullable(consultar(id, token.get(), true));
    }

    /**
     * Una consulta a identity, con un solo reintento si el token fue rechazado.
     *
     * <p>El 401 no significa que el canal no tenga permiso: significa que el token que traía en
     * memoria ya no vale —identity reinició, rotó la firma, o el token murió antes de lo anunciado—.
     * Se tira y se pide uno nuevo. Un único reintento, porque si el segundo también falla el
     * problema no es el token y insistir sólo añade latencia al camino de la auditoría.
     */
    private StaffIdentity consultar(UUID id, String token, boolean puedeReintentar) {
        try {
            var staff = identityClient.getStaff(token, id);
            StaffIdentity identity = staff == null ? null
                    : new StaffIdentity(staff.email(), staff.fullName(), staff.curp());
            cache.put(id, new Cached(identity, Instant.now().plus(TTL)));
            return identity;
        } catch (ResponseStatusException e) {
            if (puedeReintentar && e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                serviceTokens.invalidate();
                Optional<String> nuevo = serviceTokens.token();
                if (nuevo.isPresent()) {
                    return consultar(id, nuevo.get(), false);
                }
            }
            return noResuelto(id, e);
        } catch (RuntimeException e) {
            return noResuelto(id, e);
        }
    }

    private StaffIdentity noResuelto(UUID id, RuntimeException e) {
        log.debug("no se pudo resolver identidad de staff {}: {}", id, e.toString());
        cache.put(id, new Cached(null, Instant.now().plus(TTL_NEG)));
        return null;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record Cached(StaffIdentity identity, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
