package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient.PartyResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ReceiptClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ReceiptClient.ReceiptDocument;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ReceiptClient.ReceiptResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A distributor's clients in the backoffice: their payment receipts, their type (NUEVO / REVOLVENTE) and the red list.
 *
 * <p>Same rule as the statements: the receipt belongs to the client file it is asked from, as the distributor who
 * issued the line or as the client who owes it; an external collaborator does not download it (it carries the client's
 * name and RFC). Marking or clearing a client on the red list is collections' management (POST, collections.manage).
 */
@RestController
@Tag(name = "Recibos y lista roja", description = "Recibos de pago de los clientes de las distribuidoras, tipo de cliente y lista roja")
class ReceiptsController {

    private static final Logger log = LoggerFactory.getLogger(ReceiptsController.class);

    private final PartyClient partyClient;
    private final ReceiptClient receiptClient;
    private final CreditPortfolioClient portfolio;
    private final StaffIdentityResolver staffIdentities;

    ReceiptsController(PartyClient partyClient, ReceiptClient receiptClient, CreditPortfolioClient portfolio,
                       StaffIdentityResolver staffIdentities) {
        this.partyClient = partyClient;
        this.receiptClient = receiptClient;
        this.portfolio = portfolio;
        this.staffIdentities = staffIdentities;
    }

    @GetMapping("/clients/{partyId}/receipts")
    @Operation(summary = "Recibos de pago de una distribuidora (los de sus clientes) o de un cliente (los suyos)")
    Map<String, Object> receipts(@PathVariable UUID partyId) {
        requireInternalStaff("los recibos");
        Set<UUID> ids = idsOf(partyId);
        List<ReceiptResponse> all = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID id : ids) {
            for (ReceiptResponse r : safe(receiptClient.byParty(id))) {
                if (seen.add(r.receiptId())) all.add(r);
            }
        }
        all.sort(Comparator.comparing(ReceiptResponse::cutoffDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.clientName() == null ? "" : r.clientName()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asDistributor", all.stream().anyMatch(r -> ids.contains(r.distributorPartyId())));
        body.put("receipts", all);
        return body;
    }

    @GetMapping("/clients/{partyId}/receipts/{receiptId}/pdf")
    @Operation(summary = "El PDF de un recibo de pago", description = "Dato personal: no se cachea.")
    ResponseEntity<byte[]> pdf(@PathVariable UUID partyId, @PathVariable UUID receiptId,
                               @RequestParam(defaultValue = "false") boolean download) {
        requireInternalStaff("el recibo");
        ReceiptResponse receipt = receiptClient.get(receiptId);
        Set<UUID> ids = idsOf(partyId);
        if (receipt == null || !(ids.contains(receipt.beneficiaryPartyId()) || ids.contains(receipt.distributorPartyId()))) {
            log.warn("Recibo {} pedido desde el expediente de {}, que no es ni su cliente ni su distribuidora", receiptId, partyId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese recibo no es de este expediente");
        }
        ReceiptDocument document = receiptClient.pdf(receiptId);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El recibo no tiene documento generado");
        }
        String filename = "recibo-" + (receipt.folio() != null ? receipt.folio() : receiptId.toString()) + ".pdf";
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(filename).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, document.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(document.bytes());
    }

    @GetMapping("/clients/{partyId}/credit-profile")
    @Operation(summary = "Tipo de cliente (nuevo / revolvente), lista roja y marcas de un cliente de distribuidora")
    Map<String, Object> creditProfile(@PathVariable UUID partyId) {
        UUID key = beneficiaryKey(partyId);
        Map<String, Object> body = new LinkedHashMap<>(portfolio.beneficiaryProfile(key));
        body.put("history", safe(portfolio.beneficiaryFlags(key)));
        return body;
    }

    @PostMapping("/clients/{partyId}/red-list")
    @Operation(summary = "Marcar al cliente en lista roja (desde el backoffice), con motivo del catálogo y comentarios")
    Map<String, Object> report(@PathVariable UUID partyId, @RequestBody RedListRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El motivo es obligatorio");
        }
        if (request.comment() == null || request.comment().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cuenta en los comentarios por qué se marca al cliente");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", request.reason());
        body.put("comment", request.comment());
        body.put("source", "BACKOFFICE");
        body.put("raisedBy", actor());
        return portfolio.reportBeneficiary(beneficiaryKey(partyId), body);
    }

    @PostMapping("/clients/{partyId}/red-list/{flagId}/clear")
    @Operation(summary = "Levantar una marca de lista roja, con comentario")
    Map<String, Object> clear(@PathVariable UUID partyId, @PathVariable UUID flagId, @RequestBody ClearRequest request) {
        if (request == null || request.comment() == null || request.comment().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cuenta en el comentario por qué se levanta la marca");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comment", request.comment());
        body.put("clearedBy", actor());
        return portfolio.clearBeneficiaryFlag(beneficiaryKey(partyId), flagId, body);
    }

    record RedListRequest(String reason, String comment) {}

    record ClearRequest(String comment) {}

    // ── Composition ────────────────────────────────────────────────────────────

    /** The ids a client file answers to: its party and its prospect (the portfolio keys a client by the latter). */
    private Set<UUID> idsOf(UUID holderId) {
        PartyResponse party = holder(holderId);
        Set<UUID> ids = new LinkedHashSet<>();
        if (party.prospectId() != null) ids.add(party.prospectId());
        if (party.partyId() != null) ids.add(party.partyId());
        ids.add(holderId);
        return ids;
    }

    /** The id the portfolio knows the client by: his prospect when he has one. */
    private UUID beneficiaryKey(UUID holderId) {
        PartyResponse party = holder(holderId);
        return party.prospectId() != null ? party.prospectId() : party.partyId();
    }

    private PartyResponse holder(UUID holderId) {
        PartyResponse party = null;
        try {
            party = partyClient.getByPartyId(holderId);
        } catch (ResponseStatusException e) {
            if (!e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) throw e;
        }
        if (party == null) {
            party = partyClient.getByProspectId(holderId);
        }
        if (party == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay ningún cliente ni distribuidor con id=" + holderId);
        }
        return party;
    }

    private void requireInternalStaff(String what) {
        var who = staffIdentities.resolve(actor());
        if (who.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No se pudo confirmar quién pide " + what + "; intenta de nuevo en un momento");
        }
        if (who.get().externalCollaborator()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El recibo lleva el nombre y el RFC del cliente: el colaborador de un distribuidor no lo descarga");
        }
    }

    private static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : String.valueOf(auth.getPrincipal());
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
