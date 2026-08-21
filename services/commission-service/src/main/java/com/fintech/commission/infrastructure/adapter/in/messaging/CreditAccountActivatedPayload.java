package com.fintech.commission.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/** Inbound {@code credit-portfolio.credit-account-activated} — trae promoterCode (prerequisito). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        UUID creditAccountId,
        String productType,
        String promoterCode
) {}
