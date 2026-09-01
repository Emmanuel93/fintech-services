package com.fintech.banking.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ninguna ruta cubre esta petición.
 *
 * <p>Es un fallo <b>ruidoso a propósito</b>. La alternativa —caer a una cuenta por defecto— es
 * exactamente el defecto que este servicio existe para corregir: un pago que sale por una cuenta
 * que nadie eligió y que nadie se entera de que eligió mal hasta la conciliación.
 */
public class NoPayoutRouteException extends RuntimeException {

    public NoPayoutRouteException(UUID companyId, PayoutRail rail, BigDecimal monto) {
        super("Sin ruta de pago para empresa=" + companyId + " rail=" + rail + " monto=" + monto);
    }
}
