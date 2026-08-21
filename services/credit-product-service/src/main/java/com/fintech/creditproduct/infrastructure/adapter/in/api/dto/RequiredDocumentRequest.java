package com.fintech.creditproduct.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RequiredDocumentRequest(
        @NotBlank @Size(max = 50) String documentType,
        boolean mandatory
) {}
