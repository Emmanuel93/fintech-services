package com.fintech.identity.infrastructure.adapter.out.security;

import com.fintech.identity.application.port.out.TotpPort;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.stereotype.Component;

@Component
class TotpAdapter implements TotpPort {

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator(32);
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    private final CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);

    @Override
    public String generateSecret() {
        return secretGenerator.generate();
    }

    @Override
    public boolean verifyCode(String secret, String code, int windowSize) {
        ((DefaultCodeVerifier) verifier).setTimePeriod(30);
        ((DefaultCodeVerifier) verifier).setAllowedTimePeriodDiscrepancy(windowSize);
        return verifier.isValidCode(secret, code);
    }

    @Override
    public String buildQrUri(String secret, String issuer, String accountName) {
        return new QrData.Builder()
                .label(accountName)
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build()
                .getUri();
    }
}
