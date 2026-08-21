package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Selector de ejecutivos de cuenta para el backoffice (asignar cartera). Passthrough
 * a identity con el token del solicitante; devuelve solo id + nombre.
 */
@RestController
@Tag(name = "Ejecutivos", description = "Directorio de ejecutivos de cuenta")
@SecurityRequirement(name = "bearerAuth")
class ExecutivesController {

    private final IdentityClient identityClient;

    ExecutivesController(IdentityClient identityClient) {
        this.identityClient = identityClient;
    }

    @GetMapping("/executives")
    @Operation(summary = "Ejecutivos de cuenta activos (para asignación)")
    List<IdentityClient.ExecutiveResponse> list(HttpServletRequest http) {
        return identityClient.listExecutives(StaffAuthController.bearerToken(http));
    }
}
