package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.UUID;

/**
 * Forma de {@code disbursement.returned}: el banco receptor devolvió el dinero. Disbursement names the cause
 * {@code returnCauseCode}: read under that name, it used to arrive null here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisbursementReturnedPayload(UUID disbursementId,
                                          String sourceSystem,
                                          String sourceType,
                                          String sourceReference,
                                          Map<String, String> sourceMetadata,
                                          @JsonAlias("returnCauseCode") String returnReason) {}
