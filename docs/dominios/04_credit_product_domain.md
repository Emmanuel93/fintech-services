# D4 — Credit — Product [Core Domain · Catálogo / Fábrica de productos]

> Define **qué productos crediticios existen y cómo se comportan**. Catálogo versionado: capacidades por `productType`, rate cards, plazos, comisiones template, documentos y reglas de elegibilidad. **No administra cuentas ni saldos** — eso es [credit-portfolio (04b) ★](04b_credit_portfolio_domain.md).

**Servicio:** `credit-product-service` · **Schema:** `credit_product` · **Puerto:** 8084 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08). Se conservan los IDs de invariantes.

## 1. Las tres capas de "config del producto"

| Capa | Qué define | Dónde |
|---|---|---|
| **A. Catálogo / Definición** | Capacidades del *tipo*, rate cards, plazos, reglas, documentos | **credit-product (este doc)** |
| **B. Parámetros de negocio** | Valores calientes/regulatorios (IVA, gracePeriod, jerarquía de pagos) | **T5 configuration-service** |
| **C. Instancia viva** | La cuenta concreta del cliente, saldos | **credit-portfolio (04b)** |

"Dar de alta un producto" = crear una `CreditProductDefinition` (capa A). Los valores regulatorios/comerciales (capa B) se referencian de T5 por llave, no se hardcodean.

## 2. Agregado: CreditProductDefinition [Root]

Campos: `productDefinitionId`, `productCode`, `productType`, `productVersion`, `status`, `productBehavior`, `name`, `description`, `targetAudience`, `currency`, `nominalRateAnnual`, `moratoriumRateAnnual`, `minTerm/maxTerm/defaultTerm`, `minAmount/maxAmount`, `defaultCreditLine/minCreditLine/maxCreditLine`, `amountStep`, `amortizationType`, `defaultPaymentFrequency`, `allowedPaymentFrequencies`, **`minApprovalScore`**, **`defaultApprovalFlow`**, `openingFeeRate`, `prepaymentFeeRate`, `eligiblePartyTypes`, `requiredDocuments`, `channelAvailabilities`, `capabilities`.

Sub-entidades / value objects: `RateCard`, `EligibilityRule` (`EligibilityRuleType`, `EligibilityOperator`), `RequiredDocument`, `Capabilities`.

**Enums:** `ProductType` (PERSONAL_LOAN · REVOLVING_LINE · PAYROLL_LOAN · GROUP_LOAN · DISTRIBUTOR_LINE · SME_LOAN) · `ProductBehavior` · `ProductStatus` (DRAFT → ACTIVE ↔ DEACTIVATED → DEPRECATED/RETIRED) · `AmortizationType` (FRENCH · GERMAN) · `PaymentFrequency` · `ApprovalFlow` · `TargetAudience` (B2C · B2B · B2B2C).

**Invariantes:** PD-01 un solo `ACTIVE` por `productCode` (índice único parcial) · PD-02 activar una versión retira la ACTIVE previa del mismo código · PD-03 ACTIVE/RETIRED inmutables (cambios = nueva versión) · PD-04 `capabilities`/`amortizationType` coherentes con `productType` · PD-05 el catálogo **nunca borra**, versiona.

## 3. Matriz de capacidades (gobierna el motor de credit-portfolio)

| Feature | PERSONAL_LOAN | REVOLVING_LINE | DISTRIBUTOR_LINE | GROUP_LOAN | PAYROLL_LOAN |
|---|:---:|:---:|:---:|:---:|:---:|
| AmortizationSchedule | ✅ | ❌ | ❌ | ✅ | ✅ |
| creditLimit / availableCredit | ❌ | ✅ | ✅ | ❌ | ❌ |
| Múltiples disposiciones | ❌ | ✅ | ✅ | ❌ | ❌ |
| dispositionType | `SELF_USE` | `SELF_USE` | `THIRD_PARTY_CREDIT` | `SELF_USE` | `PAYROLL` |

## 4. Relación con el scoring (política que gobierna al producto)

El producto declara `minApprovalScore` + `defaultApprovalFlow`. La **política de scoring** que lo gobierna vive en scoring-service y se empareja por **`(prospectType, productTypeIntent == productType)`** — una por tipo de prospecto. El BFF de backoffice compone ambos en `GET /products/{id}`: condiciones del producto + política(s) de scoring (reglas y umbrales) **por tipo de prospecto**, marcando el hueco cuando un prospecto elegible no tiene política. Ver [scoring (02)](02_scoring_domain.md).

## 5. API REST — `/api/v1/credit-products`

| Método | Endpoint | Descripción |
|---|---|---|
| `GET` | `/` | Versiones ACTIVE (consumido por origination/BFF) |
| `GET` | `/all` | Todas las versiones y estados |
| `GET` | `/{id}` · `/{id}/rate-cards` · `/{id}/eligibility-rules` | Definición por id + sus tablas |
| `GET` | `/code/{code}` · `/code/{code}/versions` | Vigente por código + historial |
| `POST` | `/` | Crear/versionar |
| `PUT` | `/{id}/activate` · `/deactivate` · `/reactivate` · `/deprecate` · `/code/{code}/retire` | Ciclo de vida de la versión |

## 6. Eventos Kafka

**Consume:** — (catálogo REST; no consume eventos de dominio).

**Produce:** `product-catalog.product-activated`, `product-catalog.product-retired` (nombres configurables por `kafka.topics.*`) → credit-portfolio, charges, audit. Son eventos de **nivel catálogo** (definición), no de cuenta.

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 7. Patrón snapshot (cómo se consume el catálogo)

```
1. Product manager → POST /credit-products → CreditProductDefinition(ACTIVE) → product-catalog.product-activated
2. Origination/BFF lee el catálogo para armar la oferta (productos, tasas, plazos, documentos).
3. Al firmar, Origination resuelve los términos finales y los envía como SNAPSHOT en
   origination.credit-product-creation-requested.
4. credit-portfolio crea la CreditAccount con ese snapshot CONGELADO — nunca vuelve a
   llamar al catálogo en runtime (inmutabilidad regulatoria).
```

## 8. Persistencia

Liquibase, schema `credit_product` (11 changesets): `credit_product_definitions`, `eligible_party_types`, `required_documents`, `channel_availability`, `payment_frequencies`, `rate_cards`, `eligibility_rules`, + seeds (productos iniciales, tarjeta/micro-préstamo, productos B2B).

## 9. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Catálogo como servicio propio (no dentro de T5) | T5 es key-value genérico; el catálogo es un dominio rico (capacidades, rate cards, versionado, elegibilidad) |
| Separado de credit-portfolio | Configuración (lectura intensa, casi estática) ≠ cuenta viva (escritura intensa) |
| Versionado, nunca borra | Trazabilidad: qué definición regía cada cuenta originada |
| Snapshot al originar, no referencia en runtime | Cambiar el catálogo no afecta cuentas vivas |
| `capabilities` como dato, no código | Agregar/ajustar producto = registrar configuración, no modificar el motor |
