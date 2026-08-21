package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.domain.DocumentReviewStatus;
import com.fintech.origination.domain.ProspectDocumentFile;
import com.fintech.origination.domain.ProspectDocumentType;
import com.fintech.origination.domain.VerificationSource;
import com.fintech.origination.infrastructure.adapter.out.persistence.DocumentFileSummary;
import com.fintech.origination.infrastructure.adapter.out.persistence.SpringDataProspectDocumentFileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * El expediente: los archivos que el solicitante subió.
 *
 * <p>Se sirve aparte del detalle del prospecto porque son cosas de distinto peso y distinta
 * frecuencia. El detalle se pide en cada apertura de ficha; el archivo, sólo cuando alguien lo
 * abre. Juntarlos haría que ver una lista costara descargar el expediente entero.
 *
 * <p>El contenido viaja en base64 dentro de JSON, no como multipart: quien sube es la app móvil a
 * través de su canal, que ya habla JSON en todo lo demás, y el tamaño de una foto de INE no
 * justifica un segundo formato de petición en el borde.
 */
@RestController
@RequestMapping("/api/v1/origination/prospects/{prospectId}/documents")
@Tag(name = "Expediente", description = "Archivos del expediente de un prospecto")
class ProspectDocumentController {

    private static final Logger log = LoggerFactory.getLogger(ProspectDocumentController.class);

    /** Diez megabytes: una foto de credencial cabe de sobra y un PDF escaneado también. */
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private final SpringDataProspectDocumentFileRepository files;

    ProspectDocumentController(SpringDataProspectDocumentFileRepository files) {
        this.files = files;
    }

    @GetMapping
    @Operation(summary = "Qué hay en el expediente, sin descargarlo")
    List<DocumentFileSummary> list(@PathVariable UUID prospectId) {
        return files.summariesFor(prospectId);
    }

    @GetMapping("/{documentType}/file")
    @Operation(summary = "Descargar un documento",
            description = "Devuelve los bytes con su tipo de contenido, para verlo en el navegador.")
    @ApiResponse(responseCode = "404", description = "Ese prospecto no entregó ese documento")
    ResponseEntity<byte[]> download(@PathVariable UUID prospectId,
                                    @PathVariable ProspectDocumentType documentType) {
        ProspectDocumentFile f = files.findByProspectIdAndDocumentType(prospectId, documentType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Sin archivo de " + documentType + " para ese prospecto"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.getContentType()))
                // `inline`: se abre en la ficha, no se descarga. Quien analiza mira el documento,
                // no colecciona archivos en su carpeta de descargas.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + f.getFileName() + "\"")
                .body(f.getContent());
    }

    @PutMapping("/{documentType}/file")
    @Transactional
    @Operation(summary = "Subir o reemplazar un documento",
            description = "Un documento vigente por tipo: volver a subir la INE reemplaza la anterior.")
    DocumentFileSummary upload(@PathVariable UUID prospectId,
                               @PathVariable ProspectDocumentType documentType,
                               @RequestBody UploadRequest request) {
        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.contentBase64());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El contenido no es base64 válido");
        }
        if (content.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo viene vacío");
        }
        if (content.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "El archivo pesa " + (content.length / 1024) + " KB; el máximo son " + (MAX_BYTES / 1024) + " KB");
        }

        ProspectDocumentFile f = files.findByProspectIdAndDocumentType(prospectId, documentType)
                .map(existente -> {
                    existente.replaceWith(request.fileName(), request.contentType(), content);
                    return existente;
                })
                .orElseGet(() -> ProspectDocumentFile.of(
                        prospectId, documentType, request.fileName(), request.contentType(), content));

        files.save(f);
        log.info("Documento {} de {} guardado ({} bytes)", documentType, prospectId, content.length);
        return summaryOf(f);
    }

    // ── Dictamen ─────────────────────────────────────────────────────────────────────────────

    @PutMapping("/{documentType}/review")
    @Operation(summary = "Dictaminar un documento",
            description = "Registra el juicio de quien revisa: aprobado o rechazado, con autor y "
                        + "—si se rechaza— motivo. Volver a subir el archivo **borra el dictamen**: "
                        + "conservarlo aprobaría a ciegas una foto que nadie vio.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dictamen registrado"),
            @ApiResponse(responseCode = "404", description = "No hay archivo de ese tipo en el expediente"),
            @ApiResponse(responseCode = "422", description = "Dictamen sin autor, sin origen, o rechazo sin motivo")
    })
    DocumentFileSummary review(@PathVariable UUID prospectId,
                               @PathVariable ProspectDocumentType documentType,
                               @Valid @RequestBody ReviewRequest request) {

        ProspectDocumentFile file = files.findByProspectIdAndDocumentType(prospectId, documentType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay documento " + documentType + " en el expediente de " + prospectId));

        // El origen lo fija el llamador y no esta capa: hoy siempre es MANUAL porque no hay
        // proveedor, pero cuando lo haya, quien dictamina encima de una señal automática tiene que
        // poder decir que fue una escalación y no una revisión de cero.
        VerificationSource source = request.verificationSource() == null
                ? VerificationSource.MANUAL
                : request.verificationSource();

        file.review(request.decision(), source, request.reviewedBy(), request.rejectionReason());
        files.save(file);

        log.info("Documento {} de {} dictaminado {} por {} ({})",
                documentType, prospectId, request.decision(), request.reviewedBy(), source);
        return summaryOf(file);
    }

    private static DocumentFileSummary summaryOf(ProspectDocumentFile f) {
        return new DocumentFileSummary(f.getFileId(), f.getDocumentType(), f.getFileName(),
                f.getContentType(), f.getSizeBytes(), f.getUploadedAt(),
                f.getReviewStatus(), f.getVerificationSource(), f.getReviewedBy(),
                f.getReviewedAt(), f.getRejectionReason());
    }

    record UploadRequest(@NotBlank String fileName, @NotBlank String contentType,
                         @NotBlank String contentBase64) {}

    record ReviewRequest(@NotNull DocumentReviewStatus decision,
                         @NotBlank String reviewedBy,
                         String rejectionReason,
                         VerificationSource verificationSource) {}
}
