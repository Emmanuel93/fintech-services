package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.PlacementPolicyCopyRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.beneficiary.ClientLimits;
import com.fintech.creditportfolio.domain.beneficiary.ClientType;
import com.fintech.creditportfolio.domain.beneficiary.PlacementPolicyCopy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementPolicyCopyServiceTest {

    final Map<String, PlacementPolicyCopy> store = new HashMap<>();
    final PlacementPolicyCopyRepository repo = new PlacementPolicyCopyRepository() {
        @Override public PlacementPolicyCopy save(PlacementPolicyCopy c) { store.put(c.getUnitCode(), c); return c; }
        @Override public Optional<PlacementPolicyCopy> findByUnitCode(String code) { return Optional.ofNullable(store.get(code)); }
        @Override public Optional<PlacementPolicyCopy> findByPartyRef(UUID party) {
            return store.values().stream().filter(c -> party.equals(c.getPartyRef())).findFirst();
        }
    };
    final PlacementPolicyCopyService service = new PlacementPolicyCopyService(repo);

    static final ClientLimits FIVE = new ClientLimits(new BigDecimal("5000"), new BigDecimal("5000"), 1, 16);
    static final ClientLimits THREE = new ClientLimits(new BigDecimal("3000"), new BigDecimal("3000"), 1, 12);

    CreditAccount line(UUID holder, String originUnit) {
        CreditAccount a = CreditAccount.fromSnapshot(UUID.randomUUID(), "CTR", holder, "DL-002", 1, "DISTRIBUTOR_LINE", "REVOLVING",
                null, new BigDecimal("100000"), null, new BigDecimal("0.95"), new BigDecimal("0.36"), null, BigDecimal.ZERO,
                "032180000118359719", "MEDIO", null, null);
        if (originUnit != null) a.sealOriginUnit(originUnit);
        return a;
    }

    @Test
    void anOlderRedeliveryDoesNotUndoANewerCopy() {
        Instant t1 = Instant.parse("2026-11-01T10:00:00Z");
        service.upsert("S_TIJ", UUID.randomUUID(), "MX.R.Z.S_TIJ", "BRANCH", null, true, "S_TIJ", THREE, null, "e2", t1.plusSeconds(60));
        service.upsert("S_TIJ", UUID.randomUUID(), "MX.R.Z.S_TIJ", "BRANCH", null, true, "S_TIJ", FIVE, null, "e1", t1);

        assertThat(store.get("S_TIJ").limitsFor(ClientType.NUEVO)).isEqualTo(THREE);
        assertThat(store.get("S_TIJ").getEventId()).isEqualTo("e2");
    }

    @Test
    void aLineUsesHerOwnNodeFirst_thenItsOriginBranch_andNothingWhenNoneIsConfigured() {
        UUID her = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-01T10:00:00Z");
        service.upsert("S_TIJ", UUID.randomUUID(), "MX.R.Z.S_TIJ", "BRANCH", null, true, "S_TIJ", FIVE, FIVE, "e1", now);
        assertThat(service.forAccount(line(her, "S_TIJ"))).map(PlacementPolicyCopy::getUnitCode).contains("S_TIJ");

        service.upsert("D_ANA", UUID.randomUUID(), "MX.R.Z.S_TIJ.E.D_ANA", "DISTRIBUTOR", her, true, "D_ANA", THREE, FIVE, "e2", now);
        assertThat(service.forAccount(line(her, "S_TIJ"))).map(PlacementPolicyCopy::getUnitCode).contains("D_ANA");

        // A copy that says nothing applies is not a policy.
        service.upsert("D_ANA", UUID.randomUUID(), "MX.R.Z.S_TIJ.E.D_ANA", "DISTRIBUTOR", her, false, null, null, null, "e3", now.plusSeconds(1));
        service.upsert("S_TIJ", UUID.randomUUID(), "MX.R.Z.S_TIJ", "BRANCH", null, false, null, null, null, "e4", now.plusSeconds(1));
        assertThat(service.forAccount(line(her, "S_TIJ"))).isEmpty();
        assertThat(service.forAccount(line(UUID.randomUUID(), null))).isEmpty();
    }
}
