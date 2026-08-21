package com.fintech.configuration.domain;

import com.fintech.shared.exception.DomainException;
import java.util.UUID;

public class InvalidConfigStateTransitionException extends DomainException {

    public InvalidConfigStateTransitionException(UUID id, ConfigParameterStatus from, ConfigParameterStatus to) {
        super("CONFIGURATION_INVALID_TRANSITION",
                "Cannot transition config parameter " + id + " from " + from + " to " + to);
    }
}
