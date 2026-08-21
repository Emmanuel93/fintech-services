package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import com.fintech.channelbackoffice.infrastructure.config.ChannelBackofficeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * El token con el que el <b>canal</b> —no el empleado— habla con identity-service.
 *
 * <p>Existe por un defecto de fondo de la bitácora: el canal resolvía el nombre del empleado
 * llamando a {@code GET /api/v1/staff/{id}} con el token de ese mismo empleado, y ese endpoint es
 * de ADMIN. Un ejecutivo pidiendo <em>su propio</em> nombre recibía 403, y su entrada de auditoría
 * quedaba identificada sólo por un UUID. Quién actuó no puede depender de los permisos del que
 * actuó; si dependiera, bastaría con tener pocos permisos para navegar sin dejar nombre.
 *
 * <p>Se autentica con client/secret contra {@code /api/v1/auth/clients/token} y guarda el access
 * token en memoria hasta poco antes de que expire. No refresca en segundo plano ni reintenta en
 * bucle: cuando el token caduca, la siguiente resolución pide uno nuevo. Un solo hilo puede
 * atravesar el hueco y pedir dos veces — es barato y no requiere sincronizar el camino de lectura,
 * que sí se recorre en cada request.
 *
 * <p><b>Nunca lanza.</b> Vive en el camino de la auditoría, que es fire-and-forget: si identity
 * está caído, mal configurado o rechaza el secreto, devuelve vacío y la bitácora guarda el UUID sin
 * nombre. Se pierde el nombre, nunca el hecho.
 */
@Component
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    /**
     * Margen con el que se considera caducado un token todavía vigente. Cubre el vuelo de la
     * petición que lo va a usar: renovar 30s antes es gratis, usar un token que expira a mitad de
     * camino cuesta un 401 y una entrada sin nombre.
     */
    private static final Duration MARGEN = Duration.ofSeconds(30);

    /** Tras un fallo, no se vuelve a intentar hasta pasado esto: identity caído no se arregla insistiendo. */
    private static final Duration ESPERA_TRAS_FALLO = Duration.ofSeconds(30);

    private final WebClient identityWebClient;
    private final ChannelBackofficeProperties.ServiceClient config;
    private final AtomicReference<Token> token = new AtomicReference<>();
    private final AtomicReference<Instant> noReintentarAntesDe = new AtomicReference<>(Instant.EPOCH);

    public ServiceTokenProvider(@Qualifier("identityWebClient") WebClient identityWebClient,
                                ChannelBackofficeProperties properties) {
        this.identityWebClient = identityWebClient;
        this.config = properties.getServiceClient();
    }

    /** El token de servicio vigente, pidiendo uno nuevo si hace falta. Vacío si no se pudo obtener. */
    public Optional<String> token() {
        Token vigente = token.get();
        if (vigente != null && vigente.sirveAhora()) {
            return Optional.of(vigente.valor());
        }
        if (!config.isConfigured()) {
            return Optional.empty();   // sin secreto configurado no hay nada que intentar
        }
        if (Instant.now().isBefore(noReintentarAntesDe.get())) {
            return Optional.empty();
        }
        return solicitar();
    }

    /**
     * Invalida el token en memoria. Lo llama quien recibe un 401: puede que identity haya rotado la
     * firma o que el token muriera antes de lo anunciado, y la siguiente resolución debe pedir uno
     * nuevo en vez de reusar el que acaba de ser rechazado.
     */
    public void invalidate() {
        token.set(null);
    }

    private Optional<String> solicitar() {
        try {
            Map<?, ?> respuesta = identityWebClient.post()
                    .uri("/api/v1/auth/clients/token")
                    .bodyValue(Map.of("clientId", config.getClientId(),
                                      "clientSecret", config.getSecret()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError,
                            r -> DomainClientSupport.propagate("identity-service", r))
                    .bodyToMono(Map.class)
                    .block();

            String acceso = respuesta == null ? null : String.valueOf(respuesta.get("accessToken"));
            if (acceso == null || acceso.isBlank() || "null".equals(acceso)) {
                return fallo("identity no devolvió accessToken");
            }
            token.set(new Token(acceso, expiracion(respuesta)));
            log.info("credencial de servicio del canal renovada contra identity-service");
            return Optional.of(acceso);
        } catch (RuntimeException e) {
            return fallo(e.toString());
        }
    }

    /**
     * Cuándo deja de servir el token. {@code expiresIn} viene en segundos; si identity no lo manda
     * se asume un minuto, que es lo bastante corto para no quedarse con un token muerto y lo
     * bastante largo para no pedir uno por request.
     */
    private static Instant expiracion(Map<?, ?> respuesta) {
        long segundos = respuesta.get("expiresIn") instanceof Number n ? n.longValue() : 60L;
        return Instant.now().plusSeconds(Math.max(segundos, 1)).minus(MARGEN);
    }

    private Optional<String> fallo(String motivo) {
        noReintentarAntesDe.set(Instant.now().plus(ESPERA_TRAS_FALLO));
        token.set(null);
        log.warn("no se pudo obtener la credencial de servicio ({}); la bitácora guardará el "
                + "identificador sin nombre hasta el próximo intento", motivo);
        return Optional.empty();
    }

    private record Token(String valor, Instant sirveHasta) {
        boolean sirveAhora() {
            return Instant.now().isBefore(sirveHasta);
        }
    }
}
