package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.PortfolioStat;
import com.fintech.creditportfolio.domain.CreditAccount;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modo consulta de cartera contra Postgres real: filtros del listado
 * (incluido {@code partyIds IN}), rango de días de atraso, hidratación por lote
 * y las agregaciones de {@code /stats}.
 *
 * <p>Dos invariantes se prueban aquí explícitamente:
 * <ul>
 *   <li><b>El filtro por lista opcional no revienta con null</b> — la guarda
 *       {@code (:partyIds IS NULL OR ...)} tiene que dejar pasar el "sin filtro"
 *       (es el bug clásico del parámetro-colección nulo en Hibernate).</li>
 *   <li><b>El {@code /batch} es O(1) en sentencias</b> — resuelve N cuentas en
 *       una sola query, sin importar cuántas.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "origination.credit-product-creation-requested",
            "credit-portfolio.credit-account-activated"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class CreditPortfolioSearchIT {

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
        registry.add("fintech.credit-portfolio.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired CreditAccountRepository accountRepository;
    @Autowired EntityManagerFactory emf;

    // ── Filtro por partyIds (y null = sin filtro, sin reventar) ───────────────

    @Test
    void search_filtersByPartyIds_andNullMeansNoFilter() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seed(a, "PERSONAL_LOAN", "CTR-PID-A1", 0);
        seed(a, "PERSONAL_LOAN", "CTR-PID-A2", 0);
        seed(b, "PERSONAL_LOAN", "CTR-PID-B1", 0);

        Page<CreditAccount> onlyA = accountRepository.search(null, null, null, null, null, List.of(a), page());
        assertThat(onlyA.getTotalElements()).isEqualTo(2);
        assertThat(onlyA.getContent()).allMatch(x -> x.getObligorPartyId().equals(a));

        Page<CreditAccount> ab = accountRepository.search(null, null, null, null, null, List.of(a, b), page());
        assertThat(ab.getTotalElements()).isEqualTo(3);

        // La guarda del parámetro-colección nulo: "sin filtro" no debe reventar.
        Page<CreditAccount> all = accountRepository.search(null, null, null, null, null, null, page());
        assertThat(all.getTotalElements()).isGreaterThanOrEqualTo(3);
    }

    // ── Rango de días de atraso (min/max) ─────────────────────────────────────

    @Test
    void search_filtersByDpdRange() {
        UUID c = UUID.randomUUID();
        seed(c, "PERSONAL_LOAN", "CTR-DPD-CUR", 0);
        seed(c, "PERSONAL_LOAN", "CTR-DPD-45", 45);

        // maxDpd = 0 → solo al corriente
        Page<CreditAccount> current =
                accountRepository.search(null, null, null, null, 0, List.of(c), page());
        assertThat(current.getContent()).extracting(CreditAccount::getContractNumber)
                .containsExactly("CTR-DPD-CUR");

        // minDpd = 1 → solo con atraso
        Page<CreditAccount> late =
                accountRepository.search(null, null, null, 1, null, List.of(c), page());
        assertThat(late.getContent()).extracting(CreditAccount::getContractNumber)
                .containsExactly("CTR-DPD-45");
    }

    // ── Hidratación por lote ──────────────────────────────────────────────────

    @Test
    void batch_returnsRequestedAccounts_andEmptyForNoIds() {
        UUID d = UUID.randomUUID();
        List<UUID> ids = List.of(
                seed(d, "PERSONAL_LOAN", "CTR-BAT-1", 0).getCreditAccountId(),
                seed(d, "PERSONAL_LOAN", "CTR-BAT-2", 0).getCreditAccountId());

        assertThat(accountRepository.findByCreditAccountIdIn(ids))
                .extracting(CreditAccount::getCreditAccountId)
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(accountRepository.findByCreditAccountIdIn(List.of())).isEmpty();
    }

    // ── Agregaciones de /stats ────────────────────────────────────────────────

    @Test
    void stats_groupByStatus_productType_dpdBucket_andRejectsInvalid() {
        UUID e = UUID.randomUUID();
        seed(e, "SME_LOAN", "CTR-STA-1", 0);

        assertThat(accountRepository.stats("productType"))
                .anyMatch(s -> s.key().equals("SME_LOAN") && s.count() >= 1);
        assertThat(accountRepository.stats("status"))
                .anyMatch(s -> s.key().equals("ACTIVE"));
        assertThat(accountRepository.stats("dpdBucket"))
                .anyMatch(s -> s.key().equals("CURRENT"));
        assertThat(accountRepository.stats("productType"))
                .allSatisfy(s -> assertThat(s.principal()).isNotNull());
        // El rechazo de groupBy inválido (400) se valida en el borde: ver
        // CreditAccountControllerTest.stats_invalidGroupBy_returns400.
    }

    // ── Performance: /batch es O(1) en nº de sentencias ───────────────────────

    @Test
    void batch_resolvesManyAccountsInASingleStatement() {
        UUID f = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            ids.add(seed(f, "PERSONAL_LOAN", "CTR-PERF-" + i, 0).getCreditAccountId());
        }

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        List<CreditAccount> got = accountRepository.findByCreditAccountIdIn(ids);

        assertThat(got).hasSize(150);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PageRequest page() {
        return PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private CreditAccount seed(UUID obligor, String productType, String contractNo, int dpd) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), contractNo, obligor,
                "PL-001", 1, productType, "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal("50000"));
        if (dpd > 0) {
            a.updateDelinquency(dpd);
        }
        return accountRepository.save(a);
    }
}
