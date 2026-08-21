# T3 — Audit & Compliance [Transversal]

> Bitácora **inmutable** regulatoria (CNBV/CONDUSEF/UIF). Dos categorías: **hechos de dominio** (suscriptor global de Kafka) y **accesos** (quién consultó/descargó qué, desde dónde y cuándo). Solo roles de auditoría leen; cualquier empleado autenticado escribe su acceso.

**Servicio:** `audit-service` · **Schema:** `audit` · **Puerto:** 8090 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregado: AuditEntry

Dos categorías (`category`): `DOMAIN_EVENT` y `ACCESS`.

**Común:** `entryId`, `eventType`, `domainSource`, `aggregateId`, `partyId`, `correlationId`, `actor`, `payload` (saneado), `createdAt` (sello de registro), `occurredAt` (cuándo ocurrió — **siempre poblado**).

**Contexto de acceso (categoría ACCESS):** `action` (VIEW · SEARCH · DOWNLOAD · EXPORT · MUTATION · LOGIN), `actorRoles`, `actorChannel`, `actorIp`, `userAgent`, `sessionId`, `resourceType` (+ `resourceLabel` legible en la respuesta), `resourceId`, `httpMethod`/`httpPath`/`httpQuery`, `outcome` (SUCCESS · DENIED · ERROR), `statusCode`, `durationMs`.

**Quién actuó:** **`actorEmail` · `actorName` · `actorCurp` · `actorPhone`**, resueltos por el canal. La trazabilidad regulatoria exige identificar a la persona, no a su identificador: del colaborador se congelan nombre, CURP y correo; del cliente o distribuidor, además el teléfono.

**Sobre quién se actuó:** **`subjectName` · `subjectCurp` · `subjectEmail` · `subjectPhone`**, con su id en el `partyId` que ya existía. *Quién actuó* y *sobre quién* son preguntas distintas — la segunda es la que contesta «quién abrió el expediente de esta persona»— y la bitácora sólo sabía responder la primera.

> **Snapshot.** Toda esta identidad se congela al escribir. Si el correo o el teléfono cambian después, la entrada conserva los de entonces: una bitácora que siguiera al dato vivo reescribiría el pasado cada vez que alguien actualiza su perfil y dejaría de servir como prueba de lo que se sabía en ese momento. Por lo mismo **no hay backfill**: lo escrito antes de que existiera un campo se queda sin él.

Otros agregados: `DocumentFileRef` (`DocumentType`) — archivos del expediente; `UIFReport` (`UIFReportType`, `UIFReportStatus`) — reportes UIF.

## 2. Saneo — un secreto que entra no vuelve a salir

`PayloadSanitizer` redacta por nombre de campo (password, secret, otp, token, apikey…) a cualquier profundidad del JSON; el `httpQuery` de un acceso se sanea por parámetro. El log es inmutable y de larga retención: lo que se redacta al entrar, se pierde a propósito.

## 3. Ingesta de acceso (desde el BFF)

`POST /api/v1/audit/access` — lo llama el `AccessAuditInterceptor` del BFF de backoffice por cada request (fire-and-forget). Cualquier empleado autenticado puede escribir su acceso; **leer** la bitácora sigue restringido a `AUDITOR`/`REGULATOR`/`ADMIN`.

## 4. API REST — `/api/v1/audit`

| Método | Ruta | Uso |
|---|---|---|
| `POST` | `/access` | Ingesta de acceso (BFF; authenticated). |
| `GET` | `/entries` | Consulta: filtros `partyId`, `aggregateId`, `eventType`, `actor`, `actorIp`, `action`, `category`, `from`, `to` (roles de auditoría). |
| `GET` | `/entries/{entryId}` | Detalle con payload. |
| `GET` | `/parties/{partyId}/documents` · `/uif-reports` · `/expedition` | Expediente regulatorio. |

## 5. Eventos Kafka — suscriptor global

**Consume:** `origination.*` (prospect-created, score-requested, contract-signed, application-approved/rejected, documents-requested), `scoring.scoring-completed`/`scoring-approved`, `credit-portfolio.credit-account-activated`/`balance-updated`/`payment-rejected`/`charge-rejected`, `charges.charge-applied`/`charge-reversed`, `payments.payment-applied`/`payment-returned`, `product-catalog.product-activated`/`product-retired`, `identity.login-attempted`, `configuration.configuration-updated`.

**Produce:** — (la bitácora es terminal; no publica). La bitácora de acceso llega por REST, no por Kafka.

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 6. Persistencia

Liquibase, schema `audit` (9 changesets): `audit_entries` (+ `006-add-actor`, `007-add-access-context`, `008-add-actor-identity`, `009-add-full-identity`), `document_file_refs`, `uif_reports`, event-publication. Índices por `party_id`, `aggregate_id`, `event_type`, `created_at`, `actor`, `actor_ip`, `action`, `category`, `occurred_at`, `resource`, `actor_curp`, `subject_curp` y `(party_id, occurred_at DESC)` —«todo lo que le pasó a este cliente, lo más reciente primero», con la que empieza cualquier revisión.

## 7. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Suscriptor global de eventos | El QUÉ pasó se captura sin que cada dominio "avise" a auditoría. |
| Acceso capturado en el BFF, fire-and-forget | Auditar la navegación no puede retrasarla ni romperla. |
| "Quién" legible (correo + nombre) congelado | El UUID no dice nada a un auditor; se resuelve al momento y se conserva. |
| Saneo al escribir | Un log inmutable no puede almacenar secretos. |
