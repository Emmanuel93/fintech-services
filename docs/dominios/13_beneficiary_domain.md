# D13 — Beneficiary [Core Domain · Colocación B2B2C]

> Un distribuidor con línea revolvente le presta a sus clientes. Cada cliente hace su propio KYC
> desde su teléfono, autoriza su propia consulta de buró y recibe el depósito en su cuenta; el
> distribuidor ve el historial **completo** —Kredius no filtra por score— y decide, firmando que
> asume el riesgo. El servicio **no inventa dominio: compone el que ya existe**.

**Servicio:** `beneficiary-service` · **Schema:** `beneficiary` · **Puerto:** 8102 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08-17).

## 1. Quién debe

**La distribuidora.** Hay **una sola cuenta de crédito** —su línea `DISTRIBUTOR_LINE`— y cada
colocación es una `Disposition THIRD_PARTY_CREDIT` de esa línea. A la beneficiaria se le informa que
el crédito es suyo, y lo es **frente a la distribuidora**: lo que queda a su nombre es el *contrato
de colocación*, un documento del expediente y **no** una exposición en cartera.

Consecuencia: `collections` sigue viendo un solo sujeto —el distribuidor— y no se abre cuenta por
beneficiaria.

## 2. Agregados

| Agregado | Rol |
|---|---|
| `Placement` | La colocación: distribuidor, beneficiario, monto, plazo y su máquina de estados. |
| `PlacementTransition` | Bitácora append-only de cada cambio de estado — fuente del `timeline[]` de la app. |
| `PlacementLimits` | Los límites del producto: **tope por beneficiario**, mínimo, plazos y escalones. |

**El agregado es la colocación, no el beneficiario.** El diseño permite «enviarle otro préstamo» a
quien ya lo es: una persona tiene *n* colocaciones, cada una con su propio expediente de decisión.

## 3. Máquina de estados — 11 estados, 16 aristas

```
INVITED ─► KYC_IN_PROGRESS ─► KYC_COMPLETED ─► BUREAU_READY ─► APPROVED ─► DISBURSING ─► DISBURSED ─► PAID_OFF
   │             │                   │               │             │            │
   │             │                   └──► FAILED ◄───┴────────────┴────────────┘
   ├──► EXPIRED  ├──► EXPIRED / FAILED
   └──► CANCELLED└──► CANCELLED                      └──► REJECTED
```

Las **16 aristas están fijadas por un test**: cambiarlas tiene que ser una decisión. La prueba es
exhaustiva —los 132 pares (origen, destino)— porque un test por caso feliz dejaría pasar el bug que
importa: una arista de más, que es como una colocación vencida termina aprobada.

Tres estados no son del diseño y existen porque el buró y el desembolso son asíncronos:

- **`KYC_COMPLETED`** — el expediente existe pero el buró no ha respondido. Sin él, un buró lento
  sería indistinguible de un KYC incompleto.
- **`DISBURSING`** — la línea **no se aparta al invitar**, así que dos colocaciones pueden aprobarse
  contra la misma línea y la segunda encontrar saldo insuficiente. Sin este estado, `APPROVED` sería
  una promesa que el servicio no puede cumplir.
- **`FAILED`** — los demás terminales son decisiones de alguien; éste registra que algo se rompió, y
  por eso **siempre lleva motivo**.

Dos reglas del diseño viven en la máquina, no en un servicio:

1. **Revocar sólo antes del expediente.** Con documentos entregados y autorización firmada, la
   salida del distribuidor es `REJECTED` —una decisión con nombre— no una revocación silenciosa.
2. **Vencer sólo mientras la liga vive.** Los 7 días corren sobre la liga, no sobre la colocación.

## 4. Las cuatro reglas que el servicio existe para hacer cumplir

1. **La línea no se aparta al invitar**, se descuenta al aprobar.
2. **El buró se consulta con la autorización de la beneficiaria**, nunca con la del distribuidor —
   que por eso jamás ve la pantalla de consentimiento de su clienta. La constancia (fecha, hora, IP,
   versión del texto) viaja en `beneficiary.bureau-consent-granted`.
3. **Kredius no filtra por score.** El reporte se entrega completo y el distribuidor decide,
   firmando que asume el riesgo. La decisión de `scoring` se ignora deliberadamente.
4. **No se crea prospecto al invitar** — hacerlo dispararía el prefetch de buró de `scoring` antes
   de que ella autorizara nada.

## 5. API REST

### App del distribuidor — `/api/v1/placements`
`GET` (lista) · `POST` (crear + liga) · `POST /{id}/resend` · `/cancel` · `GET /{id}/bureau` ·
`POST /{id}/approve` · `/reject`. El `distributorPartyId` sale del token, **nunca de un parámetro**.

### Backoffice — `/api/v1/backoffice/placements`
Bandeja transversal que cruza **todas** las distribuidoras, con filtros de distribuidora, estado,
`identityStatus` y SLA (`stalledDays`). **Raíz separada a propósito**: si compartieran raíz, un
descuido al declarar la seguridad convertiría la consulta de la app en una fuga de cartera ajena.

### Pública — `/api/v1/beneficiary/public/**`
Los 7 pasos del KYC web, sin JWT, con token de invitado de 15 min. ⚠️ **Pendiente** (ver §8).

## 6. Composición — qué NO se reimplementa

| Necesidad | Dueño |
|---|---|
| Prospecto y documentos del expediente | `origination` |
| Party de la beneficiaria y relación con el distribuidor | `party` |
| Consulta de buró y reporte | `scoring` |
| Descuento de línea y disposición | `wallet` → `credit-portfolio` |
| SPEI a su CLABE | `disbursement` → `stp` |
| Bonificación 20 % decreciente | `commission` |
| Liga por WhatsApp y avisos | `notifications` |
| Bitácora regulatoria | `audit` (suscriptor global) |

## 7. Eventos Kafka

**Produce** — uno por transición, particionado por `placementId` para que el orden se preserve **por
colocación** (lo que no puede reordenarse es el `kyc-completed` antes que el `kyc-started` de la
misma persona):

`placement-invited` · `kyc-started` · `kyc-completed` · `bureau-consent-granted` · `bureau-ready` ·
`placement-approved` · `placement-rejected` · `placement-disbursing` · `placement-disbursed` ·
`placement-paid-off` · `placement-expired` · `placement-cancelled` · `placement-failed`

**El token de la liga nunca viaja en un evento** — un secreto en un tópico es un secreto en los logs
de todos sus consumidores.

**Consume:** `scoring.scoring-completed`, `credit-portfolio.disposition-completed` / `-rejected`.

## 7b. Verificación de identidad — manual hoy, semiautomática después

```
fintech.beneficiary.identity-verification.mode = MANUAL | AUTOMATIC   (default MANUAL)
fintech.beneficiary.identity-verification.thresholds.{facialMatch,liveness,documentAuthenticity}
```

**La bandera controla el flujo, no el arranque.** Los dos modos dan un servicio que funciona;
cambiar de uno a otro es esta línea de configuración y nada más.

| Modo | Qué hace |
|---|---|
| `MANUAL` (hoy) | Todo lo revisa un analista de crédito. **No se llama a nadie** — sin contrato de KYC, aprobar automáticamente sería aprobar sin fundamento. |
| `AUTOMATIC` | El proveedor evalúa y el analista recibe **sólo las excepciones**. |

### La degradación es el punto, no un detalle

```
AUTOMATIC → se pregunta al proveedor:
   · caído / sin contrato / timeout → revisión humana   ← RESILIENCIA
   · lanzó una excepción            → revisión humana   ← RESILIENCIA
   · no pudo validar un documento   → revisión humana
   · algún umbral no alcanzado      → revisión humana
   · todo por encima de umbral      → VERIFIED automático (source PROVIDER)
```

**Ninguna rama rompe el flujo.** Un proveedor de KYC es un tercero y se cae; cuando pase, la
colocación sigue avanzando por el camino de siempre —una persona— y el costo de la caída es trabajo
de analista, no colocaciones atoradas.

Una métrica **configurada y no reportada** cuenta como no alcanzada: no saber no es aprobar.

### El motivo siempre viaja

`identity_review_notes` guarda por qué está donde está: `REVISION_MANUAL_CONFIGURADA`,
`PROVEEDOR_NO_DISPONIBLE: timeout`, `UMBRAL_NO_ALCANZADO: facialMatch 0.82 < 0.9`,
`DOCUMENTO_NO_VALIDADO: INE_ILEGIBLE`. Es lo que el analista lee **antes** de abrir el expediente;
sin eso su cola sería una lista de casos sin pista de qué mirar, que es lo mismo que revisarlos
todos de cero.

### Dos puertos y no uno

`IdentityVerificationGateway` es la **política** —qué se hace con el resultado— y `KycProviderPort`
es la **integración**. Separarlos permite probar la degradación sin proveedor y cambiar de proveedor
sin tocar la política. Hoy el puerto de integración lo cubre `UnavailableKycProviderAdapter`, que
reporta indisponibilidad: **la ruta de resiliencia se ejerce desde hoy** en vez de ser código que
nadie corre hasta la primera caída en producción. Cuando llegue Incode, su adaptador se registra y
aquél se retira solo (`@ConditionalOnMissingBean`).

### El veredicto es un juicio propio

Antes `IdentityStatus` se derivaba de `PlacementStatus`: completar el KYC bastaba para figurar como
verificada. Eso confundía «entregó sus documentos» con «alguien comprobó que es ella» y no dejaba
dónde poner la decisión de una persona.

Ahora se guarda con **autor, fecha, motivo y origen**. El origen (`MANUAL` · `PROVIDER` ·
`PROVIDER_ESCALATED`) se graba con el veredicto y **no se deduce de la bandera vigente**: la
configuración cambia y un expediente de hace seis meses tiene que poder decir quién lo revisó
*entonces*.

### La regla que esto habilita

`approve()` exige **dos condiciones de responsables distintos**: la distribuidora asume el riesgo y
Kredius comprueba la identidad. El corte va en `approve` —que dispara el depósito— y no más
adelante: rebotar en la disposición daría un fallo mudo en vez de un motivo. La regla vive también
como `CHECK` en la tabla.

**Sólo el analista de crédito dictamina identidad** (capacidad `beneficiaries.review-identity`):
el auditor lee todo y no decide nada, soporte atiende clientes pero no firma la comprobación de una
persona, y riesgo evalúa cartera, no identidades.

## 8. Lo que falta

**La captura de evidencia de identidad.** No existe: ni OCR de INE, ni prueba de vida, ni cotejo
RENAPO, ni la web pública de 7 pasos. Hoy hay un `KycSimulationController` que la sustituye en local.

Consecuencia: el analista dictamina **sin evidencia estructurada que mirar** —la ficha responde
`identityEvidence.available=false` con su motivo— y su juicio se apoya en lo que haya en el
expediente de origination. El lugar donde se escribe el veredicto ya existe (§7b); lo que falta es
qué mirar.

**Ningún evento produce notificación todavía.** Ver
[`docs/NOTIFICATIONS_EVENT_PLAN.md`](../NOTIFICATIONS_EVENT_PLAN.md) §6.

## 9. Persistencia

Liquibase, schema `beneficiary` (5 changesets). Los `CHECK` codifican invariantes **en la base**, no
sólo en Java: expediente todo-o-nada, disposición obligatoria para desembolsar, `FAILED` siempre con
motivo, y un índice único parcial de **una sola liga viva por (distribuidor, celular)**.

## 10. Decisiones registradas

Las cinco decisiones de producto que fijaron el modelo —CLABE de la beneficiaria, prospecto sin
credenciales, base del 20 %, «un solo uso» de la liga, y quién es el deudor— están en
[`docs/BENEFICIARY_SERVICE_PLAN.md`](../BENEFICIARY_SERVICE_PLAN.md) §3.
