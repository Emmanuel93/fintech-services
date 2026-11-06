package com.fintech.receipt.infrastructure.adapter.out.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.receipt.application.port.out.ReceiptSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The portfolio (view, references, profiles) and party (names) as a receipt reads them. */
@Component
class HttpReceiptSource implements ReceiptSource {

    private static final String SERVICE_ID = "receipt-service";
    private static final int BATCH = 200;

    private static final ParameterizedTypeReference<List<PartyResponse>> PARTIES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<ReferenceResponse>> REFERENCES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<ClientProfile>> PROFILES = new ParameterizedTypeReference<>() {};

    private final RestClient portfolio;
    private final RestClient party;

    HttpReceiptSource(@Qualifier("creditPortfolioRestClient") RestClient portfolio,
                      @Qualifier("partyRestClient") RestClient party) {
        this.portfolio = portfolio;
        this.party = party;
    }

    @Override
    public LineView view(UUID creditAccountId, LocalDate from, LocalDate to, LocalDate dueUntil) {
        LineView view = portfolio.get()
                .uri(u -> u.path("/api/v1/portfolio/accounts/{id}/statement-view")
                        .queryParam("from", from).queryParam("to", to).queryParam("dueUntil", dueUntil)
                        .build(creditAccountId))
                .header("X-User-Id", SERVICE_ID)
                .header("X-Roles", "SERVICE")
                .retrieve()
                .body(LineView.class);
        if (view == null || view.account() == null) {
            throw new IllegalStateException("Cartera devolvió una vista vacía para la línea " + creditAccountId);
        }
        return view;
    }

    @Override
    public Map<UUID, Long> references(UUID creditAccountId) {
        List<ReferenceResponse> refs = portfolio.get()
                .uri("/api/v1/portfolio/beneficiaries/by-line/{id}/references", creditAccountId)
                .header("X-User-Id", SERVICE_ID)
                .header("X-Roles", "SERVICE")
                .retrieve()
                .body(REFERENCES);
        Map<UUID, Long> byClient = new HashMap<>();
        if (refs != null) refs.forEach(r -> byClient.put(r.beneficiaryPartyId(), r.paymentReference()));
        return byClient;
    }

    @Override
    public Map<UUID, PartySummary> parties(Collection<UUID> partyIds) {
        Map<UUID, PartySummary> found = new HashMap<>();
        List<UUID> ids = new ArrayList<>(partyIds);
        lookup(ids, "partyId", found);
        lookup(ids.stream().filter(id -> !found.containsKey(id)).toList(), "prospectId", found);
        return found;
    }

    @Override
    public Map<UUID, ClientProfile> profiles(Collection<UUID> partyIds) {
        Map<UUID, ClientProfile> found = new HashMap<>();
        List<UUID> ids = new ArrayList<>(partyIds);
        for (int i = 0; i < ids.size(); i += BATCH) {
            List<UUID> batch = ids.subList(i, Math.min(ids.size(), i + BATCH));
            List<ClientProfile> got = portfolio.get()
                    .uri(u -> u.path("/api/v1/portfolio/beneficiaries/profiles").queryParam("partyIds", batch.toArray()).build())
                    .header("X-User-Id", SERVICE_ID)
                    .header("X-Roles", "SERVICE")
                    .retrieve()
                    .body(PROFILES);
            if (got != null) got.forEach(p -> found.put(p.beneficiaryPartyId(), p));
        }
        return found;
    }

    private void lookup(List<UUID> ids, String by, Map<UUID, PartySummary> found) {
        for (int i = 0; i < ids.size(); i += BATCH) {
            List<UUID> batch = ids.subList(i, Math.min(ids.size(), i + BATCH));
            List<PartyResponse> got = party.get()
                    .uri(u -> u.path("/api/v1/parties/batch").queryParam("ids", batch.toArray()).queryParam("by", by).build())
                    .header("X-User-Id", SERVICE_ID)
                    .header("X-Roles", "SERVICE")
                    .retrieve()
                    .body(PARTIES);
            if (got == null) continue;
            for (PartyResponse p : got) {
                UUID key = "prospectId".equals(by) ? p.prospectId() : p.partyId();
                if (key != null) found.put(key, new PartySummary(key, p.displayName(), p.rfc()));
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReferenceResponse(Long paymentReference, UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId) {}

    /** The fiscal name when there is one; otherwise the person's name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PartyResponse(UUID partyId, UUID prospectId, String firstName, String lastName1, String lastName2, String rfc,
                         String taxName) {
        String displayName() {
            if (taxName != null && !taxName.isBlank()) return taxName;
            String name = String.join(" ", List.of(nz(firstName), nz(lastName1), nz(lastName2))).trim().replaceAll("\\s+", " ");
            return name.isEmpty() ? null : name;
        }

        private static String nz(String s) { return s == null ? "" : s; }
    }
}
