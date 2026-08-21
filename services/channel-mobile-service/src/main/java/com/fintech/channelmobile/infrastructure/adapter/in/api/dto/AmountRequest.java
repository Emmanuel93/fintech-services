package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.Positive;

/** Body compartido por /credit/payment y /credit/dispose — ambos solo mandan {amount}. */
public record AmountRequest(@Positive double amount) {}
