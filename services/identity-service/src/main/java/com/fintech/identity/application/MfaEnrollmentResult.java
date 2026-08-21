package com.fintech.identity.application;

public record MfaEnrollmentResult(String totpSecret, String qrUri) {}
