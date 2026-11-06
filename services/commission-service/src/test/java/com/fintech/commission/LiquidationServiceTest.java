package com.fintech.commission;

import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.BeneficiaryProfileRepository;
import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.application.port.out.LiquidationRunLog;
import com.fintech.commission.application.service.LiquidationService;
import com.fintech.commission.application.service.LiquidationService.LiquidationRun;
import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import com.fintech.commission.domain.CommissionSource;
import com.fintech.commission.domain.CommissionType;
import com.fintech.commission.domain.LiquidationBatch;
import com.fintech.commission.domain.LiquidationBatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LB-03 with carry-over: a run takes everything accrued up to its period, by beneficiary and product, and opens a lot
 * for each group that reaches the minimum, with its taxes, and requests its SPEI. What does not reach the minimum, or
 * whose beneficiary cannot be paid yet, stays accrued for the next run.
 */
class LiquidationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LiquidationServiceTest.class);

    private final Map<UUID, CommissionRecord> records = new HashMap<>();
    private final Map<UUID, LiquidationBatch> lots = new HashMap<>();
    private final Map<UUID, BeneficiaryProfile> profiles = new HashMap<>();
    private final List<String> published = new ArrayList<>();
    private final List<String> runs = new ArrayList<>();

    private LiquidationService service;
    private final UUID company = UUID.randomUUID();
    private final UUID person = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CommissionProperties properties = new CommissionProperties();
        service = new LiquidationService(new MemoryRecords(), new MemoryLots(), new MemoryProfiles(), new Publisher(),
                properties, new MemoryRuns());
        payable(company, "DIS010101AB1", "BUSINESS", "601");
        payable(person, "PEGJ800101AB1", "INDIVIDUAL", "612");
    }

    private void payable(UUID party, String rfc, String type, String regime) {
        BeneficiaryProfile p = BeneficiaryProfile.of(party);
        p.updateFiscal(rfc, type, "Beneficiaria " + rfc, regime);
        p.updatePayoutAccount("646180157000000004", null);
        profiles.put(party, p);
    }

    private CommissionRecord accrued(UUID beneficiary, String product, String commission, LocalDate day) {
        // basis × 1.00 = the commission, so the amounts in the tests read as commissions.
        CommissionRecord r = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                new CommissionSource(UUID.randomUUID(), beneficiary, product, "evt-" + UUID.randomUUID(),
                        "pago-" + UUID.randomUUID(), day),
                new BigDecimal(commission), BigDecimal.ONE, new BigDecimal("0.16"));
        records.put(r.getCommissionId(), r);
        return r;
    }

    private List<LiquidationBatch> lotsOf(UUID beneficiary) {
        return lots.values().stream().filter(b -> b.getBeneficiaryPartyId().equals(beneficiary))
                .sorted(Comparator.comparing(LiquidationBatch::getPeriod)).toList();
    }

    @Test
    @DisplayName("what one month left below the minimum is taken by the next run: carry-over (E2E: never paid before)")
    void carryOver() {
        accrued(company, "DISTRIBUTOR_LINE", "300.00", LocalDate.of(2026, 3, 20));

        LiquidationRun march = service.runLiquidation("202603");
        accrued(company, "DISTRIBUTOR_LINE", "250.00", LocalDate.of(2026, 4, 15));
        LiquidationRun april = service.runLiquidation("202604");

        assertThat(march.lotsOpened()).isZero();
        assertThat(march.carriedOver()).isEqualTo(1);
        assertThat(april.lotsOpened()).isEqualTo(1);
        LiquidationBatch lot = lotsOf(company).get(0);
        assertThat(lot.getPeriod()).isEqualTo("202604");
        assertThat(lot.getSubtotal()).isEqualByComparingTo("550.00");
        assertThat(lot.getIvaAmount()).isEqualByComparingTo("88.00");
        assertThat(lot.getRecordsCount()).isEqualTo(2);
        assertThat(records.values()).allMatch(r -> r.getStatus() == CommissionRecordStatus.LIQUIDATED);
    }

    @Test
    @DisplayName("a lot per beneficiary AND product: two products of the same distributor are two lots")
    void perBeneficiaryAndProduct() {
        accrued(company, "DISTRIBUTOR_LINE", "600.00", LocalDate.of(2026, 4, 2));
        accrued(company, "SME_LOAN", "700.00", LocalDate.of(2026, 4, 3));

        assertThat(service.runLiquidation("202604").lotsOpened()).isEqualTo(2);
        assertThat(lotsOf(company)).extracting(LiquidationBatch::getProductType)
                .containsExactlyInAnyOrder("DISTRIBUTOR_LINE", "SME_LOAN");
    }

    @Test
    @DisplayName("a company is paid the gross; a natural person, net of 10 % ISR and two thirds of the IVA")
    void taxesByBeneficiary() {
        accrued(company, "DISTRIBUTOR_LINE", "1000.00", LocalDate.of(2026, 4, 2));
        accrued(person, "DISTRIBUTOR_LINE", "1000.00", LocalDate.of(2026, 4, 2));

        service.runLiquidation("202604");

        LiquidationBatch ofCompany = lotsOf(company).get(0);
        LiquidationBatch ofPerson = lotsOf(person).get(0);
        assertThat(ofCompany.getNetAmount()).isEqualByComparingTo("1160.00");
        assertThat(ofCompany.getTotalAmount()).isEqualByComparingTo("1160.00");
        assertThat(ofPerson.getIsrWithheld()).isEqualByComparingTo("100.00");
        assertThat(ofPerson.getIvaWithheld()).isEqualByComparingTo("106.67");
        assertThat(ofPerson.getNetAmount()).isEqualByComparingTo("953.33");
        assertThat(published).contains("payout-requested:" + ofCompany.getBatchId() + ":1",
                "payout-requested:" + ofPerson.getBatchId() + ":1");
        assertThat(lots.values()).allMatch(b -> b.getStatus() == LiquidationBatchStatus.SENT);
    }

    @Test
    @DisplayName("a clawback (a reversal of a commission already paid) is netted from the next lot")
    void clawbackNetsTheNextLot() {
        CommissionRecord paid = accrued(company, "DISTRIBUTOR_LINE", "800.00", LocalDate.of(2026, 3, 10));
        service.runLiquidation("202603");
        CommissionRecord reversal = CommissionRecord.reversalOf(paid, new BigDecimal("200.00"),
                CommissionRecord.Reversed.NONE, "evt-ret", LocalDate.of(2026, 4, 2));
        records.put(reversal.getCommissionId(), reversal);
        accrued(company, "DISTRIBUTOR_LINE", "900.00", LocalDate.of(2026, 4, 20));

        service.runLiquidation("202604");

        LiquidationBatch april = lotsOf(company).get(1);
        assertThat(april.getSubtotal()).isEqualByComparingTo("700.00");   // 900 − 200
        assertThat(april.getIvaAmount()).isEqualByComparingTo("112.00");
        assertThat(april.getRecordsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a beneficiary without fiscal profile or payout account waits: nothing is lost, the next run pays it")
    void waitsForTheBeneficiary() {
        UUID unknown = UUID.randomUUID();
        accrued(unknown, "DISTRIBUTOR_LINE", "900.00", LocalDate.of(2026, 4, 2));

        LiquidationRun first = service.runLiquidation("202604");
        payable(unknown, "PEGJ800101AB1", "INDIVIDUAL", "626");
        LiquidationRun second = service.runLiquidation("202605");

        assertThat(first.waitingForBeneficiary()).isEqualTo(1);
        assertThat(first.lotsOpened()).isZero();
        assertThat(second.lotsOpened()).isEqualTo(1);
        assertThat(lotsOf(unknown).get(0).getIsrWithheld()).isEqualByComparingTo("11.25");   // RESICO 1.25 %
    }

    @Test
    @DisplayName("what accrues after the period's lot was opened goes to the next run, not to a second lot of the period")
    void oneLotPerPeriod() {
        accrued(company, "DISTRIBUTOR_LINE", "600.00", LocalDate.of(2026, 4, 2));
        service.runLiquidation("202604");
        accrued(company, "DISTRIBUTOR_LINE", "650.00", LocalDate.of(2026, 4, 29));

        LiquidationRun again = service.runLiquidation("202604");

        assertThat(again.lotsOpened()).isZero();
        assertThat(again.carriedOver()).isEqualTo(1);
        assertThat(lotsOf(company)).hasSize(1);
    }

    @Test
    @DisplayName("a lot whose SPEI failed is requested again by the next run, as attempt 2")
    void retriesAFailedPayout() {
        accrued(company, "DISTRIBUTOR_LINE", "600.00", LocalDate.of(2026, 4, 2));
        service.runLiquidation("202604");
        LiquidationBatch lot = lotsOf(company).get(0);
        lot.payoutFailed("rechazado: cuenta inexistente");

        LiquidationRun next = service.runLiquidation("202605");

        assertThat(next.retried()).isEqualTo(1);
        assertThat(lot.getStatus()).isEqualTo(LiquidationBatchStatus.SENT);
        assertThat(lot.getPayoutAttempts()).isEqualTo(2);
        assertThat(published).contains("payout-requested:" + lot.getBatchId() + ":2");
    }

    @Test
    @DisplayName("performance: 60,000 accruals of 1,000 beneficiaries and 2 products liquidated in under 3 seconds")
    void performance() {
        List<UUID> beneficiaries = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            UUID b = UUID.randomUUID();
            payable(b, i % 2 == 0 ? "DIS010101AB1" : "PEGJ800101AB1", "BUSINESS", "601");
            beneficiaries.add(b);
        }
        // Every beneficiary accrues on both products: 30 accruals of 25.00 per lot.
        for (int i = 0; i < 60_000; i++) {
            accrued(beneficiaries.get(i % 1_000), (i / 1_000) % 2 == 0 ? "DISTRIBUTOR_LINE" : "SME_LOAN", "25.00",
                    LocalDate.of(2026, 4, 1 + i % 28));
        }

        long start = System.nanoTime();
        LiquidationRun run = service.runLiquidation("202604");
        long millis = (System.nanoTime() - start) / 1_000_000;

        log.info("Liquidar {} comisiones en {} lotes tomó {} ms", records.size(), run.lotsOpened(), millis);
        assertThat(run.lotsOpened()).isEqualTo(2_000);
        assertThat(millis).isLessThan(3_000);
    }

    // ── in-memory adapters: what is tested is the rule, not the mapping ─────────────────────────────

    private class MemoryRecords implements CommissionRecordRepository {
        public boolean existsBySourceEventId(String id) {
            return records.values().stream().anyMatch(r -> r.getSourceEventId().equals(id));
        }
        public List<CommissionRecord> findByCreditAccountId(UUID id) {
            return records.values().stream().filter(r -> r.getCreditAccountId().equals(id)).toList();
        }
        public List<CommissionRecord> findByBeneficiaryPartyIdAndStatus(UUID id, CommissionRecordStatus s) {
            return records.values().stream().filter(r -> r.getBeneficiaryPartyId().equals(id) && r.getStatus() == s).toList();
        }
        public List<CommissionRecord> findOpenUpTo(String period) {
            return records.values().stream()
                    .filter(r -> r.getStatus() == CommissionRecordStatus.ACCRUED && r.getPeriod().compareTo(period) <= 0)
                    .sorted(Comparator.comparing(CommissionRecord::getAccrualDate)).toList();
        }
        public List<CommissionRecord> findByPaymentRef(String ref) {
            return records.values().stream().filter(r -> ref.equals(r.getPaymentRef())).toList();
        }
        public List<CommissionRecord> findByLiquidationBatchId(UUID id) {
            return records.values().stream().filter(r -> id.equals(r.getLiquidationBatchId())).toList();
        }
        public CommissionRecord save(CommissionRecord r) {
            records.put(r.getCommissionId(), r);
            return r;
        }
    }

    private class MemoryLots implements LiquidationBatchRepository {
        public Optional<LiquidationBatch> findById(UUID id) { return Optional.ofNullable(lots.get(id)); }
        public List<LiquidationBatch> findByStatus(LiquidationBatchStatus s) {
            return lots.values().stream().filter(b -> b.getStatus() == s).toList();
        }
        public List<LiquidationBatch> findByPeriod(String p) {
            return lots.values().stream().filter(b -> b.getPeriod().equals(p)).toList();
        }
        public LiquidationBatch save(LiquidationBatch b) {
            lots.put(b.getBatchId(), b);
            return b;
        }
    }

    private class MemoryProfiles implements BeneficiaryProfileRepository {
        @Override
        public List<BeneficiaryProfile> findAllById(Iterable<UUID> partyIds) {
            List<BeneficiaryProfile> found = new ArrayList<>();
            partyIds.forEach(id -> { if (profiles.containsKey(id)) found.add(profiles.get(id)); });
            return found;
        }

        public Optional<BeneficiaryProfile> findById(UUID id) { return Optional.ofNullable(profiles.get(id)); }
        public BeneficiaryProfile save(BeneficiaryProfile p) {
            profiles.put(p.getPartyId(), p);
            return p;
        }
    }

    /** Each run the service logged, as {@code period:opened:carried:waiting:retried}. */
    private class MemoryRuns implements LiquidationRunLog {
        @Override
        public void record(String period, Instant runAt, int lotsOpened, int carriedOver, int waiting, int retried) {
            runs.add(period + ":" + lotsOpened + ":" + carriedOver + ":" + waiting + ":" + retried);
        }
    }

    private class Publisher implements CommissionEventPublisher {
        public void publishCommissionAccrued(CommissionRecord r) { published.add("accrued:" + r.getCommissionId()); }
        public void publishCommissionReversed(CommissionRecord r) { published.add("reversed:" + r.getCommissionId()); }
        public void publishLotPayoutRequested(LiquidationBatch lot, BeneficiaryProfile b, int attempt) {
            published.add("payout-requested:" + lot.getBatchId() + ":" + attempt);
        }
        public void publishLotPayoutFailed(LiquidationBatch lot, int attempt, String reason) {
            published.add("payout-failed:" + lot.getBatchId() + ":" + attempt);
        }
    }
}
