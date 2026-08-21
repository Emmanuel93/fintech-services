# T5 — Configuration [Transversal]

> Parámetros de negocio calientes (IVA, gracePeriod, jerarquía de pagos, límites…) con **maker-checker**: quien propone no aprueba. Fuente única de los valores que otros dominios referencian por llave; al cambiar, publica el hecho para que los suscriptores invaliden su caché.

**Servicio:** `configuration-service` · **Schema:** `configuration` · **Puerto:** 8086 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregados

| Agregado | Rol |
|---|---|
| `ConfigParameter` | Parámetro por llave (`ConfigParameterStatus`) con ciclo maker-checker. |
| `ConfigAuditTrail` | Bitácora de cambios (`ConfigAuditAction`) — quién propuso/aprobó. |

**Invariantes:** un solo parámetro ACTIVE por llave (`DuplicateActiveParameterException`); transiciones válidas (`InvalidConfigStateTransitionException`); aprobar es un acto separado de proponer (maker-checker).

## 2. API REST — `/api/v1/config`

| Método | Ruta |
|---|---|
| `GET` | `/{key}` · `/{key}/history` |
| `PUT` | `/{id}/approve` (checker) |

## 3. Eventos Kafka

**Consume:** — (no consume eventos de dominio).

**Produce:** `configuration.configuration-updated` (→ audit + invalidación de caché en los servicios que referencian la llave).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 4. Persistencia

Liquibase, schema `configuration` (5 changesets): `config_parameters`, `config_audit_trail`, event-publication, seed de parámetros iniciales.

## 5. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Maker-checker | Un valor regulatorio no lo cambia una sola persona sin control. |
| Key-value genérico (no catálogo de productos) | El catálogo rico vive en credit-product; T5 es parámetros calientes por llave. |
| Cambio → evento | Los consumidores invalidan caché sin polling. |
