package com.fintech.channelmobile.application;

import java.time.Instant;

record OtpEntry(String code, Instant expiresAt, int attempts) {

    boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    boolean isValid(String input) {
        return !isExpired() && code.equals(input);
    }

    OtpEntry incrementAttempts() {
        return new OtpEntry(code, expiresAt, attempts + 1);
    }
}
