package com.fintech.origination;

import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.domain.TargetAudience;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bandeja de solicitudes contra Postgres real: los filtros del listado
 * (estados, producto, audiencia, prospecto, rango de fechas, código de promotor)
 * y la garantía de que la paginación se resuelve en la base.
 *
 * <p>Cada test etiqueta sus solicitudes con un {@code promoterCode} único y filtra
 * por él, para aislarse del resto de datos del contenedor compartido.
 */
@SpringBootTest
@Testcontainers
class OriginationApplicationSearchIT {

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
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired CreditApplicationRepository applicationRepository;
    @Autowired EntityManagerFactory emf;

    // ── Filtro por estados (y null = sin filtro) ──────────────────────────────

    @Test
    void search_filtersByStatuses_andNullMeansNoFilter() {
        String tag = "STSMARK";
        seed(ProductType.PERSONAL_LOAN, tag, null);                       // PENDING_SCORING
        seed(ProductType.PERSONAL_LOAN, tag, a -> a.sendToManualReview("MEDIO", "MANUAL_REVIEW"));

        Page<CreditApplication> review = applicationRepository.search(
                List.of(ApplicationStatus.UNDER_MANUAL_REVIEW), null, null, null, null, null, tag, page());
        assertThat(review.getContent()).extracting(CreditApplication::getStatus)
                .containsExactly(ApplicationStatus.UNDER_MANUAL_REVIEW);

        // statuses null = sin filtro de estado (no debe reventar la guarda IS NULL).
        Page<CreditApplication> all = applicationRepository.search(
                null, null, null, null, null, null, tag, page());
        assertThat(all.getTotalElements()).isEqualTo(2);
    }

    // ── Filtro por producto y por audiencia (→ conjunto de productos) ─────────

    @Test
    void search_filtersByProductType_andTargetAudience() {
        String tag = "AUDMARK";
        seed(ProductType.DISTRIBUTOR_LINE, tag, null);   // B2B2C
        seed(ProductType.PERSONAL_LOAN, tag, null);      // B2C

        Page<CreditApplication> b2b2c = applicationRepository.search(
                null, null, TargetAudience.B2B2C.productTypes(), null, null, null, tag, page());
        assertThat(b2b2c.getContent()).extracting(CreditApplication::getProductType)
                .containsExactly(ProductType.DISTRIBUTOR_LINE);

        Page<CreditApplication> personal = applicationRepository.search(
                null, ProductType.PERSONAL_LOAN, null, null, null, null, tag, page());
        assertThat(personal.getContent()).extracting(CreditApplication::getProductType)
                .containsExactly(ProductType.PERSONAL_LOAN);
    }

    // ── Filtro por prospecto y por rango de fechas ────────────────────────────

    @Test
    void search_filtersByProspectId_andDateRange() {
        String tag = "DTMARK";
        CreditApplication app = seed(ProductType.PERSONAL_LOAN, tag, null);

        assertThat(applicationRepository.search(
                null, null, null, app.getProspectId(), null, null, tag, page()).getContent())
                .extracting(CreditApplication::getApplicationId)
                .containsExactly(app.getApplicationId());

        // Alta = hoy; un rango que empieza mañana no debe incluirla.
        Instant tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(applicationRepository.search(
                null, null, null, null, tomorrow, null, tag, page()).getTotalElements())
                .isZero();
    }

    // ── Texto libre sobre el código de promotor ───────────────────────────────

    @Test
    void search_byPromoterCode_isCaseInsensitiveContains() {
        seed(ProductType.DISTRIBUTOR_LINE, "DIST-0042-QMARK", null);
        seed(ProductType.PERSONAL_LOAN, "OTHER-QMARK", null);

        Page<CreditApplication> hit = applicationRepository.search(
                null, null, null, null, null, null, "dist-0042", page());
        assertThat(hit.getContent()).extracting(CreditApplication::getPromoterCode)
                .containsExactly("DIST-0042-QMARK");
    }

    // ── Performance: la paginación la resuelve la base (nº de sentencias acotado)

    @Test
    void search_paginatesInDatabase_withBoundedStatements() {
        String tag = "PERFMARK";
        for (int i = 0; i < 150; i++) {
            seed(ProductType.PERSONAL_LOAN, tag, null);
        }

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        Page<CreditApplication> pageOf25 = applicationRepository.search(
                null, null, null, null, null, null, tag,
                PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(pageOf25.getContent()).hasSize(25);
        assertThat(pageOf25.getTotalElements()).isEqualTo(150);
        // Contenido + count = 2 sentencias, sin importar cuántas filas hay en total.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PageRequest page() {
        return PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** Cada solicitud usa un prospectId distinto para no chocar con OA-03 (único activo por prospecto+producto). */
    private CreditApplication seed(ProductType type, String promoterCode, Consumer<CreditApplication> transition) {
        CreditApplication a = CreditApplication.start(
                UUID.randomUUID(), ProspectType.INDIVIDUAL, type, new BigDecimal("50000"), 12, promoterCode);
        if (transition != null) {
            transition.accept(a);
        }
        return applicationRepository.save(a);
    }
}
