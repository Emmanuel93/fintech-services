package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.NotificationsClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
 * La campana de la consola: el buzón del empleado que la está usando.
 *
 * <p>El destinatario sale del {@code staffUserId} de la sesión y <b>nunca de un parámetro</b>:
 * pedirlo en la ruta dejaría que cualquiera con un token leyera el buzón de otro.
 *
 * <p>No exige capacidad. Es el buzón propio, como {@code /permissions/me}: gatearlo con una
 * capacidad dejaría sin campana a quien no la tuviera, que es justo la gente a la que hay que
 * avisarle de sus propias cosas.
 *
 * <p><b>Si el servicio de notificaciones no responde, la campana sale vacía y no rota.</b> Un
 * buzón caído no puede impedir trabajar: la consola pierde un aviso, no la sesión.
 */
@RestController
@Tag(name = "Notificaciones", description = "Buzón del empleado en sesión")
class NotificationsController {

    private static final Logger log = LoggerFactory.getLogger(NotificationsController.class);

    private final NotificationsClient notificationsClient;
    private final IdentityClient identityClient;

    NotificationsController(NotificationsClient notificationsClient, IdentityClient identityClient) {
        this.notificationsClient = notificationsClient;
        this.identityClient      = identityClient;
    }

    @Operation(summary = "Mi buzón",
               description = "Avisos dirigidos al empleado en sesión, lo más reciente primero, "
                           + "con `unreadCount` para el indicador de la campana.")
    @ApiResponse(responseCode = "200", description = "Buzón (vacío si el servicio no responde)")
    @GetMapping("/notifications")
    ResponseEntity<Map<String, Object>> myFeed(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            HttpServletRequest http) {
        UUID staffUserId = currentStaffUserId();
        try {
            ensureRegistered(staffUserId, http);
            return ResponseEntity.ok(notificationsClient.feed(staffUserId, unreadOnly));
        } catch (RuntimeException ex) {
            log.warn("Buzón no disponible para {}: {}", staffUserId, ex.toString());
            return ResponseEntity.ok(emptyFeed(staffUserId));
        }
    }

    @Operation(summary = "Marcar mi buzón como leído")
    @PutMapping("/notifications/read-all")
    ResponseEntity<Map<String, Object>> markAllRead() {
        UUID staffUserId = currentStaffUserId();
        try {
            return ResponseEntity.ok(notificationsClient.markAllRead(staffUserId));
        } catch (RuntimeException ex) {
            log.warn("No se pudo marcar leído el buzón de {}: {}", staffUserId, ex.toString());
            return ResponseEntity.ok(Map.of("updated", 0));
        }
    }

    /**
     * Alta perezosa del empleado como destinatario.
     *
     * <p>Registrar sólo al crear personal dejaría sin buzón a todos los que ya existen —y son
     * todos, porque el registro nació después que ellos—. Hacerlo al pedir el buzón repara el
     * hueco solo, sin script de migración y sin que nadie tenga que acordarse.
     *
     * <p>Si falla, no interrumpe: el buzón se pedirá igual y, si el empleado no estaba, saldrá
     * vacío. Un alta que no se pudo hacer no puede impedir leer avisos que quizá ya existan.
     */
    private void ensureRegistered(UUID staffUserId, HttpServletRequest http) {
        try {
            var perfil = identityClient.staffMe(StaffAuthController.bearerToken(http));
            if (perfil != null) {
                notificationsClient.registerStaff(staffUserId, perfil.fullName(), perfil.email());
            }
        } catch (RuntimeException ex) {
            log.warn("No se pudo inscribir al empleado {} como destinatario: {}", staffUserId, ex.toString());
        }
    }

    private static Map<String, Object> emptyFeed(UUID staffUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientType", NotificationsClient.STAFF);
        body.put("recipientId", staffUserId);
        body.put("unreadCount", 0);
        body.put("content", List.of());
        body.put("degraded", true);
        return body;
    }

    private static UUID currentStaffUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin sesión");
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión sin identificador de empleado");
        }
    }
}
