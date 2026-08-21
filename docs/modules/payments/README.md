# D6 — Payments [Supporting]

**Estado:** 🔄 En progreso — Doble validación de saldo implementada  
**Tipo:** Supporting  
**Schema DB:** `payments`  
**Puerto:** 8089  
**Paquete Java:** `com.fintech.payments`

---

## Responsabilidad

Recepción y aplicación de pagos. **Nunca modifica saldos directamente.** Emite `payment-applied` para que `credit-portfolio` aplique los saldos como fuente de verdad.

---

## Alcance del control de saldo

> **Clave conceptual:** el `AccountBalanceSnapshot` de payments está indexado por `creditAccountId`.
> Un `creditAccountId` = 1 producto de crédito + 1 cliente (obligorPartyId).
> Un cliente con 3 productos tiene 3 snapshots independientes en payments.
>
> Antes de procesar un pago, payments valida contra el snapshot local de **esa cuenta específica**,
> no contra el saldo global del cliente. Cada cuenta mantiene su propio estado.

---

## Patrón doble validación (pre-check + post-check)

```
POST /api/v1/payments
  │
  ├─ Idempotencia: externalRef único (SPEI CLAVE_RASTREO / CoDi referenceId)
  │
  ├─ PRE-CHECK (eventual, local)
  │   └─ AccountBalanceSnapshot.canAcceptPayment(amount)
  │       → amount > 0 AND amount ≤ totalDebt
  │       → si falla → PaymentOrder REJECTED inmediatamente (sin evento)
  │
  ├─ PaymentOrder persisted (PENDING) + payment-applied publicado
  │
  └─ POST-CHECK (autoritativo, transaccional — credit-portfolio)
      ├─ OK  → PaymentOrder CONFIRMED via payment-rejected ausente
      │         balance-updated propagado → snapshot se actualiza
      └─ FAIL (overpayment detectado en momento de aplicar)
          → payment-rejected publicado
          → PaymentRejectedListener → PaymentOrder REJECTED
```

### Cuándo aplica cada check

| Check | Quién | Qué valida | Consistencia |
|---|---|---|---|
| PRE-CHECK | payments-service | `amount ≤ totalDebt` en snapshot local | Eventual (puede ser optimista) |
| POST-CHECK | credit-portfolio | `amount ≤ totalDebt` real y transaccional | Autoritativo — detecta overpayment en race conditions |

---

## Ciclo de vida de PaymentOrder

```
PENDING ──▶ CONFIRMED (credit-portfolio aplicó el pago)
   │
   └──▶ REJECTED  (pre-check fallido O payment-rejected recibido)

CONFIRMED ──▶ REVERSED (devolución dentro de ventana de 72h)
```

---

## Idempotencia

`externalRef` tiene UNIQUE constraint en `payment_orders`. Doble llamada con el mismo CLAVE_RASTREO devuelve el `PaymentOrder` existente sin crear uno nuevo.

---

## Métodos de pago

`SPEI` · `CoDi` · `DOMICILIACION` · `VENTANILLA` · `TARJETA` · `INTERNAL_TRANSFER`

---

## Flujo de eventos

**Consume:**
- `credit-portfolio.credit-account-activated` → inicializa `AccountBalanceSnapshot`
- `credit-portfolio.balance-updated` → upsert `AccountBalanceSnapshot` (mantiene snapshot fresco)
- `credit-portfolio.payment-rejected` → marca `PaymentOrder` como REJECTED

**Publica:**
- `payments.payment-applied` — con `snapshotVersion` (versión del snapshot al momento del pre-check)
- `payments.payment-returned` — cuando se revierte un pago CONFIRMED

---

## API REST

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| `POST` | `/api/v1/payments` | JWT | Submite pago (incluye pre-check) |
| `GET` | `/api/v1/payments/{paymentOrderId}` | No | Detalle de una orden |
| `GET` | `/api/v1/payments/accounts/{creditAccountId}` | No | Lista órdenes de la cuenta |
| `GET` | `/api/v1/payments/accounts/{creditAccountId}/balance` | No | Snapshot de saldo local |
| `POST` | `/api/v1/payments/{paymentOrderId}/reverse` | JWT | Devuelve un pago CONFIRMED |

---

## Tablas

| Tabla | Descripción |
|---|---|
| `payments.payment_orders` | Ciclo de vida de la orden · UNIQUE(external_ref) |
| `payments.account_balance_snapshots` | Snapshot por `creditAccountId` (read-model de credit-portfolio) |
| `payments.event_publication` | Spring Modulith outbox |

---

## Pendiente

- [ ] Lógica de excedente: `APPLY_NEXT_INSTALLMENT` vs `RETURN_TO_PAYER` (configurable T5 por productType)
- [ ] Ventana de devolución 72h configurable (T5) — hoy no hay validación de ventana
- [ ] Conciliación SPEI: consumer del archivo de conciliación bancaria → `ReconciliationCompleted`
- [ ] Tests de integración end-to-end con balance snapshot + payment-rejected
