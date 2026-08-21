# D5 — Charges [Core Domain · Devengamiento]

> **Calcula** intereses, comisiones, seguros e IVA de la cuenta viva. No guarda saldos: **emite** el cargo calculado y credit-portfolio lo aplica. Trabaja sobre una proyección local del saldo. Autentica por header-trust (interno).

**Servicio:** `charges-service` · **Schema:** `charges` · **Puerto:** 8088 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Responsabilidad

Los demás **calculan, portfolio aplica**: charges determina el importe de cada cargo y lo publica; credit-portfolio muta el saldo. charges nunca escribe en la BD de portfolio; mantiene su `AccountBalanceSnapshot` (read model desde `balance-updated`) para calcular sobre el saldo correcto.

## 2. Agregados

| Agregado | Rol |
|---|---|
| `AccrualSchedule` | Calendario de devengo por cuenta (`AccrualScheduleStatus`), creado al activar el crédito. |
| `ChargeRecord` | Cargo calculado (`ChargeType`, `ChargeStatus`), con reversa/condonación. |
| `AccountBalanceSnapshot` | Proyección del saldo por cuenta. |

`ChargeType`: `ORDINARY_INTEREST · MORATORIUM_INTEREST · OPENING_FEE · ADMIN_FEE · PREPAYMENT_FEE · INSURANCE_PREMIUM · IVA`.

**Invariantes:** un solo `OPENING_FEE` por cuenta (`DuplicateOpeningFeeException`); un cargo APPLIED se revierte solo por operación REVERSED/WAIVED (`InvalidChargeStateException`).

## 3. Jobs de devengo

| Job | Acción |
|---|---|
| `InterestAccrualService` (diario) | Devenga interés ordinario → `charges.charge-applied` |
| `MoratoriumAccrualJob` | Devenga interés moratorio sobre cuentas en mora |

> Test-support (`/internal/test-support`, no enrutado): correr el devengo diario a demanda.

## 4. API REST — `/api/v1/charges`

| Método | Ruta |
|---|---|
| `GET` | `/accounts/{creditAccountId}` · `/accounts/{id}/balance` · `/accounts/{id}/schedule` |
| `POST` | `/run-daily-accrual` · `/run-moratorium-accrual` · `/{chargeId}/reverse` · `/{chargeId}/waive` |

## 5. Eventos Kafka

**Consume:** `credit-portfolio.credit-account-activated` (crea el calendario), `credit-portfolio.balance-updated` (actualiza snapshot), `credit-portfolio.charge-rejected`, `product-catalog.product-activated`/`product-retired`.

**Produce:** `charges.charge-applied`, `charges.charge-reversed` (→ credit-portfolio, audit).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 6. Persistencia

Liquibase, schema `charges` (5 changesets): `accrual_schedules`, `charge_records`, `account_balance_snapshots`, event-publication.

## 7. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| charges calcula, portfolio aplica | Separa el cálculo (evolutivo) de la mutación de saldos (consistencia fuerte). |
| Snapshot local del saldo | Calcular sobre el saldo correcto sin leer la BD de portfolio. |
| Devengo por job, no reactivo | El interés corre por tiempo, no por evento — determinista y auditable. |
