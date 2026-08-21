package com.fintech.configuration.infrastructure.adapter.out.persistence;

import com.fintech.configuration.domain.ConfigParameter;
import com.fintech.configuration.domain.ConfigParameterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaConfigParameterRepository extends JpaRepository<ConfigParameter, UUID> {

    Optional<ConfigParameter> findByParamKeyAndStatus(String paramKey, ConfigParameterStatus status);

    List<ConfigParameter> findAllByParamKeyOrderByVersionDesc(String paramKey);

    @Query("SELECT COALESCE(MAX(c.version), 0) FROM ConfigParameter c WHERE c.paramKey = :paramKey")
    int findMaxVersionByParamKey(String paramKey);
}
