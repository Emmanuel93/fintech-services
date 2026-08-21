package com.fintech.notifications.infrastructure.adapter.in.api;

import com.fintech.notifications.application.port.out.NotificationRecordRepository;
import com.fintech.notifications.application.service.RecipientNotificationService;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.NotificationRecipient;
import com.fintech.notifications.domain.NotificationRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Notificar a cualquier entidad, sin que este servicio sepa qué es.
 *
 * <p>Dos verbos y un buzón:
 *
 * <ul>
 *   <li><b>Registrar</b> a la entidad con sus vías de contacto. Lo hace quien la conoce.</li>
 *   <li><b>Notificar</b>: el emisor dice a quién, con qué clave de evento y —si quiere— por qué
 *       canales. «A dónde» es suyo, no de aquí.</li>
 *   <li><b>Leer el buzón</b> de una entidad, con su conteo de no leídas.</li>
 * </ul>
 *
 * <p>{@code recipientType} es texto libre y este servicio no ramifica sobre su valor. Un préstamo,
 * un asesor externo o un empleado se notifican por el mismo camino; lo único que cambia es quién
 * los registró.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notificaciones · entidades", description = "Destinatario abstracto: el emisor decide qué y a dónde")
class RecipientNotificationController {

    private final RecipientNotificationService service;
    private final NotificationRecordRepository records;

    RecipientNotificationController(RecipientNotificationService service,
                                    NotificationRecordRepository records) {
        this.service = service;
        this.records = records;
    }

    // ── Registro ─────────────────────────────────────────────────────────────────────────────

    public record RegisterRecipientRequest(
            String displayName,
            String phone,
            String email,
            String pushToken,
            String locale) {}

    @Operation(summary = "Registrar o actualizar un destinatario",
               description = "Idempotente por (tipo, id). Un campo nulo no borra: significa «no "
                           + "traigo dato de esto». Para quitar una vía se manda vacío.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Destinatario registrado"),
            @ApiResponse(responseCode = "422", description = "Sin ninguna vía de contacto")
    })
    @PutMapping("/recipients/{recipientType}/{recipientId}")
    ResponseEntity<Map<String, Object>> register(@PathVariable String recipientType,
                                                 @PathVariable UUID recipientId,
                                                 @RequestBody RegisterRecipientRequest req) {
        NotificationRecipient r = service.register(recipientType, recipientId,
                req.displayName(), req.phone(), req.email(), req.pushToken(), req.locale());
        return ResponseEntity.ok(view(r));
    }

    // ── Envío ────────────────────────────────────────────────────────────────────────────────

    public record NotifyRequest(
            @NotBlank String recipientType,
            @NotNull UUID recipientId,
            @NotBlank String eventKey,
            /** Idempotencia: dos envíos con el mismo id no molestan dos veces a la misma persona. */
            String sourceEventId,
            Map<String, String> variables,
            /** Canales impuestos por el emisor. Vacío deja decidir a la política de la clave. */
            List<NotificationChannel> channels) {}

    @Operation(summary = "Notificar a una entidad",
               description = "El emisor decide destinatario, clave de evento y —opcionalmente— "
                           + "canales. Sin política para la clave y sin canales impuestos no se "
                           + "manda nada: elegir un canal por defecto sería decidir por el emisor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registros escritos, uno por canal intentado"),
            @ApiResponse(responseCode = "404", description = "El destinatario no está registrado")
    })
    @PostMapping
    ResponseEntity<Map<String, Object>> notify(@Valid @RequestBody NotifyRequest req) {
        String sourceEventId = (req.sourceEventId() == null || req.sourceEventId().isBlank())
                ? UUID.randomUUID().toString()
                : req.sourceEventId();

        List<NotificationRecord> written = service.notify(sourceEventId, req.recipientType(),
                req.recipientId(), req.eventKey(), req.variables(), req.channels());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceEventId", sourceEventId);
        body.put("delivered", written.stream().map(RecipientNotificationController::recordView).toList());
        return ResponseEntity.ok(body);
    }

    // ── Buzón ────────────────────────────────────────────────────────────────────────────────

    @Operation(summary = "Buzón de una entidad",
               description = "Lo más reciente primero, con el conteo de no leídas para el indicador.")
    @GetMapping("/feed/{recipientType}/{recipientId}")
    ResponseEntity<Map<String, Object>> feed(@PathVariable String recipientType,
                                             @PathVariable UUID recipientId,
                                             @RequestParam(defaultValue = "false") boolean unreadOnly) {
        String type = recipientType.trim().toUpperCase();
        List<NotificationRecord> all = records.findFeed(type, recipientId);
        List<NotificationRecord> shown = unreadOnly
                ? all.stream().filter(n -> n.getReadAt() == null).toList()
                : all;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientType", type);
        body.put("recipientId", recipientId);
        body.put("unreadCount", records.countUnread(type, recipientId));
        body.put("content", shown.stream().map(RecipientNotificationController::recordView).toList());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Marcar como leído todo el buzón de una entidad")
    @PutMapping("/feed/{recipientType}/{recipientId}/read-all")
    ResponseEntity<Map<String, Object>> markAllRead(@PathVariable String recipientType,
                                                    @PathVariable UUID recipientId) {
        int updated = records.markAllRead(recipientType.trim().toUpperCase(), recipientId);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    // ── Vistas ───────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> view(NotificationRecipient r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipientType", r.getRecipientType());
        m.put("recipientId", r.getRecipientId());
        m.put("displayName", r.getDisplayName());
        // Las vías se declaran presentes, no se devuelven: este endpoint confirma un alta, no es
        // un directorio del que se pueda extraer el teléfono de nadie.
        m.put("hasPhone", r.getPhone() != null);
        m.put("hasEmail", r.getEmail() != null);
        m.put("hasPushToken", r.getPushToken() != null);
        m.put("locale", r.getLocale());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> recordView(NotificationRecord n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notificationId", n.getNotificationId());
        m.put("eventKey", n.getEventKey());
        m.put("channel", n.getChannel() == null ? null : n.getChannel().name());
        m.put("status", n.getStatus() == null ? null : n.getStatus().name());
        m.put("failureReason", n.getFailureReason());
        m.put("sentAt", n.getSentAt());
        m.put("readAt", n.getReadAt());
        return m;
    }
}
