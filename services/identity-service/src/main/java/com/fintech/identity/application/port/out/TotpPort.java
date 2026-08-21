package com.fintech.identity.application.port.out;

public interface TotpPort {

    String generateSecret();

    boolean verifyCode(String secret, String code, int windowSize);

    String buildQrUri(String secret, String issuer, String accountName);
}
