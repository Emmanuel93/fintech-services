/**
 * D10 — Disbursement [Supporting]
 *
 * <p>Orquestación de pagos salientes (payouts) multi-rail y multi-empresa. Decide <em>qué</em> hay
 * que pagar, <em>a quién</em>, por <em>qué rail</em> y con <em>qué proveedor</em>; el cómo hablar
 * con el proveedor es de los conectores.
 *
 * <p><strong>El núcleo no conoce el dominio de crédito.</strong> {@code DisbursementOrder} no tiene
 * {@code creditAccountId} ni {@code obligorPartyId}: lleva {@code sourceSystem},
 * {@code sourceReference} (opaco) y {@code sourceMetadata} (JSONB), que se devuelven en eco para
 * que el emisor correlacione. Todo el vocabulario de crédito vive en tres listeners ACL de
 * {@code infrastructure.adapter.in.messaging}; bórralos y el servicio sigue funcionando con su API
 * REST de ingesta. Eso lo verifica {@code DisbursementDecouplingTest}, no un comentario.
 *
 * <p><strong>Servicio interno.</strong> No recibe tráfico de internet.
 *
 * <p>Schema DB: {@code disbursement}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.disbursement;
