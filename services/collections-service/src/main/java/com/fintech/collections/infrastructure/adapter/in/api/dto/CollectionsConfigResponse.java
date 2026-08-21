package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.application.CollectionsProperties;

import java.math.BigDecimal;

/**
 * Los límites con los que el dominio valida, para que la interfaz deshabilite en vez de dejar
 * intentar. Es la misma instancia de {@link CollectionsProperties} que aplican las reglas, así que
 * no puede desincronizarse de ellas.
 */
public record CollectionsConfigResponse(
        int contactAllowedHoursStart,
        int contactAllowedHoursEnd,
        int maxContactAttemptsPerDay,
        BigDecimal maxForgivenessPct,
        int maxTermExtensionMonths,
        int writeOffThresholdDays,
        int agreementResponseDays
) {
    public static CollectionsConfigResponse from(CollectionsProperties p) {
        return new CollectionsConfigResponse(
                p.getContactAllowedHoursStart(),
                p.getContactAllowedHoursEnd(),
                p.getMaxContactAttemptsPerDay(),
                p.getMaxForgivenessPct(),
                p.getMaxTermExtensionMonths(),
                p.getWriteOffThresholdDays(),
                p.getAgreementResponseDays());
    }
}
