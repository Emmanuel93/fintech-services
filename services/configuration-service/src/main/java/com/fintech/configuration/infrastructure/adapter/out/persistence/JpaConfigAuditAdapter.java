package com.fintech.configuration.infrastructure.adapter.out.persistence;

import com.fintech.configuration.application.port.out.ConfigAuditRepository;
import com.fintech.configuration.domain.ConfigAuditTrail;
import org.springframework.stereotype.Repository;

@Repository
public class JpaConfigAuditAdapter implements ConfigAuditRepository {

    private final JpaConfigAuditRepository jpa;

    public JpaConfigAuditAdapter(JpaConfigAuditRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(ConfigAuditTrail entry) {
        jpa.save(entry);
    }
}
