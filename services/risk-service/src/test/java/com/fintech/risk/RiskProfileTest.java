package com.fintech.risk;

import com.fintech.risk.domain.DelinquencyBucket;
import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.EnumMap;
import com.fintech.risk.domain.ProvisionPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskProfileTest {

    private static final int S2 = 30, S3 = 90, CURE = 6, PROB3 = 90, PROB2 = 30;

    /** A policy with the same rate in every band and no stage floors: the rate the old tests passed in. */
    private static ProvisionPolicy flat(String rate) {
        Map<DelinquencyBucket, BigDecimal> m = new EnumMap<>(DelinquencyBucket.class);
        for (DelinquencyBucket b : DelinquencyBucket.values()) m.put(b, new BigDecimal(rate));
        return ProvisionPolicy.create("PERSONAL_LOAN", 1, m);
    }

    /** The PERSONAL_LOAN policy of changeset 009: stage floors 2 % / 12 % / 55 %, band escalation by DPD. */
    private static ProvisionPolicy byStage(String ccf) {
        Map<DelinquencyBucket, BigDecimal> m = new EnumMap<>(DelinquencyBucket.class);
        String[] r = {"0.01", "0.03", "0.15", "0.30", "0.60", "0.80", "1.00"};
        DelinquencyBucket[] b = DelinquencyBucket.values();
        for (int i = 0; i < b.length; i++) m.put(b[i], new BigDecimal(r[i]));
        Map<Ifrs9Stage, BigDecimal> pisos = new EnumMap<>(Ifrs9Stage.class);
        pisos.put(Ifrs9Stage.STAGE_1, new BigDecimal("0.02"));
        pisos.put(Ifrs9Stage.STAGE_2, new BigDecimal("0.12"));
        pisos.put(Ifrs9Stage.STAGE_3, new BigDecimal("0.55"));
        return ProvisionPolicy.create("PERSONAL_LOAN", 2, m, pisos, new BigDecimal(ccf));
    }

    private RiskProfile newProfile() {
        return RiskProfile.create(UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN");
    }

    @Test
    void create_startsStage1CurrentZero() {
        RiskProfile p = newProfile();
        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_1);
        assertThat(p.getBucket()).isEqualTo(DelinquencyBucket.CURRENT);
        assertThat(p.getEad()).isEqualByComparingTo("0");
        assertThat(p.getProvisionAmount()).isEqualByComparingTo("0");
        assertThat(p.getStatus()).isEqualTo(RiskProfileStatus.ACTIVE);
    }

    @Test
    void reassess_computesProvisionAsEadTimesRate() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("1000"));
        p.syncDaysDelinquent(45);

        boolean stageChanged = p.reassess(S2, S3, CURE, PROB3, PROB2, flat("0.15"), Instant.now());

        assertThat(p.getBucket()).isEqualTo(DelinquencyBucket.B31_60);
        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_2);
        assertThat(stageChanged).isTrue();
        assertThat(p.getProvisionAmount()).isEqualByComparingTo("150.00"); // 1000 * 0.15
    }

    @Test
    void reassess_stage3_forSeverelyDelinquent() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("2000"));
        p.syncDaysDelinquent(120);

        p.reassess(S2, S3, CURE, PROB3, PROB2, flat("0.60"), Instant.now());

        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_3);
        assertThat(p.getProvisionAmount()).isEqualByComparingTo("1200.00");
    }

    @Test
    void forbearance_floorsStage2_whileInCureWindow_evenIfCurrent() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("1000"));
        p.syncDaysDelinquent(0);          // no longer delinquent
        p.markForborne(Instant.now());     // restructure just executed

        boolean changed = p.reassess(S2, S3, CURE, PROB3, PROB2, flat("0.15"), Instant.now());

        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_2); // floored despite days=0
        assertThat(changed).isTrue();
        assertThat(p.isForborne()).isTrue();
    }

    @Test
    void forbearance_clears_afterCureWindowElapses() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("1000"));
        p.syncDaysDelinquent(0);
        // forbearance started 8 months ago — cure window (6mo) already elapsed
        p.markForborne(Instant.now().minus(240, ChronoUnit.DAYS));

        p.reassess(S2, S3, CURE, PROB3, PROB2, flat("0.01"), Instant.now());

        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_1); // can drop back now
        assertThat(p.isForborne()).isFalse();
    }

    @Test
    void close_freezesProvision_andIgnoresFurtherSync() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("1000"));
        p.syncDaysDelinquent(200);
        p.reassess(S2, S3, CURE, PROB3, PROB2, flat("1.00"), Instant.now());
        BigDecimal frozen = p.getProvisionAmount();

        p.close();
        p.syncEad(new BigDecimal("9999"));       // ignored — closed
        p.syncDaysDelinquent(0);                 // ignored — closed
        boolean changed = p.reassess(S2, S3, CURE, PROB3, PROB2, flat("0.01"), Instant.now());

        assertThat(p.getStatus()).isEqualTo(RiskProfileStatus.CLOSED);
        assertThat(changed).isFalse();
        assertThat(p.getProvisionAmount()).isEqualByComparingTo(frozen);
        assertThat(p.getEad()).isEqualByComparingTo("1000");
    }

    @Test
    void stage2_reservesTheLifetimeFloor_evenAtZeroDaysPastDue() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("10000"));
        p.markForborne(Instant.now());

        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), Instant.now());

        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_2);
        assertThat(p.getProvisionAmount()).as("lifetime floor, not the performing band").isEqualByComparingTo("1200.00");
    }

    @Test
    void curingOutOfStage3_takesTheProbation_andStepsDownOneStageAtATime() {
        // 2 Nov E2E: a loan that caught up released its whole reserve the next day.
        Instant t0 = Instant.parse("2026-06-16T05:59:59Z");
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("10000"));
        p.syncDaysDelinquent(95);
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0);
        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_3);

        p.syncDaysDelinquent(0);
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0.plus(1, ChronoUnit.DAYS));
        assertThat(p.getIfrs9Stage()).as("current again, but on probation").isEqualTo(Ifrs9Stage.STAGE_3);
        assertThat(p.getProvisionAmount()).isEqualByComparingTo("5500.00");

        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0.plus(91, ChronoUnit.DAYS));
        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_2);
        assertThat(p.getProvisionAmount()).isEqualByComparingTo("1200.00");

        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0.plus(110, ChronoUnit.DAYS));
        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_2);
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0.plus(122, ChronoUnit.DAYS));
        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_1);
        assertThat(p.getProvisionAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void fallingBack_duringProbation_restartsIt() {
        Instant t0 = Instant.parse("2026-06-16T05:59:59Z");
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("10000"));
        p.syncDaysDelinquent(40);
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0);
        p.syncDaysDelinquent(0);
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0.plus(1, ChronoUnit.DAYS));
        p.syncDaysDelinquent(35);
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0.plus(10, ChronoUnit.DAYS));
        assertThat(p.getProbationSince()).isNull();
        p.syncDaysDelinquent(0);
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0.plus(20, ChronoUnit.DAYS));
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), t0.plus(45, ChronoUnit.DAYS));
        assertThat(p.getIfrs9Stage()).as("30 days counted from the second cure, not the first").isEqualTo(Ifrs9Stage.STAGE_2);
    }

    @Test
    void undrawnCredit_countsAtTheConversionFactor() {
        RiskProfile p = RiskProfile.create(UUID.randomUUID(), UUID.randomUUID(), "CREDIT_CARD");
        p.syncExposure(new BigDecimal("5000"), new BigDecimal("25000"));

        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0.20"), Instant.now());

        // (5,000 drawn + 20 % of 25,000 undrawn) × 2 % (Stage 1 floor)
        assertThat(p.getProvisionAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void settle_takesTheReserveToZero() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("1000"));
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), Instant.now());
        assertThat(p.getProvisionAmount()).isPositive();

        p.settle(Instant.now());

        assertThat(p.getProvisionAmount()).isEqualByComparingTo("0");
        assertThat(p.getStatus()).isEqualTo(RiskProfileStatus.CLOSED);
    }

    @Test
    void writeOff_closesWithNoReserveNorExposure() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("1000"));
        p.reassess(S2, S3, CURE, PROB3, PROB2, byStage("0"), Instant.now());
        assertThat(p.getProvisionAmount()).isPositive();

        p.writeOff(Instant.now());

        assertThat(p.getProvisionAmount()).isEqualByComparingTo("0");
        assertThat(p.getStatus()).isEqualTo(RiskProfileStatus.CLOSED);
        assertThat(p.getEad()).isEqualByComparingTo("0");
    }
}
