package com.fintech.disbursement.infrastructure.adapter.in.api;

import com.fintech.disbursement.application.port.in.ManageRoutingUseCase;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;
import com.fintech.disbursement.infrastructure.adapter.in.api.dto.CompanyMappingRequest;
import com.fintech.disbursement.infrastructure.adapter.in.api.dto.CompanyMappingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Configuración que mueve dinero: quién paga qué y por dónde. Sólo {@code ADMIN}.
 *
 * <p>Que esto sea una API y no un {@code switch} en el código es lo que permite cambiar de proveedor
 * sin desplegar (DC-7).
 */
@RestController
@RequestMapping("/api/v1/disbursements")
@Tag(name = "Routing", description = "Reglas de encaminamiento y mapeo de empresas")
public class DisbursementRoutingController {

    private final ManageRoutingUseCase manageRouting;

    public DisbursementRoutingController(ManageRoutingUseCase manageRouting) {
        this.manageRouting = manageRouting;
    }




    /**
     * Alta o corrección. Devuelve {@code 200 OK} y no {@code 201}: la operación es un upsert, y
     * responder "creado" sobre un mapeo que se acaba de reasignar sería mentir con un código de
     * estado.
     */
    @PostMapping("/company-mappings")
    @Operation(summary = "Mapea (o reasigna) la clave de empresa de un emisor a una empresa de este servicio")
    public CompanyMappingResponse mapCompany(@Valid @RequestBody CompanyMappingRequest request) {
        return CompanyMappingResponse.from(manageRouting.mapCompany(
                request.sourceSystem(), request.sourceKey(), request.companyId()));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(type.getSimpleName() + " desconocido: '" + value
                    + "'. Válidos: " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    @GetMapping("/company-mappings")
    @Operation(summary = "Lista los mapeos de empresa")
    public List<CompanyMappingResponse> listMappings() {
        return manageRouting.listMappings().stream().map(CompanyMappingResponse::from).toList();
    }
}
