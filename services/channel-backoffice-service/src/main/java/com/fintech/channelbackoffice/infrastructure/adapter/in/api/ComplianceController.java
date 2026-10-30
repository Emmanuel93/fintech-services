package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ComplianceClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Mesa de cumplimiento: la cola de identidad y listas de <b>toda</b> persona registrada.
 *
 * <h2>Por qué existe</h2>
 *
 * Sin proveedor de KYC, toda alta —titular de uso propio, distribuidora o beneficiaria— queda en
 * identidad {@code IN_REVIEW}, y las compuertas de originación y disposición la bloquean. El
 * dictamen existía en compliance, pero sólo se alcanzaba por la red interna: en la práctica lo
 * firmaban los scripts de siembra. Esta mesa es la salida con nombre y apellido.
 *
 * <h2>Composición (patrón A)</h2>
 *
 * La cola la resuelve compliance, paginada. La ficha suma, una llamada por fuente y cada bloque
 * degradándose solo: el veredicto (compliance), lo que la persona capturó y sus documentos
 * (origination, por prospecto — existen desde el alta, antes de cualquier solicitud) y la bitácora
 * de consultas a listas. Que origination no conteste no impide dictaminar: impide ver la INE, y la
 * pantalla lo dice.
 *
 * <h2>Quién firma</h2>
 *
 * El autor sale de la sesión y nunca del cuerpo. Se firma con el correo del staff —legible en el
 * expediente seis meses después— y, si identity no lo resuelve, con su id: un dictamen nunca se
 * queda sin autor por una consulta auxiliar que falló.
 */
@RestController
@Tag(name = "Cumplimiento", description = "Mesa de identidad y listas (KYC/AML) — revisión manual, sin Incode")
class ComplianceController {

    private static final Logger log = LoggerFactory.getLogger(ComplianceController.class);

    private final ComplianceClient compliance;
    private final OriginationClient origination;
    private final StaffIdentityResolver staffIdentities;

    ComplianceController(ComplianceClient compliance, OriginationClient origination,
                         StaffIdentityResolver staffIdentities) {
        this.compliance      = compliance;
        this.origination     = origination;
        this.staffIdentities = staffIdentities;
    }

    @Operation(summary = "Cola de cumplimiento",
               description = "Personas por estado de identidad y de listas. Los filtros se repiten "
                           + "(`identityStatus=IN_REVIEW&identityStatus=PENDING`); vacíos = todos. "
                           + "`q` busca por nombre o CURP. Del más viejo al más nuevo.")
    @GetMapping("/compliance/verifications")
    Map<String, Object> queue(@RequestParam(required = false) List<String> identityStatus,
                              @RequestParam(required = false) List<String> screeningStatus,
                              @RequestParam(required = false) String q,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size) {
        return compliance.search(identityStatus, screeningStatus, q, page, size);
    }

    @Operation(summary = "Ficha de una persona en la mesa",
               description = "Veredicto + lo capturado + documentos + bitácora de listas. Cada bloque "
                           + "es independiente: si una fuente no responde, ese bloque viene nulo o "
                           + "vacío y el resto sigue.")
    @GetMapping("/compliance/verifications/{verificationId}")
    Map<String, Object> detail(@PathVariable UUID verificationId) {
        Map<String, Object> verification = compliance.verification(verificationId);
        UUID prospectId = uuid(verification.get("prospectId"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verification", verification);
        body.put("prospect", prospectId == null ? null : prospectView(prospectId));
        body.put("documents", prospectId == null ? List.of() : documents(prospectId));
        body.put("checks", prospectId == null ? List.of() : checks(prospectId));
        return body;
    }

    @Operation(summary = "Ver un documento de identidad de la persona",
               description = "INE, selfie o comprobante, tal como lo subió. Es dato personal: no se "
                           + "cachea y sólo lo ve quien tiene la mesa.")
    @GetMapping("/compliance/verifications/{verificationId}/documents/{documentType}/file")
    ResponseEntity<byte[]> documentFile(@PathVariable UUID verificationId,
                                        @PathVariable String documentType) {
        UUID prospectId = uuid(compliance.verification(verificationId).get("prospectId"));
        if (prospectId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "La verificación no tiene prospecto");
        }
        var content = origination.documentContent(prospectId, documentType);
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sin archivo de " + documentType);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content.bytes());
    }

    @Operation(summary = "Dictaminar la identidad",
               description = "VERIFIED o REJECTED, con argumento obligatorio. Sin proveedor "
                           + "biométrico, quien dictamina compara la INE contra la selfie y los datos. "
                           + "Republica el veredicto: originación y disposición se enteran solas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dictamen registrado"),
            @ApiResponse(responseCode = "400", description = "Sin argumento, o una decisión que no lo es"),
            @ApiResponse(responseCode = "404", description = "No existe la verificación")
    })
    @PostMapping("/compliance/verifications/{verificationId}/identity")
    Map<String, Object> decideIdentity(@PathVariable UUID verificationId, @RequestBody DecisionRequest req) {
        req.requireNotes();
        return compliance.decideIdentity(verificationId, req.decision(), author(), req.notes().trim());
    }

    @Operation(summary = "Adjudicar una posible coincidencia en listas",
               description = "CLEARED (homónimo) o CONFIRMED_MATCH. Confirmar abre un reporte a la "
                           + "UIF en audit; por eso pide argumento siempre.")
    @PostMapping("/compliance/verifications/{verificationId}/adjudicate")
    Map<String, Object> adjudicate(@PathVariable UUID verificationId, @RequestBody DecisionRequest req) {
        req.requireNotes();
        return compliance.adjudicate(verificationId, req.decision(), author(), req.notes().trim());
    }

    /** {@code notes} se exige aquí también: un 400 con motivo claro antes de cruzar la red. */
    record DecisionRequest(String decision, String notes) {
        void requireNotes() {
            if (decision == null || decision.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta la decisión");
            }
            if (notes == null || notes.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Falta el argumento: es lo que sustenta el dictamen ante una revisión");
            }
        }
    }

    // ── Composición ──────────────────────────────────────────────────────────────────────────

    private Map<String, Object> prospectView(UUID prospectId) {
        try {
            var p = origination.getProspect(prospectId);
            if (p == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("firstName", p.firstName());
            m.put("lastName1", p.lastName1());
            m.put("lastName2", p.lastName2());
            m.put("curp", p.curp());
            m.put("rfc", p.rfc());
            m.put("dateOfBirth", p.dateOfBirth());
            m.put("gender", p.gender());
            m.put("phone", p.phone());
            m.put("email", p.email());
            var a = p.address();
            m.put("street", a == null ? null : a.street());
            m.put("exteriorNumber", a == null ? null : a.exteriorNumber());
            m.put("neighborhood", a == null ? null : a.neighborhood());
            m.put("municipality", a == null ? null : a.municipality());
            m.put("city", a == null ? null : a.city());
            m.put("state", a == null ? null : a.state());
            m.put("postalCode", a == null ? null : a.postalCode());
            m.put("channelType", p.channelType());
            m.put("prospectType", p.prospectType());
            return m;
        } catch (RuntimeException ex) {
            log.warn("No se pudo leer el prospecto {} para la mesa: {}", prospectId, ex.toString());
            return null;
        }
    }

    /** Los documentos en la forma que pinta la consola, sin los bytes. */
    private List<Map<String, Object>> documents(UUID prospectId) {
        try {
            List<Map<String, Object>> raw = origination.documents(prospectId);
            if (raw == null) return List.of();
            return raw.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("documentType", first(d, "documentType", "type"));
                m.put("fileName", first(d, "fileName", "name"));
                m.put("contentType", d.get("contentType"));
                m.put("sizeBytes", first(d, "sizeBytes", "size"));
                m.put("uploadedAt", d.get("uploadedAt"));
                return m;
            }).toList();
        } catch (RuntimeException ex) {
            log.warn("No se pudieron listar los documentos del prospecto {}: {}", prospectId, ex.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checks(UUID prospectId) {
        try {
            Object list = compliance.checks(prospectId, 20).get("checks");
            return list instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (RuntimeException ex) {
            log.warn("No se pudo leer la bitácora de listas de {}: {}", prospectId, ex.toString());
            return List.of();
        }
    }

    private String author() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin sesión");
        }
        String userId = auth.getName();
        return staffIdentities.resolve(userId)
                .map(StaffIdentityResolver.StaffIdentity::email)
                .filter(e -> e != null && !e.isBlank())
                .orElse(userId);
    }

    private static Object first(Map<String, Object> m, String a, String b) {
        return m.get(a) != null ? m.get(a) : m.get(b);
    }

    private static UUID uuid(Object value) {
        if (value == null) return null;
        try {
            return value instanceof UUID u ? u : UUID.fromString(value.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
