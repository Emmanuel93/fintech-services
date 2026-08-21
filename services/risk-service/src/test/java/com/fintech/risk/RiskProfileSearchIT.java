package com.fintech.risk;

import com.fintech.risk.application.port.in.GetRiskProfileUseCase;
import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileStatus;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modo consulta de riesgo contra Postgres real: la bandeja paginada (partyId
 * opcional) con filtros por producto/etapa/estado, y la hidratación por lote por
 * id de cuenta — con la prueba de que el {@code /batch} resuelve N perfiles en
 * una sola sentencia.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.credit-account-activated", "credit-portfolio.balance-updated",
        "credit-portfolio.delinquency-status-updated", "collections.agreement-executed",
        "risk.assessment-updated"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class RiskProfileSearchIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired RiskProfileRepository profileRepository;
    @Autowired GetRiskProfileUseCase useCase;
    @Autowired EntityManagerFactory emf;

    // ── partyId opcional (bandeja por población) ──────────────────────────────

    @Test
    void search_filtersByParty_andNullMeansNoFilter() {
        UUID pa = UUID.randomUUID();
        UUID pb = UUID.randomUUID();
        seed(pa, "PERSONAL_LOAN");
        seed(pa, "PERSONAL_LOAN");
        seed(pb, "PERSONAL_LOAN");

        Page<RiskProfile> onlyA = useCase.search(pa, null, null, null, page());
        assertThat(onlyA.getTotalElements()).isEqualTo(2);
        assertThat(onlyA.getContent()).allMatch(x -> x.getObligorPartyId().equals(pa));

        // Sin partyId = bandeja completa (no debe reventar ni exigir sujeto).
        Page<RiskProfile> all = useCase.search(null, null, null, null, page());
        assertThat(all.getTotalElements()).isGreaterThanOrEqualTo(3);
    }

    // ── Filtros por producto / etapa / estado ─────────────────────────────────

    @Test
    void search_filtersByProductType_stage_andStatus() {
        UUID p = UUID.randomUUID();
        seed(p, "PERSONAL_LOAN");                               // STAGE_1 / ACTIVE

        RiskProfile late = RiskProfile.create(UUID.randomUUID(), p, "SME_LOAN");
        late.syncDaysDelinquent(120);
        late.reassess(30, 90, 6, new BigDecimal("0.60"), Instant.now());  // → STAGE_3
        profileRepository.save(late);

        RiskProfile closed = RiskProfile.create(UUID.randomUUID(), p, "PAYROLL_LOAN");
        closed.close();                                          // → CLOSED
        profileRepository.save(closed);

        assertThat(useCase.search(p, "SME_LOAN", null, null, page()).getContent())
                .extracting(RiskProfile::getProductType).containsExactly("SME_LOAN");
        assertThat(useCase.search(p, null, Ifrs9Stage.STAGE_3, null, page()).getContent())
                .allMatch(x -> x.getIfrs9Stage() == Ifrs9Stage.STAGE_3);
        assertThat(useCase.search(p, null, null, RiskProfileStatus.CLOSED, page()).getContent())
                .allMatch(x -> x.getStatus() == RiskProfileStatus.CLOSED);
    }

    // ── Hidratación por lote ──────────────────────────────────────────────────

    @Test
    void batch_returnsProfilesByAccountId_andEmptyForNoIds() {
        UUID p = UUID.randomUUID();
        List<UUID> ids = List.of(
                seed(p, "PERSONAL_LOAN").getCreditAccountId(),
                seed(p, "PERSONAL_LOAN").getCreditAccountId());

        assertThat(useCase.findByCreditAccountIds(ids))
                .extracting(RiskProfile::getCreditAccountId)
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(useCase.findByCreditAccountIds(List.of())).isEmpty();
    }

    // ── Performance: /batch es O(1) en nº de sentencias ───────────────────────

    @Test
    void batch_resolvesManyProfilesInASingleStatement() {
        UUID p = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            ids.add(seed(p, "PERSONAL_LOAN").getCreditAccountId());
        }

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        List<RiskProfile> got = profileRepository.findByCreditAccountIdIn(ids);

        assertThat(got).hasSize(150);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PageRequest page() {
        return PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private RiskProfile seed(UUID partyId, String productType) {
        return profileRepository.save(RiskProfile.create(UUID.randomUUID(), partyId, productType));
    }
}
