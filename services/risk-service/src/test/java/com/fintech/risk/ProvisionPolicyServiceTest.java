package com.fintech.risk;

import com.fintech.risk.application.CreateProvisionPolicyCommand;
import com.fintech.risk.application.port.out.ProvisionPolicyRepository;
import com.fintech.risk.application.service.ProvisionPolicyService;
import com.fintech.risk.domain.DelinquencyBucket;
import com.fintech.risk.domain.InvalidPolicyStateException;
import com.fintech.risk.domain.PolicyStatus;
import com.fintech.risk.domain.ProvisionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ProvisionPolicyServiceTest {

    @Mock ProvisionPolicyRepository repository;

    ProvisionPolicyService service;

    @BeforeEach
    void setUp() {
        service = new ProvisionPolicyService(repository);
    }

    private static Map<DelinquencyBucket, BigDecimal> fullRates() {
        Map<DelinquencyBucket, BigDecimal> m = new EnumMap<>(DelinquencyBucket.class);
        m.put(DelinquencyBucket.CURRENT,   new BigDecimal("0.01"));
        m.put(DelinquencyBucket.B1_30,     new BigDecimal("0.03"));
        m.put(DelinquencyBucket.B31_60,    new BigDecimal("0.15"));
        m.put(DelinquencyBucket.B61_90,    new BigDecimal("0.30"));
        m.put(DelinquencyBucket.B91_120,   new BigDecimal("0.60"));
        m.put(DelinquencyBucket.B121_180,  new BigDecimal("0.80"));
        m.put(DelinquencyBucket.B181_PLUS, new BigDecimal("1.00"));
        return m;
    }

    @Test
    void create_firstVersion_isActiveVersion1() {
        given(repository.findActiveByProductType("PERSONAL_LOAN")).willReturn(Optional.empty());
        given(repository.maxVersionForProductType("PERSONAL_LOAN")).willReturn(0);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ProvisionPolicy result = service.create(new CreateProvisionPolicyCommand("PERSONAL_LOAN", fullRates()));

        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(result.rateFor(DelinquencyBucket.B31_60)).isEqualByComparingTo("0.15");
    }

    @Test
    void create_deprecatesPriorActive_andBumpsVersion() {
        ProvisionPolicy prior = ProvisionPolicy.create("PERSONAL_LOAN", 1, fullRates());
        given(repository.findActiveByProductType("PERSONAL_LOAN")).willReturn(Optional.of(prior));
        given(repository.maxVersionForProductType("PERSONAL_LOAN")).willReturn(1);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ProvisionPolicy result = service.create(new CreateProvisionPolicyCommand("PERSONAL_LOAN", fullRates()));

        assertThat(prior.getStatus()).isEqualTo(PolicyStatus.DEPRECATED);
        assertThat(result.getVersion()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo(PolicyStatus.ACTIVE);

        // both the deprecated prior and the new active are persisted
        ArgumentCaptor<ProvisionPolicy> captor = ArgumentCaptor.forClass(ProvisionPolicy.class);
        then(repository).should(times(2)).save(captor.capture());
    }

    @Test
    void create_missingBucket_isRejected_PP02() {
        Map<DelinquencyBucket, BigDecimal> incomplete = new HashMap<>(fullRates());
        incomplete.remove(DelinquencyBucket.B181_PLUS);
        given(repository.findActiveByProductType("PERSONAL_LOAN")).willReturn(Optional.empty());
        given(repository.maxVersionForProductType("PERSONAL_LOAN")).willReturn(0);

        assertThatThrownBy(() -> service.create(new CreateProvisionPolicyCommand("PERSONAL_LOAN", incomplete)))
                .isInstanceOf(InvalidPolicyStateException.class);
    }
}
