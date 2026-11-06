package com.fintech.statement.infrastructure.adapter.out.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.statement.application.port.out.StatementSource;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.EarnedCommission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * La vista del período en cartera, los nombres en party y la comisión de la distribuidora en comisiones, por HTTP.
 *
 * <p>Servicio a servicio: se manda {@code X-User-Id} con la identidad de este servicio, como hace el
 * resto de la plataforma. Un error no se disfraza de dato vacío: se propaga, el documento queda
 * FAILED con la causa y el reintento lo vuelve a pedir.
 */
@Component
class HttpStatementSource implements StatementSource {

    private static final String SERVICE_ID = "statement-service";
    /** Lo que party acepta por lote. */
    private static final int PARTY_BATCH = 200;

    private static final ParameterizedTypeReference<List<PartyResponse>> PARTIES = new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<CommissionRecordResponse>> RECORDS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<CommissionPolicyResponse>> POLICIES = new ParameterizedTypeReference<>() {};
    private static final String DISTRIBUTOR_LINE = "DISTRIBUTOR_LINE";
    private static final String DISTRIBUTOR_SHARE = "DISTRIBUTOR_INTEREST_SHARE";

    private final RestClient portfolio;
    private final RestClient party;
    private final RestClient commissions;

    HttpStatementSource(@Qualifier("creditPortfolioRestClient") RestClient portfolio,
                        @Qualifier("partyRestClient") RestClient party,
                        @Qualifier("commissionRestClient") RestClient commissions) {
        this.portfolio   = portfolio;
        this.party       = party;
        this.commissions = commissions;
    }

    @Override
    public SourceView view(UUID creditAccountId, LocalDate from, LocalDate to, LocalDate dueUntil) {
        SourceView view = portfolio.get()
                .uri(u -> u.path("/api/v1/portfolio/accounts/{id}/statement-view")
                        .queryParam("from", from).queryParam("to", to).queryParam("dueUntil", dueUntil)
                        .build(creditAccountId))
                .header("X-User-Id", SERVICE_ID)
                .header("X-Roles", "SERVICE")
                .retrieve()
                .body(SourceView.class);
        if (view == null || view.account() == null) {
            throw new IllegalStateException("Cartera devolvió una vista vacía para la cuenta " + creditAccountId);
        }
        return view;
    }

    @Override
    public Map<UUID, PartySummary> parties(Collection<UUID> partyIds) {
        Map<UUID, PartySummary> found = new HashMap<>();
        List<UUID> ids = new ArrayList<>(partyIds);
        lookup(ids, "partyId", found);
        // The portfolio stores the prospect id as the obligor (and as a placement's beneficiary): what is not a party
        // id is looked up by prospect, and keyed by the id the statement asked for. Without this every statement of
        // the local stack said "Titular de la cuenta" and listed clients by a short id.
        List<UUID> faltan = ids.stream().filter(id -> !found.containsKey(id)).toList();
        lookup(faltan, "prospectId", found);
        return found;
    }

    /**
     * The line's commissions and the share that applies to it: the distributor's own policy when she has one, the
     * product's otherwise. A line without commissions yet still gets the rate, so its first statement can say what it
     * earns.
     */
    @Override
    public DistributorCommission commission(UUID creditAccountId) {
        List<CommissionRecordResponse> records = commissions.get()
                .uri(u -> u.path("/api/v1/commissions/accounts/{id}").build(creditAccountId))
                .header("X-User-Id", SERVICE_ID)
                .header("X-Roles", "SERVICE")
                .retrieve()
                .body(RECORDS);
        List<CommissionPolicyResponse> policies = commissions.get()
                .uri("/api/v1/commissions/policies")
                .header("X-User-Id", SERVICE_ID)
                .header("X-Roles", "SERVICE")
                .retrieve()
                .body(POLICIES);
        List<CommissionRecordResponse> mine = records == null ? List.of() : records.stream()
                .filter(r -> DISTRIBUTOR_SHARE.equals(r.commissionType())).toList();
        UUID distributor = mine.stream().map(CommissionRecordResponse::beneficiaryPartyId).filter(Objects::nonNull)
                .findFirst().orElse(null);
        BigDecimal rate = policies == null ? null : policies.stream()
                .filter(p -> DISTRIBUTOR_LINE.equals(p.productType()) && DISTRIBUTOR_SHARE.equals(p.commissionType()))
                .filter(p -> p.distributorPartyId() == null || p.distributorPartyId().equals(distributor))
                .min(Comparator.comparing((CommissionPolicyResponse p) -> p.distributorPartyId() == null ? 1 : 0))
                .map(CommissionPolicyResponse::rate)
                .orElse(null);
        return new DistributorCommission(rate, mine.stream()
                .map(r -> new EarnedCommission(r.valueDate(), r.basis(), r.rate(), r.amount(), r.ivaAmount(), r.status(),
                        r.period(), r.settlement(), r.cycleNumber(), r.dueDate(), r.daysLate()))
                .toList());
    }

    private void lookup(List<UUID> ids, String by, Map<UUID, PartySummary> found) {
        for (int i = 0; i < ids.size(); i += PARTY_BATCH) {
            List<UUID> lote = ids.subList(i, Math.min(ids.size(), i + PARTY_BATCH));
            List<PartyResponse> respuesta = party.get()
                    .uri(u -> u.path("/api/v1/parties/batch").queryParam("ids", lote.toArray()).queryParam("by", by).build())
                    .header("X-User-Id", SERVICE_ID)
                    .header("X-Roles", "SERVICE")
                    .retrieve()
                    .body(PARTIES);
            if (respuesta == null) {
                continue;
            }
            for (PartyResponse p : respuesta) {
                UUID clave = "prospectId".equals(by) ? p.prospectId() : p.partyId();
                if (clave != null) {
                    found.put(clave, new PartySummary(clave, p.displayName(), p.rfc()));
                }
            }
        }
    }

    /** What the statement uses of a commission record. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommissionRecordResponse(String commissionType, UUID beneficiaryPartyId, BigDecimal basis, BigDecimal rate,
                                    BigDecimal amount, BigDecimal ivaAmount, String status, String period,
                                    LocalDate valueDate, String settlement, Integer cycleNumber, LocalDate dueDate,
                                    Integer daysLate) {}

    /** What the statement uses of a commission policy. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommissionPolicyResponse(String productType, UUID distributorPartyId, String commissionType, BigDecimal rate) {}

    /** Lo que se usa de {@code PartyResponse}: nombre fiscal si lo hay; si no, el nombre de la persona. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PartyResponse(UUID partyId, UUID prospectId, String firstName, String lastName1, String lastName2, String rfc,
                         String taxName) {
        String displayName() {
            if (taxName != null && !taxName.isBlank()) return taxName;
            String nombre = String.join(" ", List.of(nz(firstName), nz(lastName1), nz(lastName2))).trim()
                    .replaceAll("\\s+", " ");
            return nombre.isEmpty() ? null : nombre;
        }

        private static String nz(String s) { return s == null ? "" : s; }
    }
}
