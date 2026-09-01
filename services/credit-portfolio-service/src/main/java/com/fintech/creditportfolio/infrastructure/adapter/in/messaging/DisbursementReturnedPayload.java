package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.UUID;

/** Forma de {@code disbursement.returned}: el banco receptor devolvió el dinero. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisbursementReturnedPayload(UUID disbursementId,
                                          String sourceReference,
                                          Map<String, String> sourceMetadata,
                                          String returnReason) {}
