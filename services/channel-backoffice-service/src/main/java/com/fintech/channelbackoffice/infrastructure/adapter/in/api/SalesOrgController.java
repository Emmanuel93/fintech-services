package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.CommercialScopeService;
import com.fintech.channelbackoffice.application.PermissionsService;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Estructura comercial y asignaciones para el backoffice.
 *
 * <p>Passthrough sobre sales-org-service con la política de acceso del backoffice: <b>leer</b> la
 * estructura y el alcance lo puede cualquier empleado autenticado; <b>administrarla</b> (crear
 * niveles/unidades, asignar, cerrar asignaciones) queda a roles con facultad de organización. El BFF
 * es la autoridad de RBAC; el dominio confía en esa decisión y solo atribuye por X-User-Id.
 */
@RestController
@RequestMapping("/sales-org")
@Tag(name = "Estructura comercial", description = "Niveles, unidades y asignaciones (sales-org)")
class SalesOrgController {

    private static final Logger log = LoggerFactory.getLogger(SalesOrgController.class);

    private final SalesOrgClient salesOrgClient;
    private final PermissionsService permissionsService;
    private final CommercialScopeService scope;

    SalesOrgController(SalesOrgClient salesOrgClient, PermissionsService permissionsService,
                       CommercialScopeService scope) {
        this.salesOrgClient = salesOrgClient;
        this.permissionsService = permissionsService;
        this.scope = scope;
    }

    // ── Alcance propio ─────────────────────────────────────────────────────────

    /**
     * Hasta dónde llega quien pregunta.
     *
     * <p>Existe para que la consola no tenga que adivinarlo. Sin esto la pantalla sólo puede
     * ofrecer todas las acciones y dejar que el backend rechace la mitad con un 403, que es la
     * peor forma de comunicar una regla: la persona descubre lo que no puede hacer intentándolo.
     * El backend sigue siendo la autoridad —esto no autoriza nada, describe.
     */
    @GetMapping("/my-scope")
    @Operation(summary = "Alcance comercial de quien llama: su unidad y el subárbol que gobierna")
    Map<String, Object> myScope() {
        CommercialScopeService.Scope s = scope.callerScope();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orgWide", s.orgWide());
        body.put("unitId", s.unitId() == null ? null : s.unitId().toString());
        body.put("unitName", s.unitName());
        body.put("path", s.path());
        body.put("canManage", permissionsService.callerHas("salesorg.manage"));
        body.put("unitIds", s.unitIds().stream().map(UUID::toString).toList());
        return body;
    }

    // ── Niveles ────────────────────────────────────────────────────────────────

    @GetMapping("/levels")
    @Operation(summary = "Escalera de niveles")
    List<Map<String, Object>> listLevels() {
        return salesOrgClient.listLevels();
    }

    @PostMapping("/levels")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear un nivel (admin de estructura con alcance completo)")
    Map<String, Object> createLevel(@RequestBody Map<String, Object> body) {
        requireOrgAdmin();
        requireOrgWide();
        return salesOrgClient.createLevel(body);
    }

    // ── Unidades ───────────────────────────────────────────────────────────────

    @GetMapping("/units")
    @Operation(summary = "Todas las unidades")
    List<Map<String, Object>> listUnits() {
        return salesOrgClient.listUnits();
    }

    /**
     * Fija el IVA que se traslada al colocar en esta unidad. Su subárbol lo hereda.
     *
     * <p>Exige administración de estructura, como crear una unidad: cambiar la tasa de una región
     * cambia lo que se le cobra a cada crédito que nazca ahí.
     */
    @PutMapping("/units/by-code/{code}/vat-rate")
    @Operation(summary = "Fija el IVA de una unidad y su subárbol")
    Map<String, Object> setUnitVatRate(@PathVariable String code,
                                       @RequestParam(required = false) java.math.BigDecimal rate) {
        requireOrgAdmin();
        return salesOrgClient.setUnitVatRate(code, rate);
    }

    @PostMapping("/units")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear una unidad (admin de estructura, dentro del propio alcance)")
    Map<String, Object> createUnit(@RequestBody Map<String, Object> body) {
        requireOrgAdmin();
        // Colgar de un padre fuera del alcance es crear estructura ajena: la unidad nace donde
        // quien la crea no la puede ni ver después.
        scope.requireWithin(uuidOrNull(body.get("parentUnitId")), "colgar de");
        return salesOrgClient.createUnit(body);
    }

    @GetMapping("/units/{unitId}")
    @Operation(summary = "Detalle de una unidad")
    Map<String, Object> getUnit(@PathVariable UUID unitId) {
        return salesOrgClient.getUnit(unitId);
    }

    @GetMapping("/units/{unitId}/children")
    @Operation(summary = "Hijos directos de una unidad")
    List<Map<String, Object>> children(@PathVariable UUID unitId) {
        return salesOrgClient.children(unitId);
    }

    @GetMapping("/units/{unitId}/subtree")
    @Operation(summary = "La unidad y su subárbol (su alcance estructural)")
    List<Map<String, Object>> subtree(@PathVariable UUID unitId) {
        return salesOrgClient.subtree(unitId);
    }

    // ── Asignaciones ─────────────────────────────────────────────────────────────

    @PutMapping("/units/{unitId}/parent")
    @Operation(summary = "Mover una unidad a otro padre",
            description = "Reorganiza la fuerza de ventas sin perder identidad: la unidad conserva "
                        + "su id, su código, sus asignaciones y su historia. Exige salesorg.manage "
                        + "y que origen y destino caigan dentro del alcance de quien mueve.")
    Map<String, Object> moveUnit(@PathVariable UUID unitId, @RequestBody Map<String, String> body) {
        requireOrgAdmin();
        String padre = body.get("parentUnitId");
        UUID destino = padre == null || padre.isBlank() ? null : UUID.fromString(padre);
        scope.requireMoveWithin(unitId, destino);
        return salesOrgClient.moveUnit(unitId, destino);
    }

    @PostMapping("/units/{unitId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Asignar (o reasignar) a una unidad (admin de estructura, dentro del alcance)")
    Map<String, Object> assign(@PathVariable UUID unitId, @RequestBody Map<String, Object> body) {
        requireOrgAdmin();
        scope.requireWithin(unitId, "asignar a");
        return salesOrgClient.assign(unitId, body);
    }

    @GetMapping("/units/{unitId}/assignments")
    @Operation(summary = "Asignaciones vigentes de una unidad")
    List<Map<String, Object>> activeInUnit(@PathVariable UUID unitId) {
        return salesOrgClient.activeInUnit(unitId);
    }

    @GetMapping("/units/{unitId}/scope")
    @Operation(summary = "Alcance de la unidad: asignaciones vigentes en todo su subárbol")
    List<Map<String, Object>> scope(@PathVariable UUID unitId) {
        return salesOrgClient.scope(unitId);
    }

    @GetMapping("/assignments/{assigneeType}/{assigneeId}/current")
    @Operation(summary = "Unidad actual del asignado")
    ResponseEntity<Map<String, Object>> current(@PathVariable String assigneeType,
                                                @PathVariable UUID assigneeId) {
        Map<String, Object> current = salesOrgClient.currentAssignment(assigneeType, assigneeId);
        return current == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(current);
    }

    @GetMapping("/assignments/{assigneeType}/{assigneeId}/history")
    @Operation(summary = "Historial de asignaciones del asignado")
    List<Map<String, Object>> history(@PathVariable String assigneeType, @PathVariable UUID assigneeId) {
        return salesOrgClient.assignmentHistory(assigneeType, assigneeId);
    }

    @DeleteMapping("/assignments/{assigneeType}/{assigneeId}")
    @Operation(summary = "Cerrar la asignación vigente del asignado (admin de estructura, dentro del alcance)")
    ResponseEntity<Void> end(@PathVariable String assigneeType, @PathVariable UUID assigneeId) {
        requireOrgAdmin();
        // El alcance se mide sobre **dónde está hoy** la persona. Sacar a alguien de una unidad
        // ajena es reorganizar equipo ajeno, aunque el destino luego sea propio.
        Map<String, Object> actual = salesOrgClient.currentAssignment(assigneeType, assigneeId);
        if (actual != null) scope.requireWithin(uuidOrNull(actual.get("unitId")), "reasignar desde");
        boolean ended = salesOrgClient.endAssignment(assigneeType, assigneeId);
        return ended ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Exige la capacidad de editar la estructura; si no, 403 (no se toca el dominio). */
    private void requireOrgAdmin() {
        if (!permissionsService.callerHas("salesorg.manage")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Se requiere la capacidad salesorg.manage");
        }
    }

    /**
     * La escalera de niveles es de toda la red, no de una rama: crear un nivel se lo cambia a
     * todos. Sólo quien tiene alcance completo puede tocarla.
     */
    private void requireOrgWide() {
        if (!scope.callerIsOrgWide()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La escalera de niveles es de toda la red: se requiere alcance completo");
        }
    }

    private static UUID uuidOrNull(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
