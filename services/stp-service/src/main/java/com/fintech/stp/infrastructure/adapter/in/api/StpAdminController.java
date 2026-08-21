package com.fintech.stp.infrastructure.adapter.in.api;

import com.fintech.stp.application.port.in.ManageCompanyUseCase;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.infrastructure.adapter.in.api.dto.CompanyResponse;
import com.fintech.stp.infrastructure.adapter.in.api.dto.KeyMetadataResponse;
import com.fintech.stp.infrastructure.adapter.in.api.dto.RegisterCompanyRequest;
import com.fintech.stp.infrastructure.adapter.in.api.dto.RegisterKeyRequest;
import com.fintech.stp.infrastructure.adapter.in.api.dto.RegisterOrderingAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Catálogo multi-empresa. Interno, vía el BFF de backoffice. Rol ADMIN. */
@RestController
@RequestMapping("/api/v1/stp/companies")
@Tag(name = "STP Admin", description = "Empresas, cuentas ordenantes y custodia de llaves")
class StpAdminController {

    private final ManageCompanyUseCase manageCompany;

    StpAdminController(ManageCompanyUseCase manageCompany) {
        this.manageCompany = manageCompany;
    }

    @PostMapping
    @Operation(summary = "Da de alta una empresa con contrato propio ante STP")
    @ApiResponse(responseCode = "201", description = "Empresa registrada")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<CompanyResponse> register(@Valid @RequestBody RegisterCompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CompanyResponse.from(
                manageCompany.registerCompany(request.code(), request.stpEmpresa(),
                        request.institucionOperante(), request.trackingPrefix(),
                        request.clabeBankCode(), request.clabePlazaCode(), request.clabeClientPrefix())));
    }

    @GetMapping
    @Operation(summary = "Lista las empresas configuradas")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<List<CompanyResponse>> list() {
        return ResponseEntity.ok(manageCompany.listCompanies().stream()
                .map(CompanyResponse::from).toList());
    }

    @PostMapping("/{companyId}/ordering-accounts")
    @Operation(summary = "Registra una cuenta ordenante — valida el dígito verificador de la CLABE")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Cuenta registrada"),
        @ApiResponse(responseCode = "422", description = "CLABE inválida")
    })
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<Void> addOrderingAccount(@PathVariable UUID companyId,
                                            @Valid @RequestBody RegisterOrderingAccountRequest request) {
        manageCompany.addOrderingAccount(companyId, request.clabe(), request.holderName(),
                request.taxId(), request.accountType(), request.stpClientNumber(), request.defaultAccount());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{companyId}/keys")
    @Operation(summary = "Alta o rotación de llave. La respuesta NUNCA incluye material criptográfico")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Llave registrada — se devuelven metadatos"),
        @ApiResponse(responseCode = "400", description = "El material no es una llave RSA válida")
    })
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<KeyMetadataResponse> registerKey(@PathVariable UUID companyId,
                                                     @Valid @RequestBody RegisterKeyRequest request,
                                                     Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "SYSTEM";
        return ResponseEntity.status(HttpStatus.CREATED).body(KeyMetadataResponse.from(
                manageCompany.registerKey(companyId, request.alias(),
                        KeyPurpose.valueOf(request.purpose().toUpperCase()),
                        request.materialBase64(), request.validFrom(), request.validTo(), actor)));
    }

    @GetMapping("/{companyId}/keys")
    @Operation(summary = "Metadatos de las llaves: huella, vigencia y estado. Sin material")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<List<KeyMetadataResponse>> listKeys(@PathVariable UUID companyId) {
        return ResponseEntity.ok(manageCompany.listKeys(companyId).stream()
                .map(KeyMetadataResponse::from).toList());
    }

    @DeleteMapping("/{companyId}/keys/{keyId}")
    @Operation(summary = "Revoca una llave. Irreversible")
    @ApiResponse(responseCode = "204", description = "Llave revocada")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeKey(@PathVariable UUID companyId, @PathVariable UUID keyId) {
        manageCompany.revokeKey(companyId, keyId);
    }
}
