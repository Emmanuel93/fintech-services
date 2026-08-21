# T2 — Notifications [Transversal]

**Estado:** ✅ Implementado
**Tipo:** Transversal
**Schema DB:** `notifications`
**Paquete Java:** `com.fintech.notifications`

> Reconciliado con el código el 2026-08-14. Lo anterior decía «Pendiente» y describía un fallback
> fijo `PUSH → SMS → EMAIL`; el canal lo decide una política por evento y nivel de valor.

## Responsabilidad

Suscriptor de eventos. **Nunca modifica estado de ningún dominio.** Resuelve por dónde y con qué
texto se le habla al cliente, y deja constancia de cada envío.

Es el **único que habla con los proveedores**. Los dominios no envían: publican lo que pasó —o piden
que se avise— y aquí se decide el canal. Repartir esa decisión duplicaría plantillas, preferencias y
opt-outs en varios sitios que se desincronizan.

## Canales

`PUSH_NOTIFICATION` · `EMAIL` · `WHATSAPP` · `SMS` · `IVR_CALLBACK` · `IN_APP`

No hay una cascada fija. Cada `NotificationPolicy` combina `(eventType, valueTier)` y define canal
primario, canales de respaldo y estrategia:

| Estrategia | Cuándo |
|---|---|
| `SEQUENTIAL_FALLBACK` | Se intenta el primario y se cae al siguiente sólo si no está disponible |
| `SIMULTANEOUS` | Se manda por todos a la vez — picos que no pueden perderse |

## Tipos de evento

### Ciclo del crédito

`OFFER_PRESENTED` · `WELCOME_ACTIVATED` · `DISBURSEMENT_COMPLETED` · `PAYMENT_REMINDER` ·
`PAYMENT_OVERDUE` · `INSTALLMENT_PAID` · `LOAN_SETTLED`

### Cobranza

Los cinco escalones del ciclo de cobranza son **tipos distintos y no uno solo con variable de
tono**: cada uno lleva su plantilla y su política, porque el que menciona el historial crediticio no
puede salir por el mismo canal ni con el mismo texto que el que sólo recuerda una fecha.

| Día del ciclo | `EventType` | Tono |
|---|---|---|
| 1 | `COLLECTION_REMINDER` | «Parece que se te pasó» |
| 2 | `COLLECTION_HOW_TO_PAY` | Mismo tono, con las formas de pago |
| 3 | `COLLECTION_OVERDUE_NOTICE` | Nombra el atraso |
| 4 | `COLLECTION_CREDIT_HISTORY` | El historial, una sola vez y en positivo |
| 5 | `COLLECTION_OFFER_HELP` | Ofrece hablar |

Más `COLLECTION_PAYMENT_THANKS` (el único que no pide nada), `COLLECTION_AGREEMENT_OFFERED` y
`COLLECTION_AGREEMENT_EXECUTED`.

> **`COLLECTION_CREDIT_HISTORY` no promete evitar el reporte.** El atraso se informa a las
> sociedades de información crediticia porque es un hecho del crédito, no una decisión de cobranza,
> así que no hay nada que ofrecer a cambio. La plantilla dice que ponerse al corriente protege el
> historial; nunca «si no pagas hoy te reportamos».

## Reglas

- Plantillas parametrizadas por `(eventType, locale)` con sustitución de variables `{{nombre}}`.
- Notificaciones regulatorias **no opt-outable**: `ApplicationRejected` (CONDUSEF),
  `WriteOffExecuted`, `CollectionCaseEscalated`.
- El opt-out de un canal bloquea ese canal; **no bloquea las regulatorias**.
- **La ventana horaria y el freno de comunicaciones NO se deciden aquí.** Quien pide el envío ya los
  evaluó: collections comprueba la ventana CONDUSEF y sus frenos antes de publicar. Duplicar esa
  lógica pondría dos relojes a decidir lo mismo.

## Eventos

### Consumidos

| Tópico | Produce |
|---|---|
| `origination.offer-presented` | `OFFER_PRESENTED` |
| `origination.prospect-created` | Bienvenida |
| `credit-portfolio.credit-account-activated` | `WELCOME_ACTIVATED` |
| `credit-portfolio.disposition-completed` | `DISBURSEMENT_COMPLETED` |
| `credit-portfolio.installment-due` | `PAYMENT_REMINDER` o `PAYMENT_OVERDUE` según el día |
| `credit-portfolio.balance-updated` | `LOAN_SETTLED` cuando llega `SETTLED` |
| `payments.payment-applied` | `INSTALLMENT_PAID` |
| `collections.pre-due-reminder-triggered` | `PAYMENT_REMINDER` |
| **`collections.dunning-requested`** | **El escalón que indique el payload** |
| **`collections.payment-thanks`** | **`COLLECTION_PAYMENT_THANKS`** |

### Publicados

| Tópico | Consumidor |
|---|---|
| `notifications.notification-sent` | **collections** |
| `notifications.notification-failed` | **collections** |

Ambos llevan **`sourceEventId`**: el identificador con el que llegó la petición, devuelto tal cual.
Es lo que permite a quien pidió el envío reconocer su propio encargo sin que notifications tenga que
conocer los conceptos de cada dominio —collections usa `dunning:{caseId}:{paso}` y lo interpreta al
recibirlo—. Aquí sólo se acarrea.

Ese mismo identificador da la **idempotencia**: estable por origen, dos publicaciones del mismo
hecho no producen dos mensajes.

## Registro

`NotificationRecord` guarda `notificationId`, `sourceEventId`, `recipientId`, `eventType`, `channel`,
`status`, `failureReason`, `sentAt` y `readAt`. Consultable por
`GET /api/v1/notifications/history/{partyId}`.

Para cobranza ese historial **no basta**: la constancia tiene que estar en el expediente del caso,
que es donde se audita y donde el gestor mira antes de llamar. Por eso collections consume los
eventos de entrega y escribe su propio `ContactAttempt` — ver el README de D8.

## Pendientes conocidos

1. **No hay senders configurados.** Las políticas resuelven canal y las plantillas renderizan, pero
   ningún proveedor entrega todavía. Toda la instrumentación aguas arriba está completa.
2. **Sin rate limiting ni quiet hours propias.** Hoy dependen de que quien pide el envío los respete.
3. **`readAt` no lo actualiza nadie** — `READ` sólo llegará cuando haya un proveedor que lo informe.
