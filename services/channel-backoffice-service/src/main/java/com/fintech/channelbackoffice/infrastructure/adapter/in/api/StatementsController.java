package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient.PartyResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.StatementClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.StatementClient.StatementDocument;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.StatementClient.StatementResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The statements of a client or a distributor, from their file: the list and the PDF.
 *
 * <p>A distributor is not another kind of party but a party with one more role, so the same route
 * serves both screens: the statements are those of the party's accounts (its distributor line and,
 * when it has one, its own-use credit).
 *
 * <h2>Who can get a statement</h2>
 * <ul>
 *   <li><b>The capability</b> is the file's ({@code clients.view}, the rule for {@code /clients/**}).</li>
 *   <li><b>Ownership</b> is checked here: a statement is delivered only if it belongs to an account of
 *       the party in the route. Without it, anyone who can see one client would download anyone's by
 *       changing the statement id. When it is not theirs the answer is 404, not 403: it does not confirm
 *       the statement exists.</li>
 *   <li><b>A distributor's employee</b> (external collaborator) does not get it: the statement carries the
 *       borrower's full name, RFC and address, exactly what their view of the file hides.</li>
 * </ul>
 * Every download lands in the access log with the client as subject (the route carries its id).
 */
@RestController
@Tag(name = "Estados de cuenta", description = "Los estados de cuenta del expediente de un cliente o distribuidor")
class StatementsController {

    private static final Logger log = LoggerFactory.getLogger(StatementsController.class);

    private final PartyClient partyClient;
    private final CreditPortfolioClient creditPortfolioClient;
    private final StatementClient statementClient;
    private final StaffIdentityResolver staffIdentities;

    StatementsController(PartyClient partyClient, CreditPortfolioClient creditPortfolioClient,
                         StatementClient statementClient, StaffIdentityResolver staffIdentities) {
        this.partyClient = partyClient;
        this.creditPortfolioClient = creditPortfolioClient;
        this.statementClient = statementClient;
        this.staffIdentities = staffIdentities;
    }

    @GetMapping("/clients/{partyId}/statements")
    @Operation(summary = "Estados de cuenta de un cliente o distribuidor",
            description = "Los de todas sus cuentas, del corte más reciente al más viejo. Fan-out de UNA "
                        + "entidad: una consulta por cuenta de la party.")
    ResponseEntity<Map<String, Object>> list(@PathVariable UUID partyId) {
        requireInternalStaff();
        List<CreditAccountResponse> accounts = accountsOf(partyId);

        List<Map<String, Object>> statements = new ArrayList<>();
        for (CreditAccountResponse account : accounts) {
            for (StatementResponse s : safe(statementClient.byAccount(account.creditAccountId()))) {
                statements.add(view(s, account));
            }
        }
        statements.sort(Comparator.comparing((Map<String, Object> s) -> String.valueOf(s.get("cutoffDate"))).reversed()
                .thenComparing(s -> String.valueOf(s.get("contractNumber"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accounts", accounts.stream().map(StatementsController::account).toList());
        body.put("statements", statements);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/clients/{partyId}/statements/{statementId}/pdf")
    @Operation(summary = "El PDF de un estado de cuenta",
            description = "Tal como lo generó statement-service. download=true lo entrega como descarga; "
                        + "si no, para verse en el navegador. Dato personal: no se cachea.")
    ResponseEntity<byte[]> pdf(@PathVariable UUID partyId, @PathVariable UUID statementId,
                               @RequestParam(defaultValue = "false") boolean download) {
        requireInternalStaff();
        StatementResponse statement = statementClient.get(statementId);
        boolean belongsToParty = statement != null && accountsOf(partyId).stream()
                .anyMatch(a -> a.creditAccountId().equals(statement.creditAccountId()));
        if (!belongsToParty) {
            log.warn("Estado de cuenta {} pedido desde el expediente de {}, que no es su titular", statementId, partyId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese estado de cuenta no es de este cliente");
        }
        StatementDocument document = statementClient.pdf(statementId);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El estado de cuenta no tiene documento generado");
        }
        String filename = document.filename() != null ? document.filename()
                : "estado-de-cuenta-" + Objects.requireNonNullElse(statement.contractNumber(), statement.creditAccountId().toString())
                  + "-" + statement.cutoffDate() + ".pdf";
        // The contract number is ASCII: a plain filename, without the RFC 2047 encoding a charset adds.
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(filename).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, document.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(document.bytes());
    }

    // ── Composition ────────────────────────────────────────────────────────────

    /**
     * The holder's accounts. The portfolio keys a borrower by its prospectId when it has one (onboarded
     * through the app) and by its partyId when it does not: both are asked and merged, without repeats.
     */
    private List<CreditAccountResponse> accountsOf(UUID holderId) {
        PartyResponse party = holder(holderId);
        Map<UUID, CreditAccountResponse> byId = new LinkedHashMap<>();
        if (party.prospectId() != null) {
            safe(creditPortfolioClient.listByParty(party.prospectId())).forEach(a -> byId.putIfAbsent(a.creditAccountId(), a));
        }
        if (party.partyId() != null && !party.partyId().equals(party.prospectId())) {
            safe(creditPortfolioClient.listByParty(party.partyId())).forEach(a -> byId.putIfAbsent(a.creditAccountId(), a));
        }
        return List.copyOf(byId.values());
    }

    /**
     * The person or business behind the id in the route. The clients screen sends the partyId; the
     * distributors screen sends the sales-org node's partyRef, which for a distributor onboarded through the
     * app is its prospectId (demo 3 Nov: DIST0005's line hangs from prospect e3df03a3, party 35d278fd). So
     * the id is looked up as a party first and as a prospect after.
     */
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

    /**
     * A distributor's employee does not get statements. Nor does anyone whose employer cannot be told
     * (identity does not answer): it is personal data, and the doubt is not settled in favour of handing
     * it over.
     */
    private void requireInternalStaff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = auth == null ? null : String.valueOf(auth.getPrincipal());
        var who = staffIdentities.resolve(actor);
        if (who.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No se pudo confirmar quién pide el estado de cuenta; intenta de nuevo en un momento");
        }
        if (who.get().externalCollaborator()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El estado de cuenta lleva RFC y domicilio del acreditado: el colaborador de un distribuidor no lo descarga");
        }
    }

    private static Map<String, Object> account(CreditAccountResponse a) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("creditAccountId", a.creditAccountId());
        view.put("contractNumber", a.contractNumber());
        view.put("productType", a.productType());
        view.put("status", a.status());
        return view;
    }

    private static Map<String, Object> view(StatementResponse s, CreditAccountResponse account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("statementId", s.statementId());
        view.put("creditAccountId", s.creditAccountId());
        view.put("contractNumber", s.contractNumber() != null ? s.contractNumber() : account.contractNumber());
        view.put("productType", s.productType() != null ? s.productType() : account.productType());
        view.put("cycleNumber", s.cycleNumber());
        view.put("periodStart", s.periodStart());
        view.put("cutoffDate", s.cutoffDate());
        view.put("paymentDueDate", s.paymentDueDate());
        view.put("balanceAtCutoff", s.balanceAtCutoff());
        view.put("amountDue", s.amountDue());
        view.put("minimumPayment", s.minimumPayment());
        view.put("status", s.status());
        view.put("documentBytes", s.documentBytes());
        view.put("generatedAt", s.generatedAt());
        // Only a generated statement has a PDF; a queued or failed one is listed but not offered.
        view.put("downloadable", "GENERATED".equals(s.status()));
        return view;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
