# D7 — Wallet [Supporting]

> Monedero del cliente sobre una línea revolvente: **proyección de saldo** para la app y origen de las **disposiciones** (uso propio o pago a terceros), **retiros** de saldo a favor e **instrucciones de pago**. No es la fuente de verdad de los saldos —esa es [credit-portfolio (04b)](04b_credit_portfolio_domain.md)— sino un read model más las solicitudes que dispara.

**Servicio:** `wallet-service` · **Schema:** `wallet` · **Puerto:** 8092 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregados

| Agregado | Rol |
|---|---|
| `WalletView` | Proyección del saldo por `creditAccountId` (desde `balance-updated`), incluye `walletBalance` (saldo a favor). |
| `WalletMovement` | Movimiento del monedero. |
| `WalletWithdrawal` | Retiro de saldo a favor a una cuenta del cliente. |
| `PaymentInstruction` | Instrucción de pago (`PaymentType`, `PaymentMethod`). |

**Invariantes en código:** `DispositionBlockedException` (cuenta bloqueada no dispone), `InsufficientCreditException` (disposición > cupo), `InsufficientWalletBalanceException` (retiro > saldo a favor), `DuplicatePendingInstructionException` (una instrucción pendiente a la vez).

## 2. Disposición: SELF_USE vs THIRD_PARTY

- **SELF_USE:** el dinero se queda en la plataforma → acredita `walletBalance` desde `credit-portfolio.disposition-completed` (DO-06). No sale por SPEI.
- **THIRD_PARTY_CREDIT:** paga a un tercero. Hoy se liquida en credit-portfolio con el stub SPEI; su ruta por `disposition-authorized` → disbursement es el entregable **2B.2** (pendiente).

## 3. API REST — `/api/v1/wallet`

| Método | Ruta |
|---|---|
| `GET` | `/{creditAccountId}` · `/{id}/movements` |
| `POST` | `/{id}/dispositions` · `/{id}/withdrawals` · `/{id}/payment-instructions` |

## 4. Eventos Kafka

**Consume:** `credit-portfolio.credit-account-activated`, `credit-portfolio.balance-updated`, `credit-portfolio.disposition-completed`, `credit-portfolio.installment-due`.

**Produce:** `wallet.disposition-requested` (→ credit-portfolio), `wallet.withdrawal-completed` (→ accounting, disbursement), `wallet.payment-instruction-created`, `wallet.snapshot-updated`.

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 5. Persistencia

Liquibase, schema `wallet` (7 changesets): `wallet_views` (+ `wallet_balance`), `payment_instructions`, `wallet_withdrawals`, `wallet_movements`, event-publication.

## 6. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Read model, no fuente de verdad | Los saldos viven en credit-portfolio; wallet proyecta y dispara solicitudes. |
| SELF_USE acredita walletBalance | El dinero de uso propio no sale de la plataforma. |
| Una instrucción pendiente a la vez | Evita doble disposición concurrente. |
