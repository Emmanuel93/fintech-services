package com.fintech.identity.infrastructure.adapter.in.api.dto;

import java.time.Instant;
import java.util.UUID;

public record WhitelistEntryResponse(UUID id, String cidr, String label, Instant createdAt) {}
