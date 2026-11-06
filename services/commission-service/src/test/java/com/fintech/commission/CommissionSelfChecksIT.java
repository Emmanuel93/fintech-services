package com.fintech.commission;

import com.fintech.commission.application.port.out.BeneficiaryProfileRepository;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.service.CommissionSelfCheckService;
import com.fintech.commission.application.service.LiquidationService;
import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionSource;
import com.fintech.commission.domain.CommissionType;
import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commission service checks itself against a real Postgres: what the real liquidation leaves passes, and each
 * figure broken by hand is caught by its own check and by no other. Plus how long it takes over a year of lots.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "credit-portfolio.credit-account-activated", "party.fiscal-profile-updated",
        "commission.commission-accrued", "commission.commission-reversed", "commission.lot-payout-requested",
        "commission.lot-payout-failed", "disbursement.completed", "disbursement.failed", "disbursement.returned"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class CommissionSelfChecksIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired CommissionRecordRepository records;
    @Autowired BeneficiaryProfileRepository profiles;
    @Autowired LiquidationService liquidation;
    @Autowired CommissionSelfCheckService selfChecks;
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessClock clock;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM commission.commission_records");
        jdbc.update("DELETE FROM commission.liquidation_batches");
        jdbc.update("DELETE FROM commission.liquidation_runs");
        jdbc.update("DELETE FROM commission.beneficiary_profiles");
        clock.resetShift();
    }

    private UUID payable(String rfc, String type, String regime) {
        UUID party = UUID.randomUUID();
        BeneficiaryProfile p = BeneficiaryProfile.of(party);
        p.updateFiscal(rfc, type, "Beneficiaria " + rfc, regime);
        p.updatePayoutAccount("646180157000000004", null);
        profiles.save(p);
        return party;
    }

    private CommissionRecord accrue(UUID beneficiary, String commission, LocalDate day) {
        return records.save(CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                new CommissionSource(UUID.randomUUID(), beneficiary, "DISTRIBUTOR_LINE", "evt-" + UUID.randomUUID(),
                        "pago-" + UUID.randomUUID(), day),
                new BigDecimal(commission), BigDecimal.ONE, new BigDecimal("0.16")));
    }

    private static List<String> failures(SelfCheckReport r) {
        return r.failures().stream().map(SelfCheck::code).toList();
    }

    @Test
    @DisplayName("lo que deja la liquidación real pasa; cada cifra rota a mano la atrapa su comprobación y ninguna otra")
    void eachBrokenFigureIsCaughtByItsCheck() {
        UUID person = payable("PEGJ800101AB1", "INDIVIDUAL", "612");
        UUID company = payable("DIS010101AB1", "BUSINESS", "601");
        clock.shiftTo(LocalDateTime.of(2026, 5, 20, 10, 0));
        CommissionRecord paid = accrue(person, "600.00", LocalDate.of(2026, 5, 20));
        accrue(company, "300.00", LocalDate.of(2026, 5, 20));   // below the minimum: carried over
        clock.shiftTo(LocalDateTime.of(2026, 6, 5, 0, 5));
        assertThat(liquidation.runLiquidation("202605").lotsOpened()).isEqualTo(1);

        SelfCheckReport clean = selfChecks.check(null);
        assertThat(failures(clean)).as("%s", clean.checks()).isEmpty();

        // A lot whose net was tampered with.
        jdbc.update("UPDATE commission.liquidation_batches SET net_amount = net_amount + 1");
        assertThat(failures(selfChecks.check(null))).containsExactly("NETO_DE_CADA_LOTE");
        jdbc.update("UPDATE commission.liquidation_batches SET net_amount = net_amount - 1");

        // A reversal that takes back twice what its accrual accrued (D57).
        jdbc.update("""
                INSERT INTO commission.commission_records (commission_type, credit_account_id, beneficiary_party_id,
                       source_event_id, basis, rate, amount, status, period, accrual_date, iva_amount,
                       reverses_commission_id, product_type)
                SELECT commission_type, credit_account_id, beneficiary_party_id, 'evt-reversa-de-mas', -2 * basis, rate,
                       -2 * amount, 'ACCRUED', '202606', accrual_date, -2 * iva_amount, commission_id, product_type
                  FROM commission.commission_records WHERE commission_id = ?""", paid.getCommissionId());
        assertThat(failures(selfChecks.check(null))).containsExactly("REVERSAS_SIN_EXCESO");
        jdbc.update("DELETE FROM commission.commission_records WHERE source_event_id = 'evt-reversa-de-mas'");

        // A payable sum over the minimum the run left without a lot.
        jdbc.update("UPDATE commission.commission_records SET amount = 900, basis = 900 WHERE beneficiary_party_id = ?",
                company);
        assertThat(failures(selfChecks.check(null))).containsExactly("ARRASTRE_EXPLICADO");
    }

    @Test
    @DisplayName("rendimiento: 2,000 lotes de 20,000 registros y 2,000 grupos en arrastre se revisan en segundos")
    void performance() {
        jdbc.update("""
                INSERT INTO commission.beneficiary_profiles (party_id, rfc, party_type, tax_name, tax_regime, payout_clabe)
                SELECT md5('b' || g)::uuid, 'PEGJ800101AB1', 'INDIVIDUAL', 'Beneficiaria ' || g, '612', '646180157000000004'
                  FROM generate_series(1, 2000) g""");
        // Each lot: ten records of 100.00 (+16.00 IVA); ISR 10 % = 100.00, IVA withheld 2/3 = 106.67, net 953.33.
        jdbc.update("""
                INSERT INTO commission.liquidation_batches (batch_id, beneficiary_party_id, period, total_amount, status,
                       product_type, subtotal, iva_amount, isr_withheld, iva_withheld, net_amount, records_count,
                       payout_attempts)
                SELECT md5('l' || g)::uuid, md5('b' || g)::uuid, '202605', 1160.00, 'CONFIRMED', 'DISTRIBUTOR_LINE',
                       1000.00, 160.00, 100.00, 106.67, 953.33, 10, 1
                  FROM generate_series(1, 2000) g""");
        jdbc.update("""
                INSERT INTO commission.commission_records (commission_type, credit_account_id, beneficiary_party_id,
                       source_event_id, basis, rate, amount, status, period, accrual_date, iva_amount,
                       liquidation_batch_id, product_type)
                SELECT 'DISTRIBUTOR_INTEREST_SHARE', md5('c' || g)::uuid, md5('b' || ((g - 1) / 10 + 1))::uuid,
                       'evt-' || g, 100.00, 1, 100.00, 'LIQUIDATED', '202605', timestamptz '2026-05-20 10:00Z', 16.00,
                       md5('l' || ((g - 1) / 10 + 1))::uuid, 'DISTRIBUTOR_LINE'
                  FROM generate_series(1, 20000) g""");
        jdbc.update("""
                INSERT INTO commission.commission_records (commission_type, credit_account_id, beneficiary_party_id,
                       source_event_id, basis, rate, amount, status, period, accrual_date, iva_amount, product_type)
                SELECT 'DISTRIBUTOR_INTEREST_SHARE', md5('a' || g)::uuid, md5('b' || g)::uuid, 'evt-arrastre-' || g,
                       300.00, 1, 300.00, 'ACCRUED', '202605', timestamptz '2026-05-25 10:00Z', 48.00, 'DISTRIBUTOR_LINE'
                  FROM generate_series(1, 2000) g""");
        jdbc.update("""
                INSERT INTO commission.liquidation_runs (period, run_at, lots_opened, carried_over, waiting, retried)
                VALUES ('202605', timestamptz '2026-06-05 06:05Z', 2000, 2000, 0, 0)""");
        clock.shiftTo(LocalDateTime.of(2026, 6, 6, 10, 0));

        long started = System.nanoTime();
        SelfCheckReport all = selfChecks.check(null);
        SelfCheckReport may = selfChecks.check("202605");
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(failures(all)).as("%s", all.checks()).isEmpty();
        assertThat(may.inOrder()).isTrue();
        assertThat(all.checks().get(0).detail()).startsWith("2000 lotes");
        assertThat(ms).as("dos revisiones de 2,000 lotes en %d ms", ms).isLessThan(10_000);
    }
}
