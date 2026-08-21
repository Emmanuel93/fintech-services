# D9 — Risk [Supporting]

> Riesgo de la **cuenta viva** (distinto de [scoring (02)](02_scoring_domain.md), que evalúa al originar). Mantiene el **perfil de riesgo IFRS-9** por cuenta a partir de la actividad de cartera y administra las **políticas de provisión** (bandas de pérdida esperada por tipo de producto), publicando la valuación para contabilidad.

**Servicio:** `risk-service` · **Schema:** `risk` · **Puerto:** 8094 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregados

| Agregado | Rol |
|---|---|
| `RiskProfile` [root] | Perfil por `creditAccountId` (`Ifrs9Stage`, `RiskProfileStatus`, `DelinquencyBucket`). |
| `ProvisionPolicy` | Política de provisión por tipo de producto (`PolicyStatus`). |
| `ProvisionRateBand` | Banda de tasa de pérdida esperada por tramo de atraso. |

**IFRS-9 (`Ifrs9StageResolver`):** `STAGE_1` (al corriente, ≤30 dpd) · `STAGE_2` (SICR, 31–90) · `STAGE_3` (deteriorado, >90). La provisión = saldo × tasa de la banda del stage.

**Invariantes:** política con transiciones válidas (`InvalidPolicyStateException`); una cuenta sin política aplicable → `MissingProvisionPolicyException`.

## 2. API REST — `/api/v1/risk`

| Método | Ruta | Uso |
|---|---|---|
| `GET` | `/accounts` | Perfiles con filtros + paginación (sin `partyId` obligatorio). |
| `GET` | `/accounts/batch?ids=` | Hidrata riesgo de varias cuentas en **una** consulta (mata el N+1 del BFF). |
| `GET` | `/accounts/{creditAccountId}` | Perfil de una cuenta. |
| `GET` | `/provisions/summary` | Provisión agregada. |
| `GET`/`POST` | `/provision-policies`, `/provision-policies/{productType}` | Políticas de provisión. |

## 3. Eventos Kafka

**Consume:** `credit-portfolio.credit-account-activated`, `credit-portfolio.balance-updated`, `credit-portfolio.delinquency-status-updated`, `collections.agreement-executed`.

**Produce:** `risk.assessment-updated` (→ accounting) — la valuación IFRS-9 que contabilidad convierte en provisión contable.

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 4. Persistencia

Liquibase, schema `risk` (6 changesets): `risk_profiles`, `provision_policies`, `provision_rate_bands`, índices.

## 5. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Riesgo de cuenta ≠ scoring de originación | Uno evalúa al prospecto para prestar; otro valúa la cuenta viva para provisionar. |
| `/accounts/batch` | El BFF pinta la columna de riesgo de una tabla de cartera sin N+1. |
| Provisión por política configurable | Ajustar bandas IFRS-9 = configuración, no cambio de código. |
