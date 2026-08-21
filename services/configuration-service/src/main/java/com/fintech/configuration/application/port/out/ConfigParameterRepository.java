package com.fintech.configuration.application.port.out;

import com.fintech.configuration.domain.ConfigParameter;
import com.fintech.configuration.domain.ConfigParameterStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConfigParameterRepository {
    ConfigParameter save(ConfigParameter parameter);
    Optional<ConfigParameter> findById(UUID id);
    Optional<ConfigParameter> findByParamKeyAndStatus(String paramKey, ConfigParameterStatus status);
    List<ConfigParameter> findAllByParamKeyOrderByVersionDesc(String paramKey);
    /** Returns the current max version for the given key (0 if none). */
    int findMaxVersionByParamKey(String paramKey);
}
