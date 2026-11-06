package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.ClientReferenceRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.BeneficiaryProfileService;
import com.fintech.creditportfolio.application.service.ClientReferenceBackfill;
import com.fintech.creditportfolio.domain.PaymentReference;
import com.fintech.creditportfolio.application.service.PlacementPolicyCopyService;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryFlag;
import com.fintech.creditportfolio.domain.beneficiary.ClientType;
import com.fintech.creditportfolio.domain.beneficiary.FlagSource;
import com.fintech.creditportfolio.domain.beneficiary.ReportReason;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.PlacementPolicyChangedListener;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.PlacementPolicyChangedPayload;
import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributor line's client side against a real Postgres: migration 050 applies and Hibernate validates the new
 * tables; the policy copy fed by its listener is found by the line's holder; a client is typed from the placements
 * stored by his id; a report puts him on the red list and refuses him until it is cleared.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"sales-org.placement-policy-changed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
@ActiveProfiles("test")
@Transactional
class BeneficiaryRedListIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("fintech.credit-portfolio.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    @Autowired PlacementPolicyChangedListener policyListener;
    @Autowired PlacementPolicyCopyService policies;
    @Autowired BeneficiaryProfileService profiles;
    @Autowired CreditAccountRepository accounts;
    @Autowired DispositionRepository dispositions;
    @Autowired InstallmentRepository installments;
    @Autowired EntityManager em;
    @Autowired ClientReferenceBackfill backfill;
    @Autowired ClientReferenceRepository references;

    @Test
    @DisplayName("política copiada, cliente tipado desde sus colocaciones y lista roja que rechaza hasta levantarse")
    void theClientSideOfADistributorLine() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID her = UUID.randomUUID();
        CreditAccount line = CreditAccount.fromSnapshot(UUID.randomUUID(), "CTR-IT-" + suffix, her,
                "DL-IT", 1, "DISTRIBUTOR_LINE", "REVOLVING", null, new BigDecimal("100000"), null,
                new BigDecimal("0.95"), new BigDecimal("0.36"), null, BigDecimal.ZERO, "032180000118359719", "MEDIO", null, null);
        line.activate(BigDecimal.ZERO);
        line.sealOriginUnit("S_IT_" + suffix);
        accounts.save(line);

        // The branch's policy and her own, stricter one, as sales-org publishes them.
        Instant now = BusinessClock.current().now();
        policyListener.onMessage(new PlacementPolicyChangedPayload("e1", UUID.randomUUID(), "S_IT_" + suffix, "MX.S_IT_" + suffix,
                "BRANCH", null, "DISTRIBUTOR_LINE", true, "S_IT_" + suffix,
                new PlacementPolicyChangedPayload.Limits(new BigDecimal("5000"), new BigDecimal("5000"), 1, 16),
                new PlacementPolicyChangedPayload.Limits(new BigDecimal("10000"), new BigDecimal("15000"), 3, 24), now));
        policyListener.onMessage(new PlacementPolicyChangedPayload("e2", UUID.randomUUID(), "D_IT_" + suffix,
                "MX.S_IT_" + suffix + ".E.D_IT_" + suffix, "DISTRIBUTOR", her, "DISTRIBUTOR_LINE", true, "D_IT_" + suffix,
                new PlacementPolicyChangedPayload.Limits(new BigDecimal("3000"), new BigDecimal("5000"), 1, 12),
                new PlacementPolicyChangedPayload.Limits(new BigDecimal("10000"), new BigDecimal("15000"), 3, 24), now));
        em.flush();
        assertThat(policies.forAccount(line)).hasValueSatisfying(c -> assertThat(c.getUnitCode()).isEqualTo("D_IT_" + suffix));

        // A paid-off placement types him REVOLVENTE; a live one counts against her limits.
        UUID client = UUID.randomUUID();
        LocalDate today = BusinessClock.current().today();
        placement(line, client, "3000", 12, today.minusDays(200), 12);
        assertThat(profiles.profile(client).type()).isEqualTo(ClientType.REVOLVENTE);
        placement(line, client, "4000", 12, today.plusDays(10), 0);
        em.flush();
        var profile = profiles.profile(client);
        assertThat(profile.loansGranted()).isEqualTo(2);
        assertThat(profile.loansPaid()).isEqualTo(1);
        assertThat(profile.activeLoans()).isEqualTo(1);
        assertThat(profiles.reject(line, client, new BigDecimal("12000"), 12)).startsWith("AMOUNT_ABOVE_CLIENT_LIMIT: cliente REVOLVENTE");
        assertThat(profiles.reject(line, client, new BigDecimal("8000"), 12)).isNull();

        // Placed before references existed: the backfill gives him one on her line, valid, and it resolves back.
        backfill.assignMissing();
        em.flush();
        var ref = references.findByLineAndClient(line.getCreditAccountId(), client).orElseThrow();
        assertThat(PaymentReference.isValid(ref.getPaymentReference())).isTrue();
        assertThat(references.findByReference(ref.getPaymentReference())).hasValueSatisfying(r -> assertThat(r.getObligorPartyId()).isEqualTo(her));
        backfill.assignMissing();
        assertThat(references.findByLine(line.getCreditAccountId())).hasSize(1);

        // Reported: no placement from anyone until the mark is cleared; the history keeps both.
        BeneficiaryFlag report = profiles.report(client, FlagSource.DISTRIBUTOR, her, line.getCreditAccountId(),
                ReportReason.NOT_LOCATABLE, "No contesta", her.toString());
        em.flush();
        assertThat(profiles.reject(line, client, new BigDecimal("1000"), 12)).isEqualTo("CLIENT_RED_LISTED: DISTRIBUTOR_REPORT");
        profiles.clear(report.getFlagId(), "bo-1", "Ya la localizó");
        em.flush();
        assertThat(profiles.reject(line, client, new BigDecimal("1000"), 12)).isNull();
        assertThat(profiles.history(client)).singleElement().satisfies(f -> {
            assertThat(f.isActive()).isFalse();
            assertThat(f.getClearComment()).isEqualTo("Ya la localizó");
        });
    }

    private void placement(CreditAccount line, UUID client, String amount, int n, LocalDate firstDue, int paid) {
        Disposition d = Disposition.create(line.getCreditAccountId(), DispositionType.THIRD_PARTY_CREDIT, new BigDecimal(amount), client);
        d.markProcessing();
        d.complete("SPEI-IT");
        dispositions.save(d);
        BigDecimal principal = new BigDecimal(amount).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        List<Installment> plan = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Installment c = Installment.of(d.getDispositionId(), i, firstDue.plusDays(15L * (i - 1)), principal, new BigDecimal("40"));
            if (i <= paid) {
                c.billIn(i, c.getDueDate());
                c.settleAs(InstallmentStatus.PAID);
            }
            plan.add(c);
        }
        installments.saveAll(plan);
    }
}
