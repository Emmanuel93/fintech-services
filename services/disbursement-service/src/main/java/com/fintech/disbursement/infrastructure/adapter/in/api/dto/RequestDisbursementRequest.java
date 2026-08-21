package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Alta directa de un pago por API.
 *
 * <p>Es la puerta que <strong>no</strong> es Kafka (DC-6): un comprador sin nuestra infraestructura
 * de eventos puede usar el servicio con sólo esto. La clave de idempotencia va en la cabecera
 * {@code Idempotency-Key}, no en el cuerpo.
 */
public record RequestDisbursementRequest(

        @NotNull(message = "companyId es obligatorio")
        UUID companyId,

        /** Etiqueta libre de procedencia; se devuelve en eco. */
        @Size(max = 60)
        String sourceReference,

        Map<String, String> sourceMetadata,

        @NotBlank(message = "El nombre del beneficiario es obligatorio")
        @Size(max = 150)
        String beneficiaryName,

        @NotBlank(message = "La cuenta del beneficiario es obligatoria")
        @Size(max = 20)
        String beneficiaryAccount,

        /** "40" CLABE · "3" tarjeta · "10" celular. Por omisión, "40". */
        @Size(max = 4)
        String beneficiaryAccountType,

        @Size(max = 18)
        String beneficiaryTaxId,

        Integer beneficiaryInstitution,

        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "0.01", message = "El monto debe ser positivo")
        BigDecimal amount,

        @Size(max = 3)
        String currency,

        @Size(max = 40)
        String concept,

        Long numericReference,

        /** SPEI · CODI · INTERNAL. Por omisión, SPEI. */
        String rail,

        @Size(max = 64)
        String correlationId
) {}
