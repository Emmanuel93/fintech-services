package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient.PartyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Clientes para el backoffice: la búsqueda por población y el expediente de uno.
 *
 * <p>Dos formas distintas del invariante rector:
 * <ul>
 *   <li><b>La búsqueda</b> la resuelve party-service, paginada; aquí no se itera
 *       por fila (party ya trae nombre, CURP, RFC, tipo y estado).</li>
 *   <li><b>El expediente</b> es el único fan-out proporcional aceptable: es de
 *       <b>una</b> entidad, así que juntar sus créditos y sus solicitudes es correcto.</li>
 * </ul>
 */
@RestController
@Tag(name = "Clientes", description = "Búsqueda de clientes y expediente")
class ClientsController {

    private static final Logger log = LoggerFactory.getLogger(ClientsController.class);

    private final PartyClient partyClient;
    private final CreditPortfolioClient creditPortfolioClient;
    private final OriginationClient originationClient;
    private final IdentityClient identityClient;

    ClientsController(PartyClient partyClient,
                      CreditPortfolioClient creditPortfolioClient,
                      OriginationClient originationClient,
                      IdentityClient identityClient) {
        this.partyClient = partyClient;
        this.creditPortfolioClient = creditPortfolioClient;
        this.originationClient = originationClient;
        this.identityClient = identityClient;
    }

    @GetMapping("/clients")
    @Operation(summary = "Búsqueda paginada de clientes",
            description = "Texto libre (q) contra nombre, CURP y RFC; filtros por tipo, estado y "
                        + "ejecutivo de cuenta. La página la arma party-service.")
    ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String executiveId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        var result = partyClient.search(q, type, status, executiveId, page, size, sort);
        List<PartyResponse> rows = result == null || result.content() == null
                ? List.of() : result.content();

        List<Map<String, Object>> content = rows.stream().map(BackofficeViews::client).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("page", result == null ? page : result.number());
        body.put("size", result == null ? size : result.size());
        body.put("totalElements", result == null ? 0 : result.totalElements());
        body.put("totalPages", result == null ? 0 : result.totalPages());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/clients/{partyId}")
    @Operation(summary = "Expediente de un cliente",
            description = "Datos del cliente, sus créditos y sus solicitudes. Fan-out de UNA "
                        + "entidad — el único proporcional aceptable.")
    ResponseEntity<Map<String, Object>> detail(@PathVariable UUID partyId) {
        log.info("GET /clients/{}", partyId);
        PartyResponse party = partyClient.getByPartyId(partyId);
        if (party == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> body = new LinkedHashMap<>(BackofficeViews.client(party));
        String obligorName = BackofficeViews.fullName(party);

        // Cartera guarda el prospectId como obligado; si no hay prospecto, se usa partyId.
        UUID obligorId = party.prospectId() != null ? party.prospectId() : partyId;
        List<Map<String, Object>> accounts = safe(creditPortfolioClient.listByParty(obligorId)).stream()
                .map(a -> BackofficeViews.account(a, obligorName))
                .toList();
        body.put("accounts", accounts);

        // Las solicitudes viven en origination y se llavean por prospectId.
        List<Map<String, Object>> applications = party.prospectId() == null ? List.of()
                : safe(originationClient.findByProspect(party.prospectId())).stream()
                        .map(BackofficeViews::application)
                        .toList();
        body.put("applications", applications);

        return ResponseEntity.ok(body);
    }

    @PostMapping("/clients/{partyId}/assign-executive")
    @Operation(summary = "Asignar el ejecutivo de cuenta de un cliente",
            description = "El nombre del ejecutivo se resuelve del directorio y se guarda "
                        + "desnormalizado en party para pintar el listado sin N+1.")
    ResponseEntity<Void> assignExecutive(@PathVariable UUID partyId,
                                         @RequestBody AssignExecutiveRequest request,
                                         HttpServletRequest http) {
        log.info("POST /clients/{}/assign-executive exec={}", partyId, request.executiveId());
        UUID executiveId = UUID.fromString(request.executiveId());
        // Resuelve el nombre del ejecutivo (una vez) para desnormalizarlo en party.
        String name = identityClient.listExecutives(StaffAuthController.bearerToken(http)).stream()
                .filter(e -> executiveId.equals(e.id()))
                .map(IdentityClient.ExecutiveResponse::name)
                .findFirst()
                .orElse(null);
        partyClient.assignExecutive(partyId, executiveId, name);
        return ResponseEntity.ok().build();
    }

    /** Cuerpo del POST de asignación (shared-types: { executiveId }). */
    record AssignExecutiveRequest(String executiveId) {}

    private static <T> List<T> safe(List<T> xs) { return xs == null ? List.of() : xs; }
}
