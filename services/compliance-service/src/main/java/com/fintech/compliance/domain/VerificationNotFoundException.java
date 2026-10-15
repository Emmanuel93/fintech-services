package com.fintech.compliance.domain;

import java.util.UUID;

public class VerificationNotFoundException extends RuntimeException {
    public VerificationNotFoundException(UUID verificationId) {
        super("No existe la verificación " + verificationId);
    }
}
