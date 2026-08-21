package com.fintech.identity.application;

public sealed interface LoginResult {

    record TokensIssued(TokenPair tokens) implements LoginResult {}

    record MfaRequired(String mfaToken) implements LoginResult {}
}
