package com.fintech.configuration.application.port.out;

import com.fintech.configuration.domain.ConfigParameter;

public interface ConfigEventPublisher {
    void publishConfigurationUpdated(ConfigParameter parameter);
}
