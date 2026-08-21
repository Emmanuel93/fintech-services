# D1 — Channels [Supporting · Captura de intención]

> Punto de captura de la **intención** del cliente antes de que exista una solicitud formal: sesión, dispositivo, intent (qué quiere), lead. Convierte una intención de crédito en el disparo de originación. Es el "embudo" superior.

**Servicio:** `channels-service` · **Schema:** `channels` · **Puerto:** 8091 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08). No confundir con los **BFF** (`channel-mobile`/`channel-backoffice`), que son compositores REST sin dominio propio.

## 1. Agregados

| Agregado | Rol |
|---|---|
| `Channel` | Canal de captación (`ChannelType`: MOBILE_APP…; `ChannelStatus`). |
| `Session` | Sesión del cliente en el canal (`SessionStatus`) + `DeviceContext` (`DeviceType`). |
| `CustomerIntent` | Intención capturada (`IntentType`: CREDIT_APPLICATION; `IntentStatus`: CAPTURED → routed/abandoned) hacia un `DomainTarget`. |
| `LeadRequest` | Lead (`LeadStatus`: NEW → convertido). |

## 2. API REST

| Base | Endpoints |
|---|---|
| `/api/v1/channels` | catálogo de canales |
| `/api/v1/sessions` | `GET /{sessionId}`, `PUT /{sessionId}/close` |
| `/api/v1/sessions/{sessionId}/intents` | `PUT /{intentId}/route`, `/abandon` |
| `/api/v1/leads` | `GET /{leadId}`, `PUT /{leadId}/convert` |

## 3. Eventos Kafka

**Consume:** — (no consume eventos de dominio).

**Produce:** `channels.application-started` (→ **origination**, arranca el flujo) y hechos de embudo sin consumidor hoy: `intent-captured`, `intent-routed`, `intent-abandoned`, `lead-created`, `lead-converted`, `session-started`, `session-expired` (analítica/futuro).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 4. Persistencia

Liquibase, schema `channels` (8 changesets): `channels`, `sessions`, `customer_intents`, `lead_requests`, `device_context` (007), seed de canales por defecto (006), event-publication.

## 5. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Captura de intención separada de originación | El embudo (intención/lead) tiene ciclo y analítica propios; solo cuando madura dispara `application-started`. |
| Eventos de embudo emitidos aunque nadie los consuma aún | Traza y base para analítica de conversión sin acoplar consumidores. |
