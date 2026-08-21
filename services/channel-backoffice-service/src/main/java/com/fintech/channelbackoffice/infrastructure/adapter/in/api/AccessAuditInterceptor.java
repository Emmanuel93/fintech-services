package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.AuditClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bitácora de acceso del backoffice: por cada request de un empleado deja en audit-service el
 * <b>quién</b> (staffUserId, roles, canal, IP, user-agent, sesión/correlación), el <b>sobre qué</b>
 * (recurso, id, método y ruta), el resultado y el <b>cuándo</b> (instante del acceso + duración).
 *
 * <p>Se ejecuta en {@code afterCompletion}, cuando ya se conoce el status y la respuesta ya salió:
 * el envío a audit es fire-and-forget, así que auditar jamás retrasa ni rompe la navegación.
 */
public class AccessAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AccessAuditInterceptor.class);
    private static final String START_ATTR = AccessAuditInterceptor.class.getName() + ".start";
    private static final Pattern UUID_SEG = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Qué servicio reporta el acceso. Iba fijo en la entidad de audit, para todos los canales. */
    static final String DOMAIN_SOURCE = "channel-backoffice-service";

    /**
     * Recursos cuyo id en la ruta es un cliente, y por tanto un sujeto sobre el que se actúa.
     *
     * <p>La ruta lleva UUIDs de muchas cosas —solicitudes, créditos, productos— y preguntarle a
     * party-service por cada uno sería tráfico garantizado para una respuesta que casi siempre es
     * 404. Aquí sólo se intenta cuando la ruta dice que ese id es un cliente.
     */
    private static final java.util.Set<String> RECURSOS_DE_CLIENTE =
            java.util.Set.of("clients", "clientes", "parties");

    private final AuditClient auditClient;
    private final StaffIdentityResolver identityResolver;
    private final CustomerIdentityResolver customerResolver;

    public AccessAuditInterceptor(AuditClient auditClient, StaffIdentityResolver identityResolver,
                                  CustomerIdentityResolver customerResolver) {
        this.auditClient = auditClient;
        this.identityResolver = identityResolver;
        this.customerResolver = customerResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            // Sin identidad no hay a quién atribuir: es tráfico de infra (health), no un acceso de operador.
            if (auth == null || auth.getPrincipal() == null) {
                return;
            }
            String actor = String.valueOf(auth.getPrincipal());
            String roles = auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                    .collect(Collectors.joining(","));
            String channel = header(request, "X-Channel", "BACKOFFICE");

            // El "quién" completo: resuelve el staffUserId (UUID) a nombre, CURP y correo. Cacheado
            // y con la credencial del canal; si falla, degrada a sólo el UUID sin romper nada.
            String bearer = bearerToken(request);
            var identity = identityResolver.resolve(actor).orElse(null);
            String actorEmail = identity != null ? identity.email() : null;
            String actorName  = identity != null ? identity.fullName() : null;
            String actorCurp  = identity != null ? identity.curp() : null;

            long start = request.getAttribute(START_ATTR) instanceof Long l ? l : System.currentTimeMillis();
            long durationMs = System.currentTimeMillis() - start;

            String method = request.getMethod();
            String path   = request.getRequestURI();
            String query  = request.getQueryString();
            int status    = response.getStatus();

            String resourceType = resourceType(path);
            String resourceId   = resourceId(path);

            // El "sobre quién": el backoffice actúa sobre clientes, y saber quién abrió el
            // expediente de una persona es una pregunta distinta de quién actuó.
            var sujeto = subject(resourceType, resourceId);

            var access = new AuditClient.AccessLog(
                    classifyAction(method, path, query),
                    actor, actorEmail, actorName, actorCurp,
                    null,                       // el colaborador no aporta teléfono
                    roles, channel,
                    sujeto != null && sujeto.partyId() != null ? sujeto.partyId().toString() : null,
                    sujeto != null ? sujeto.fullName() : null,
                    sujeto != null ? sujeto.curp() : null,
                    sujeto != null ? sujeto.email() : null,
                    sujeto != null ? sujeto.phone() : null,
                    clientIp(request),
                    request.getHeader(HttpHeaders.USER_AGENT),
                    sessionId(bearer),
                    resourceType,
                    resourceId,
                    method, path, query,
                    outcome(status), status, durationMs,
                    correlationId(request),
                    DOMAIN_SOURCE,
                    Instant.ofEpochMilli(start));

            Consumer<HttpHeaders> identityHeaders = h -> {
                h.set("X-User-Id", actor);
                if (!roles.isBlank()) h.set("X-Roles", roles);
                h.set("X-Channel", channel);
            };
            auditClient.logAccess(access, identityHeaders);
        } catch (RuntimeException e) {
            // La auditoría de acceso nunca escala a la respuesta; ya se envió. Sólo se registra local.
            log.debug("no se pudo componer el registro de acceso: {}", e.toString());
        }
    }

    /** El cliente sobre el que se actuó, si la ruta apunta a uno. Null en cualquier otro caso. */
    private CustomerIdentityResolver.CustomerIdentity subject(String resourceType, String resourceId) {
        if (resourceId == null || resourceType == null
                || !RECURSOS_DE_CLIENTE.contains(resourceType.toLowerCase())) {
            return null;
        }
        return customerResolver.resolve(resourceId).orElse(null);
    }

    /**
     * El identificador de correlación de esta petición.
     *
     * <p>Se lee del MDC, no de la cabecera entrante. {@link MdcCorrelationFilter} genera uno cuando
     * el cliente no lo manda —que es casi siempre— y lo deja en el MDC y en la <b>respuesta</b>;
     * mirar la <b>petición</b> devolvía null en el 100% de los casos, y la columna de la bitácora
     * quedaba vacía aunque el valor existiera y saliera impreso en los logs de la misma petición.
     */
    static String correlationId(HttpServletRequest request) {
        String delMdc = org.slf4j.MDC.get(MdcCorrelationFilter.MDC_KEY);
        if (delMdc != null && !delMdc.isBlank()) {
            return delMdc;
        }
        // Fuera del hilo del filtro (despacho asíncrono) el MDC puede venir vacío: entonces sí vale
        // lo que trajera el cliente.
        return header(request, MdcCorrelationFilter.HEADER, null);
    }

    /**
     * La sesión desde la que se actuó: el {@code jti} del token.
     *
     * <p>Iba la cabecera de correlación, que identifica <b>una petición</b> y casi nunca viaja, así
     * que la columna quedaba siempre vacía. Para auditar hace falta lo contrario: el hilo que une
     * todo lo que hizo una persona entre que entró y salió. Sin él, «qué hizo Ana el martes» se
     * responde juntando eventos por nombre y hora, que es lo que hace imposible distinguir dos
     * sesiones simultáneas — la del navegador y la de alguien con su token.
     *
     * <p>El token no se valida aquí: el gateway ya lo hizo, y esto sólo lee una etiqueta para
     * correlacionar. Guardar el {@code jti} y no el token es deliberado — identifica la sesión sin
     * que la bitácora se convierta en un almacén de credenciales vivas.
     */
    static String sessionId(String bearer) {
        if (bearer == null || bearer.isBlank()) return null;
        try {
            String[] partes = bearer.split("\\.");
            if (partes.length < 2) return null;
            String json = new String(java.util.Base64.getUrlDecoder().decode(partes[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            var m = java.util.regex.Pattern.compile("\"jti\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (RuntimeException e) {
            // Un token con forma inesperada no debe tumbar el registro de acceso: se pierde la
            // sesión, no el hecho.
            return null;
        }
    }

    // ── Clasificación ────────────────────────────────────────────────────────

    /** VIEW/SEARCH/DOWNLOAD para lectura; MUTATION para escritura (también se audita quién intentó qué). */
    static String classifyAction(String method, String path, String query) {
        String m = method == null ? "" : method.toUpperCase();
        if (!m.equals("GET") && !m.equals("HEAD")) {
            return "MUTATION";
        }
        String p = path == null ? "" : path.toLowerCase();
        if (p.contains("/export") || p.contains("/download") || p.contains("/documents")
                || p.contains("/document/") || p.endsWith("/file")) {
            return "DOWNLOAD";
        }
        if (p.contains("/search") || (query != null && !query.isBlank())) {
            return "SEARCH";
        }
        return "VIEW";
    }

    /** El recurso consultado = primer segmento significativo de la ruta (portfolio, clientes, audit…). */
    static String resourceType(String path) {
        if (path == null || path.isBlank()) return null;
        String[] segs = path.split("/");
        for (String s : segs) {
            if (s.isBlank()) continue;
            if (s.equals("api") || s.matches("v\\d+")) continue;   // salta prefijos /api/v1
            return s;
        }
        return null;
    }

    /** El id concreto consultado, si la ruta trae un UUID. */
    static String resourceId(String path) {
        if (path == null) return null;
        var matcher = UUID_SEG.matcher(path);
        return matcher.find() ? matcher.group() : null;
    }

    static String outcome(int status) {
        if (status >= 200 && status < 400) return "SUCCESS";
        if (status == 401 || status == 403) return "DENIED";
        return "ERROR";
    }

    /** IP real del cliente: primer salto de X-Forwarded-For, luego X-Real-IP, luego el socket. */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return request.getRemoteAddr();
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String v = request.getHeader(name);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    /** El JWT del request entrante, para resolver la identidad contra identity-service. Null si falta. */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }
}
