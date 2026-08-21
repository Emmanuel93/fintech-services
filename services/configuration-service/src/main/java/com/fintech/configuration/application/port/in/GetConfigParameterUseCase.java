package com.fintech.configuration.application.port.in;

import com.fintech.configuration.domain.ConfigParameter;
import java.util.Optional;

public interface GetConfigParameterUseCase {
    /** Returns the ACTIVE parameter for the given key (and optional segment). */
    Optional<ConfigParameter> getActive(String paramKey, String productType, String channelType);
}
