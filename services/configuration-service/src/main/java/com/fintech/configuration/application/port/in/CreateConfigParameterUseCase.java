package com.fintech.configuration.application.port.in;

import com.fintech.configuration.application.CreateConfigParameterCommand;
import com.fintech.configuration.domain.ConfigParameter;

public interface CreateConfigParameterUseCase {
    /** Maker creates a new parameter version in PENDING_APPROVAL status. */
    ConfigParameter create(CreateConfigParameterCommand command);
}
