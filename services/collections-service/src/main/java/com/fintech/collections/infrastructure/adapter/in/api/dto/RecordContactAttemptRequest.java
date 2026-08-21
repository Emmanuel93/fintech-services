package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.ContactChannel;
import com.fintech.collections.domain.ContactResult;
import jakarta.validation.constraints.NotNull;

/**
 * El canal es un enum y ya no texto libre: un valor desconocido se rechaza con 400 en vez de
 * guardarse. Cuando era libre, el mismo hecho entraba como «PHONE», «phone» y «Teléfono» según qué
 * cliente lo mandara, y el reporte de gestión dejaba de poder agruparse.
 */
public record RecordContactAttemptRequest(
        @NotNull ContactChannel channel,
        @NotNull ContactResult result
) {}
