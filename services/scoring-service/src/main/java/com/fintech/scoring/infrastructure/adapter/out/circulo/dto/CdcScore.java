package com.fintech.scoring.infrastructure.adapter.out.circulo.dto;

import java.util.List;

public record CdcScore(
        String nombreScore,
        Integer valor,
        List<String> razones
) {}
