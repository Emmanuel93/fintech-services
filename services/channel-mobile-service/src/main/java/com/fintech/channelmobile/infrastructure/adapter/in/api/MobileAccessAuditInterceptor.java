package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.out.client.MobileAuditClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Registra en la bitácora todo lo que un cliente hace desde la app.
 *
 * <p>Este canal no auditaba nada. La bitácora conocía al personal interno y a nadie más, así que
 * una revisión que preguntara «desde qué dispositivo entró este cliente», «qué consultó antes de
 * quejarse» o «quién vio este expediente» sólo obtenía la mitad de la película — la del
 * backoffice— sin que nada indicara que faltaba la otra.
 *
 * <p>Es el reflejo del interceptor del backoffice a propósito: mismo contrato, mismos campos y
 * misma clasificación, cambiando el canal. Dos formatos distintos para el mismo hecho obligarían
 * a quien audita a leer cada fila sabiendo antes de dónde vino, y eso es exactamente lo que una
 * bitácora unificada existe para evitar.
 *
 * <p>Nunca escala a la respuesta: si la bitácora falla, el cliente no se entera. Un registro que
 * puede tumbar la operación acaba desactivándose el primer día que falla.
 */
@Component
public class MobileAccessAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MobileAccessAuditInterceptor.class);
    private static final String START_ATTR = "auditStart";
    private static final Pattern JTI = Pattern.compile("\"jti\"\\s*:\\s*\"([^\"]+)\"");

    /** Qué servicio reporta el acceso. Iba fijo al backoffice para todos los canales. */
    static final String DOMAIN_SOURCE = "channel-mobile-service";

    /** Rutas que no vale la pena registrar: ruido de infraestructura, no actos de nadie. */
    private static final Pattern IGNORADAS =
            Pattern.compile("^/(actuator|swagger-ui|v3/api-docs|favicon).*");

    /** El id concreto que trae la ruta. Lo mismo que extrae el backoffice; aquí iba siempre nulo. */
    private static final Pattern UUID_SEG = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final MobileAuditClient auditClient;
    private final CustomerIdentityResolver identityResolver;

    public MobileAccessAuditInterceptor(MobileAuditClient auditClient,
                                        CustomerIdentityResolver identityResolver) {
        this.auditClient = auditClient;
        this.identityResolver = identityResolver;
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
            String path = request.getRequestURI();
            if (path == null || IGNORADAS.matcher(path).matches()) return;

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            // El login se audita **aunque no haya sesión todavía**: el intento fallido es
            // justamente el hecho que interesa, y exigir autenticación para registrarlo dejaría
            // fuera todos los accesos que no lograron entrar.
            boolean esAcceso = path.contains("/auth/");
            if (auth == null && !esAcceso) return;

            String actor = auth != null ? String.valueOf(auth.getPrincipal()) : null;
            String roles = auth == null ? null : auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                    .collect(Collectors.joining(","));

            // Quién es el cliente detrás del UUID: nombre, CURP, correo y teléfono. Este canal
            // pasaba null literal en esas posiciones, así que ningún cliente tenía nombre jamás.
            // En la app el actor y el sujeto son la misma persona: quien entra actúa sobre lo suyo.
            var cliente = actor == null ? null : identityResolver.resolve(actor).orElse(null);

            long start = request.getAttribute(START_ATTR) instanceof Long l ? l : System.currentTimeMillis();
            long durationMs = System.currentTimeMillis() - start;

            String method = request.getMethod();
            String query  = request.getQueryString();
            int status    = response.getStatus();

            var access = new MobileAuditClient.AccessLog(
                    accion(method, path, esAcceso, status),
                    actor,
                    cliente != null ? cliente.email() : null,
                    cliente != null ? cliente.fullName() : null,
                    cliente != null ? cliente.curp() : null,
                    cliente != null ? cliente.phone() : null,
                    roles,
                    "MOBILE",
                    cliente != null && cliente.partyId() != null ? cliente.partyId().toString() : null,
                    cliente != null ? cliente.fullName() : null,
                    cliente != null ? cliente.curp() : null,
                    cliente != null ? cliente.email() : null,
                    cliente != null ? cliente.phone() : null,
                    ipCliente(request),
                    request.getHeader(HttpHeaders.USER_AGENT),
                    sessionId(bearer(request)),
                    recurso(path),
                    recursoId(path),
                    method, path, query,
                    desenlace(status, esAcceso), status, durationMs,
                    correlationId(request),
                    DOMAIN_SOURCE,
                    Instant.ofEpochMilli(start));

            auditClient.logAccess(access, h -> {
                if (actor != null) h.set("X-User-Id", actor);
                if (roles != null && !roles.isBlank()) h.set("X-Roles", roles);
                h.set("X-Channel", "MOBILE");
            });
        } catch (RuntimeException e) {
            log.debug("no se pudo componer el registro de acceso móvil: {}", e.toString());
        }
    }

    /** El verbo del hecho, con el mismo vocabulario que usa el backoffice. */
    static String accion(String method, String path, boolean esAcceso, int status) {
        if (esAcceso) {
            if (path.contains("logout")) return "LOGOUT";
            return status < 400 ? "LOGIN" : "LOGIN_FAILED";
        }
        String m = method == null ? "" : method.toUpperCase();
        if (!m.equals("GET") && !m.equals("HEAD")) return "MUTATION";
        String p = path.toLowerCase();
        if (p.contains("/documents") || p.contains("/download")) return "DOWNLOAD";
        if (p.contains("search") || p.contains("?q=")) return "SEARCH";
        return "VIEW";
    }

    /** El módulo tocado, deducido del primer segmento de la ruta. */
    static String recurso(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int corte = p.indexOf('/');
        String primero = corte > 0 ? p.substring(0, corte) : p;
        return primero.isBlank() ? null : primero;
    }

    /**
     * El id concreto sobre el que se actuó, si la ruta trae un UUID.
     *
     * <p>Iba {@code null} fijo: ninguna entrada móvil lo tenía, mientras el backoffice lo extraía en
     * las 189 suyas. Una bitácora que registra la ruta pero no el id obliga a quien audita a
     * parsearla a mano, y sólo en la mitad de los canales.
     */
    static String recursoId(String path) {
        if (path == null) return null;
        var m = UUID_SEG.matcher(path);
        return m.find() ? m.group() : null;
    }

    /**
     * El identificador de correlación de esta petición, leído del MDC.
     *
     * <p>{@link MdcCorrelationFilter} lo genera cuando el cliente no lo manda —que es siempre desde
     * una app— y lo deja en el MDC y en la respuesta. Leerlo de la petición devolvía null en todas
     * las entradas, aunque el valor existiera e imprimiera en los logs de esa misma petición.
     */
    static String correlationId(HttpServletRequest request) {
        String delMdc = org.slf4j.MDC.get(MdcCorrelationFilter.MDC_KEY);
        if (delMdc != null && !delMdc.isBlank()) return delMdc;
        return request.getHeader(MdcCorrelationFilter.HEADER);
    }

    static String desenlace(int status, boolean esAcceso) {
        if (status < 400) return "SUCCESS";
        if (status == 401) return esAcceso ? "FAILED_CREDENTIALS" : "DENIED";
        if (status == 403) return "DENIED";
        return status >= 500 ? "ERROR" : "FAILED";
    }

    /**
     * La IP real del cliente.
     *
     * <p>Con el gateway delante, {@code getRemoteAddr} devuelve la del propio gateway y toda la
     * bitácora quedaría registrando la misma dirección — inútil para detectar desde dónde entró
     * alguien. La primera de {@code X-Forwarded-For} es la del cliente.
     */
    static String ipCliente(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static String bearer(HttpServletRequest request) {
        String h = request.getHeader(HttpHeaders.AUTHORIZATION);
        return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
    }

    /**
     * La sesión: el {@code jti} del token, no el token.
     *
     * <p>Identifica el hilo que une todo lo que hizo una persona entre que entró y salió, sin que
     * la bitácora se convierta en un almacén de credenciales vivas. No se valida la firma: el
     * gateway ya lo hizo, y aquí sólo se lee una etiqueta para correlacionar.
     */
    static String sessionId(String bearer) {
        if (bearer == null || bearer.isBlank()) return null;
        try {
            String[] partes = bearer.split("\\.");
            if (partes.length < 2) return null;
            String json = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8);
            var m = JTI.matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
