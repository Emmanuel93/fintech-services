package com.fintech.configuration.application.port.out;

import com.fintech.configuration.domain.ConfigAuditTrail;

public interface ConfigAuditRepository {
    void save(ConfigAuditTrail entry);
}
