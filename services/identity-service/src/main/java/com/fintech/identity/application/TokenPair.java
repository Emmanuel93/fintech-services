package com.fintech.identity.application;

public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}
