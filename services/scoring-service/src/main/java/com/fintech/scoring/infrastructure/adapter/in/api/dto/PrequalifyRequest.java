package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import java.util.List;

public record PrequalifyRequest(List<String> productTypes) {}
