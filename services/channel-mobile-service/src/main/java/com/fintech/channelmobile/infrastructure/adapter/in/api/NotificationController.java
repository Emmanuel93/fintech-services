package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.out.client.NotificationsClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.NotificationsClient.NotificationRecordResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * fa_notifications espera un inbox in-app (title/body/isRead). notifications-service
 * (dominio v1, 6 eventos) solo trackea historial de ENVÍO por canal (push/whatsapp/email),
 * sin título/cuerpo de usuario — este controller traduce cada eventType a copy fijo en
 * español (decisión de presentación del BFF, no del dominio). El estado de lectura
 * (read_at) sí lo persiste notifications-service desde aquí.
 */
@RestController
@Tag(name = "Notifications", description = "Notificaciones in-app del usuario autenticado")
@SecurityRequirement(name = "bearerAuth")
class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationsClient notificationsClient;

    NotificationController(NotificationsClient notificationsClient) {
        this.notificationsClient = notificationsClient;
    }

    @Operation(summary = "Listar notificaciones del usuario autenticado")
    @GetMapping("/notifications")
    ResponseEntity<Map<String, Object>> list(HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID partyId = UUID.fromString(userId);
        log.info("GET /notifications partyId={}", partyId);
        List<Map<String, Object>> notifications = notificationsClient.getHistory(partyId, userId)
                .stream()
                .map(NotificationController::toAppShape)
                .toList();
        return ResponseEntity.ok(Map.of("notifications", notifications));
    }

    @Operation(summary = "Marcar una notificación como leída")
    @PutMapping("/notifications/{id}/read")
    ResponseEntity<Map<String, Object>> markAsRead(@PathVariable String id, HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID notificationId = UUID.fromString(id);
        log.info("PUT /notifications/{}/read", notificationId);
        notificationsClient.markAsRead(notificationId, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @Operation(summary = "Marcar todas las notificaciones del usuario autenticado como leídas")
    @PutMapping("/notifications/read-all")
    ResponseEntity<Map<String, Object>> markAllRead(HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID partyId = UUID.fromString(userId);
        log.info("PUT /notifications/read-all partyId={}", partyId);
        notificationsClient.markAllAsRead(partyId, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private static Map<String, Object> toAppShape(NotificationRecordResponse r) {
        Copy copy = COPY_BY_EVENT_TYPE.getOrDefault(r.eventType(),
                new Copy("marketing", "Notificación", "Tienes una notificación nueva."));
        Map<String, Object> map = new HashMap<>();
        map.put("id", r.notificationId().toString());
        map.put("type", copy.type());
        map.put("title", copy.title());
        map.put("body", copy.body());
        map.put("date", r.sentAt().toString());
        map.put("isRead", r.isRead());
        map.put("actionRoute", null);
        return map;
    }

    private record Copy(String type, String title, String body) {}

    // eventType (notifications-service) → copy in-app (fa_notifications.NotificationType).
    // Lo que no esté aquí cae al genérico "Tienes una notificación nueva", que no
    // dice nada: cada tipo nuevo del dominio necesita su entrada.
    private static final Map<String, Copy> COPY_BY_EVENT_TYPE = Map.ofEntries(
            Map.entry("PAYMENT_OVERDUE", new Copy("paymentDue", "Tu crédito está en mora",
                    "Tu pago venció y sigue pendiente. Ponte al corriente para dejar de "
                    + "generar intereses moratorios.")),
            Map.entry("OFFER_PRESENTED", new Copy("benefit", "Nueva oferta disponible",
                    "Tienes una nueva oferta de crédito esperándote.")),
            Map.entry("WELCOME_ACTIVATED", new Copy("statement", "¡Tu crédito está activo!",
                    "Ya puedes empezar a usar tu línea de crédito.")),
            Map.entry("DISBURSEMENT_COMPLETED", new Copy("movement", "Disposición completada",
                    "El dinero de tu disposición ya está disponible en tu wallet.")),
            Map.entry("PAYMENT_REMINDER", new Copy("paymentDue", "Tu pago está por vencer",
                    "Recuerda realizar tu pago antes de la fecha límite.")),
            Map.entry("INSTALLMENT_PAID", new Copy("paymentReceived", "Pago recibido",
                    "Registramos tu pago correctamente.")),
            Map.entry("LOAN_SETTLED", new Copy("statement", "¡Crédito liquidado!",
                    "Has terminado de pagar tu crédito. ¡Felicidades!"))
    );
}
