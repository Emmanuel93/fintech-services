package com.fintech.origination.infrastructure.adapter.out.stub;

import com.fintech.origination.application.port.out.SignatureValidator;
import org.springframework.stereotype.Component;

/** Stub — always approves. TODO: integrate with T1 identity-service e-signature endpoint. */
@Component
public class NoopSignatureValidator implements SignatureValidator {
    @Override
    public boolean isValid(String signatureMethod, String signatureProof) {
        return true;
    }
}
