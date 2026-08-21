package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Traducción común de errores de los servicios de dominio.
 *
 * <p>El criterio no es "conservar el status": es <em>de quién es el problema</em>.
 *
 * <ul>
 *   <li>Si el error describe algo que la consola hizo o pidió —credenciales malas, cuenta bloqueada,
 *       recurso inexistente, conflicto de estado— se conserva tal cual. Un 401 y un 423 tienen que
 *       llegar distintos a la pantalla de login.</li>
 *   <li>Si el error dice que el BFF llamó mal al dominio (405, 415) o que el dominio se cayó (5xx),
 *       eso no es culpa de quien está usando la consola: se traduce a <b>502</b>. Reenviar un 405 al
 *       navegador convierte un bug nuestro en un mensaje sin sentido para el operador.</li>
 * </ul>
 */
final class DomainClientSupport {

    /** Statuses que describen la petición del usuario y por tanto viajan intactos. */
    private static final Set<HttpStatus> PASS_THROUGH = Set.of(
            HttpStatus.BAD_REQUEST,
            HttpStatus.UNAUTHORIZED,
            HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND,
            HttpStatus.CONFLICT,
            HttpStatus.GONE,
            HttpStatus.UNPROCESSABLE_ENTITY,
            HttpStatus.LOCKED,
            HttpStatus.TOO_MANY_REQUESTS);

    private DomainClientSupport() {}

    /**
     * Reenvía la identidad del empleado a los servicios de dominio que autentican
     * por {@code X-User-Id}/{@code X-Roles} (el gateway los inyecta al llegar al BFF).
     *
     * <p>Se lee del contexto de seguridad en vez de arrastrar el request por cada
     * firma, y así queda rastro de <b>quién</b> consultó, no solo de que alguien lo hizo.
     */
    static Consumer<HttpHeaders> staffIdentity() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String staffUserId = auth == null ? null : String.valueOf(auth.getPrincipal());
        String roles = auth == null ? "" : auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .collect(Collectors.joining(","));
        return h -> {
            if (staffUserId != null && !staffUserId.isBlank()) h.set("X-User-Id", staffUserId);
            if (!roles.isBlank()) h.set("X-Roles", roles);
            h.set("X-Channel", "BACKOFFICE");
        };
    }

    /** El id del empleado autenticado, para atribuir acciones (p.ej. decidedBy). */
    static String currentStaffUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : String.valueOf(auth.getPrincipal());
    }

    static Mono<? extends Throwable> propagate(String serviceName, ClientResponse resp) {
        HttpStatusCode upstream = resp.statusCode();
        HttpStatusCode exposed = PASS_THROUGH.contains(HttpStatus.resolve(upstream.value()))
                ? upstream
                : HttpStatus.BAD_GATEWAY;

        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new ResponseStatusException(exposed, detail(serviceName, upstream, exposed, body)));
    }

    private static String detail(String serviceName, HttpStatusCode upstream,
                                 HttpStatusCode exposed, String body) {
        // Cuando se traduce el status, el original queda en el mensaje para no perder la pista al depurar.
        String origin = exposed.equals(upstream)
                ? serviceName
                : serviceName + " respondió " + upstream.value();
        return body.isBlank() ? origin : origin + ": " + body;
    }
}
