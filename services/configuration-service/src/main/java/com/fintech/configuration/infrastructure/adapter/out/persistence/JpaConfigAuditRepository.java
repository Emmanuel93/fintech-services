package com.fintech.configuration.infrastructure.adapter.out.persistence;

import com.fintech.configuration.domain.ConfigAuditTrail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaConfigAuditRepository extends JpaRepository<ConfigAuditTrail, UUID> {
}
