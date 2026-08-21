# Notificaciones por evento — plan de ejecución

**Fecha:** 2026-08-17 · **Estado:** propuesta, no se ha escrito código.
**Verificado contra el código**, no derivado del diseño.

---

## 1. Diagnóstico: el acoplamiento que queda

El destinatario ya es abstracto y el `eventKey` ya es texto. Pero **notifications-service todavía
consume diez tópicos de dominio directamente**:

```
collections.dunning-requested          credit-portfolio.balance-updated
collections.payment-thanks             credit-portfolio.credit-account-activated
collections.pre-due-reminder-triggered credit-portfolio.disposition-completed
origination.offer-presented            credit-portfolio.installment-due
origination.prospect-created           payments.payment-applied
```

Cada uno trae su `@KafkaListener`, su record de payload y su rama en el disparador. Es decir: **cada
hecho nuevo que quiera avisar algo obliga a escribir código dentro de notifications**. Es el mismo
acoplamiento que quitamos del enum, movido un piso más abajo.

Y el endpoint `POST /notifications` que se publicó resuelve lo externo, pero para lo interno pedirle
a un servicio de dominio que haga una llamada HTTP síncrona a notifications es peor: acopla su
transacción a que el notificador esté vivo.

---

## 2. La arquitectura: un solo tópico genérico

> **`notifications.notification-requested`** — el mismo contrato que el POST, en asíncrono.

```json
{
  "sourceEventId": "app-9f1c-approved",
  "recipientType": "STAFF",
  "recipientId":   "…uuid…",
  "eventKey":      "CLIENTES_ASIGNADOS",
  "variables":     { "cantidad": "40", "ejecutivo": "Ximena Herrera" },
  "channels":      ["IN_APP"]
}
```

- **notifications consume UN tópico y no sabe nada.** Ni de origination, ni de cobranza, ni de quién
  es `STAFF`. Un hecho nuevo no toca este servicio: se publica y ya.
- **El emisor decide destinatario, clave y canales**, igual que en el POST. Un camino síncrono para
  lo externo, uno asíncrono para lo interno, **un solo contrato**.
- El payload se declara por servicio como record propio, siguiendo la convención del monorepo
  (cada consumidor define su `…Payload`), en vez de una clase compartida que volvería a atar a
  todos con el calendario de despliegue de notifications.

**Lo que NO hay que hacer:** que notifications consuma `origination.prospect-created` para armar
avisos de backoffice. Funcionaría hoy y repetiría exactamente el problema que este plan resuelve.

---

## 3. Qué se puede notificar hoy del journey del cliente — y qué no

Verificado en el código, no supuesto:

| Hecho | ¿Existe el evento? | ¿Hay destinatario identificable? | Veredicto |
|---|---|---|---|
| Alta de prospecto | ✅ `origination.prospect-created` | ❌ **no** | ver §3.1 |
| Cliente asignado a un ejecutivo | ❌ **no se emite** | ✅ el ejecutivo | **falta el evento** |
| Solicitud asignada a un analista | ❌ no existe el concepto | ❌ | **es una feature, no un aviso** |
| Documentos recibidos | ❌ endpoint sin evento | ⚠️ quien los pidió, si se guarda | falta evento + dato |
| Solicitud aprobada / rechazada | ✅ `origination.application-approved/-rejected` | ⚠️ vía el ejecutivo del cliente | viable |

### 3.1 El alta de prospecto no tiene a quién avisarle

`ProspectCreatedEvent` no lleva promotor ni ejecutivo, y el Party nace **de** ese mismo evento: en
el instante del alta no hay nadie asignado todavía. Avisar «entró un alta» a todo el que tenga el
rol no es una campana, es una bandeja con luces.

### 3.2 «Algo que revisar si se le asignó» no existe como hecho

Origination tiene `decidedBy` —quién decidió— pero **ningún concepto de asignación**: las
solicitudes viven en una cola (`GET /underwriting/applications`), no en la bandeja de una persona.
No es que falte la notificación: falta la asignación. Construir el aviso primero sería avisar de
algo que nunca ocurre.

### 3.3 El principio que decide qué merece campana

> **La campana es para lo que es tuyo. La bandeja es para lo del equipo.**

Si el hecho no tiene una persona identificada, no es campana. Esto ya descartó tres candidatos
arriba y evita que la campana se vuelva un segundo tablero que nadie mira.

---

## 4. Plan por fases

### Fase 1 · El carril genérico — sin esto nada más sirve · ~0.5 día

1. Tópico `notifications.notification-requested`.
2. `NotificationRequestedPayload` + un `@KafkaListener` en notifications que llama a
   `RecipientNotificationService.notify(...)`. **Cero conocimiento de dominio.**
3. Manejo de `UnknownRecipientException`: se registra y se descarta, no se reintenta en bucle —
   un destinatario que no existe no va a existir por reintentar.

*Prueba:* EmbeddedKafka — publicar el evento escribe el registro; un destinatario desconocido no
tumba el consumidor.

### Fase 2 · Que el personal exista como destinatario · ~0.5 día

Sin esto, **todo aviso a `STAFF` muere en `UNKNOWN_RECIPIENT`**.

1. Alta explícita cuando el BFF da de alta a un empleado (`/staff/**`).
2. **Alta perezosa** al pedir el buzón: el BFF ya tiene `staffMe(bearerToken)` con correo y nombre,
   así que registra al llamador si no estaba. Resuelve de golpe a todos los empleados ya sembrados
   sin script de migración.

*Prueba:* pedir el buzón de un empleado nunca registrado lo registra y devuelve buzón vacío, no 404.

### Fase 3 · Los hechos que sí tienen destinatario · ~1 día

| # | Emisor | Evento nuevo | Aviso | Destinatario |
|---|---|---|---|---|
| 1 | `party` | `party.executive-assigned` | «Se te asignaron N clientes» | el ejecutivo |
| 2 | `origination` | `origination.documents-received` | «Llegaron los documentos que pediste» | quien los pidió |
| 3 | `origination` | (reusa `application-approved/-rejected`) | «Se resolvió una solicitud de tu cliente» | el ejecutivo del cliente |

**El 1 es el que más valor da por menos trabajo:** la operación ya existe
(`PartyService.assignExecutive`), sólo no emite nada, y el destinatario es inequívoco.

El 2 exige que origination guarde **quién** pidió los documentos (hoy sólo lo escribe en el log).
El 3 exige resolver ejecutivo desde party en el emisor — o dejarlo para después.

*Prueba:* por cada uno, que el aviso llegue al buzón del destinatario correcto y a nadie más.

### Fase 4 · Políticas y plantillas de las claves nuevas · ~0.5 día

Seed de `notification_policies` y `notification_templates` para cada `eventKey` de backoffice, con
canal `IN_APP`. **Sin política no se manda nada** — es por diseño, y sin este paso las fases 1-3
funcionan y no se ve nada.

### Fase 5 · Migrar los diez listeners legacy · ~1.5 días, posterior

Que cada dominio publique `notification-requested` en vez de que notifications escuche sus tópicos.
Se hace **después** y de a uno: los diez de hoy funcionan y están probados, y romperlos para ganar
elegancia sería cambiar valor por forma. Hasta entonces conviven dos caminos, y eso es una
migración, no un diseño.

**Total fases 1–4: ~2.5 días.**

---

## 5. Decisiones que hay que tomar antes de la Fase 3

1. **¿El aviso #3 va al ejecutivo o a nadie?** Resolver el ejecutivo desde origination implica que
   origination consulte party. Alternativa: que lo emita party al enterarse. Cambia quién publica.
2. **¿Se guarda quién pidió los documentos?** Sin ese dato el aviso #2 no tiene destinatario.
3. **¿Se quiere asignación de solicitudes a analistas?** Es la feature que haría de la bandeja de
   dictamen una campana. Es trabajo de origination, no de notificaciones.

---

## 6. Journey de notificaciones de los usuarios de la app

Tres perfiles distintos usan la misma app, y hoy **el catálogo sólo cubre a uno**.

### 6.1 Qué existe hoy

Las 14 claves del catálogo (`EventType`) son, sin excepción, del **cliente directo B2C**: su oferta,
su bienvenida, su desembolso, sus recordatorios de pago, su liquidación y los cinco escalones de
cobranza. El journey del cliente directo está **completo**.

### 6.2 El hueco: los otros dos perfiles no tienen ninguna

`beneficiary-service` publica **trece eventos** de la colocación B2B2C y **notifications no escucha
ni uno**:

```
placement-invited     kyc-started         kyc-completed        bureau-consent-granted
bureau-ready          placement-approved  placement-rejected   placement-disbursing
placement-disbursed   placement-paid-off  placement-expired    placement-cancelled
placement-failed
```

Es decir: la beneficiaria recibe la liga por WhatsApp desde la app del distribuidor —eso está en el
diseño— pero **después de eso el sistema no le vuelve a hablar nunca**, y al distribuidor tampoco.
Es el hueco más grande de notificaciones, y es de los dos perfiles nuevos.

### 6.3 Distribuidor — lo que necesita saber

Su negocio es colocar y cobrar. Los avisos que le importan son los que le **cambian el dinero
disponible** o le **piden actuar**:

| Clave propuesta | Se dispara con | Por qué merece aviso |
|---|---|---|
| `DIST_BENEFICIARIA_VERIFICADA` | `beneficiary.bureau-ready` | **Le toca decidir.** Es el único aviso del que depende que la colocación avance. |
| `DIST_LIGA_POR_VENCER` | barrido de vencimiento (día 6) | Puede reenviarla antes de perder la colocación. |
| `DIST_LIGA_VENCIDA` | `beneficiary.placement-expired` | Se cerró sola; si la quiere, hay que empezar de nuevo. |
| `DIST_COLOCACION_DEPOSITADA` | `beneficiary.placement-disbursed` | Su línea bajó: es el hecho que le mueve el disponible. |
| `DIST_COLOCACION_FALLIDA` | `beneficiary.placement-failed` | Aprobó y no salió. Sin esto se entera cuando le reclama su clienta. |
| `DIST_BONIFICACION_LIQUIDADA` | `commission.commission-liquidated` | Le pagaron. |
| `DIST_PAGO_A_KREDIUS` | recordatorio de su propia línea | Su línea es un crédito y ya tiene el catálogo B2C. |

**No merece aviso** `kyc-started` ni `kyc-completed`: son avance de trámite, no acción suya. La
pantalla de colocaciones ya los muestra. Avisarlos enseñaría a ignorar la campana.

### 6.4 Beneficiaria — lo que necesita saber

No es usuaria de la app: su relación con el sistema es **la liga y su teléfono**. Notificarla es
más delicado porque no tiene dónde consultar nada.

| Clave propuesta | Se dispara con | Nota |
|---|---|---|
| `BEN_LIGA_ENVIADA` | `beneficiary.placement-invited` | Ya se manda desde la app; formalizarlo aquí lo hace auditable. |
| `BEN_LIGA_POR_VENCER` | barrido (día 6) | Es su último recordatorio útil. |
| `BEN_VERIFICACION_COMPLETA` | `beneficiary.kyc-completed` | «Ya quedó, falta que tu distribuidora decida.» |
| `BEN_DEPOSITO_REALIZADO` | `beneficiary.placement-disbursed` | El dinero llegó a su cuenta. |
| `BEN_RECORDATORIO_PAGO` | calendario de su disposición | **Ver §6.5** |

**No se le avisa que la rechazaron.** Kredius no rechazó: la distribuidora decidió no colocar, y esa
conversación es entre ellas. Un WhatsApp de Kredius diciendo «no te lo dieron» mete a la plataforma
en una relación comercial que no es suya y expone la decisión de su clienta.

### 6.5 El punto que hay que resolver antes de construir 6.4

**¿Quién le cobra a la beneficiaria?** Lo decidimos en `BENEFICIARY_SERVICE_PLAN.md` §3.5: la
deudora frente a Kredius es la distribuidora. Si la cobranza la hace ella, `BEN_RECORDATORIO_PAGO`
**no debe existir** —Kredius estaría cobrando una deuda que no le deben— y la escala 20/17/14/10/6/0
tampoco sería calculable, que es la contradicción que ya está anotada en §10.7 de aquel plan.

Los otros cuatro avisos de 6.4 no dependen de esto y se pueden construir ya.

### 6.6 Cómo se construyen, sin volver a acoplar

**Ninguno de estos avisos se implementa con un listener en notifications.** Cada uno lo publica su
dueño en `notifications.notification-requested`:

- `beneficiary-service` ya tiene los trece eventos y conoce al distribuidor y a la beneficiaria:
  publica el aviso al mismo tiempo que su evento de dominio.
- El destinatario se registra con el mismo mecanismo: `PARTY` para el distribuidor (ya existe),
  y un tipo propio para la beneficiaria —que no es cliente de la plataforma sino contacto de una
  colocación—.

Con eso, agregar los doce avisos de 6.3 y 6.4 **no toca notifications-service en absoluto**: es
trabajo dentro de `beneficiary-service`, que es donde vive el conocimiento.

### 6.7 Orden sugerido

1. `DIST_BENEFICIARIA_VERIFICADA` — el único que **bloquea el flujo del negocio** si falta.
2. `DIST_COLOCACION_DEPOSITADA` + `BEN_DEPOSITO_REALIZADO` — el hecho que ambos esperan.
3. Los dos de liga por vencer — recuperan colocaciones que hoy se pierden en silencio.
4. El resto.
