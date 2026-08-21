package com.fintech.channelbackoffice.application;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Hasta dónde llega una persona en la estructura comercial.
 *
 * <p>El alcance sale de <b>dónde está parada</b>, no de qué rol tiene. Es la decisión que ya
 * tomó la estructura: el rol dice <i>qué</i> puede hacer —ver cartera, reorganizar— y la unidad
 * dice <i>sobre qué</i>. Un rol por nivel (gerente de sucursal, de zona, regional…) obligaría a
 * repetir cada regla cuatro veces y a tocar la matriz entera el día que se abra una subdirección.
 *
 * <p>Vive aquí y no en cada controlador porque la regla es una sola y estaba escrita a medias: el
 * tablero comercial ya rechazaba pedir cartera ajena, pero <b>mover una unidad no comprobaba
 * nada</b>. Quien podía reorganizar podía reorganizar el país entero, así que el alcance servía
 * para mirar y no para mandar — que es justo al revés de lo que importa.
 *
 * <p>Regla, una sola frase: <b>puedes operar sobre lo que cuelga de tu unidad, y el destino
 * también tiene que colgar de ella.</b> De ahí se derivan solas las cuatro que pide el negocio —el
 * nacional mueve todo porque su subárbol es el país, el regional sólo su región, el de zona su
 * zona, el de sucursal sus ejecutivos— sin una línea por nivel.
 */
@Service
public class CommercialScopeService {

    /**
     * Roles transversales: ven y operan sobre cualquier unidad.
     *
     * <p>No es una excepción a la regla sino su caso límite: no están adscritos a ninguna
     * sucursal, así que su unidad es la raíz y su subárbol es la red completa.
     */
    private static final Set<String> ORG_WIDE_ROLES =
            Set.of("ADMIN", "OPS_SUPERVISOR", "RISK_ANALYST", "FINANCE", "AUDITOR");

    private final SalesOrgClient salesOrgClient;

    public CommercialScopeService(SalesOrgClient salesOrgClient) {
        this.salesOrgClient = salesOrgClient;
    }

    /** Lo que la consola necesita saber para no ofrecer acciones que el backend va a rechazar. */
    /**
     * @param unitIds   los ids del subárbol (incluye la propia unidad)
     * @param unitCodes los <b>códigos</b> del subárbol. Van aparte de los ids porque la cartera
     *                  sella su unidad de origen por código ({@code origin_unit_code}), no por id:
     *                  para acotar los números al subárbol hace falta el código, y resolverlo
     *                  después obligaría a una segunda vuelta a sales-org.
     */
    public record Scope(boolean orgWide, UUID unitId, String unitName, String path,
                        Set<UUID> unitIds, Set<String> unitCodes) {}

    public boolean callerIsOrgWide() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .anyMatch(ORG_WIDE_ROLES::contains);
    }

    /**
     * El alcance de quien llama.
     *
     * <p>Un rol transversal cuelga de la raíz. Alguien comercial cuelga de su adscripción vigente;
     * si no tiene ninguna, su alcance es <b>vacío</b> y no un error: la pantalla tiene que poder
     * decir «no estás adscrito a ninguna unidad» en vez de fallar al cargar.
     */
    public Scope callerScope() {
        UUID unitId = callerIsOrgWide() ? rootUnitId() : callerUnitId();
        if (unitId == null) {
            return new Scope(callerIsOrgWide(), null, null, null, Set.of(), Set.of());
        }
        List<Map<String, Object>> subtree = safe(salesOrgClient.subtree(unitId));
        Set<UUID> ids = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        String name = null;
        String path = null;
        for (Map<String, Object> u : subtree) {
            UUID id = uuid(u.get("unitId"));
            if (id == null) continue;
            ids.add(id);
            String code = str(u.get("code"));
            if (code != null && !code.isBlank()) codes.add(code);
            if (id.equals(unitId)) {
                name = str(u.get("name"));
                path = str(u.get("path"));
            }
        }
        ids.add(unitId);   // el subárbol se incluye a sí mismo, pero no depende de que lo haga
        return new Scope(callerIsOrgWide(), unitId, name, path, ids, codes);
    }

    /**
     * Los códigos del subárbol de una unidad cualquiera, con su nombre.
     *
     * <p>Una sola consulta a sales-org: el número de llamadas no depende de cuántas unidades
     * cuelguen. El llamador ya validó el alcance con {@link #resolveAndAuthorize(UUID)}.
     */
    public Map<String, String> subtreeCodeNames(UUID unitId) {
        Map<String, String> codes = new LinkedHashMap<>();
        for (Map<String, Object> u : safe(salesOrgClient.subtree(unitId))) {
            String code = str(u.get("code"));
            if (code != null && !code.isBlank()) codes.put(code, str(u.get("name")));
        }
        return codes;
    }

    /**
     * Resuelve la unidad de una consulta y valida que caiga dentro del alcance.
     *
     * <p>Sin unidad, la propia. Un rol transversal no tiene propia y por eso recibe 400 pidiendo
     * que elija: devolverle la raíz en silencio escondería que la pregunta estaba incompleta.
     */
    public UUID resolveAndAuthorize(UUID requested) {
        if (callerIsOrgWide()) {
            if (requested == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Indica unitId: un rol transversal no tiene unidad propia por defecto");
            }
            return requested;
        }
        UUID mine = callerUnitId();
        if (mine == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tienes una unidad asignada: sin alcance comercial");
        }
        if (requested == null || requested.equals(mine)) return mine;
        requireWithin(requested, "consultar");
        return requested;
    }

    /**
     * Exige que una unidad caiga dentro del alcance, o 403.
     *
     * <p>403 y no una lista vacía a propósito: un 200 sin filas es indistinguible de «esa zona no
     * tiene cartera» y filtra la forma de la organización a quien no debería verla.
     */
    public void requireWithin(UUID unitId, String verbo) {
        if (unitId == null || callerIsOrgWide()) return;
        if (!callerScope().unitIds().contains(unitId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Fuera de tu alcance comercial: no puedes " + verbo + " una unidad que no cuelga de la tuya");
        }
    }

    /**
     * Exige alcance sobre origen y destino de un movimiento.
     *
     * <p>Las dos puntas, no sólo la que se mueve: mover una sucursal <i>propia</i> a una zona
     * ajena la saca del alcance de quien la mueve y la mete en el de alguien que no lo pidió. Con
     * comprobar sólo el origen, reorganizar sería una vía para regalar cartera hacia afuera.
     */
    public void requireMoveWithin(UUID unitId, UUID destinoPadre) {
        requireWithin(unitId, "mover");
        requireWithin(destinoPadre, "colgar de");
        if (!callerIsOrgWide() && unitId != null && unitId.equals(callerScope().unitId())) {
            // Cambiarle el padre a la propia raíz es moverse uno mismo dentro del árbol: el
            // destino queda por encima del alcance por definición.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No puedes mover tu propia unidad: su destino queda por encima de tu alcance");
        }
    }

    /** La unidad vigente de quien llama, o {@code null} si no está adscrito a ninguna. */
    public UUID callerUnitId() {
        Map<String, Object> mine = salesOrgClient.currentAssignment("STAFF", callerStaffId());
        return mine == null ? null : uuid(mine.get("unitId"));
    }

    private UUID rootUnitId() {
        return safe(salesOrgClient.listUnits()).stream()
                .filter(u -> u.get("parentUnitId") == null)
                .map(u -> uuid(u.get("unitId")))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static UUID callerStaffId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No autenticado");
        }
        return UUID.fromString(String.valueOf(auth.getPrincipal()));
    }

    private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static UUID uuid(Object v) {
        if (v == null) return null;
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
