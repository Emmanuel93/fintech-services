package com.fintech.origination.application;

import java.util.UUID;

public record GenerateContractCommand(
        UUID applicationId,
        String signatureMethod
) {}
