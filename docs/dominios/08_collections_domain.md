# D8 — Collections [Core Domain · Cobranza]

> Gestión de mora y recuperación: abre **casos** cuando una cuenta cae en mora, registra gestión (contactos, promesas), negocia **convenios** (quita/reestructura) con maker-checker, y ejecuta el **quebranto**. Trabaja sobre una proyección local del saldo; nunca lee la cartera directamente.

**Servicio:** `collections-service` · **Schema:** `collections` · **Puerto:** 8093 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregados

| Agregado | Rol |
|---|---|
| `CollectionCase` [root] | Caso de una cuenta en mora (`CaseStatus`). |
| `ContactAttempt` | Intento de contacto (`ContactResult`). |
| `PaymentPromise` | Promesa de pago (`PromiseStatus`: ACTIVE → cumplida/rota). |
| `CollectionAgreement` | Convenio (`AgreementType`: QUITA_PARCIAL · RESTRUCTURE; `AgreementStatus`: PROPOSED → ACCEPTED → AUTHORIZED → EXECUTED / REJECTED) con `RestructureTerms`. |
| `WriteOffRecord` | Quebranto (`WriteOffReason`). |
| `BureauReport` | Reporte a buró de eventos de cobranza (`BureauReportStatus`, `BureauEventType` p.ej. WRITE_OFF). |
| `AccountBalanceSnapshot` | Read model del saldo (desde `balance-updated`). |
| `DelinquencyBucket` | Tramo de atraso; `CollectionStrategyResolver` elige estrategia. |

**Invariantes:** convenio con ciclo maker-checker (`InvalidAgreementStateException`); límite de condonación (`ForgivenessLimitExceededException`); caso con transiciones válidas (`InvalidCaseStateException`).

## 2. API REST — `/api/v1/collections`

| Método | Ruta |
|---|---|
| `GET` | `/accounts/{creditAccountId}/case` · `/cases/{caseId}` · `/bureau-reports` |
| `POST` | `/cases/{id}/contact-attempts` · `/payment-promises` · `/agreements` · `/request-write-off` · `/write-offs` |
| `PUT` | `/agreements/{id}/accept` · `/authorize` · `/reject` |

### Bandejas transversales (2026-08-17)

| Método | Ruta | Qué resuelve |
|---|---|---|
| `GET` | `/payment-promises` | «Promesas vigentes» **cruzando casos**. Filtros: `status`, `bucket`, `agentId`, `dueFrom`/`dueTo`, `dueToday`. |
| `GET` | `/contact-attempts` | «Gestión de contacto» cruzando casos. Filtros: `result`, `channel`, `bucket`, `agentId`, `from`/`to`. |

Antes sólo existían como sub-recursos de un caso, así que armar una bandeja obligaba a **una llamada
por fila** desde el canal. Ahora la consulta la resuelve el dueño con `JOIN` al caso —el tramo y el
gestor viven ahí, no en la promesa— más dos subconsultas correlacionadas que traen los intentos de
hoy y el resultado del último contacto.

**Los flags de trato al cliente se calculan aquí, no en la consola:** `contactCapReached`,
`contactable` y la ventana horaria salen de `CollectionsProperties`. Del lado de quien pinta la
pantalla serían una sugerencia visual en vez de una regla.

El día se cuenta en **hora de México** y no en UTC: el tope diario es una regla de trato, y su día es
el de la persona a la que se le llama — con UTC el contador se reiniciaría a las 6 de la tarde.

## 3. Eventos Kafka

**Consume:** `credit-portfolio.credit-account-activated`, `credit-portfolio.balance-updated`, `credit-portfolio.delinquency-status-updated` (abre/actualiza caso), `credit-portfolio.installment-upcoming`, `payments.payment-applied`.

**Produce:** `collections.agreement-executed` (→ credit-portfolio, risk), `collections.write-off-executed` (→ credit-portfolio), `collections.pre-due-reminder-triggered` (→ notifications), `collections.recovery-payment-applied` (→ accounting), y hechos de gestión sin consumidor externo hoy (`case-created`, `case-escalated`, `contact-attempt-registered`, `payment-promise-made/broken`, `agreement-proposed`, `bureau-report-submitted`, `write-off-requested`).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 4. Persistencia

Liquibase, schema `collections` (9 changesets): `collection_cases`, `contact_attempts`, `payment_promises`, `collection_agreements`, `write_off_records`, `bureau_reports`, `account_balance_snapshots`, event-publication.

## 5. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Convenio con maker-checker | Quita/reestructura tienen impacto contable; exigen autorización separada. |
| Cobranza reactiva a `delinquency-status-updated` | La mora la calcula credit-portfolio (job); collections reacciona al hecho. |
| `pre-due-reminder-triggered` en vez de `@Scheduled` en notifications | El recordatorio nace de un hecho de cobranza, no de un cron que revisa cuentas. |
