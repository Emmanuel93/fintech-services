package com.fintech.configuration.domain;

import com.fintech.shared.exception.DomainException;

public class DuplicateActiveParameterException extends DomainException {

    public DuplicateActiveParameterException(String key, String productType, String channelType) {
        super("CONFIGURATION_DUPLICATE_ACTIVE",
                "An active config parameter already exists for key=" + key
                + " productType=" + productType + " channelType=" + channelType
                + ". Deprecate the existing one before creating a new version.");
    }
}
