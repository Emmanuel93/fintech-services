# D5 — Charges [Core]

**Estado:** 🔄 En progreso — Motor de devengamiento + doble validación de saldo implementados  
**Tipo:** Core  
**Schema DB:** `charges`  
**Puerto:** 8088  
**Paquete Java:** `com.fintech.charges`

---

## Responsabilidad

Motor de devengamiento. **Nunca modifica saldos directamente.** Emite eventos `charge-applied` / `charge-reversed` para que `credit-portfolio` aplique los saldos como fuente de verdad.

---

## Alcance del control de saldo

> **Clave conceptual:** el `AccountBalanceSnapshot` de charges está indexado por `creditAccountId`.
> Un `creditAccountId` = 1 producto de crédito + 1 cliente (obligorPartyId).
> Un cliente con 3 productos tiene 3 snapshots independientes en charges.
>
> El snapshot es una proyección local de lo que `credit-portfolio` ya confirmó.
> **No es un saldo global por cliente — es por cuenta de crédito.**

---

## Patrón doble validación (pre-check + post-check)

```
Accrual Job
  │
  ├─ PRE-CHECK (eventual, local)
  │   └─ AccountBalanceSnapshot.isChargeable()
  │       → si WRITTEN_OFF / CLOSED / SETTLED → abort silencioso
  │
  ├─ ChargeRecord persisted (APPLIED) + charge-applied publicado
  │
  └─ POST-CHECK (autoritativo, transaccional — credit-portfolio)
      ├─ OK  → balance-updated propagado → snapshot se actualiza
      └─ FAIL (cuenta terminal)
          → charge-rejected publicado
          → ChargeRejectedListener revierte ChargeRecord a REVERSED
```

### Cuándo aplica cada check

| Check | Quién | Qué valida | Consistencia |
|---|---|---|---|
| PRE-CHECK | charges-service | `accountStatus` en snapshot local | Eventual (puede estar desactualizado) |
| POST-CHECK | credit-portfolio | Estado real de la cuenta en BD | Autoritativo y transaccional |

---

## Tipos de cargo

| Tipo | Base cálculo | Trigger | Bucket en portfolio |
|---|---|---|---|
| Interés ordinario, modo `DAILY` | capital de la foto de D (sin las cuotas por vencer de compras a meses sin intereses) × tasa / 360 | `DayCloseAccrualJob` (00:05 de D+1, sobre D) | `accruedInterestBalance` |
| Interés ordinario, modo `PER_PERIOD` | el interés que el plan de cada colocación fija para el periodo que **termina** en D (`scheduledInterest` de la foto de cartera); cero los demás días y cuando ya no se debe capital | `DayCloseAccrualJob`, misma unidad del día | `accruedInterestBalance` |
| Interés moratorio | capital vencido × tasa mora diaria | `DayCloseAccrualJob`, después del ordinario, solo cuando `moratoriumActive=true` | `penaltyBalance` |
| Comisión apertura | `approvedAmount` × tasa | Una vez en activación | `penaltyBalance` |
| IVA 16% | Sobre cada cargo | Automático, vinculado por `linkedChargeId` | Mismo bucket que cargo padre |

El modo lo declara el producto en su ciclo de facturación (`billingCycle.interestAccrual`) y se fija a la
cuenta al activarla (`accrual_schedules.interest_accrual`): una cuenta abierta antes de que existiera
devenga diario. Hoy sólo la línea de distribuidora es `PER_PERIOD`: cada colocación nace con su plan
francés quincenal, y cobrar tasa / 360 diaria sobre la línea pedía 14 × tasa / 360 por un periodo de
14 días en lugar de la tasa / 26 del plan, y lo cobraba día a día antes de que el periodo se debiera.

---

## Regla clave: Reversed ≠ Waived

| Operación | Significado | Impacto contable (T4) |
|---|---|---|
| `REVERSED` | Corrección técnica | Cancela el asiento — no impacta P&L |
| `WAIVED` | Condonación comercial | Genera gasto — impacta P&L |

Ambas operaciones cascadean al `ChargeRecord` de IVA vinculado (`linkedChargeId`).

---

## Flujo de eventos

**Consume:**
- `credit-portfolio.credit-account-activated` → crea `AccrualSchedule` + inicializa `AccountBalanceSnapshot`
- `credit-portfolio.balance-updated` → actualiza `principalBalance` en `AccrualSchedule` + upsert `AccountBalanceSnapshot`
- `credit-portfolio.charge-rejected` → revierte `ChargeRecord` (post-check fallido)

**Publica:**
- `charges.charge-applied` — para cada `ChargeRecord` creado (fee + IVA por separado)
- `charges.charge-reversed` — con campo `waived: boolean` para distinción contable

---

## Jobs nocturnos

| Job | Cron | Aislamiento transaccional |
|---|---|---|
| `DayCloseAccrualJob` | `0 5/15 * * * *` | Cierra los días pendientes en orden, desde las 00:05 de D+1 y sólo si abren sus compuertas (D-1 cerrado aquí, en cartera y en closing; saldos de cartera consumidos): ordinario, moratorio y `charges.day-accrued` con cifras de control. Cada `AccrualSchedule` en su propia `@Transactional`; si alguno falla, el día queda `FAILED` y no se avisa |

---

## Tablas

| Tabla | Descripción |
|---|---|
| `charges.accrual_schedules` | Una por `creditAccountId` · contiene tasas, status, `principalBalance` actual |
| `charges.charge_records` | Un registro por cargo + su IVA (`linkedChargeId`) |
| `charges.account_balance_snapshots` | Snapshot de saldo completo por `creditAccountId` (read-model de credit-portfolio) |
| `charges.event_publication` | Spring Modulith outbox |

---

## API REST

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| `GET` | `/api/v1/charges/accounts/{creditAccountId}` | No | Lista `ChargeRecord`s de la cuenta |
| `GET` | `/api/v1/charges/accounts/{creditAccountId}/schedule` | No | Devuelve `AccrualSchedule` |
| `GET` | `/api/v1/charges/accounts/{creditAccountId}/balance` | No | Snapshot de saldo local (proyección de credit-portfolio) |
| `POST` | `/api/v1/charges/{chargeId}/reverse` | JWT | Reversión técnica |
| `POST` | `/api/v1/charges/{chargeId}/waive` | JWT | Condonación |

---

## Pendiente

- [ ] Delinquency trigger: `AccrualSchedule.moratoriumActive` hoy se activa desde `credit-portfolio.balance-updated` (cuando `accountStatus=DELINQUENT`). Requiere que credit-portfolio implemente `DelinquencyCalculationJob` (Fase 3).
- [ ] `AdminFeeJob` — comisión de administración periódica (requiere T5 config)
- [ ] Tests de integración: `ChargesAcceptanceTest` con flujo completo incluyendo balance snapshot y charge-rejected
