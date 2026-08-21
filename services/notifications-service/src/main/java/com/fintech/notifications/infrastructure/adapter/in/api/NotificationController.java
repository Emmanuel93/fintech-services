package com.fintech.notifications.infrastructure.adapter.in.api;

import com.fintech.notifications.application.CreateNotificationPolicyCommand;
import com.fintech.notifications.application.port.in.GetNotificationHistoryUseCase;
import com.fintech.notifications.application.port.in.ManageNotificationPreferenceUseCase;
import com.fintech.notifications.application.port.in.ManageNotificationReadStateUseCase;
import com.fintech.notifications.application.port.in.NotificationPolicyUseCase;
import com.fintech.notifications.infrastructure.adapter.in.api.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Entrega de comunicaciones al cliente — alcance v1: 6 notificaciones")
@SecurityRequirement(name = "bearerAuth")
class NotificationController {

    private final GetNotificationHistoryUseCase historyUseCase;
    private final ManageNotificationPreferenceUseCase preferenceUseCase;
    private final NotificationPolicyUseCase policyUseCase;
    private final ManageNotificationReadStateUseCase readStateUseCase;

    NotificationController(GetNotificationHistoryUseCase historyUseCase,
                            ManageNotificationPreferenceUseCase preferenceUseCase,
                            NotificationPolicyUseCase policyUseCase,
                            ManageNotificationReadStateUseCase readStateUseCase) {
        this.historyUseCase = historyUseCase;
        this.preferenceUseCase = preferenceUseCase;
        this.policyUseCase = policyUseCase;
        this.readStateUseCase = readStateUseCase;
    }

    @Operation(summary = "Historial de notificaciones de un party")
    @GetMapping("/history/{partyId}")
    ResponseEntity<List<NotificationRecordResponse>> history(@PathVariable UUID partyId) {
        return ResponseEntity.ok(historyUseCase.getByRecipientId(partyId)
                .stream().map(NotificationRecordResponse::from).toList());
    }

    @Operation(summary = "Marca una notificación como leída")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Marcada como leída"),
        @ApiResponse(responseCode = "404", description = "No existe esa notificación")
    })
    @PutMapping("/{notificationId}/read")
    ResponseEntity<Map<String, Object>> markAsRead(@PathVariable UUID notificationId) {
        if (!readStateUseCase.markAsRead(notificationId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @Operation(summary = "Marca todas las notificaciones de un party como leídas")
    @PutMapping("/read-all/{recipientId}")
    ResponseEntity<Map<String, Object>> markAllAsRead(@PathVariable UUID recipientId) {
        int updated = readStateUseCase.markAllAsRead(recipientId);
        return ResponseEntity.ok(Map.of("success", true, "updated", updated));
    }

    @Operation(summary = "Preferencias de notificación de un party (push token, WhatsApp)")
    @GetMapping("/preferences/{partyId}")
    ResponseEntity<NotificationPreferenceResponse> getPreferences(@PathVariable UUID partyId) {
        return preferenceUseCase.get(partyId)
                .map(p -> ResponseEntity.ok(NotificationPreferenceResponse.from(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Registra/actualiza preferencias de notificación (push token, WhatsApp)")
    @PutMapping("/preferences/{partyId}")
    NotificationPreferenceResponse updatePreferences(@PathVariable UUID partyId,
                                                       @RequestBody UpdateNotificationPreferenceRequest req) {
        return NotificationPreferenceResponse.from(
                preferenceUseCase.update(partyId, req.pushToken(), req.whatsappNumber()));
    }

    @Operation(summary = "Políticas de notificación vigentes (transparencia)")
    @GetMapping("/policies")
    ResponseEntity<List<NotificationPolicyResponse>> listPolicies() {
        return ResponseEntity.ok(policyUseCase.listActive()
                .stream().map(NotificationPolicyResponse::from).toList());
    }

    @Operation(summary = "Crea/versiona una política de notificación (equipo de marketing/producto)")
    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    NotificationPolicyResponse createPolicy(@Valid @RequestBody CreateNotificationPolicyRequest req) {
        return NotificationPolicyResponse.from(policyUseCase.create(new CreateNotificationPolicyCommand(
                req.eventType(), req.valueTier(), req.channelStrategy(), req.primaryChannel(), req.fallbackChannels())));
    }
}
