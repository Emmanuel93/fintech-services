# T4 — Accounting / GL [Transversal]

**Estado:** ⬜ Pendiente (Módulo 14)  
**Tipo:** Transversal  
**Schema DB:** `accounting`  
**Paquete Java:** `com.fintech.accounting`

## Responsabilidad

Libro mayor. Traduce eventos financieros a asientos contables de partida doble. **Nunca modifica estado de otros dominios.**

## Regla clave: WAIVED ≠ REVERSED

- **Waived**: condonación = **gasto (P&L)** — impacta utilidades
- **Reversed**: corrección técnica = cancelación de ingreso — no impacta P&L

## Verdad operativa vs verdad contable

La verdad operativa (CreditProduct) y la verdad contable (GL) son sistemas separados. Reconciliación diaria detecta deltas y genera alertas.

## Eventos que generan asiento

`CreditProductActivated` · `DispositionCompleted` · `OrdinaryInterestAccrued` · `MoratoriumInterestCharged` · `*FeeCharged` · `ChargeWaived` · `ChargeReversed` · `PaymentApplied` · `WriteOffExecuted` · `RecoveryPaymentApplied`

## Jobs

| Job | Hora | Acción |
|---|---|---|
| `ReconciliationJob` | 02:00 | GL balance == CreditProduct balance (alerta en delta) |
