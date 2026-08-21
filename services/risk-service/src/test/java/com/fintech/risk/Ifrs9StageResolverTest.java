package com.fintech.risk;

import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.Ifrs9StageResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class Ifrs9StageResolverTest {

    private static final int STAGE2 = 30;
    private static final int STAGE3 = 90;

    @ParameterizedTest
    @CsvSource({
            "0,STAGE_1",     // CURRENT
            "15,STAGE_1",    // B1_30
            "29,STAGE_1",
            "30,STAGE_2",    // SICR backstop
            "45,STAGE_2",    // B31_60
            "89,STAGE_2",
            "90,STAGE_3",    // default presumption
            "150,STAGE_3",
            "400,STAGE_3"
    })
    void baseStage_mapsDaysToStage(int days, Ifrs9Stage expected) {
        assertThat(Ifrs9StageResolver.baseStage(days, STAGE2, STAGE3)).isEqualTo(expected);
    }

    @Test
    void targetStage_forbearanceFloorsAtStage2_evenWhenCurrent() {
        // days=0 → base STAGE_1, but in cure window → floored to STAGE_2 (RC-04)
        Ifrs9Stage target = Ifrs9StageResolver.targetStage(Ifrs9Stage.STAGE_1, Ifrs9Stage.STAGE_2, true);
        assertThat(target).isEqualTo(Ifrs9Stage.STAGE_2);
    }

    @Test
    void targetStage_stage3IsStickyDownward_neverStraightToStage1() {
        // base STAGE_1 (days low) but currently STAGE_3 and not in cure → can only step down to STAGE_2 (RC-05)
        Ifrs9Stage target = Ifrs9StageResolver.targetStage(Ifrs9Stage.STAGE_1, Ifrs9Stage.STAGE_3, false);
        assertThat(target).isEqualTo(Ifrs9Stage.STAGE_2);
    }

    @Test
    void targetStage_noFloors_returnsBase() {
        Ifrs9Stage target = Ifrs9StageResolver.targetStage(Ifrs9Stage.STAGE_1, Ifrs9Stage.STAGE_1, false);
        assertThat(target).isEqualTo(Ifrs9Stage.STAGE_1);
    }

    @Test
    void targetStage_worseBaseWins_overStage2Floor() {
        // base STAGE_3 dominates the STAGE_2 forbearance floor
        Ifrs9Stage target = Ifrs9StageResolver.targetStage(Ifrs9Stage.STAGE_3, Ifrs9Stage.STAGE_2, true);
        assertThat(target).isEqualTo(Ifrs9Stage.STAGE_3);
    }
}
