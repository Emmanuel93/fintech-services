package com.fintech.risk;

import com.fintech.risk.domain.DelinquencyBucket;
import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskProfileTest {

    private static final int S2 = 30, S3 = 90, CURE = 6;

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

        boolean stageChanged = p.reassess(S2, S3, CURE, new BigDecimal("0.15"), Instant.now());

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

        p.reassess(S2, S3, CURE, new BigDecimal("0.60"), Instant.now());

        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_3);
        assertThat(p.getProvisionAmount()).isEqualByComparingTo("1200.00");
    }

    @Test
    void forbearance_floorsStage2_whileInCureWindow_evenIfCurrent() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("1000"));
        p.syncDaysDelinquent(0);          // no longer delinquent
        p.markForborne(Instant.now());     // restructure just executed

        boolean changed = p.reassess(S2, S3, CURE, new BigDecimal("0.15"), Instant.now());

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

        p.reassess(S2, S3, CURE, new BigDecimal("0.01"), Instant.now());

        assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_1); // can drop back now
        assertThat(p.isForborne()).isFalse();
    }

    @Test
    void close_freezesProvision_andIgnoresFurtherSync() {
        RiskProfile p = newProfile();
        p.syncEad(new BigDecimal("1000"));
        p.syncDaysDelinquent(200);
        p.reassess(S2, S3, CURE, new BigDecimal("1.00"), Instant.now());
        BigDecimal frozen = p.getProvisionAmount();

        p.close();
        p.syncEad(new BigDecimal("9999"));       // ignored — closed
        p.syncDaysDelinquent(0);                 // ignored — closed
        boolean changed = p.reassess(S2, S3, CURE, new BigDecimal("0.01"), Instant.now());

        assertThat(p.getStatus()).isEqualTo(RiskProfileStatus.CLOSED);
        assertThat(changed).isFalse();
        assertThat(p.getProvisionAmount()).isEqualByComparingTo(frozen);
        assertThat(p.getEad()).isEqualByComparingTo("1000");
    }
}
