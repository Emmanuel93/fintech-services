package com.fintech.identity.application;

import java.time.Instant;
import java.util.UUID;

public record WhitelistEntryResult(UUID id, String cidr, String label, Instant createdAt) {}
