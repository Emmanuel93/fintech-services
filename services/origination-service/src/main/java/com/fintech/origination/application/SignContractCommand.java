package com.fintech.origination.application;

import java.util.UUID;

public record SignContractCommand(
        UUID applicationId,
        String clabeAccount,
        String signatureProof,
        String documentRef
) {}
