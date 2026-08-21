package com.fintech.risk;

import com.fintech.risk.application.RiskProperties;
import com.fintech.risk.application.port.out.ProvisionPolicyRepository;
import com.fintech.risk.application.port.out.RiskEventPublisher;
import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.application.service.RiskProfileAssessor;
import com.fintech.risk.domain.DelinquencyBucket;
import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.MissingProvisionPolicyException;
import com.fintech.risk.domain.ProvisionPolicy;
import com.fintech.risk.domain.RiskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class RiskProfileAssessorTest {

    @Mock RiskProfileRepository profileRepository;
    @Mock ProvisionPolicyRepository policyRepository;
    @Mock RiskEventPublisher eventPublisher;

    RiskProfileAssessor assessor;
    final RiskProperties properties = new RiskProperties(); // 30 / 90 / 6

    private final UUID creditAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assessor = new RiskProfileAssessor(profileRepository, policyRepository, eventPublisher, properties);
    }

    private static ProvisionPolicy policy() {
        Map<DelinquencyBucket, BigDecimal> m = new EnumMap<>(DelinquencyBucket.class);
        m.put(DelinquencyBucket.CURRENT,   new BigDecimal("0.01"));
        m.put(DelinquencyBucket.B1_30,     new BigDecimal("0.03"));
        m.put(DelinquencyBucket.B31_60,    new BigDecimal("0.15"));
        m.put(DelinquencyBucket.B61_90,    new BigDecimal("0.30"));
        m.put(DelinquencyBucket.B91_120,   new BigDecimal("0.60"));
        m.put(DelinquencyBucket.B121_180,  new BigDecimal("0.80"));
        m.put(DelinquencyBucket.B181_PLUS, new BigDecimal("1.00"));
        return ProvisionPolicy.create("PERSONAL_LOAN", 1, m);
    }

    private RiskProfile delinquentProfile(int days, String debt) {
        RiskProfile p = RiskProfile.create(creditAccountId, UUID.randomUUID(), "PERSONAL_LOAN");
        p.syncEad(new BigDecimal(debt));
        p.syncDaysDelinquent(days);
        return p;
    }

    @Test
    void assess_appliesRate_savesAndPublishes() {
        RiskProfile profile = delinquentProfile(45, "1000");
        given(profileRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(profile));
        given(policyRepository.findActiveByProductType("PERSONAL_LOAN")).willReturn(Optional.of(policy()));
        given(profileRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        assessor.assess(creditAccountId, Instant.now());

        assertThat(profile.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_2);
        assertThat(profile.getProvisionAmount()).isEqualByComparingTo("150.00"); // 1000 * 0.15 (B31_60)
        then(profileRepository).should().save(profile);
        then(eventPublisher).should().publishRiskAssessmentUpdated(eq(profile), eq(true));
    }

    @Test
    void assess_noActivePolicy_throwsPR03() {
        RiskProfile profile = delinquentProfile(45, "1000");
        given(profileRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(profile));
        given(policyRepository.findActiveByProductType("PERSONAL_LOAN")).willReturn(Optional.empty());

        assertThatThrownBy(() -> assessor.assess(creditAccountId, Instant.now()))
                .isInstanceOf(MissingProvisionPolicyException.class);
        then(eventPublisher).shouldHaveNoInteractions();
        then(profileRepository).should(never()).save(any());
    }

    @Test
    void assess_closedProfile_isNoOp() {
        RiskProfile profile = delinquentProfile(200, "1000");
        profile.close();
        given(profileRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(profile));

        assessor.assess(creditAccountId, Instant.now());

        then(policyRepository).shouldHaveNoInteractions();
        then(profileRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }
}
