package com.fintech.configuration.domain;

import com.fintech.shared.exception.DomainException;

public class ConfigParameterNotFoundException extends DomainException {

    public ConfigParameterNotFoundException(String key) {
        super("CONFIGURATION_NOT_FOUND", "Active config parameter not found for key: " + key);
    }

    public ConfigParameterNotFoundException(java.util.UUID id) {
        super("CONFIGURATION_NOT_FOUND", "Config parameter not found: " + id);
    }
}
