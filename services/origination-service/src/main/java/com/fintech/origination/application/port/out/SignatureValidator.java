package com.fintech.origination.application.port.out;

/** ACL port — verifies a digital/biometric signature proof (T1 integration, Phase F stub). */
public interface SignatureValidator {
    boolean isValid(String signatureMethod, String signatureProof);
}
