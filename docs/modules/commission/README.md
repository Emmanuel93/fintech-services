# T6 — Commission [Transversal]

**Estado:** ⬜ Pendiente (Módulo 15)  
**Tipo:** Transversal  
**Schema DB:** `commission`  
**Paquete Java:** `com.fintech.commission`

## Responsabilidad

Acumulación y liquidación de comisiones de la red comercial (promotores, distribuidores). **T6 acumula; T4 ejecuta el pago a CLABE.**

## Tipos de comisión

| Tipo | Trigger |
|---|---|
| `ORIGINATION_FEE` | D4 activación de producto |
| `DISPOSITION_FEE` | D4 disposición en DISTRIBUTOR_LINE |
| `COLLECTION_BONUS` | D6 pago recibido |
| `RENEWAL_BONUS` | Renovación de producto |

## Reglas

- Snapshot de tasa al momento del devengamiento → **inmutable** para cambios futuros de tasa
- Liquidación batch mensual con umbral mínimo (T5, default 100 MXN)
- `ProductSettled` antes de meses mínimos → reversión proporcional
- T6 acumula comisiones; T4 ejecuta transferencia a CLABE del promotor

## Eventos publicados

`CommissionAccrued` · `CommissionReversed` · `LiquidationBatchCompleted`

## Eventos consumidos

`CreditProductActivated` · `DispositionCompleted` · `PaymentApplied` · `ProductSettled`
