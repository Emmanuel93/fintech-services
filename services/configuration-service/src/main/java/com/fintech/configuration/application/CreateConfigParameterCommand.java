package com.fintech.configuration.application;

import java.time.LocalDate;
import java.util.UUID;

public record CreateConfigParameterCommand(
        String paramKey,
        String value,
        String productType,
        String channelType,
        LocalDate effectiveDate,
        UUID createdBy
) {}
