# T2 — Notifications [Transversal]

> Comunicaciones multicanal disparadas por eventos. Mantiene plantillas, políticas (qué clave dispara qué), preferencias e historial con estado de lectura.
>
> **El destinatario es una entidad abstracta.** El servicio no sabe si notifica a quien pide un préstamo, a un asesor externo, a una distribuidora o a un empleado: recibe un `(recipientType, recipientId)` y **no ramifica sobre el tipo en ninguna parte**.

**Servicio:** `notifications-service` · **Schema:** `notifications` · **Puerto:** 8098 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08-17).

## 1. Invariante NT-12 — sin `@Scheduled`

**No hay jobs de reloj.** Todo trigger temporal viene de un **evento** publicado por el dominio dueño del dato: el recordatorio de vencimiento nace de `credit-portfolio.installment-due`; el aviso de mora, de `credit-portfolio`/`collections`; el de "próximo a vencer", de `collections.pre-due-reminder-triggered`. Notifications nunca revisa cuentas por su cuenta — si hiciera falta recordar algo, el emisor publica el hecho.

## 2. Agregados

| Agregado | Rol |
|---|---|
| `NotificationRecipient` | **A quién se le notifica, sin saber qué es.** Llave `(recipientType, recipientId)`; el tipo es texto libre. |
| `NotificationRecord` | Notificación emitida, con `recipientType`, `eventKey` y estado de lectura. |
| `NotificationTemplate` | Plantilla por `eventKey` + canal + locale. |
| `NotificationPolicy` | Qué clave dispara qué (`PolicyStatus`, `ChannelStrategy`). |
| `NotificationPreference` | Preferencias de contacto. |
| `PartyContactDirectory` / `ProspectContactShadow` | Read models de contacto **del journey de crédito** — hoy son *un productor más* del registro de destinatarios, no el modelo. |
| `CreditAccountProgress` / `ApplicationProspectLink` | Proyecciones para correlacionar avisos. |

### `eventKey` sustituyó a `EventType` como llave (2026-08-17)

`EventType` era un enum cerrado y llaveaba políticas **y** plantillas: **cada emisor nuevo obligaba
a recompilar y redesplegar este servicio** para agregar su caso. Era el acoplamiento más caro,
porque volvía «mandar un aviso» un cambio de código ajeno.

Hoy la llave es `event_key` (texto). El enum sobrevive como **catálogo del journey de crédito** —
OFFER_PRESENTED · WELCOME_ACTIVATED · DISBURSEMENT_COMPLETED · PAYMENT_REMINDER · PAYMENT_OVERDUE ·
INSTALLMENT_PAID · LOAN_SETTLED + los 7 de cobranza— y una clave de otro emisor deja `event_type`
en **nulo** en vez de inventarse uno.

## 3. API REST — `/api/v1/notifications`

**El emisor controla qué manda y a dónde.** Los `channels` que impone ganan sobre la política; sin
política y sin canales impuestos **no se manda nada** —elegir un canal por defecto sería decidir por
el emisor justo en lo que es suyo—.

| Método | Ruta | Para |
|---|---|---|
| `PUT` | `/recipients/{tipo}/{id}` | Alta o actualización de destinatario. Idempotente; un campo nulo **no borra**. |
| `POST` | `/` | Notificar. `{recipientType, recipientId, eventKey, variables, channels?, sourceEventId?}` |
| `GET` | `/feed/{tipo}/{id}?unreadOnly=` | Buzón + `unreadCount` |
| `PUT` | `/feed/{tipo}/{id}/read-all` | Marcar leído |
| `GET` | `/history/{partyId}` · `/preferences/{partyId}` · `/policies` | Camino del journey de crédito (previo) |
| `POST` | `/policies` | Catálogo de políticas |
| `PUT` | `/preferences/{partyId}` · `/{notificationId}/read` · `/read-all/{recipientId}` | Camino previo |

## 4. Eventos Kafka

### El carril genérico — `notifications.notification-requested`

**Un solo tópico, el mismo contrato que el `POST`.** Un camino síncrono para quien nos notifica
desde fuera, uno asíncrono para los hechos internos. notifications consume **uno** y no sabe nada
de nadie: un aviso nuevo se publica y ya, **sin tocar este servicio**.

Un destinatario no registrado se **descarta y no rebota**: reintentarlo no lo haría aparecer, y
dejar el mensaje rebotando bloquearía la partición para todos los avisos que sí tienen a quién
llegarle.

### Los diez listeners de dominio (legacy, en migración)

**Consume:** `credit-portfolio.credit-account-activated` (bienvenida), `credit-portfolio.disposition-completed` (desembolso), `credit-portfolio.installment-due` (recordatorio), `credit-portfolio.balance-updated`, `origination.prospect-created`, `origination.offer-presented`, `payments.payment-applied`, `collections.pre-due-reminder-triggered`, `collections.dunning-requested`, `collections.payment-thanks`.

> Conviven con el carril genérico **mientras se migran** (fase 5 del plan). Es una migración, no un
> diseño: funcionan y están probados, y romperlos para ganar simetría sería cambiar valor por forma.

**Produce:** `notifications.notification-sent`, `notifications.notification-failed` (traza/analítica).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 5. Persistencia

Liquibase, schema `notifications` (13 changesets): `notification_policies`, `notification_templates`, `notification_preferences`, `notification_records`, read models de contacto, `credit_account_progress`, event-publication, seeds de políticas/plantillas, estado de lectura (011), **destinatario abstracto + `event_key` (012)**, políticas y plantillas de backoffice (013).

## 6. Efecto colateral bueno

Al desembolsar por eventos honestos (§ disbursement), la notificación de desembolso se corrige sola: `disposition-completed` ahora se publica cuando `disbursement.completed` confirma la liquidación (con `externalRef` real), no antes. Notifications **no cambió una línea** — se arregló el emisor y la cadena se corrigió.

## 7. Lo que falta

**Faltan los emisores.** El carril existe y está probado, pero **ningún servicio publica todavía**
`notifications.notification-requested`. Consecuencia visible: la campana del backoffice funciona y
está vacía, y los 13 eventos de la colocación B2B2C no producen ningún aviso.

El catálogo actual cubre **sólo al cliente directo B2C**. Distribuidores y beneficiarias de crédito
—los otros dos perfiles de la app— no tienen ni una notificación.

Análisis por perfil y orden sugerido en
[`docs/NOTIFICATIONS_EVENT_PLAN.md`](../NOTIFICATIONS_EVENT_PLAN.md) §6.

