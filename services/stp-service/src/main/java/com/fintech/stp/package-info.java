/**
 * D11 — STP Connector [Generic Subdomain / ACL]
 *
 * <p>Anti-corruption layer con el proveedor de pagos SPEI STP. Traduce órdenes de pago genéricas al
 * protocolo de STP: arma la cadena original, la firma con la llave de la empresa, registra la orden
 * y <strong>consulta activamente</strong> su liquidación.
 *
 * <p><strong>Servicio interno.</strong> No recibe tráfico de internet: STP nunca llama a la
 * plataforma. La única dirección hacia afuera es egress TLS iniciado por este servicio.
 *
 * <p><strong>No conoce el dominio de crédito ni el de desembolso.</strong> Consume una orden de pago
 * (topic configurable en {@code fintech.stp.inbound-topic}) y publica el resultado. Puede extraerse
 * a otro repositorio sin borrar un solo archivo — ver {@code StpDecouplingTest}.
 *
 * <p>Multi-empresa desde el día uno: cada empresa tiene su cuenta ordenante, su prefijo de clave de
 * rastreo y su llave de firma, custodiada con envelope encryption.
 *
 * <p>Schema DB: {@code stp}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.stp;
