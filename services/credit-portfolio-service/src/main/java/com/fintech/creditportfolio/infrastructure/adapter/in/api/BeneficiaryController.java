package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.service.BeneficiaryProfileService;
import com.fintech.creditportfolio.domain.beneficiary.ClientPaymentReference;
import com.fintech.creditportfolio.application.port.out.ClientReferenceRepository;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryFlag;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryProfile;
import com.fintech.creditportfolio.domain.beneficiary.FlagSource;
import com.fintech.creditportfolio.domain.beneficiary.ReportReason;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A distributor's client as the portfolio sees him: his type (NUEVO / REVOLVENTE), his red-list marks and what he
 * owes; and the marks a distributor or the backoffice put on him.
 */
@RestController
@RequestMapping("/api/v1/portfolio/beneficiaries")
@Tag(name = "Beneficiaries", description = "Tipo de cliente, lista roja y saldo de los clientes de las distribuidoras")
class BeneficiaryController {

    private final BeneficiaryProfileService profiles;
    private final ClientReferenceRepository references;

    BeneficiaryController(BeneficiaryProfileService profiles, ClientReferenceRepository references) {
        this.profiles = profiles;
        this.references = references;
    }

    /** The clients of a line with their payment references: what the receipts print. */
    @GetMapping("/by-line/{creditAccountId}/references")
    @Operation(summary = "Referencias de pago de los clientes de una línea de distribuidora")
    List<ClientReference> referencesOfLine(@PathVariable UUID creditAccountId) {
        return references.findByLine(creditAccountId).stream().map(ClientReference::of).toList();
    }

    /** Whose a client reference is: the line and the client a deposit with it belongs to. */
    @GetMapping("/references/{paymentReference}")
    @Operation(summary = "De quién es una referencia de pago de cliente")
    ResponseEntity<ClientReference> reference(@PathVariable long paymentReference) {
        return references.findByReference(paymentReference).map(ClientReference::of)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record ClientReference(Long paymentReference, UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId) {
        static ClientReference of(ClientPaymentReference r) {
            return new ClientReference(r.getPaymentReference(), r.getCreditAccountId(), r.getObligorPartyId(), r.getBeneficiaryPartyId());
        }
    }

    @GetMapping("/{partyId}/profile")
    @Operation(summary = "Perfil del cliente: tipo, marcas de lista roja y cómo va con cada distribuidora")
    BeneficiaryProfile profile(@PathVariable UUID partyId) {
        return profiles.profile(partyId);
    }

    /** At most a directory's page at a time: the profile reads every placement of each client. */
    private static final int MAX_BATCH = 200;

    @GetMapping("/profiles")
    @Operation(summary = "Perfiles de varios clientes a la vez (el directorio de una distribuidora)")
    List<BeneficiaryProfile> profiles(@RequestParam List<UUID> partyIds) {
        if (partyIds.size() > MAX_BATCH) {
            throw new IllegalArgumentException("A lo más " + MAX_BATCH + " clientes por consulta, pidió " + partyIds.size());
        }
        return profiles.profiles(partyIds);
    }

    @GetMapping("/{partyId}/flags")
    @Operation(summary = "Historial de marcas del cliente (vigentes y levantadas), la más reciente primero")
    List<BeneficiaryFlag> flags(@PathVariable UUID partyId) {
        return profiles.history(partyId);
    }

    @PostMapping("/{partyId}/flags")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reportar al cliente (lista roja) con un motivo del catálogo y comentarios",
            description = "Mientras la marca esté vigente ninguna distribuidora le coloca. La levanta quien la puso o el backoffice.")
    BeneficiaryFlag report(@PathVariable UUID partyId, @RequestBody ReportRequest request,
                           @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return profiles.report(partyId, request.source(), request.distributorPartyId(), request.creditAccountId(),
                request.reason(), request.comment(), request.raisedBy() != null ? request.raisedBy() : userId);
    }

    @PostMapping("/{partyId}/flags/{flagId}/clear")
    @Operation(summary = "Levantar una marca, con comentario")
    BeneficiaryFlag clear(@PathVariable UUID partyId, @PathVariable UUID flagId, @RequestBody ClearRequest request,
                          @RequestHeader(value = "X-User-Id", required = false) String userId) {
        BeneficiaryFlag flag = profiles.clear(flagId, request.clearedBy() != null ? request.clearedBy() : userId, request.comment());
        if (!flag.getBeneficiaryPartyId().equals(partyId)) {
            throw new IllegalArgumentException("La marca " + flagId + " no es del cliente " + partyId);
        }
        return flag;
    }

    public record ReportRequest(@NotNull ReportReason reason, String comment, FlagSource source, UUID distributorPartyId,
                                UUID creditAccountId, String raisedBy) {}

    public record ClearRequest(String comment, String clearedBy) {}
}
