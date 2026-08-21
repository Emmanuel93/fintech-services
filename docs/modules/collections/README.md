# D8 — Collections [Supporting]

**Estado:** ✅ Implementado, con backoffice conectado
**Tipo:** Supporting
**Schema DB:** `collections`
**Paquete Java:** `com.fintech.collections`

> Reconciliado con el código el 2026-08-14. Incluye la cadencia automática, el registro único de
> gestión y el freno de comunicaciones. Lo anterior a esta revisión describía cuatro buckets
> (`B91_PLUS`) y un umbral de quebranto de 91 días; el código tiene siete buckets y 181 días.

## Responsabilidad

Gestión de cobranza, recuperación y quebranto. **Nunca modifica saldos directamente.**
Cobra siempre al `obligorPartyId`.

Los saldos que guarda son una **proyección local** (`AccountBalanceSnapshot`) alimentada por
eventos de credit-portfolio. Van un instante por detrás de la fuente de verdad, y es deliberado:
`totalDebt` del caso sirve para validar el tope de condonación —el dominio valida contra él—, no
para mostrarse como saldo. El backoffice pinta el de cartera.

## Buckets de mora y estrategia

| Bucket | Días | `strategy` |
|---|---|---|
| `CURRENT` | 0 | `PRE_DUE_REMINDER` |
| `B1_30` | 1–30 | `AUTO_NOTIFY` |
| `B31_60` | 31–60 | `AGENT_ASSIGNED_RESTRUCTURE_OFFER` |
| `B61_90` | 61–90 | `INTENSIVE_LEGAL_PREVENTIVE` |
| `B91_120` | 91–120 | `PRE_WRITEOFF_EXTERNAL_AGENCY` |
| `B121_180` | 121–180 | `EXTERNAL_AGENCY_QUITA_OFFER` |
| `B181_PLUS` | 181+ | `WRITEOFF_CANDIDATE` |

## Cadencia automática — el ciclo de cinco días

Desde que un caso entra en mora, **un mensaje diario durante cinco días, y se acaba.** Después no
sale nada más de forma automática: la gestión pasa a una persona.

| Día | `DunningStep` | `EventType` en notifications | Qué dice |
|---|---|---|---|
| 1 | `RECORDATORIO` | `COLLECTION_REMINDER` | «Parece que se te pasó». Sin consecuencias |
| 2 | `COMO_PAGAR` | `COLLECTION_HOW_TO_PAY` | Mismo tono, con las formas de pago |
| 3 | `ATRASO` | `COLLECTION_OVERDUE_NOTICE` | Nombra el atraso y lo que se acumula |
| 4 | `HISTORIAL` | `COLLECTION_CREDIT_HISTORY` | Ponerse al corriente protege tu historial |
| 5 | `OFRECER_AYUDA` | `COLLECTION_OFFER_HELP` | Cierra ofreciendo hablar y buscar opciones |

**Por qué cinco y no una escalada hasta el quebranto.** El recordatorio funciona los primeros días
porque la causa más probable es el olvido. A partir de ahí el que no pagó no es que no se haya
enterado, y escribirle solo durante meses no cobra más: satura el canal, entrena a ignorarlo y
acumula quejas. El esfuerzo tardío rinde con una persona, no con un job.

**Sobre el escalón 4 y el buró.** Se menciona el historial crediticio **una sola vez**, como
consecuencia evitable, nunca como amenaza. No es sólo cortesía: el atraso se reporta a las
sociedades de información crediticia porque es un hecho del crédito, no una decisión de cobranza.
No hay nada que ofrecer a cambio de no reportar, así que un «si no pagas hoy te reportamos» promete
algo que el servicio no controla.

### Cómo funciona

```
DunningScheduleJob (10:00)
   └─> DunningService.runDailyCycle()
         ├─ ¿dunning-enabled?           no → 0 envíos
         ├─ ¿dentro de 08:00–20:00?     no → 0 envíos, se registra el aviso
         ├─ casos activos con mora ≥ 1
         ├─ descarta los que no caen en día 1..5
         ├─ descarta los que tienen freno vivo  (una consulta, no una por caso)
         └─ publica collections.dunning-requested
                          │
                    notifications  (política + preferencias eligen canal y plantilla)
                          │
              notifications.notification-sent / -failed
                          │
                    collections ──> ContactAttempt(origin=AUTOMATIC)
```

**Cobranza no envía: pide que se envíe.** notifications sigue siendo el único que habla con los
proveedores, y así no se duplican plantillas, preferencias ni opt-outs. Pero la **prueba** de que
salió vive en el caso, que es donde se audita.

**La correlación va en `sourceEventId`**, con la forma `dunning:{caseId}:{paso}`. notifications lo
devuelve tal cual en los eventos de entrega, así que cobranza reconoce su propio encargo sin que
notifications tenga que conocer el concepto de «caso». Lo que no lleve ese prefijo —un aviso de
bienvenida, uno de desembolso— se ignora y no ensucia el expediente.

**Se registra el desenlace, no la intención.** El apunte se escribe al confirmarse la entrega o el
fallo. Anotar la petición haría que la bitácora afirmara que se contactó a alguien cuyo teléfono
estaba dado de baja.

### Registro único de gestión

Todo contacto acaba en `contact_attempts`, lo origine una persona o el sistema.

| Campo | Manual | Automático |
|---|---|---|
| `origin` | `MANUAL` | `AUTOMATIC` |
| `agent_id` | obligatorio | **null** — no hay persona a quien atribuirle el acto |
| `notification_id` | null | el mensaje en notifications |
| `dunning_step` | null | 1–5 |
| `result` | `ANSWERED`, `NO_ANSWER`, `WRONG_NUMBER`, `PROMISE_MADE` | `DELIVERED`, `READ`, `FAILED` |
| Cuenta contra el tope CT-03 | **sí** | no |

Los constraints de la base impiden las dos combinaciones incoherentes: un manual sin agente y un
automático con agente.

> **El tope de 3/día cuenta sólo los `MANUAL`.** Si la cadencia consumiera el cupo, el gestor
> llegaría al caso sin intentos disponibles por mensajes que él no mandó, y un límite pensado para
> proteger al cliente acabaría impidiendo la única llamada capaz de resolverle el problema.
> **Es una interpretación regulatoria y está sin validar por cumplimiento** — vive en un solo punto
> (`ContactOrigin`) para que invertirla sea cambiar una consulta.

`channel` es un catálogo cerrado (`PHONE`, `SMS`, `EMAIL`, `WHATSAPP`, `PUSH`, `VISIT`, `IVR`): un
valor desconocido devuelve **400**. Era texto libre, y el mismo hecho entraba como «PHONE», «phone»
y «Teléfono» según qué cliente lo mandara.

## Freno de comunicaciones

`CommunicationHold` es lo que detiene la cadencia. **Sólo calla lo automático**: el agente puede
seguir llamando, porque puede haber razón para hacerlo y quitarle esa opción convertiría el freno en
un escondite donde los casos se pierden.

| Motivo | Se abre cuando | Dura |
|---|---|---|
| `ACTIVE_PROMISE` | Se registra una promesa | Fecha prometida + `promise-grace-days` (2) |
| `PROMISE_KEPT` | Un pago cubre lo prometido | `promise-kept-quiet-days` (7) |
| `AGREEMENT_IN_FLIGHT` | Convenio propuesto o aceptado | Vigencia del convenio |
| `AGENCY_ASSIGNED` | El caso pasa a despacho | Mientras esté asignado |
| `MANUAL` | Un agente lo pausa, con motivo | Lo que se indique |

Un abono parcial **no** cumple la promesa pero extiende el freno `promise-partial-quiet-days` (3):
tratar igual a quien abonó la mitad que a quien ignoró todo es la forma de conseguir que la próxima
vez no abone nada.

Una promesa rota **libera** el freno explícitamente en vez de dejarlo expirar, para que la cadencia
pueda distinguir «todavía no le toca» de «se le acabó el plazo y no cumplió».

Se guarda como historial y no como bandera: la pregunta que hay que poder contestar es «por qué no
se le escribió a esta persona entre el 3 y el 12», y una bandera sólo dice el estado de hoy. Cuando
hay varios frenos vivos, vale el que termina más tarde.

### El agradecimiento

`collections.payment-thanks` es el único mensaje del módulo que **no pide nada**. Va por un tópico
aparte a propósito: si formara parte de la cadencia, el freno `PROMISE_KEPT` que abre el propio
cumplimiento silenciaría justo el mensaje que hay que mandar.

La plantilla distingue si quedó saldo (`daysDelinquent > 0`) o no. Decirle «quedaste al corriente»
a quien sigue debiendo hace que el siguiente aviso parezca un error del banco.

## Ciclo de vida del caso

```
OPEN → MANAGED → LEGAL → WRITTEN_OFF · CLOSED
```

Ninguna transición se dispara desde la UI: todas vienen de eventos de cartera. Abre cuando
`delinquency-status-updated` trae bucket ≥ `B1_30`, escala al cambiar de tramo, y cierra cuando la
mora vuelve a cero o la cuenta se liquida. **No hay operación de cerrar caso.**

## Convenios — maker-checker

```
PROPOSED → ACCEPTED → EXECUTED
         ↘ REJECTED  ↘ EXPIRED
```

`authorize` autoriza **y ejecuta** en un paso: no existe un estado intermedio. `EXPIRED` lo pone
`AgreementExpirationJob` a las 08:15 tras `agreement-response-days` sin respuesta.

⚠️ **El dominio no compara `authorizedBy` contra quien propuso.** El control de cuatro ojos es hoy
convención, no regla: cualquiera puede autorizar lo que él mismo propuso. Pendiente en el backlog.

## Flujo de quebranto (write-off)

```
daysDelinquent ≥ write-off-threshold-days (181)
  → WriteOffRequested (sólo intención: no persiste nada, 202 sin cuerpo)
  → Aprobación con acta
  → WriteOffExecuted → credit-portfolio consume y pone los saldos a 0
```

La separación intención/ejecución mantiene a D4 con autoridad sobre sus saldos. El desglose
(capital / intereses / moratorios) se **congela al aprobar**: es la cifra del acta, no el saldo de
hoy.

## Parámetros configurables

Viven en `application.yml` bajo `fintech.collections`. Los siete de negocio se exponen por API
(`GET /api/v1/collections/config`) para que el canal valide con los mismos valores en vez de
copiarlos: una copia en el cliente empieza a mentir en cuanto ops mueva una variable de entorno, y
el usuario descubre el límite real con un 422 que la pantalla le dijo que no iba a pasar.

| Parámetro | Default | Regla |
|---|---|---|
| `contact-allowed-hours-start` / `-end` | 8 / 20 | CT-02 (CONDUSEF) |
| `max-contact-attempts-per-day` | 3 | CT-03 (CONDUSEF) |
| `max-forgiveness-pct` | 0.30 | AG-08 |
| `max-term-extension-months` | 12 | AG-09 |
| `write-off-threshold-days` | 181 | ES-03 |
| `agreement-response-days` | 5 | AG-05 |

Los de la cadencia son internos —el cliente no los necesita para validar nada— y no se publican:

| Parámetro | Default | Qué controla |
|---|---|---|
| `dunning-enabled` | `true` | Apaga la cadencia entera sin tocar el resto del módulo |
| `promise-grace-days` | 2 | Margen tras la fecha prometida antes de dar la promesa por rota |
| `promise-kept-quiet-days` | 7 | Silencio que concede haber cumplido |
| `partial-payment-quiet-days` | 3 | Silencio que suma un abono parcial |

## API

Prefijo `/api/v1/collections`. El canal de backoffice lo expone como `/collections/**`
(plural, sin versionar) — ver `CollectionsController` en channel-backoffice-service.

### Lectura

| Método | Ruta | Devuelve |
|---|---|---|
| `GET` | `/cases` | Página de casos. Filtros opcionales: `status`, `bucket`, `productType`, `assignedAgentId`, `minDaysDelinquent` |
| `GET` | `/cases/{caseId}` | Un caso |
| `GET` | `/accounts/{creditAccountId}/case` | El caso **activo** de una cuenta |
| `GET` | `/cases/{caseId}/contact-attempts` | Contactos (manuales y automáticos) + `todayCount` y `maxPerDay` |
| `GET` | `/cases/{caseId}/communication-holds` | Historial de frenos, con `active` ya resuelto |
| `GET` | `/cases/{caseId}/payment-promises` | Promesas del caso |
| `GET` | `/cases/{caseId}/agreements` | Historial de convenios, incluidos rechazados y expirados |
| `GET` | `/agreements/awaiting-authorization` | Convenios `ACCEPTED` — bandeja de cuatro ojos |
| `GET` | `/accounts/{creditAccountId}/write-off` | Acta de quebranto, 404 si no se quebrantó |
| `GET` | `/bureau-reports` | Reportes `PENDING` y `FAILED`. Los `SUBMITTED` no son consultables |
| `GET` | `/config` | Los siete parámetros de arriba |

`todayCount` usa el mismo corte de día **y el mismo filtro** que la validación CT-03 —sólo intentos
de agente—, para que el cliente pueda mostrar «2 de 3» sin recalcularlo. Contar aquí también los
automáticos hacía que la pantalla dijera «2 de 3» a un gestor con el cupo intacto, y ése es el
número con el que decide si todavía puede llamar.

### Escritura

| Método | Ruta | Notas |
|---|---|---|
| `POST` | `/cases/{caseId}/contact-attempts` | El agente sale del token, no del cuerpo. `channel` es catálogo cerrado: un valor inválido devuelve 400 |
| `POST` | `/cases/{caseId}/payment-promises` | Una activa por caso. **No valida que la fecha sea futura** |
| `POST` | `/cases/{caseId}/agreements` | Sólo en `MANAGED`/`LEGAL` y sin otro convenio activo |
| `PUT` | `/agreements/{id}/accept` · `/reject` | Sin cuerpo, sólo desde `PROPOSED` |
| `PUT` | `/agreements/{id}/authorize` | Autoriza y ejecuta |
| `POST` | `/cases/{caseId}/request-write-off` | 202 sin cuerpo. **No persiste**: sólo publica la intención |
| `POST` | `/cases/{caseId}/write-offs` | Crea el acta inmutable y cierra el caso |

## Errores

ProblemDetail (RFC 7807) con el código en `type`.

| Código | HTTP | Cuándo |
|---|---|---|
| `COLLECTIONS_CASE_NOT_FOUND` | 404 | Caso inexistente o cuenta sin caso activo |
| `COLLECTIONS_AGREEMENT_NOT_FOUND` | 404 | Convenio inexistente |
| `COLLECTIONS_INVALID_CASE_STATE` | 422 | Horario, tope de intentos, promesa o convenio duplicado, caso terminal, umbral de quebranto |
| `COLLECTIONS_INVALID_AGREEMENT_STATE` | 422 | Transición inválida, o `RESTRUCTURE` sin plazo / plazo excedido |
| `COLLECTIONS_FORGIVENESS_LIMIT_EXCEEDED` | 422 | Condonación por encima del tope |

`COLLECTIONS_INVALID_CASE_STATE` cubre seis situaciones con un solo código: el motivo concreto y su
número van en `detail`, así que el cliente debe mostrar el `detail`, no traducir el código.

## Reglas CONDUSEF

- **Máximo 3 intentos de contacto por día y por caso**, contando sólo los de agente
  (`origin = MANUAL`). Los de la cadencia se registran pero no consumen cupo — decisión pendiente de
  validar por cumplimiento, ver el registro único de gestión.
- **Horario permitido 08:00–20:00**, con la hora del servidor, y **aplica a todo**: la corrida de la
  cadencia lo comprueba antes de publicar nada. Un WhatsApp automático a las 22:00 es un contacto
  fuera de horario igual que una llamada.
- Las notificaciones de escalación no son opt-outables.
- **El buró no se usa como palanca.** El historial crediticio se menciona una vez en el ciclo, como
  consecuencia evitable. El atraso se reporta porque es un hecho del crédito, no una decisión de
  cobranza: no hay nada que ofrecer a cambio de no reportar.

## Jobs

| Cuándo | Qué |
|---|---|
| 02:00 | Reintento de reportes a buró — indefinido, sin reintento manual |
| 06:00 lunes | Candidatos a quebranto |
| 08:00 | Promesas vencidas → `BROKEN`, y se libera su freno |
| 08:15 | Convenios sin respuesta → `EXPIRED` |
| **10:00** | **Corrida de la cadencia** — `DunningScheduleJob` |

**El orden de 08:00 y 10:00 es deliberado.** Una promesa que venció anoche tiene que estar marcada
como rota antes de que la cadencia decida a quién escribe; si no, el caso seguiría silenciado un día
más por un compromiso que ya se incumplió.

**Las 10:00 tampoco son arbitrarias:** caen dentro de la ventana permitida con margen por los dos
lados —arrancar a las 08:00 en punto deja la corrida a merced de que el reloj del contenedor vaya un
minuto adelantado— y a media mañana, que es cuando un recordatorio de pago se lee.

Las promesas se cumplen solas: un pago que cubre el importe la marca `KEPT`, dispara el
agradecimiento y abre el freno de siete días. **Un pago parcial no la cumple**, pero extiende el
freno tres días.

## Eventos

### Publicados

| Tópico | Consumidor |
|---|---|
| `collections.case-created` · `.case-escalated` | — (traza) |
| `collections.contact-attempt-registered` | — (traza) |
| `collections.payment-promise-made` · `.payment-promise-broken` | — (traza) |
| `collections.agreement-proposed` | — (traza) |
| `collections.agreement-executed` | credit-portfolio, risk |
| `collections.write-off-requested` | — (traza) |
| `collections.write-off-executed` | credit-portfolio |
| `collections.recovery-payment-applied` | accounting |
| `collections.bureau-report-submitted` | — (traza) |
| `collections.pre-due-reminder-triggered` | notifications |
| **`collections.dunning-requested`** | **notifications** |
| **`collections.payment-thanks`** | **notifications** |

### Consumidos

| Tópico | Para qué |
|---|---|
| `credit-portfolio.delinquency-status-updated` | Abre, escala y cierra casos |
| `credit-portfolio.credit-account-activated` | Siembra el snapshot local |
| `credit-portfolio.balance-updated` | Sincroniza saldos; `SETTLED` cierra el caso |
| `credit-portfolio.installment-upcoming` | Recordatorio pre-vencimiento |
| `payments.payment-applied` | Cumple promesas y detecta recuperación post-quebranto |
| **`notifications.notification-sent`** | **Registra el contacto automático entregado** |
| **`notifications.notification-failed`** | **Registra el intento fallido** |

## Pendientes conocidos

1. **`authorizedBy` no se compara contra quien propuso** — el cuatro ojos no está impuesto en servidor.
2. **`assignedAgentId` y `externalAgencyId` siempre nulos** — `assignAgent()` y `escalateToAgency()`
   existen en el dominio y nadie los invoca. Sin ellos no hay reparto de trabajo ni filtro «mis casos»,
   y el freno `AGENCY_ASSIGNED` no se abre nunca.
3. **La solicitud de quebranto no se persiste** — viaja como evento, así que el comité no tiene
   bandeja que mirar.
4. **No hay senders configurados.** La cadencia publica y notifications resuelve canal y plantilla,
   pero ningún proveedor entrega todavía. La instrumentación está completa; falta el último tramo.
5. **`AgreementType` no admite quita dentro de reestructura.** `RESTRUCTURE` y `QUITA_PARCIAL` son
   excluyentes y `forgivenAmount` sólo existe en la segunda, así que el convenio más común —«te
   reestructuro y te condono los moratorios»— no se puede expresar. Necesita un `forgivenConcept`
   (`MORATORIOS` | `INTERESES` | `CAPITAL`), que además determina el asiento contable.
6. **El tope CT-03 sobre automáticos está sin validar por cumplimiento.** Ver la nota en el registro
   único de gestión.
7. **Capacidades propias** — el canal gatea `/collections/**` con `portfolio.view`. Autorizar un
   convenio y aprobar un quebranto mueven saldo y merecen `collections.authorize`.
8. **El conector de buró es un stub** — `bureauReference` llega como `BUREAU-STUB-XXXXXXXX`; no es
   folio oficial.

---

El diseño completo de la estrategia —incluido el tratamiento contable del quebranto con recuperación
posterior y las decisiones abiertas— vive en `docs/ESTRATEGIA_COBRANZA.md` del repo de backoffice.
