package com.fintech.configuration.infrastructure.adapter.out.persistence;

import com.fintech.configuration.application.port.out.ConfigParameterRepository;
import com.fintech.configuration.domain.ConfigParameter;
import com.fintech.configuration.domain.ConfigParameterStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaConfigParameterAdapter implements ConfigParameterRepository {

    private final JpaConfigParameterRepository jpa;

    public JpaConfigParameterAdapter(JpaConfigParameterRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ConfigParameter save(ConfigParameter parameter) {
        return jpa.save(parameter);
    }

    @Override
    public Optional<ConfigParameter> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public Optional<ConfigParameter> findByParamKeyAndStatus(String paramKey, ConfigParameterStatus status) {
        return jpa.findByParamKeyAndStatus(paramKey, status);
    }

    @Override
    public List<ConfigParameter> findAllByParamKeyOrderByVersionDesc(String paramKey) {
        return jpa.findAllByParamKeyOrderByVersionDesc(paramKey);
    }

    @Override
    public int findMaxVersionByParamKey(String paramKey) {
        return jpa.findMaxVersionByParamKey(paramKey);
    }
}
