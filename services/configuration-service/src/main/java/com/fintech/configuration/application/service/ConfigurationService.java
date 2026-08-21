package com.fintech.configuration.application.service;

import com.fintech.configuration.application.ConfigCacheNames;
import com.fintech.configuration.application.CreateConfigParameterCommand;
import com.fintech.configuration.application.port.in.*;
import com.fintech.configuration.application.port.out.*;
import com.fintech.configuration.domain.*;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ConfigurationService implements
        GetConfigParameterUseCase,
        CreateConfigParameterUseCase,
        ApproveConfigParameterUseCase,
        GetConfigHistoryUseCase {

    private final ConfigParameterRepository parameterRepository;
    private final ConfigAuditRepository auditRepository;
    private final ConfigEventPublisher eventPublisher;

    public ConfigurationService(ConfigParameterRepository parameterRepository,
                                 ConfigAuditRepository auditRepository,
                                 ConfigEventPublisher eventPublisher) {
        this.parameterRepository = parameterRepository;
        this.auditRepository = auditRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = ConfigCacheNames.CONFIG_PARAMS, key = "#paramKey + ':' + #productType + ':' + #channelType")
    public Optional<ConfigParameter> getActive(String paramKey, String productType, String channelType) {
        return parameterRepository.findByParamKeyAndStatus(paramKey, ConfigParameterStatus.ACTIVE);
    }

    @Override
    public ConfigParameter create(CreateConfigParameterCommand cmd) {
        int nextVersion = parameterRepository.findMaxVersionByParamKey(cmd.paramKey()) + 1;
        ConfigParameter param = ConfigParameter.create(
                cmd.paramKey(), cmd.value(),
                cmd.productType(), cmd.channelType(),
                cmd.effectiveDate(), cmd.createdBy(),
                null, nextVersion);
        parameterRepository.save(param);
        auditRepository.save(ConfigAuditTrail.record(
                param.getId(), ConfigAuditAction.CREATED, cmd.createdBy(), null, cmd.value()));
        return param;
    }

    @Override
    @CacheEvict(value = ConfigCacheNames.CONFIG_PARAMS, allEntries = true)
    public ConfigParameter approve(UUID parameterId, UUID approverId) {
        ConfigParameter param = parameterRepository.findById(parameterId)
                .orElseThrow(() -> new ConfigParameterNotFoundException(parameterId));

        // Deprecate any currently ACTIVE version of the same key (CF-02)
        parameterRepository.findByParamKeyAndStatus(param.getParamKey(), ConfigParameterStatus.ACTIVE)
                .ifPresent(active -> {
                    active.deprecate();
                    parameterRepository.save(active);
                    auditRepository.save(ConfigAuditTrail.record(
                            active.getId(), ConfigAuditAction.DEPRECATED, approverId,
                            active.getValue(), null));
                });

        String oldValue = param.getValue();
        param.approve(approverId);
        parameterRepository.save(param);
        auditRepository.save(ConfigAuditTrail.record(
                param.getId(), ConfigAuditAction.APPROVED, approverId, oldValue, param.getValue()));

        // Notify other services via Kafka (CF-03)
        eventPublisher.publishConfigurationUpdated(param);
        return param;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfigParameter> getHistory(String paramKey) {
        return parameterRepository.findAllByParamKeyOrderByVersionDesc(paramKey);
    }
}
