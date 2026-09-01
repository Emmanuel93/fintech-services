package com.fintech.channelbackoffice.application;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.TreeSet;

/**
 * Matriz de permisos del backoffice: qué puede hacer cada rol. Es la fuente única de la que el
 * frontend decide qué módulos y acciones mostrar; hoy los controladores además la aplican en línea.
 *
 * <p>La matriz <b>ya no vive aquí</b>: la publica identity-service, que es quien también responde
 * qué roles tiene cada persona. Este servicio la lee al arrancar y la mantiene {@code volatile} en
 * memoria, porque se consulta en cada petición y una llamada de red por autorización convertiría a
 * identity en un punto único de falla del backoffice entero.
 *
 * <p>El mapa escrito en código se queda como <b>respaldo de arranque</b>. Si identity no responde
 * al iniciar, el canal aplica la política conocida en vez de quedarse sin ninguna: negar todo
 * dejaría a la consola sin módulos y sin forma de arreglarlo desde la consola.
 */
@Service
public class PermissionsService {

    private static final Logger log = LoggerFactory.getLogger(PermissionsService.class);

    /**
     * Respaldo de arranque: la política tal como estaba antes de moverla a identity. No es la
     * fuente —lo es {@code identity.role_capabilities}—, es lo que se aplica mientras esa fuente
     * no conteste.
     */
    private static final Map<String, Set<String>> CAPABILITY_ROLES = Map.ofEntries(
            Map.entry("dashboard.view",       roles("ADMIN", "OPS_SUPERVISOR", "CREDIT_ANALYST", "UNDERWRITER",
                    "COMMITTEE", "EXECUTIVE", "RISK_ANALYST", "PRODUCT_MANAGER", "FINANCE", "AUDITOR", "SUPPORT")),
            Map.entry("dashboard.commercial", roles("ADMIN", "OPS_SUPERVISOR", "EXECUTIVE", "RISK_ANALYST", "FINANCE", "AUDITOR")),
            Map.entry("portfolio.view",       roles("ADMIN", "OPS_SUPERVISOR", "CREDIT_ANALYST", "UNDERWRITER",
                    "COMMITTEE", "EXECUTIVE", "RISK_ANALYST", "COLLECTIONS_AGENT", "FINANCE", "AUDITOR", "SUPPORT")),
            Map.entry("clients.view",         roles("ADMIN", "OPS_SUPERVISOR", "CREDIT_ANALYST", "UNDERWRITER",
                    "COMMITTEE", "EXECUTIVE", "RISK_ANALYST", "SUPPORT", "AUDITOR")),
            Map.entry("clients.assign-executive", roles("ADMIN", "OPS_SUPERVISOR")),
            // Mesa de KYC de la colocación B2B2C. Es verificación de identidad, no de riesgo: por
            // eso la tienen los perfiles que comprueban personas (cumplimiento, soporte, análisis)
            // y no los que deciden crédito. La distribuidora decide a quién le presta; aquí sólo se
            // vigila que la persona a la que se le va a depositar sea quien dice ser.
            Map.entry("beneficiaries.view",   roles("ADMIN", "OPS_SUPERVISOR", "CREDIT_ANALYST",
                    "RISK_ANALYST", "SUPPORT", "AUDITOR")),
            // Dictaminar identidad **no** es ver la bandeja: es el analista de crédito y nadie más.
            // El auditor lee todo y no decide nada; soporte atiende clientes pero no firma la
            // comprobación de una persona; y riesgo evalúa cartera, no identidades.
            Map.entry("beneficiaries.review-identity", roles("ADMIN", "CREDIT_ANALYST")),
            // Dictaminar documentos del expediente. Misma lógica y los mismos perfiles que arriba,
            // más el underwriter: es el que ya decide sobre la solicitud completa.
            Map.entry("applications.review-documents", roles("ADMIN", "CREDIT_ANALYST",
                    "UNDERWRITER", "RISK_ANALYST")),
            Map.entry("applications.view",    roles("ADMIN", "OPS_SUPERVISOR", "CREDIT_ANALYST", "UNDERWRITER",
                    "COMMITTEE", "RISK_ANALYST", "AUDITOR")),
            Map.entry("applications.analyze", roles("ADMIN", "CREDIT_ANALYST", "UNDERWRITER", "COMMITTEE", "RISK_ANALYST", "AUDITOR")),
            Map.entry("applications.decide",  roles("ADMIN", "UNDERWRITER", "COMMITTEE")),
            Map.entry("applications.request-documents", roles("ADMIN", "CREDIT_ANALYST", "UNDERWRITER", "COMMITTEE")),
            Map.entry("products.manage",      roles("ADMIN", "PRODUCT_MANAGER")),
            Map.entry("salesorg.view",        roles("ADMIN", "OPS_SUPERVISOR", "EXECUTIVE", "RISK_ANALYST", "FINANCE", "AUDITOR")),
            Map.entry("salesorg.manage",      roles("ADMIN", "OPS_SUPERVISOR")),
            Map.entry("audit.view",           roles("ADMIN", "AUDITOR")),
            // Contabilidad. El respaldo tiene que llevar las mismas que el seed `014`: si identity
            // no contesta al arrancar —lo que pasa cada vez que los dos se recrean juntos— este mapa
            // es lo que se aplica, y una capacidad que falte aquí se ve como un 403 inexplicable en
            // un módulo que sí está bien configurado en la base.
            Map.entry("accounting.view",      roles("ADMIN", "FINANCE", "AUDITOR")),
            Map.entry("accounting.close",     roles("ADMIN", "FINANCE")),
            // La pantalla de roles. Faltaba aquí aunque identity sí la publica, así que el día que
            // identity no conteste al arrancar, el respaldo dejaba a ADMIN sin poder abrir la
            // matriz — es decir, sin forma de arreglar los permisos desde la consola, que es
            // justamente lo que este respaldo existe para evitar. Ver a ADMIN y OPS_SUPERVISOR,
            // editar sólo ADMIN, igual que el seed `011-create-role-capabilities`.
            Map.entry("permissions.view",     roles("ADMIN", "OPS_SUPERVISOR")),
            Map.entry("permissions.manage",   roles("ADMIN")));

    /** Caché: rol → capacidades. Se refresca desde identity con {@link #reload()}. */
    private volatile Map<String, Set<String>> roleCapabilities = invert();

    /** De dónde salió lo que está cacheado ahora. Se expone para no depurar a ciegas. */
    private volatile String source = "respaldo en código (aún no se consulta a identity)";

    private final IdentityClient identityClient;

    public PermissionsService(IdentityClient identityClient) {
        this.identityClient = identityClient;
    }

    public String source() {
        return source;
    }

    /** Rol → capacidades (ordenado, inmutable), para el frontend y la vista de administración. */
    public Map<String, Set<String>> matrix() {
        return roleCapabilities;
    }

    /**
     * Si quien está haciendo la petición tiene esa capacidad.
     *
     * <p>Los controladores comprobaban roles a mano —{@code Set.of("ADMIN", "AUDITOR")}— y esa
     * lista es la matriz otra vez, escrita en un segundo lugar. Cuando las dos se separaban el
     * síntoma era una consola que ofrece un módulo y un servidor que lo rechaza: exactamente lo
     * que pasó con la pantalla de roles, visible para todos y prohibida para casi todos.
     */
    public boolean callerHas(String capability) {
        return has(SecurityContextHolder.getContext().getAuthentication(), capability);
    }

    /**
     * Si esa autenticación concreta tiene la capacidad.
     *
     * <p>Sobrecarga explícita porque las reglas de ruta se evalúan con la {@code Authentication}
     * en la mano, antes de que exista contexto de seguridad del que leerla.
     */
    public boolean has(Authentication auth, String capability) {
        if (auth == null || !auth.isAuthenticated()) return false;
        Set<String> roles = auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .collect(Collectors.toCollection(TreeSet::new));
        return capabilitiesFor(roles).contains(capability);
    }

    /** Capacidades efectivas de un usuario: la unión de las de sus roles. */
    public Set<String> capabilitiesFor(Collection<String> userRoles) {
        Set<String> caps = new TreeSet<>();
        if (userRoles != null) {
            for (String role : userRoles) {
                caps.addAll(roleCapabilities.getOrDefault(role, Set.of()));
            }
        }
        return caps;
    }

    /**
     * Relee la matriz desde identity. Se llama al arrancar y después de cada edición.
     *
     * <p>Un fallo aquí <b>no</b> vacía la caché: se conserva lo último que sí se pudo leer. Una
     * autorización que se queda sin datos no es más segura, es una consola caída.
     */
    public void reload() {
        try {
            List<IdentityClient.RoleResponse> roles = identityClient.listRoles();
            if (roles == null || roles.isEmpty()) {
                log.warn("identity devolvió un catálogo de roles vacío; se conserva {}", source);
                return;
            }
            Map<String, Set<String>> nueva = new TreeMap<>();
            roles.forEach(r -> nueva.put(r.code(),
                    r.capabilities() == null ? Set.of() : Set.copyOf(r.capabilities())));
            this.roleCapabilities = Map.copyOf(nueva);
            this.source = "identity-service (" + roles.size() + " roles)";
            log.info("Matriz de permisos recargada desde identity: {} roles", roles.size());
        } catch (Exception ex) {
            log.warn("No se pudo leer la matriz de identity ({}); se conserva {}", ex.getMessage(), source);
        }
    }

    /** Al arrancar se intenta una vez; si falla, queda el respaldo y se reintenta al primer cambio. */
    @jakarta.annotation.PostConstruct
    void loadOnStartup() {
        reload();
    }

    /**
     * Y se vuelve a leer periódicamente, porque una sola lectura al arrancar no basta.
     *
     * <p>Arrancar los dos servicios a la vez sobre base limpia dejó al backoffice con <b>media
     * política</b>: leyó la matriz mientras identity aún aplicaba sus migraciones, se quedó con las
     * capacidades que existían en ese instante y siguió con ellas. El síntoma es un 403 sobre una
     * facultad que la base sí concede, y no se parece en nada a «el arranque fue a destiempo».
     *
     * <p>El respaldo en código cubre «identity no contesta». No cubría «identity contesta a
     * medias», que es peor porque una foto parcial se parece a una completa. Releer converge sola:
     * cualquier desfase se cierra en el siguiente ciclo sin que nadie tenga que reiniciar nada.
     *
     * <p>Cada cinco minutos y no cada minuto: la matriz cambia con una edición humana, y esas ya
     * llaman a {@link #reload()} directamente. Esto es la red de abajo, no el camino principal.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            initialDelayString = "PT30S", fixedDelayString = "${fintech.backoffice.permisos.refresco:PT5M}")
    void releerPeriodicamente() {
        reload();
    }

    private static Map<String, Set<String>> invert() {
        Map<String, Set<String>> byRole = new TreeMap<>();
        CAPABILITY_ROLES.forEach((capability, capRoles) ->
                capRoles.forEach(role ->
                        byRole.computeIfAbsent(role, r -> new TreeSet<>()).add(capability)));
        // Inmutabilizar.
        Map<String, Set<String>> immutable = new TreeMap<>();
        byRole.forEach((role, caps) -> immutable.put(role, Set.copyOf(caps)));
        return Map.copyOf(immutable);
    }

    private static Set<String> roles(String... rs) {
        return Set.of(rs);
    }
}
