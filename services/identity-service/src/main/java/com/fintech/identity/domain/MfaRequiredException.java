package com.fintech.identity.domain;

public class MfaRequiredException extends RuntimeException {

    private final String mfaToken;

    public MfaRequiredException(String mfaToken) {
        super("MFA verification required");
        this.mfaToken = mfaToken;
    }

    public String getMfaToken() {
        return mfaToken;
    }
}
