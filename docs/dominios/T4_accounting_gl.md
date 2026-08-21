# T4 — Accounting / General Ledger [Transversal]

> Libro mayor por **partida doble**. No decide negocio: **proyecta** los hechos económicos de otros dominios en asientos balanceados y deriva balanza, provisión e ítems facturables. Contabilidad es un observador, no un actor.

**Servicio:** `accounting-service` · **Schema:** `accounting` · **Puerto:** 8095 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregados

| Agregado | Rol |
|---|---|
| `JournalEntry` | Asiento (partida doble), inmutable una vez posteado; `BalanceDelta` por línea. |
| `LedgerAccount` (`AccountType`, `AccountCodes`) | Catálogo mayor. |
| `PostingRule` | Traduce un evento de dominio en débitos/créditos. |
| `AccountBalanceShadow` | Read model del saldo (desde `balance-updated`). |
| `ProvisionLedgerEntry` | Provisión contable (pérdida esperada) derivada del riesgo. |
| `InvoiceableItem` (`InvoiceableItemStatus`) | Ítem facturable acumulado que dispara facturación. |
| `PeriodStatus` | Estatus del periodo contable. |

## 2. API REST — `/api/v1/accounting`

| Método | Ruta |
|---|---|
| `GET` | `/accounts/{creditAccountId}/journal` · `/parties/{partyId}/journal` · `/trial-balance` |
| `POST` | `/billing-runs` (genera ítems facturables → `invoice-requested`) |

## 3. Eventos Kafka

**Consume:** `credit-portfolio.balance-updated`, `commission.commission-accrued`/`liquidated`/`reversed`, `risk.assessment-updated`, `wallet.withdrawal-completed`, `collections.recovery-payment-applied`.

**Produce:** `accounting.invoice-requested` (→ invoicing), `accounting.journal-entry-created`, `accounting.reconciliation-alert`.

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 4. Persistencia

Liquibase, schema `accounting` (6 changesets): catálogo (`ledger_accounts`), `journal_entries`, read models, seed del catálogo, event-publication.

## 5. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Contabilidad proyecta, no decide | Los hechos económicos nacen en los dominios; accounting los asienta. |
| Posting rules como datos | Cambiar el mapeo evento→asiento es configuración, no código. |
| Asiento inmutable | Corrección = asiento correctivo, nunca edición. |
