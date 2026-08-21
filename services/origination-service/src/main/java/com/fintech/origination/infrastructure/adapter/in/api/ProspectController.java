package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.application.RegisterProspectCommand;
import com.fintech.origination.application.RegisterProspectResult;
import com.fintech.origination.application.port.in.FindProspectUseCase;
import com.fintech.origination.application.port.in.RegisterProspectUseCase;
import com.fintech.origination.domain.ProspectDocument;
import com.fintech.origination.infrastructure.adapter.in.api.dto.ProspectDocumentRequest;
import com.fintech.origination.infrastructure.adapter.out.persistence.SpringDataProspectDocumentFileRepository;
import com.fintech.origination.domain.ProspectDocumentFile;
import com.fintech.origination.infrastructure.adapter.in.api.dto.ProspectDetailResponse;
import com.fintech.origination.infrastructure.adapter.in.api.dto.ProspectResponse;
import com.fintech.origination.infrastructure.adapter.in.api.dto.RegisterProspectRequest;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/origination/prospects")
@Tag(name = "Prospects", description = "Prospect intake and onboarding registration")
class ProspectController {

    private static final Logger log = LoggerFactory.getLogger(ProspectController.class);

    private final RegisterProspectUseCase registerProspectUseCase;
    private final FindProspectUseCase findProspectUseCase;
    private final SpringDataProspectDocumentFileRepository documentFiles;

    ProspectController(RegisterProspectUseCase registerProspectUseCase,
                       FindProspectUseCase findProspectUseCase,
                       SpringDataProspectDocumentFileRepository documentFiles) {
        this.registerProspectUseCase = registerProspectUseCase;
        this.findProspectUseCase = findProspectUseCase;
        this.documentFiles = documentFiles;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Register a new prospect",
            description = "Captures prospect personal data and emits ProspectCreated event. " +
                          "Privacy notice acceptance is mandatory (LFPDPPP Art. 9).",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Prospect registered successfully"),
                    @ApiResponse(responseCode = "400", description = "Validation error or privacy notice not accepted"),
                    @ApiResponse(responseCode = "409", description = "Prospect already exists (CURP or phone)")
            })
    ProspectResponse register(
            @Valid @RequestBody RegisterProspectRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        log.info("Prospect registration request curp={} phone={} channel={}",
                request.curp(), request.phone(), request.channelType());

        List<ProspectDocument> documents = request.documents() == null
                ? Collections.emptyList()
                : request.documents().stream()
                        .map(d -> new ProspectDocument(d.documentType(), d.documentRef(), d.incomeProofType()))
                        .toList();

        RegisterProspectCommand command = new RegisterProspectCommand(
                request.prospectType(),
                request.firstName(),
                request.lastName1(),
                request.lastName2(),
                request.curp(),
                request.rfc(),
                request.dateOfBirth(),
                request.gender(),
                request.stateOfBirth(),
                request.phone(),
                request.email(),
                request.street(),
                request.exteriorNumber(),
                request.interiorNumber(),
                request.neighborhood(),
                request.municipality(),
                request.city(),
                request.state(),
                request.postalCode(),
                request.country(),
                request.channelType(),
                request.privacyNoticeAccepted(),
                request.circuloConsentAccepted(),
                documents,
                request.username(),
                request.password(),   // plain text — identity-service hashes on provisioning
                correlationId);

        RegisterProspectResult result = registerProspectUseCase.register(command);

        return switch (result) {
            case RegisterProspectResult.ProspectRegistered r -> {
                storeFiles(r.prospectId(), request.documents());
                log.info("Prospect registered prospectId={} curp={} phone={}",
                        r.prospectId(), r.curp(), r.phone());
                yield ProspectResponse.from(r.prospectId(), r.curp(), r.phone(),
                                            r.createdAt(), r.expiresAt());
            }
        };
    }

    /**
     * Guarda los archivos que vinieron con el alta.
     *
     * <p>Después de crear el prospecto y no dentro de su transacción: un archivo que no se pueda
     * decodificar no puede tirar un alta que ya es válida. Lo que falte queda como documento
     * pendiente, que es un estado que el dominio ya sabe manejar.
     */
    private void storeFiles(java.util.UUID prospectId, List<ProspectDocumentRequest> documents) {
        if (prospectId == null || documents == null) return;
        for (ProspectDocumentRequest d : documents) {
            if (!d.hasContent()) continue;
            try {
                byte[] content = java.util.Base64.getDecoder().decode(d.contentBase64());
                documentFiles.save(ProspectDocumentFile.of(
                        prospectId, d.documentType(),
                        d.fileName() == null ? d.documentType() + ".bin" : d.fileName(),
                        d.contentType() == null ? "application/octet-stream" : d.contentType(),
                        content));
                log.info("Documento {} guardado con el alta de {} ({} bytes)",
                        d.documentType(), prospectId, content.length);
            } catch (Exception ex) {
                log.warn("No se pudo guardar {} del alta de {}: {}",
                        d.documentType(), prospectId, ex.getMessage());
            }
        }
    }

    @GetMapping("/{prospectId}")
    @Operation(
            summary = "Prospect capture detail (backoffice)",
            description = "Returns everything the subject captured — personal data, address, "
                        + "consents with their timestamps and uploaded documents — for the "
                        + "analyst's review desk. Read side of intake; no state change.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Prospect found"),
                    @ApiResponse(responseCode = "404", description = "Prospect not found")
            })
    ProspectDetailResponse getById(@PathVariable UUID prospectId) {
        log.info("GET prospect detail prospectId={}", prospectId);
        return ProspectDetailResponse.from(findProspectUseCase.getById(prospectId));
    }

    @GetMapping("/lookup")
    @Operation(
            summary = "Resolver un prospecto por su dato de contacto",
            description = "Busca por correo, teléfono o CURP **exactos**. Existe para la bitácora "
                        + "del backoffice: la auditoría guarda partyId y agregado, no correos, y "
                        + "quien investiga llega con el dato que le dio el cliente. Sin criterio "
                        + "devuelve lista vacía, no el padrón completo.",
            responses = @ApiResponse(responseCode = "200", description = "Coincidencias (posiblemente ninguna)"))
    List<ProspectDetailResponse> lookup(@RequestParam(required = false) String email,
                                        @RequestParam(required = false) String phone,
                                        @RequestParam(required = false) String curp) {
        log.info("GET prospect lookup email={} phone={} curp={}",
                email != null, phone != null, curp != null);
        return findProspectUseCase.findByContact(email, phone, curp)
                .stream().map(ProspectDetailResponse::from).toList();
    }
}
