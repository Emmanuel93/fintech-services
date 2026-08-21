# D4★ — Credit Portfolio [Core Domain ★ — EL CORAZÓN]

**Estado:** 🔄 En progreso — Fases 0/1/2 completas; jobs de delinquency e installments pendientes  
**Schema DB:** `credit_portfolio` · **Puerto:** 8087  
**Spec profunda:** [docs/dominios/04b_credit_portfolio_domain.md](../../dominios/04b_credit_portfolio_domain.md)

---

## Responsabilidad

**Fuente de verdad única de saldos.** Motor único de cuentas de crédito vivas.

> Un `CreditAccount` = 1 contrato de crédito activo para 1 cliente (obligorPartyId)
> con 1 versión fijada de producto (productCode + productVersion).
>
> Todos los demás servicios (charges, payments, collections, wallet) **publican eventos** —
> nunca escriben saldos directamente. credit-portfolio los aplica, los audita en `balance_events`
> y republica `balance-updated` como canal de sincronización.

---

## Modelo de config: propagación versionada

- `credit-product` emite `product-catalog.product-activated` → portfolio proyecta a `product_config_versions` (read-model local, inmutable por versión).
- Cada `CreditAccount` fija `(productCode, productVersion)` al originar → usa esa versión para toda su vida.
- Cambio de config = versión nueva propagada; cuentas existentes no se ven afectadas.

---

## Balance engine: doble validación (fuente de verdad autoritativa)

credit-portfolio es el **post-check autoritativo** del patrón de doble validación:

```
Evento entrante (charge-applied / payment-applied)
  │
  ├─ Idempotencia: balance_events.source_event_id UNIQUE
  │
  ├─ Validación autoritativa
  │   ├─ charge-applied: ¿cuenta en estado terminal (WRITTEN_OFF/CLOSED)?
  │   │   → SÍ: publica credit-portfolio.charge-rejected → charges revierte ChargeRecord
  │   │   → NO: aplica al bucket correcto (interest/penalty según chargeType)
  │   │
  │   └─ payment-applied: ¿amount > totalDebt?
  │       → SÍ: publica credit-portfolio.payment-rejected → payments marca REJECTED
  │       → NO: aplica jerarquía penalty→interest→principal, settle si deuda=0
  │
  └─ Publica credit-portfolio.balance-updated
      → charges + payments actualizan AccountBalanceSnapshot local
```

### Jerarquía de aplicación de pago

```
1. penaltyBalance → 2. accruedInterestBalance → 3. principalBalance
```

### Routing de cargos por bucket

| chargeType | Bucket en CreditAccount |
|---|---|
| `ORDINARY_INTEREST` | `accruedInterestBalance` |
| `MORATORIUM_INTEREST`, `IVA`, `OPENING_FEE`, `ADMIN_FEE`, cualquier otro | `penaltyBalance` |

---

## Versionado de saldo (balanceVersion)

Cada mutación de saldo incrementa `CreditAccount.balanceVersion` (campo `BIGINT`).
Se propaga en `balance-updated` → almacenado en `AccountBalanceSnapshot.balanceVersion` de cada servicio consumidor.
Permite detectar staleness: si el `snapshotVersion` del evento de payments difiere del `balanceVersion` actual, hubo cambio entre pre-check y post-check.

---

## Estado de implementación

| Fase | Componente | Estado |
|---|---|---|
| 0 | `product_config_versions` read-model + `ProductConfigVersionListener` | ✅ |
| 1 | `CreditAccount` + activación + `AmortizationEngine` (FRENCH/GERMAN/BULLET) | ✅ |
| 1 | `ProductConfigResolver` + motor por capabilities + `productVersion` pin | ✅ |
| 2 | `BalanceReconciliationService` + `balance_events` (idempotente, auditado) | ✅ |
| 2 | `CreditAccount` mutaciones: interest/penalty/payment/reverse/writeOff/settle | ✅ |
| 2 | `balanceVersion` + `payment-rejected` (overpayment) + `charge-rejected` (terminal) | ✅ |
| 3 | Disposiciones revolventes múltiples + `AccountStatement` | ⬜ |
| **4** | **`DelinquencyCalculationJob` (23:59) + `InstallmentDueJob` (00:01)** | **⬜ — BLOQUEANTE** |
| — | `SecurityConfig` + JWT filter | ✅ |

> **Fase 4 es bloqueante** para:
> - charges-service: `MoratoriumAccrualJob` necesita que credit-portfolio publique un evento que active `moratoriumActive=true` en `AccrualSchedule`
> - collections-service: depende de delinquency status para iniciar cobranza

---

## Eventos

**Consume:**
- `origination.credit-product-creation-requested` → activa `CreditAccount`
- `product-catalog.product-activated/retired` → actualiza `product_config_versions`
- `charges.charge-applied` → aplica cargo (con post-check terminal)
- `charges.charge-reversed` → revierte cargo del bucket
- `payments.payment-applied` → aplica pago (con post-check overpayment)
- `payments.payment-returned` → restaura deuda
- `collections.write-off-executed` → zeroes all balances, terminal WRITTEN_OFF

**Publica:**
- `credit-portfolio.credit-account-activated` → charges + payments inicializan snapshot
- `credit-portfolio.balance-updated` → todos actualizan `AccountBalanceSnapshot`
- `credit-portfolio.payment-rejected` → payments marca `PaymentOrder` REJECTED
- `credit-portfolio.charge-rejected` → charges revierte `ChargeRecord` REVERSED

*Pendiente (Fase 3-4):* `InstallmentDue` · `DelinquencyStatusUpdated` · `AccountSettled` · `AccountStatementGenerated`

---

## Tablas

| Tabla | Descripción |
|---|---|
| `credit_accounts` | Agregado principal · pin `productVersion` · `balanceVersion` |
| `product_config_versions` | Read-model del catálogo (inmutable por versión) |
| `balance_events` | Auditoría inmutable de cada mutación · UNIQUE(source_event_id) |
| `dispositions` | Disposiciones (revolving) — pendiente Fase 3 |
| `installments` | Plan de pagos (term loans) — pendiente Fase 3 |
| `event_publication` | Spring Modulith outbox |

---

## Tests

52 tests · 0 fallos — incluye ITs (Testcontainers + EmbeddedKafka): activación, config versionada, reconciliación de saldos, idempotencia, payment-rejected.
