/**
 * T4 — Accounting / GL [Transversal]
 *
 * <p>Libro mayor del core crediticio. Consume eventos financieros y genera asientos contables
 * dobles (doble partida) según un catálogo de cuentas configurable. <strong>Fuente de verdad
 * contable</strong> (≠ credit-portfolio, fuente operacional). Nunca modifica saldos en otros
 * dominios — traduce eventos económicos a asientos.
 *
 * <p>Asiento a <strong>nivel préstamo</strong> (auxiliar por {@code creditAccountId}/{@code
 * obligorPartyId}), agregado al mayor por cuenta contable — cumple CNBV (R04-C) e IFRS-9. El monto
 * de cada asiento se deriva del <strong>delta</strong> de saldos (Accounting mantiene un shadow por
 * cuenta) porque {@code balance-updated} trae saldos nuevos, no el monto de la transacción.
 *
 * <p>Provisión IFRS-9 (EPR/ECL): se asienta por delta contra {@code ProvisionLedger} (GL-09);
 * quebranto/quita consumen la reserva ya provisionada antes de golpear P&amp;L (GL-10).
 *
 * <p>Reconocimiento de ingresos: intereses y comisiones se acumulan como {@code InvoiceableItem} y
 * un job mensual consolida <strong>un CFDI por party/período</strong>, emitiendo {@code
 * accounting.invoice-requested} al servicio de Facturación (que timbra vía PAC — stub por ahora).
 *
 * <p>{@code WAIVED} ≠ {@code REVERSED}: condonación = gasto P&amp;L; reversión = cancelación ingreso.
 *
 * <p>Schema DB: {@code accounting}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.accounting;
