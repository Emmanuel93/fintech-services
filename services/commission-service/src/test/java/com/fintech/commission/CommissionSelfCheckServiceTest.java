package com.fintech.commission;

import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.BeneficiaryProfileRepository;
import com.fintech.commission.application.port.out.CommissionSelfCheckQueries;
import com.fintech.commission.application.service.CommissionSelfCheckService;
import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommissionSelfCheckServiceTest {

    private static final Instant RUN_AT = Instant.parse("2026-06-05T06:05:00Z");

    private final Map<UUID, BeneficiaryProfile> profiles = new HashMap<>();
    private final Queries queries = new Queries();
    private final BusinessClock clock = BusinessClock.current();
    private CommissionSelfCheckService service;

    private final UUID person = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();
    private final UUID unpaid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CommissionSelfCheckService(queries, new Profiles(), new CommissionProperties());
        profile(person, "PEGJ800101AB1", "INDIVIDUAL", "612", true);
        profile(company, "DIS010101AB1", "BUSINESS", "601", true);
        profile(unpaid, "PEUN800101AB1", "INDIVIDUAL", "612", false);
        clock.shiftTo(LocalDateTime.of(2026, 6, 6, 10, 0));
        queries.run = Optional.of(new CommissionSelfCheckQueries.Run("202605", RUN_AT));
    }

    @AfterEach
    void tearDown() {
        clock.resetShift();
    }

    private void profile(UUID party, String rfc, String type, String regime, boolean payable) {
        BeneficiaryProfile p = BeneficiaryProfile.of(party);
        p.updateFiscal(rfc, type, "Beneficiaria " + rfc, regime);
        if (payable) p.updatePayoutAccount("646180157000000004", null);
        profiles.put(party, p);
    }

    /** A lot of a natural person (612): ISR 10 % of the commission, IVA withheld 2/3 of its IVA. */
    private CommissionSelfCheckQueries.LotFigures personLot(String subtotal, String status, int attempts, Instant created) {
        BigDecimal s = new BigDecimal(subtotal);
        BigDecimal iva = s.multiply(new BigDecimal("0.16")).setScale(2);
        BigDecimal isr = s.multiply(new BigDecimal("0.10")).setScale(2);
        BigDecimal ivaWithheld = iva.multiply(new BigDecimal("0.666666666667")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = s.add(iva);
        return new CommissionSelfCheckQueries.LotFigures(UUID.randomUUID(), person, "DISTRIBUTOR_LINE", "202605", status,
                s, iva, isr, ivaWithheld, total, total.subtract(isr).subtract(ivaWithheld), 3, s, iva, 3,
                attempts, attempts > 0 ? "rechazado: -200 RECHAZO_POR_PLD" : null, created);
    }

    private SelfCheck check(SelfCheckReport r, String code) {
        return r.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("lo que la liquidación deja bien hecho pasa su propia revisión: lotes, neto, retenciones, arrastre")
    void inOrder() {
        queries.lots.add(personLot("600.00", "CONFIRMED", 1, RUN_AT));
        queries.open.add(new CommissionSelfCheckQueries.OpenGroup(company, "DISTRIBUTOR_LINE", new BigDecimal("300.00"), 2));
        queries.open.add(new CommissionSelfCheckQueries.OpenGroup(unpaid, "DISTRIBUTOR_LINE", new BigDecimal("700.00"), 3));

        SelfCheckReport r = service.check(null);

        assertThat(r.failures()).as("%s", r.checks()).isEmpty();
        assertThat(r.inOrder()).isTrue();
        assertThat(check(r, "ARRASTRE_EXPLICADO").detail())
                .contains("1 bajo el mínimo").contains("1 esperan datos de pago");
    }

    @Test
    @DisplayName("un lote que no suma sus registros, o cuyo neto está mal armado, no pasa")
    void lotOff() {
        CommissionSelfCheckQueries.LotFigures good = personLot("600.00", "CONFIRMED", 1, RUN_AT);
        queries.lots.add(new CommissionSelfCheckQueries.LotFigures(good.batchId(), good.beneficiaryPartyId(),
                good.productType(), good.period(), good.status(), good.subtotal(), good.iva(), good.isrWithheld(),
                good.ivaWithheld(), good.total(), good.net().add(BigDecimal.ONE), good.recordsCount(),
                good.recordsSubtotal().subtract(new BigDecimal("10.00")), good.recordsIva(), good.recordsFound(),
                good.payoutAttempts(), null, good.createdAt()));

        SelfCheckReport r = service.check("202605");

        assertThat(r.failures()).extracting(SelfCheck::code)
                .containsExactlyInAnyOrder("LOTES_SUMAN_SUS_REGISTROS", "NETO_DE_CADA_LOTE");
        assertThat(check(r, "LOTES_SUMAN_SUS_REGISTROS").amount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("una retención que no es la del régimen (RESICO retiene 1.25 %, no 10 %) no pasa")
    void wrongWithholding() {
        profile(person, "PEGJ800101AB1", "INDIVIDUAL", "626", true);   // RESICO
        queries.lots.add(personLot("600.00", "CONFIRMED", 1, RUN_AT));  // withheld at 10 %

        SelfCheckReport r = service.check(null);

        assertThat(r.failures()).extracting(SelfCheck::code).containsExactly("RETENCIONES_POR_REGIMEN");
        assertThat(check(r, "RETENCIONES_POR_REGIMEN").amount()).isEqualByComparingTo("52.50");   // 60.00 − 7.50
    }

    @Test
    @DisplayName("una reversa que toma más de lo devengado no pasa (D57)")
    void overReversal() {
        queries.over.add(new CommissionSelfCheckQueries.OverReversal(UUID.randomUUID(), new BigDecimal("150.00"),
                new BigDecimal("24.00"), new BigDecimal("-270.00"), new BigDecimal("-43.20")));

        SelfCheckReport r = service.check(null);

        assertThat(r.failures()).extracting(SelfCheck::code).containsExactly("REVERSAS_SIN_EXCESO");
        assertThat(check(r, "REVERSAS_SIN_EXCESO").amount()).isEqualByComparingTo("139.20");   // 120 + 19.20
    }

    @Test
    @DisplayName("un grupo pagable sobre el mínimo que la corrida no tomó no pasa")
    void skippedGroup() {
        queries.open.add(new CommissionSelfCheckQueries.OpenGroup(company, "DISTRIBUTOR_LINE", new BigDecimal("900.00"), 4));

        SelfCheckReport r = service.check(null);

        assertThat(r.failures()).extracting(SelfCheck::code).containsExactly("ARRASTRE_EXPLICADO");
        assertThat(check(r, "ARRASTRE_EXPLICADO").amount()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("lo informativo no detiene: un lote enviado hace días sin desenlace, y uno que espera reintento")
    void informative() {
        queries.lots.add(personLot("600.00", "SENT", 1, RUN_AT.minusSeconds(3 * 86_400)));
        queries.lots.add(personLot("700.00", "PENDING", 2, RUN_AT));

        SelfCheckReport r = service.check(null);

        assertThat(r.inOrder()).isTrue();
        assertThat(check(r, "LOTES_SIN_DESENLACE").ok()).isFalse();
        assertThat(check(r, "LOTES_SIN_DESENLACE").items()).isEqualTo(1);
        assertThat(check(r, "LOTES_POR_REINTENTAR").detail()).contains("2 intentos").contains("PLD");
    }

    @Test
    @DisplayName("rendimiento: 20,000 lotes y 5,000 grupos abiertos se revisan en menos de un segundo")
    void performance() {
        for (int i = 0; i < 20_000; i++) {
            queries.lots.add(personLot("600.00", "CONFIRMED", 1, RUN_AT));
        }
        for (int i = 0; i < 5_000; i++) {
            queries.open.add(new CommissionSelfCheckQueries.OpenGroup(company, "P" + i, new BigDecimal("100.00"), 1));
        }
        long started = System.nanoTime();
        SelfCheckReport r = service.check(null);
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(r.inOrder()).isTrue();
        assertThat(ms).as("20,000 lotes revisados en %d ms", ms).isLessThan(1_000);
    }

    private class Queries implements CommissionSelfCheckQueries {
        final List<LotFigures> lots = new ArrayList<>();
        final List<OverReversal> over = new ArrayList<>();
        final List<OpenGroup> open = new ArrayList<>();
        Optional<Run> run = Optional.empty();

        @Override
        public List<LotFigures> lots(String period) {
            return period == null ? lots : lots.stream().filter(l -> l.period().equals(period)).toList();
        }

        @Override
        public List<OverReversal> overReversals() {
            return over;
        }

        @Override
        public List<OpenGroup> openAsOf(String period, Instant runAt) {
            return open;
        }

        @Override
        public Optional<Run> lastRun() {
            return run;
        }
    }

    private class Profiles implements BeneficiaryProfileRepository {
        @Override
        public Optional<BeneficiaryProfile> findById(UUID partyId) {
            return Optional.ofNullable(profiles.get(partyId));
        }

        @Override
        public List<BeneficiaryProfile> findAllById(Iterable<UUID> partyIds) {
            List<BeneficiaryProfile> found = new ArrayList<>();
            partyIds.forEach(id -> { if (profiles.containsKey(id)) found.add(profiles.get(id)); });
            return found;
        }

        @Override
        public BeneficiaryProfile save(BeneficiaryProfile profile) {
            profiles.put(profile.getPartyId(), profile);
            return profile;
        }
    }
}
