package com.fintech.party.infrastructure.adapter.in.api;

import com.fintech.party.application.service.PartyService;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyStatus;
import com.fintech.party.domain.PartyType;
import com.fintech.party.infrastructure.adapter.in.api.dto.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/parties")
class PartyController {

    /** Tope de página: un cliente no puede pedir el directorio entero en una llamada. */
    private static final int MAX_PAGE_SIZE = 100;

    /** Tope de ids por lote: el /batch hidrata una página, no toda la base. */
    private static final int MAX_BATCH_IDS = 200;

    /** Campos por los que se puede ordenar (whitelist: el `sort` viene del cliente). */
    private static final Set<String> SORTABLE =
            Set.of("createdAt", "firstName", "lastName1", "status", "partyType", "totalScore");

    private final PartyService partyService;

    PartyController(PartyService partyService) {
        this.partyService = partyService;
    }

    /**
     * Búsqueda paginada de clientes para el backoffice.
     *
     * <p>El backoffice pregunta por población, no por sujeto: {@code q} es texto
     * libre contra nombre, CURP y RFC. Filtros, orden y paginación los resuelve
     * la base. El filtro por rol comercial ({@code role=}) llega con Task 2,
     * cuando exista {@code party.party_roles}.
     */
    @GetMapping
    Page<PartyResponse> search(@RequestParam(required = false) String q,
                               @RequestParam(required = false) PartyType type,
                               @RequestParam(required = false) PartyStatus status,
                               @RequestParam(required = false) UUID executiveId,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "25") int size,
                               @RequestParam(defaultValue = "createdAt,desc") String sort) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                parseSort(sort));
        return partyService.search(q, type, status, executiveId, pageable).map(PartyResponse::from);
    }

    @PutMapping("/{partyId}/executive")
    ResponseEntity<PartyResponse> assignExecutive(@PathVariable UUID partyId,
                                                  @Valid @RequestBody AssignExecutiveRequest request) {
        return ResponseEntity.ok(PartyResponse.from(
                partyService.assignExecutive(partyId, request.executiveId(), request.executiveName())));
    }

    /**
     * Hidratación por lote: varios parties por id en una sola llamada.
     *
     * <p>Es el endpoint que mata el N+1 en los listados de otros servicios
     * (cartera, solicitudes): en vez de una consulta por fila para pintar el
     * nombre del obligado, se piden los ids distintos de la página de una vez.
     */
    @GetMapping("/batch")
    List<PartyResponse> batch(@RequestParam List<UUID> ids,
                             @RequestParam(defaultValue = "partyId") String by) {
        if (ids.size() > MAX_BATCH_IDS) {
            throw new IllegalArgumentException(
                    "Máximo " + MAX_BATCH_IDS + " ids por lote (recibidos " + ids.size() + ")");
        }
        // Cartera y otros read models guardan el prospectId como obligado, así que
        // el llamador elige la clave: `partyId` (por defecto) o `prospectId`.
        List<Party> found = switch (by) {
            case "partyId"    -> partyService.findByIds(ids);
            case "prospectId" -> partyService.findByProspectIds(ids);
            default -> throw new IllegalArgumentException(
                    "by inválido: '" + by + "' (esperado: partyId | prospectId)");
        };
        return found.stream().map(PartyResponse::from).toList();
    }

    @GetMapping("/{partyId}")
    ResponseEntity<PartyResponse> getById(@PathVariable UUID partyId) {
        return partyService.findById(partyId)
                .map(PartyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-prospect/{prospectId}")
    ResponseEntity<PartyResponse> getByProspectId(@PathVariable UUID prospectId) {
        return partyService.findByProspectId(prospectId)
                .map(PartyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{partyId}/kyc-status")
    ResponseEntity<KycVerificationResponse> updateKycStatus(
            @PathVariable UUID partyId,
            @Valid @RequestBody UpdateKycStatusRequest request) {
        KycVerificationResponse response = KycVerificationResponse.from(
                partyService.addKycVerification(
                        partyId,
                        request.documentType(),
                        request.verificationStatus(),
                        request.verifiedBy(),
                        request.documentRef(),
                        request.expiresAt(),
                        request.rejectionReason()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{partyId}/kyc-verifications")
    ResponseEntity<List<KycVerificationResponse>> listKycVerifications(@PathVariable UUID partyId) {
        List<KycVerificationResponse> verifications = partyService.findKycVerifications(partyId)
                .stream()
                .map(KycVerificationResponse::from)
                .toList();
        return ResponseEntity.ok(verifications);
    }

    @PostMapping("/{partyId}/blacklist")
    ResponseEntity<PartyResponse> blacklist(
            @PathVariable UUID partyId,
            @Valid @RequestBody BlacklistPartyRequest request) {
        return ResponseEntity.ok(
                PartyResponse.from(
                        partyService.blacklistParty(partyId, request.reason(), request.sourceList())));
    }

    @PutMapping("/{partyId}/fiscal-profile")
    ResponseEntity<PartyResponse> updateFiscalProfile(
            @PathVariable UUID partyId,
            @Valid @RequestBody UpdateFiscalProfileRequest request) {
        return ResponseEntity.ok(
                PartyResponse.from(partyService.updateFiscalProfile(
                        partyId, request.taxName(), request.taxRegime(),
                        request.taxZipCode(), request.cfdiUse())));
    }

    /**
     * Traduce {@code sort=campo[,asc|desc]} a un {@link Sort} seguro.
     *
     * <p>El campo se valida contra una whitelist —el cliente no puede ordenar por
     * una propiedad arbitraria— y por defecto ordena por más reciente.
     */
    private static Sort parseSort(String sort) {
        Sort defaultSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) {
            return defaultSort;
        }
        Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }
}
