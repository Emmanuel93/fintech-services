# D6 — Payments [Core Domain]

> Registra los **pagos** del cliente y su aplicación al crédito, con reversas y manejo de sobrepago. Calcula cómo baja la deuda y **emite** el resultado; credit-portfolio aplica la mutación de saldos. Autentica por header-trust (interno).

**Servicio:** `payments-service` · **Schema:** `payments` · **Puerto:** 8089 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregados

| Agregado | Rol |
|---|---|
| `PaymentOrder` [root] | Un pago (`PaymentMethod`, `PaymentStatus`), su aplicación y reversa. |
| `AccountBalanceSnapshot` | Proyección del saldo (desde `balance-updated`) para aplicar sobre el saldo correcto. |

`PaymentStatus`: PENDING · CONFIRMED · REJECTED · REVERSED.
`PaymentMethod`: SPEI · CODI · DOMICILIACION · VENTANILLA · TARJETA · INTERNAL_TRANSFER.
`OverpaymentStrategy`: RETURN_TO_PAYER · APPLY_NEXT_INSTALLMENT.

## 2. Reglas de negocio (invariantes en código)

- **Guarda WRITTEN_OFF:** no se aplican pagos a una cuenta quebrantada.
- **Ventana de reversa (72h):** un pago CONFIRMED solo se reversa dentro de la ventana; fuera → `InvalidPaymentStateException`.
- **VENTANILLA no es reversible:** el efectivo en ventanilla no se deshace por sistema.
- **Sobrepago:** el excedente se resuelve por `OverpaymentStrategy` (devolver al pagador o aplicar a la siguiente mensualidad).
- **Jerarquía de pago:** penalty → interés → principal (configurable T5), aplicada por credit-portfolio.

## 3. API REST — `/api/v1/payments`

| Método | Ruta |
|---|---|
| `GET` | `/accounts/{creditAccountId}` · `/accounts/{id}/balance` · `/{paymentOrderId}` |
| `POST` | `/{paymentOrderId}/reverse` |

## 4. Eventos Kafka

**Consume:** `credit-portfolio.credit-account-activated`, `credit-portfolio.balance-updated`, `credit-portfolio.payment-rejected`.

**Produce:** `payments.payment-applied` (→ credit-portfolio, collections, notifications, audit), `payments.payment-returned` (→ credit-portfolio, audit).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 5. Persistencia

Liquibase, schema `payments` (5 changesets): `payment_orders`, `account_balance_snapshots`, event-publication, `005-add-overpayment-columns`.

## 6. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| payments calcula, portfolio aplica | El registro y la política de aplicación evolucionan aparte de la mutación de saldos. |
| Ventana de reversa acotada | Reversar un pago viejo descuadra la contabilidad; se limita en el tiempo. |
| VENTANILLA no reversible | El efectivo entregado no se deshace por software. |
