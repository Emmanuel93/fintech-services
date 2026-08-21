package com.fintech.identity.infrastructure.adapter.in.api;

import com.fintech.identity.domain.Role;
import com.fintech.identity.infrastructure.adapter.out.persistence.JpaRoleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catálogo de roles del backoffice y sus capacidades.
 *
 * <p>Es la fuente de la matriz que el BFF aplica en cada petición. Estaba escrita en el código de
 * ese canal, y moverla de rol exigía compilar y desplegar; aquí es un dato, y cambiarlo tiene
 * efecto en cuanto el canal refresca su caché.
 */
@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles", description = "Roles del backoffice y sus capacidades")
class RoleController {

    private static final Logger log = LoggerFactory.getLogger(RoleController.class);

    /**
     * Sin ADMIN nadie puede volver a administrar nada, y la pantalla que lo quitó deja de estar
     * disponible para deshacerlo. Es el único candado que no se puede abrir desde la consola.
     */
    private static final String ROOT_ROLE = "ADMIN";

    private final JpaRoleRepository repository;

    RoleController(JpaRoleRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Catálogo de roles con sus capacidades")
    List<RoleResponse> list(@RequestParam(defaultValue = "BACKOFFICE") String channel) {
        return repository.findByChannelOrderByCodeAsc(channel).stream().map(RoleResponse::from).toList();
    }

    @GetMapping("/{code}")
    @Operation(summary = "Detalle de un rol")
    @ApiResponse(responseCode = "404", description = "No existe ese rol")
    RoleResponse get(@PathVariable String code) {
        return repository.findById(code.toUpperCase())
                .map(RoleResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rol no encontrado: " + code));
    }

    @PutMapping("/{code}/capabilities")
    @Transactional
    @Operation(summary = "Reemplazar las capacidades de un rol",
            description = "Recibe el conjunto completo al que se quiere llegar, no altas sueltas: "
                        + "la pantalla edita una matriz y manda su estado final.")
    @ApiResponse(responseCode = "409", description = "Se intentó dejar a ADMIN sin la capacidad de administrar")
    RoleResponse replaceCapabilities(@PathVariable String code,
                                     @RequestBody @NotNull CapabilitiesRequest request,
                                     @AuthenticationPrincipal String actor) {
        Role role = repository.findById(code.toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rol no encontrado: " + code));

        Set<String> nuevas = request.capabilities() == null ? Set.of() : new TreeSet<>(request.capabilities());
        if (ROOT_ROLE.equals(role.getCode()) && nuevas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ADMIN no puede quedarse sin capacidades: nadie podría devolvérselas");
        }

        log.info("Capacidades de {} pasan de {} a {} por {}",
                role.getCode(), role.getCapabilities().size(), nuevas.size(), actor);
        role.replaceCapabilities(nuevas);
        return RoleResponse.from(repository.save(role));
    }

    record CapabilitiesRequest(Set<String> capabilities) {}

    record RoleResponse(String code, String name, String description, String channel,
                        boolean systemManaged, Set<String> capabilities, Instant updatedAt) {

        static RoleResponse from(Role r) {
            return new RoleResponse(r.getCode(), r.getName(), r.getDescription(), r.getChannel(),
                    r.isSystemManaged(), new TreeSet<>(r.getCapabilities()), r.getUpdatedAt());
        }
    }
}
