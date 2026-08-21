# T6 — Commission [Transversal]

> Comisiones de la **red comercial** (B2B2C). Devenga la comisión que genera cada crédito para el promotor/distribuidor que lo originó, la acumula y la **liquida** en corridas, con reversa cuando el crédito se cae.

**Servicio:** `commission-service` · **Schema:** `commission` · **Puerto:** 8097 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregados

| Agregado | Rol |
|---|---|
| `CommissionPolicy` (`PolicyStatus`) | Regla de cálculo por producto/segmento. |
| `CommissionRecord` (`CommissionType`, `CommissionRecordStatus`) | Comisión devengada de un crédito. |
| `CreditPromoterAssignment` | Vínculo crédito → promotor/distribuidor beneficiario. |
| `LiquidationBatch` (`LiquidationBatchStatus`) | Corrida que paga lo devengado pendiente. |
| `AccountBalanceShadow` | Read model del saldo (desde `balance-updated`). |

**Invariantes:** un crédito sin política aplicable → `MissingCommissionPolicyException`; registro con transiciones válidas (`InvalidCommissionRecordStateException`).

## 2. API REST — `/api/v1/commissions`

| Método | Ruta |
|---|---|
| `GET` | `/accounts/{creditAccountId}` · `/beneficiaries/{partyId}/pending` · `/promoters/credits` · `/policies` |
| `POST` | `/policies` · `/liquidation-runs` |

## 3. Eventos Kafka

**Consume:** `credit-portfolio.credit-account-activated` (devenga), `credit-portfolio.balance-updated`.

**Produce:** `commission.commission-accrued`, `commission.commission-liquidated`, `commission.commission-reversed` (todos → accounting).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 4. Persistencia

Liquibase, schema `commission` (7 changesets): `commission_policies`, `commission_records`, `liquidation_batches`, read models, event-publication, seed de políticas.

## 5. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Devengo por evento de activación | La comisión nace cuando el crédito se activa, con el promotor ya resuelto por originación. |
| Liquidación en corridas | Pagar en lote es operativamente más simple y auditable que pago por pago. |
| Reversa explícita | Un crédito que se cae revierte su comisión hacia contabilidad. |
