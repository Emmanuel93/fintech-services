package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.AuditSubjectResolver;
import com.fintech.channelbackoffice.application.PermissionsService;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.AuditClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bitácora regulatoria para el backoffice.
 *
 * <p>Dato sensible por definición: la auditoría se consulta solo con rol de auditoría (AUDITOR o
 * ADMIN). Filtrable por sujeto, agregado, tipo de evento y —lo nuevo de E2— por ACTOR: "qué hizo el
 * empleado X". El BFF gate; audit-service confía en esa decisión.
 */
@RestController
@RequestMapping("/audit")
@Tag(name = "Auditoría", description = "Bitácora regulatoria (solo AUDITOR/ADMIN)")
class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditClient auditClient;
    private final AuditSubjectResolver subjectResolver;
    private final PermissionsService permissionsService;

    AuditController(AuditClient auditClient, AuditSubjectResolver subjectResolver,
                    PermissionsService permissionsService) {
        this.auditClient = auditClient;
        this.subjectResolver = subjectResolver;
        this.permissionsService = permissionsService;
    }

    @GetMapping("/entries")
    @Operation(summary = "Consultar la bitácora",
            description = "Por identificador (partyId, agregado, tipo, actor) o por `subject`: "
                        + "un teléfono, correo, CURP o número de contrato, que el BFF traduce a "
                        + "los identificadores con los que la bitácora indexa.")
    List<Map<String, Object>> entries(
            @RequestParam(required = false) String partyId,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "200") int limit,
            HttpServletRequest http) {
        requireAuditRole();
        if (subject != null && !subject.isBlank()) {
            return bySubject(subject, eventType, from, to, limit, http);
        }
        return auditClient.listEntries(partyId, aggregateId, eventType, actor, from, to, limit);
    }

    /**
     * Busca por lo que la persona escribió, no por lo que la bitácora indexa.
     *
     * <p>Un teléfono puede llevar a un party, a un prospecto y a un actor de inicio de sesión a la
     * vez —son la misma persona vista por tres servicios—, así que se consulta cada camino y se
     * unen los resultados. Se deduplica por `entryId` porque un mismo evento aparece tanto por
     * sujeto como por agregado, y se ordena por fecha descendente: la historia se lee del final.
     *
     * <p>Que el texto no resuelva a nadie devuelve lista vacía. No es un 404: la pregunta era
     * válida y la respuesta es que no hay nada.
     */
    private List<Map<String, Object>> bySubject(String subject, String eventType,
                                                String from, String to, int limit,
                                                HttpServletRequest http) {
        var r = subjectResolver.resolve(subject, StaffAuthController.bearerToken(http));
        log.info("Búsqueda por sujeto «{}» interpretada como {} → {} partes, {} agregados",
                subject, r.interpretedAs(), r.partyIds().size(), r.aggregateIds().size());
        if (r.isEmpty()) return List.of();

        Map<Object, Map<String, Object>> porEntrada = new LinkedHashMap<>();
        r.partyIds().forEach(id -> merge(porEntrada,
                () -> auditClient.listEntries(id.toString(), null, eventType, null, from, to, limit)));
        r.aggregateIds().forEach(id -> merge(porEntrada,
                () -> auditClient.listEntries(null, id.toString(), eventType, null, from, to, limit)));
        r.actors().forEach(a -> merge(porEntrada,
                () -> auditClient.listEntries(null, null, eventType, a, from, to, limit)));

        List<Map<String, Object>> todos = new ArrayList<>(porEntrada.values());
        todos.sort(Comparator.comparing(
                (Map<String, Object> e) -> String.valueOf(e.get("createdAt")), Comparator.reverseOrder()));
        return todos;
    }

    /** Suma un tramo al resultado. Si ese camino falla, los demás siguen valiendo. */
    private void merge(Map<Object, Map<String, Object>> acc,
                       java.util.function.Supplier<List<Map<String, Object>>> tramo) {
        try {
            List<Map<String, Object>> filas = tramo.get();
            if (filas == null) return;
            for (Map<String, Object> f : filas) {
                Object id = f.get("entryId");
                if (id != null) acc.putIfAbsent(id, f);
            }
        } catch (Exception ex) {
            log.warn("Un tramo de la búsqueda por sujeto falló: {}", ex.getMessage());
        }
    }

    @GetMapping("/entries/{entryId}")
    @Operation(summary = "Detalle de una entrada (incluye payload saneado)")
    Map<String, Object> entry(@PathVariable UUID entryId) {
        requireAuditRole();
        return auditClient.getEntry(entryId);
    }

    /**
     * Exige la capacidad, no un rol.
     *
     * <p>Antes esto era {@code Set.of("ADMIN", "AUDITOR")}: la misma matriz escrita por segunda
     * vez, en un lugar donde nadie la mantiene junto con la primera.
     */
    private void requireAuditRole() {
        if (!permissionsService.callerHas("audit.view")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Se requiere la capacidad audit.view");
        }
    }
}
