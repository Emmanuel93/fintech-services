package com.fintech.collections.infrastructure.adapter.in.api;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.ContactQueueRow;
import com.fintech.collections.application.PromiseQueueRow;
import com.fintech.collections.application.port.out.CollectionsQueueRepository;
import com.fintech.collections.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

/**
 * Las bandejas transversales de cobranza: promesas y contactos <b>cruzando casos</b>.
 *
 * <p>Hasta ahora sólo existían como sub-recursos de un caso, así que armar «promesas vigentes» o
 * «casos por contactar» obligaba a una llamada por fila desde el canal. Aquí la consulta la
 * resuelve el dueño —con {@code JOIN} al caso, paginada e indexada— y el canal sólo enriquece
 * nombres por lote.
 *
 * <p>Los dos flags que la bandeja necesita para decidir si se puede marcar —fuera de ventana y
 * tope de intentos— se calculan <b>aquí</b> y no en el canal: son reglas de trato al cliente que
 * viven en {@link CollectionsProperties}, y dejarlas del lado de quien pinta la pantalla las
 * volvería una sugerencia visual en vez de una regla.
 */
@RestController
@RequestMapping("/api/v1/collections")
@Tag(name = "Cobranza · bandejas", description = "Promesas y contactos cruzando casos")
@SecurityRequirement(name = "bearerAuth")
class CollectionsQueueController {

    private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
    /** Tope de página: una bandeja se navega, no se descarga. */
    private static final int MAX_PAGE_SIZE = 200;

    private final CollectionsQueueRepository queue;
    private final CollectionsProperties properties;

    CollectionsQueueController(CollectionsQueueRepository queue, CollectionsProperties properties) {
        this.queue      = queue;
        this.properties = properties;
    }

    @Operation(summary = "Promesas de pago cruzando casos",
               description = "Filtros: estado, tramo, gestor y rango de fecha prometida. "
                           + "`dueToday=true` acota a las que vencen hoy. Cada fila trae el "
                           + "contexto de su caso, los intentos de contacto de hoy y el resultado "
                           + "del último contacto.")
    @GetMapping("/payment-promises")
    ResponseEntity<Map<String, Object>> promises(
            @RequestParam(required = false) List<PromiseStatus> status,
            @RequestParam(required = false) List<DelinquencyBucket> bucket,
            @RequestParam(required = false) List<String> agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(required = false, defaultValue = "false") boolean dueToday,
            Pageable pageable) {

        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = dueToday ? today : dueFrom;
        LocalDate to   = dueToday ? today : dueTo;

        Page<PromiseQueueRow> page = queue.searchPromises(
                status, bucket, agentId, from, to, capped(pageable));

        boolean withinWindow = withinContactWindow(LocalTime.now(ZONE));

        Map<String, Object> body = envelope(page);
        body.put("content", page.getContent().stream()
                .map(r -> promiseView(r, today, withinWindow)).toList());
        body.put("contactWindow", contactWindow(withinWindow));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Intentos de contacto cruzando casos",
               description = "Filtros: resultado, canal, tramo, gestor y rango de fecha del intento.")
    @GetMapping("/contact-attempts")
    ResponseEntity<Map<String, Object>> contactAttempts(
            @RequestParam(required = false) List<ContactResult> result,
            @RequestParam(required = false) List<ContactChannel> channel,
            @RequestParam(required = false) List<DelinquencyBucket> bucket,
            @RequestParam(required = false) List<String> agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {

        Page<ContactQueueRow> page = queue.searchContactAttempts(
                result, channel, bucket, agentId, from, to, capped(pageable));

        Map<String, Object> body = envelope(page);
        body.put("content", page.getContent().stream().map(CollectionsQueueController::contactView).toList());
        body.put("contactWindow", contactWindow(withinContactWindow(LocalTime.now(ZONE))));
        return ResponseEntity.ok(body);
    }

    // ── Vistas ───────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> promiseView(PromiseQueueRow r, LocalDate today, boolean withinWindow) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promiseId", r.promiseId());
        m.put("caseId", r.caseId());
        m.put("amount", r.amount());
        m.put("promisedDate", r.promisedDate());
        m.put("status", r.status().name());
        m.put("recordedBy", r.recordedBy());
        m.put("createdAt", r.createdAt());

        m.put("creditAccountId", r.creditAccountId());
        m.put("obligorPartyId", r.obligorPartyId());
        m.put("productType", r.productType());
        m.put("currentBucket", r.currentBucket() == null ? null : r.currentBucket().name());
        m.put("daysDelinquent", r.daysDelinquent());
        m.put("assignedAgentId", r.assignedAgentId());

        // Estado de agenda, derivado: «vence hoy» y «se pasó» sólo aplican a una promesa viva.
        // Una promesa cumplida cuya fecha ya pasó no está vencida, está cumplida.
        boolean active = r.status() == PromiseStatus.ACTIVE;
        m.put("dueToday", active && today.equals(r.promisedDate()));
        m.put("overdue", active && r.promisedDate() != null && r.promisedDate().isBefore(today));

        m.put("attemptsToday", r.attemptsToday());
        m.put("lastContactResult", r.lastContactResult() == null ? null : r.lastContactResult().name());

        boolean capReached = r.attemptsToday() >= properties.getMaxContactAttemptsPerDay();
        m.put("contactCapReached", capReached);
        m.put("contactable", withinWindow && !capReached);
        return m;
    }

    private static Map<String, Object> contactView(ContactQueueRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attemptId", r.attemptId());
        m.put("caseId", r.caseId());
        m.put("channel", r.channel() == null ? null : r.channel().name());
        m.put("result", r.result() == null ? null : r.result().name());
        m.put("origin", r.origin() == null ? null : r.origin().name());
        m.put("agentId", r.agentId());
        m.put("dunningStep", r.dunningStep());
        m.put("attemptedAt", r.attemptedAt());

        m.put("creditAccountId", r.creditAccountId());
        m.put("obligorPartyId", r.obligorPartyId());
        m.put("productType", r.productType());
        m.put("currentBucket", r.currentBucket() == null ? null : r.currentBucket().name());
        m.put("daysDelinquent", r.daysDelinquent());
        m.put("assignedAgentId", r.assignedAgentId());
        return m;
    }

    // ── Reglas de trato ──────────────────────────────────────────────────────────────────────

    /**
     * Si la hora permite contactar.
     *
     * <p>Es la misma para todas las filas, así que viaja una vez en el sobre y no repetida en cada
     * una: no depende del caso sino del reloj.
     */
    private boolean withinContactWindow(LocalTime now) {
        return now.getHour() >= properties.getContactAllowedHoursStart()
            && now.getHour() <  properties.getContactAllowedHoursEnd();
    }

    private Map<String, Object> contactWindow(boolean within) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("startHour", properties.getContactAllowedHoursStart());
        w.put("endHour", properties.getContactAllowedHoursEnd());
        w.put("maxAttemptsPerDay", properties.getMaxContactAttemptsPerDay());
        w.put("withinWindowNow", within);
        return w;
    }

    private static Pageable capped(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) return pageable;
        return org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }

    private static Map<String, Object> envelope(Page<?> page) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", page.getNumber());
        body.put("size", page.getSize());
        body.put("totalElements", page.getTotalElements());
        body.put("totalPages", page.getTotalPages());
        return body;
    }
}
