package com.fintech.beneficiary.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Lo que beneficiary necesita de {@code product-catalog.product-activated}: el producto y su
 * política de KYC.
 *
 * <p>Se declara sólo eso y se ignora el resto —tasas, plazos, montos, opciones de pago— a
 * propósito. Este servicio no origina crédito: copiar aquí el resto de la configuración sería
 * mantener una segunda versión de algo que no usa, y que se desincroniza sin que nadie lo note.
 *
 * <p>{@code kyc} puede venir nulo: los productos publicados antes de F3b no lo traen. El
 * resolvedor cae entonces al default seguro, no a «sin KYC». Ver {@link com.fintech.beneficiary.domain.KycPolicy}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductActivatedPayload(
        String productCode,
        int productVersion,
        Capabilities capabilities) {

    /** De {@code Capabilities} sólo interesa el bloque de KYC; lo demás se ignora. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capabilities(KycBlock kyc) {}

    /** Réplica de {@code KycOptions} del catálogo. Un record propio y no un import: son servicios distintos. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KycBlock(
            boolean kycRequired,
            boolean amlRequired,
            boolean automationEnabled,
            String provider,
            boolean manualFallbackAllowed) {}
}
