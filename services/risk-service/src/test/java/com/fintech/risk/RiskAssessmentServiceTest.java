package com.fintech.risk;

import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.application.service.RiskAssessmentService;
import com.fintech.risk.application.service.RiskProfileAssessor;
import com.fintech.risk.domain.MissingProvisionPolicyException;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentServiceTest {

    @Mock RiskProfileRepository profileRepository;
    @Mock RiskProfileAssessor assessor;

    RiskAssessmentService service;

    @BeforeEach
    void setUp() {
        service = new RiskAssessmentService(profileRepository, assessor);
    }

    private RiskProfile profile() {
        return RiskProfile.create(UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN");
    }

    @Test
    void reassessAll_countsAndIsolatesFailures() {
        RiskProfile ok = profile();
        RiskProfile noPolicy = profile();
        RiskProfile boom = profile();
        given(profileRepository.findByStatus(RiskProfileStatus.ACTIVE))
                .willReturn(List.of(ok, noPolicy, boom));

        // PR-03: one account has no ACTIVE policy → skipped, not failed, not aborting the batch.
        // lenient() because the OK account's assess() call matches neither throw-stub (strict stubs
        // would otherwise raise PotentialStubbingProblem on that unstubbed-args call).
        lenient().doThrow(new MissingProvisionPolicyException("PERSONAL_LOAN"))
                .when(assessor).assess(eq(noPolicy.getCreditAccountId()), any());
        // an unexpected error on one account is isolated → counted as failed, batch continues
        lenient().doThrow(new RuntimeException("db blip"))
                .when(assessor).assess(eq(boom.getCreditAccountId()), any());

        RiskAssessmentService.Result result = service.reassessAll();

        assertThat(result.totalActive()).isEqualTo(3);
        assertThat(result.assessed()).isEqualTo(1);
        assertThat(result.skippedNoPolicy()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        then(assessor).should(times(3)).assess(any(), any());
    }

    @Test
    void reassessAll_allSucceed() {
        RiskProfile a = profile();
        RiskProfile b = profile();
        given(profileRepository.findByStatus(RiskProfileStatus.ACTIVE)).willReturn(List.of(a, b));

        RiskAssessmentService.Result result = service.reassessAll();

        assertThat(result.assessed()).isEqualTo(2);
        assertThat(result.skippedNoPolicy()).isZero();
        assertThat(result.failed()).isZero();
    }
}
