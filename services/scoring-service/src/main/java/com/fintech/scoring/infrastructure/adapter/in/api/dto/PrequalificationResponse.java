package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import com.fintech.scoring.domain.PrequalificationItem;
import com.fintech.scoring.domain.PrequalificationSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PrequalificationResponse(
        UUID prospectId,
        Instant computedAt,
        List<PrequalificationItem> results
) {
    public static PrequalificationResponse from(PrequalificationSnapshot s) {
        return new PrequalificationResponse(s.getProspectId(), s.getComputedAt(), s.getResults());
    }
}
