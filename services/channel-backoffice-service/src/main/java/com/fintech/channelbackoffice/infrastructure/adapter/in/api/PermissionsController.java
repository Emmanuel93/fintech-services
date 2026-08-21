package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.PermissionsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Matriz de permisos para el backoffice (read-only). El frontend usa {@code /permissions/me} para
 * decidir qué módulos y acciones mostrar; {@code /permissions/matrix} es la referencia completa para
 * administración. La edición en caliente está diferida (exige maker-checker).
 */
@RestController
@RequestMapping("/permissions")
@Tag(name = "Permisos", description = "Roles del backoffice y sus capacidades")
class PermissionsController {

    private static final Logger log = LoggerFactory.getLogger(PermissionsController.class);

    private final PermissionsService permissionsService;
    private final IdentityClient identityClient;

    PermissionsController(PermissionsService permissionsService, IdentityClient identityClient) {
        this.permissionsService = permissionsService;
        this.identityClient = identityClient;
    }

    @GetMapping("/me")
    @Operation(summary = "Roles y capacidades efectivas del usuario autenticado")
    Map<String, Object> me() {
        Set<String> roles = currentRoles();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roles", roles);
        body.put("capabilities", permissionsService.capabilitiesFor(roles));
        return body;
    }

    @GetMapping("/matrix")
    @Operation(summary = "Matriz completa rol → capacidades (referencia de administración)")
    Map<String, Set<String>> matrix() {
        requireMatrixRole();
        return permissionsService.matrix();
    }

    @GetMapping("/roles")
    @Operation(summary = "Catálogo de roles con su descripción y sus capacidades",
            description = "La matriz con nombre y propósito de cada rol, para la pantalla de "
                        + "administración. `/matrix` sigue devolviendo sólo el mapa.")
    List<IdentityClient.RoleResponse> roles() {
        requireMatrixRole();
        return identityClient.listRoles();
    }

    @GetMapping("/capabilities")
    @Operation(summary = "Capacidades que existen hoy",
            description = "La unión de las que algún rol ya tiene. Es lo que la pantalla puede "
                        + "ofrecer para asignar: inventar una capacidad que ningún controlador "
                        + "consulta daría un permiso que no abre ninguna puerta.")
    Set<String> capabilities() {
        requireMatrixRole();
        return permissionsService.matrix().values().stream()
                .flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new));
    }

    @PutMapping("/roles/{code}/capabilities")
    @Operation(summary = "Reemplazar las capacidades de un rol",
            description = "Sólo ADMIN. Al guardar, el canal relee su matriz: el cambio surte "
                        + "efecto en la siguiente petición, no en el siguiente despliegue.")
    IdentityClient.RoleResponse replaceCapabilities(@PathVariable String code,
                                                    @RequestBody Map<String, Set<String>> body,
                                                    HttpServletRequest http) {
        if (!permissionsService.callerHas("permissions.manage")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Se requiere la capacidad permissions.manage");
        }
        Set<String> capabilities = body.getOrDefault("capabilities", Set.of());
        var actualizado = identityClient.replaceRoleCapabilities(
                StaffAuthController.bearerToken(http), code, capabilities);
        // Sin esto el canal seguiría aplicando la matriz vieja hasta reiniciarse, y la pantalla
        // mostraría un cambio guardado que el servidor todavía no respeta.
        permissionsService.reload();
        log.info("Capacidades de {} reemplazadas; matriz recargada desde {}",
                code, permissionsService.source());
        return actualizado;
    }

    private void requireMatrixRole() {
        if (!permissionsService.callerHas("permissions.view")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Se requiere la capacidad permissions.view");
        }
    }

    private static Set<String> currentRoles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
