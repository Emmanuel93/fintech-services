package com.fintech.notifications.application.service;

import com.fintech.notifications.application.CreateNotificationPolicyCommand;
import com.fintech.notifications.application.port.in.NotificationPolicyUseCase;
import com.fintech.notifications.application.port.out.NotificationPolicyRepository;
import com.fintech.notifications.domain.NotificationPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Una ACTIVE por eventType — mismo patrón versionado que CommissionPolicyService/ProvisionPolicy. */
@Service
@Transactional
public class NotificationPolicyService implements NotificationPolicyUseCase {

    private final NotificationPolicyRepository policyRepository;

    public NotificationPolicyService(NotificationPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @Override
    public NotificationPolicy create(CreateNotificationPolicyCommand cmd) {
        policyRepository.findActiveByEventType(cmd.eventType())
                .ifPresent(prior -> { prior.deprecate(); policyRepository.save(prior); });

        int nextVersion = policyRepository.maxVersionFor(cmd.eventType()) + 1;
        NotificationPolicy policy = NotificationPolicy.create(cmd.eventType(), cmd.valueTier(),
                cmd.channelStrategy(), cmd.primaryChannel(), cmd.fallbackChannels(), nextVersion);
        return policyRepository.save(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPolicy> listActive() {
        return policyRepository.findAllActive();
    }
}
