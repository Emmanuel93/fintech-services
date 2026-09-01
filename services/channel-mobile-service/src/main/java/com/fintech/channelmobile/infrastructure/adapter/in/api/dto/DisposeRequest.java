package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.Positive;

/**
 * Disposición de crédito desde la app.
 *
 * <p><b>Ya no lleva {@code dispositionType}, y quitarlo es el punto.</b> El tipo lo decide el
 * <b>producto</b> desde sus {@code Capabilities} (BK-13). Mientras viajó en la petición, una
 * solicitud sobre una línea de distribuidor que mandara {@code "SELF_USE"} —o un valor basura, que
 * caía al mismo default silencioso— acreditaba el dinero a la distribuidora en vez de mandarlo a la
 * beneficiaria.
 *
 * <p>Cartera dejó de leerlo, pero este DTO seguía ofreciéndolo: <b>un control que la API anuncia y
 * que no hace nada es peor que no tenerlo</b>, porque quien lo use va a creer que decide adónde va
 * su dinero.
 *
 * <p>{@code beneficiaryPartyId} sí se queda: en una línea de distribuidor hay que decir <b>a quién</b>
 * se le coloca, y eso no lo sabe el producto.
 */
public record DisposeRequest(
        @Positive double amount,
        String beneficiaryPartyId,
        /**
         * A cuántos pagos se coloca. Una línea revolvente no tiene plazo; lo tiene cada colocación.
         *
         * <p>Sólo lo usa un producto que genera el plan <b>al disponer</b> (distribuidor). En una
         * tarjeta la compra nace revolvente pura y el plazo se elige <b>después</b>, al diferir.
         */
        Integer termPeriods
) {}
