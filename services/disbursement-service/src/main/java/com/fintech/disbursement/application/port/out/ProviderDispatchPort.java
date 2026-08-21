package com.fintech.disbursement.application.port.out;

import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.Provider;

/**
 * Salida hacia el conector del proveedor.
 *
 * <p>El núcleo no sabe si detrás hay Kafka, REST o un archivo. Añadir un proveedor es implementar
 * esto (o configurar otro topic) — no tocar el dominio. Es el punto que sostiene DC-7.
 *
 * <p>Bloquea hasta confirmar la entrega y lanza si no pudo entregar: el llamador decide si reintenta.
 */
public interface ProviderDispatchPort {

    void dispatch(DisbursementOrder order, Provider provider);
}
