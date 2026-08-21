package com.fintech.commission;

import com.fintech.commission.application.CreateCommissionPolicyCommand;
import com.fintech.commission.application.port.out.CommissionPolicyRepository;
import com.fintech.commission.application.service.CommissionPolicyService;
import com.fintech.commission.domain.CommissionPolicy;
import com.fintech.commission.domain.CommissionType;
import com.fintech.commission.domain.PolicyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CommissionPolicyServiceTest {

    @Mock CommissionPolicyRepository repository;

    CommissionPolicyService service;

    @BeforeEach
    void setUp() {
        service = new CommissionPolicyService(repository);
    }

    @Test
    void create_defaultRate_firstVersion() {
        given(repository.findActiveDefaultByProductAndType("DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE))
                .willReturn(Optional.empty());
        given(repository.maxVersionFor("DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE, null)).willReturn(0);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CommissionPolicy result = service.create(new CreateCommissionPolicyCommand(
                "DISTRIBUTOR_LINE", null, CommissionType.DISTRIBUTOR_INTEREST_SHARE, new BigDecimal("0.30")));

        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(result.getDistributorPartyId()).isNull();
    }

    @Test
    void create_deprecatesPriorActive_andBumpsVersion() {
        CommissionPolicy prior = CommissionPolicy.create("DISTRIBUTOR_LINE", null,
                CommissionType.DISTRIBUTOR_INTEREST_SHARE, new BigDecimal("0.25"), 1);
        given(repository.findActiveDefaultByProductAndType("DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE))
                .willReturn(Optional.of(prior));
        given(repository.maxVersionFor("DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE, null)).willReturn(1);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CommissionPolicy result = service.create(new CreateCommissionPolicyCommand(
                "DISTRIBUTOR_LINE", null, CommissionType.DISTRIBUTOR_INTEREST_SHARE, new BigDecimal("0.35")));

        assertThat(prior.getStatus()).isEqualTo(PolicyStatus.DEPRECATED);
        assertThat(result.getVersion()).isEqualTo(2);
        then(repository).should(times(2)).save(any());
    }

    @Test
    void create_distributorOverride_doesNotTouchDefaultPolicy() {
        UUID distributorPartyId = UUID.randomUUID();
        given(repository.findActiveByProductAndTypeAndDistributor(
                "DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE, distributorPartyId))
                .willReturn(Optional.empty());
        given(repository.maxVersionFor("DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE, distributorPartyId))
                .willReturn(0);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CommissionPolicy result = service.create(new CreateCommissionPolicyCommand(
                "DISTRIBUTOR_LINE", distributorPartyId, CommissionType.DISTRIBUTOR_INTEREST_SHARE, new BigDecimal("0.45")));

        assertThat(result.getDistributorPartyId()).isEqualTo(distributorPartyId);
        then(repository).should(never()).findActiveDefaultByProductAndType(any(), any());
    }
}
