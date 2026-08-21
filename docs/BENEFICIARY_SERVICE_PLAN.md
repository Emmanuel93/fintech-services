# beneficiary-service — Análisis y plan de ejecución

**Fecha:** 2026-08-16
**Estado:** propuesta. No se ha escrito código.
**Fuentes:** `Kredius Journey App.dc.html` (s18 L1029-1078, s22 L1275-1340, s23 L1342-1384,
s25 L1211-1273, tabs L804-837 y L873-932, bindings L1930-2080) · `KYC Beneficiario.dc.html`
(7 pasos, L326-482) · `fintech-app/docs/KREDIUS_COLOCACION_PLAN.md` §6 · `base-service.md`
· código de party, commission, scoring, origination, wallet, credit-portfolio,
disbursement, notifications, gateway.

---

## 1. Qué es este servicio (y qué no)

`beneficiary-service` es **el orquestador de la colocación B2B2C**. Su agregado es la
*colocación*: el hilo que va desde que un distribuidor teclea tres datos de su clienta
hasta que el dinero cae en la CLABE de ella.

**Lo que sí hace, y nadie más hace hoy:**

1. El **alta previa** del beneficiario: un borrador con tres campos, sin expediente, sin
   prospecto, sin buró. Nada del dominio de crédito se toca todavía.
2. La **liga**: token, vigencia, reenvío, revocación, y una API pública sin JWT para que la
   beneficiaria haga su KYC desde su teléfono.
3. La **carga del KYC** y su promoción a expediente real: prospecto `INDIVIDUAL` + Party
   propio + relación con el distribuidor.
4. La **constancia de autorización de buró** (fecha, hora, IP, versión del texto) y su
   consulta con esa autorización, no la del distribuidor.
5. La **entrega del reporte completo** al distribuidor y la **evidencia de asunción de riesgo**.
6. El disparo de la **disposición THIRD_PARTY_CREDIT** y el seguimiento del desembolso.

**Lo que NO hace, porque ya existe:** motor de scoring, catálogo de productos, cuentas de
crédito, saldos, amortización, desembolso SPEI, comisiones, notificaciones, auditoría.

---

## 2. Mapa de composición — qué se compone y qué se crea

Recorrido paso por paso del riel del diseño. Esta tabla es el entregable que decide el
alcance real: **de 22 pasos, 13 son composición.**

| # | Paso del flujo | Quién lo resuelve hoy | Veredicto |
|---|---|---|---|
| 1 | Línea del distribuidor (autorizado / colocado / disponible) | `credit-portfolio` `CreditAccount` DISTRIBUTOR_LINE (revolvente) + `wallet` proyección | **compone** — read model |
| 2 | Alta previa: nombre, celular, cómo la conoces | — | **nuevo** — `Placement` en estado `INVITED` |
| 3 | Monto y plazo de la colocación | `credit-product` `DISTRIBUTOR_LINE` (rate cards, amountStep, term) | **compone** — cotiza contra el catálogo |
| 4 | Generar la liga (token, 7 días) | — | **nuevo** — `PlacementInvite` |
| 5 | Enviar la liga por WhatsApp | `notifications` canal `WHATSAPP` + políticas por `EventType` | **compone** + 5 `EventType` nuevos |
| 6 | OTP al celular de la beneficiaria | `channel-mobile` `OtpService` (Redis) — pero es un BFF, no reutilizable cross-canal | **nuevo aquí** (ver §4.2) |
| 7 | OCR de la INE | `channel-mobile` `POST /ocr/extract` (stub, sin bytes reales) | **compone** vía cliente HTTP + puerto propio |
| 8 | Prueba de vida / selfie | no existe en ningún servicio | **nuevo** — puerto `LivenessGateway` + adaptador stub |
| 9 | Guardar los archivos del expediente | `origination` `PUT /prospects/{id}/documents/{type}/file` | **compone** |
| 10 | Datos personales verificados | `origination` `POST /prospects` (CURP, RFC, domicilio, ocupación) | **compone** (con un ajuste, §4.1) |
| 11 | Consentimientos (privacidad, buró, "el crédito es mío") | `origination` `privacyNoticeAccepted` / `circuloConsentAccepted` + `party` consent records | **compone** + **nuevo**: la constancia con IP y hora |
| 12 | Party propio de la beneficiaria | `party` (se crea al emitirse `origination.prospect-created`) | **compone** |
| 13 | Relación distribuidor → beneficiaria | `party` `POST /parties/{partyId}/relationships` | **compone** |
| 14 | Consulta de buró | `scoring` `BureauPrefetchService` disparado por `origination.prospect-created` con `circuloConsentAccepted=true` | **compone** — la autorización viaja en el prospecto de ella |
| 15 | Reporte y score | `scoring` `GET /reports/{prospectId}` + `/evaluations/latest/{prospectId}` | **compone** |
| 16 | Semáforo sin filtrar por score | `scoring` devuelve `AUTO_APPROVED/MANUAL_REVIEW/REJECTED` | **nuevo** — se ignora la decisión, se entrega el reporte |
| 17 | Casilla "asumo el riesgo" | — | **nuevo** — `RiskAcknowledgement` con evidencia |
| 18 | Descontar la línea | `wallet` `POST /wallet/{creditAccountId}/dispositions` con `THIRD_PARTY_CREDIT`, `beneficiaryPartyId`, `payeeAccount`, `termPeriods` | **compone** — el contrato ya está listo, campo por campo |
| 19 | Desembolso SPEI a su CLABE | `credit-portfolio` → `disbursement` → `stp` | **compone** |
| 20 | Bonificación 20% decreciente | `commission` `DISTRIBUTOR_INTEREST_SHARE`, devengo por pago contra interés cobrado | **compone + extiende** (ver §7) |
| 21 | Lista de colocaciones y de beneficiarios | — | **nuevo** — read models de este servicio |
| 22 | Bitácora regulatoria | `audit` (suscriptor global de Kafka) | **compone** — basta con emitir eventos |

**Resumen:** lo nuevo es el agregado, la liga, la API pública, la constancia de buró, la
evidencia de riesgo y los read models. Todo lo demás son llamadas HTTP y consumo de eventos.

---

## 3. Los cinco huecos — decisiones cerradas (2026-08-16)

Los cinco quedaron resueltos antes de escribir código. Se dejan documentados con su
razonamiento porque cada uno cambia el modelo.

### 3.1 La CLABE de la beneficiaria — ✅ se le pide a ella

`KYC Beneficiario.dc.html` tiene 7 pasos y **ninguno pide cuenta bancaria** — pero la
pantalla de bienvenida promete *"el dinero se deposita directo a tu cuenta, a tu nombre"* y
la disposición `THIRD_PARTY_CREDIT` exige `payeeAccount`.

**Decidido:** se le pide su CLABE en el paso 5 (*Tus datos*), con la misma validación de 18
dígitos que `SignContractRequest` de origination y el `ClabeValidator` de disbursement. Es
campo obligatorio: sin CLABE no hay `submit`, porque sin CLABE no hay a dónde depositar.
Se descarta emitirle una CLABE STP — es otro producto.

### 3.2 Prospecto sin credenciales — ✅ ella nunca tiene cuenta

`RegisterProspectRequest` trae `@NotBlank username` y `@NotBlank password`, e identity
aprovisiona credenciales. La beneficiaria **no instala la app**: generarle credenciales que
nadie le entrega deja una cuenta muerta con contraseña conocida por el sistema.

**Decidido:** la beneficiaria no es usuaria de la plataforma. Lo único que la identifica es
un **token de invitado de 15 minutos** que le permite hablar con este servicio y con nadie
más. `username` y `password` pasan a ser opcionales en origination para altas por este
canal (`channelType: WEB`), y no se aprovisionan credenciales en identity.

Consecuencia directa en el diseño de la sesión: el `sessionToken` **vive 15 minutos**, no 30
—es el número que fija el negocio, no una constante técnica— y se renueva pasando OTP otra
vez dentro de la ventana de 7 días de la liga.

### 3.3 ¿20% de qué? — ✅ `basis` explícita en la política

El diseño calcula `colPago * colT * 0.20` — 20% del **total cobrado**, no del interés. Pero
`CommissionAccrualService` devenga contra `interestDelta`: un porcentaje del **interés
cobrado**. Con los números del diseño (pago = monto × 1.3067) la diferencia es grande: 20%
del total es ~26% del principal, contra un interés total de ~31% del principal.

**Decidido:** la política lleva una `basis` explícita (`COLLECTED_TOTAL` |
`COLLECTED_INTEREST`) en vez de asumir. Se siembra `COLLECTED_TOTAL` porque es lo que dice
la pantalla, y queda configurable sin redeploy.

### 3.4 "Un solo uso" contra "retomas donde quedaste" — ✅ un uso por colocación

El requisito dice token de un solo uso; el paso 1 del KYC dice *"si te interrumpen, la liga
sigue viva 7 días y retomas donde quedaste"*. Son incompatibles al pie de la letra.

**Decidido:** el token es de un solo uso **para producir una colocación** — se quema al
llegar a `KYC_COMPLETED`, `CANCELLED` o `EXPIRED`, y nunca puede generar un segundo
expediente. Dentro de la ventana de 7 días puede acuñar sesión de 15 minutos varias veces,
cada una detrás de un OTP y del rate limit. Reanudar no es reusar.

> **Corregido el 2026-08-16 (§10.8):** aquí se había decidido además que reenviar la liga
> **no** extendiera la ventana. El contrato de la app dice lo contrario —*«reiniciando los 7
> días»*— y gana el contrato. El riesgo de vigencia infinita lo acota `max-invite-resends: 3`:
> peor caso, 28 días.

### 3.5 ¿De quién es el contrato? — ✅ la deudora es la distribuidora

Aquí hay una tensión real entre pantallas:

- s18 al distribuidor: *"El crédito y el contrato quedan a nombre de esta persona. **La deuda
  de tu línea sigue siendo tuya**."*
- Consentimiento 3 a la beneficiaria: *"El contrato queda a mi nombre… **yo soy quien debe
  pagarlo**"*, y *"le paga a su distribuidora"*.
- Tarjeta de línea: *Total a cobrar $11,700 · Pago a Kredius $3,180 · Bonificación $8,240* —
  el distribuidor cobra a sus clientes y le paga a Kredius una fracción.

**Decidido: la deudora frente a Kredius es la distribuidora.** A la beneficiaria se le
informa que el crédito es suyo —y lo es, frente a la distribuidora— pero el obligor del
crédito es el distribuidor, tal como el código ya lo modela (`credit-product` README: *"El
distribuidor es el obligor; los créditos fluyen a terceros beneficiarios"*).

Consecuencias concretas para el modelo:

- **Una sola cuenta de crédito**, la del distribuidor (`DISTRIBUTOR_LINE`, revolvente). Cada
  colocación es una `Disposition` `THIRD_PARTY_CREDIT` de esa línea. No se abre cuenta por
  beneficiaria.
- El `PlacementContract` que este servicio genera y guarda firmado es un **contrato de
  colocación**: beneficiaria ↔ distribuidora, con Kredius como originador y dispersor. Es un
  documento del expediente, **no** una cuenta de crédito ni una exposición en cartera.
- La bonificación es lo que Kredius le devuelve al distribuidor de lo que él paga — coherente
  con §7.
- La cobranza a la beneficiaria la hace la distribuidora. `collections` sigue viendo un solo
  sujeto: el distribuidor.

Queda descartada la lectura alternativa (una cuenta por beneficiaria con el distribuidor como
garante): implicaría cuentas, amortización, cobranza y contabilidad por cada beneficiaria.

---

## 4. El agregado y su máquina de estados

### 4.1 `Placement`

Un `Placement` es **una** colocación: un distribuidor, un beneficiario, un monto, un plazo.
Un mismo beneficiario puede tener varias a lo largo del tiempo (*"enviarle otro préstamo"*),
por eso el beneficiario no es el agregado — la colocación sí.

**Decisión de diseño clave: al invitar no se crea prospecto.** El alta previa vive
íntegramente aquí, con tres campos y sin expediente. Crear un prospecto con nombre y
teléfono llenaría origination de fantasmas y —peor— dispararía el prefetch de buró de
`scoring` antes de que ella haya autorizado nada. El prospecto `INDIVIDUAL` nace en
`KYC_COMPLETED`, con el expediente entero y el consentimiento firmado.

### 4.2 Máquina de estados

Los ocho estados pedidos son los **públicos** (los que la app pinta). Se agregan tres
internos porque el buró y el desembolso son asíncronos y pueden fallar sin que nadie haya
decidido nada:

```
                     ┌───────────────────────────────────────┐
                     │            INVITED                    │  liga viva, línea intacta
                     └───┬──────────────┬───────────┬────────┘
       primer OTP OK     │              │ 7 días    │ distribuidor revoca
                     ┌───▼──────────┐   │           │
                     │ KYC_IN_      │   │           │
                     │ PROGRESS     │   │           │
                     └───┬──────┬───┘   │           │
    7 pasos completos    │      │ falla │           │
                     ┌───▼──────┴───┐   │           │
                     │ KYC_COMPLETED│   │           │   ← prospecto + Party + relación
                     └───┬──────┬───┘   │           │      + consentimiento con constancia
   scoring responde      │      │ buró  │           │
                     ┌───▼──────┴───┐   │           │
                     │ BUREAU_READY │   │           │   ← el distribuidor ve el reporte
                     └──┬────────┬──┘   │           │
      aprueba + firma   │        │ no coloca        │
                  ┌─────▼────┐ ┌─▼────────┐  ┌──────▼────┐  ┌──────────┐
                  │ APPROVED │ │ REJECTED │  │ CANCELLED │  │ EXPIRED  │
                  └─────┬────┘ └──────────┘  └───────────┘  └──────────┘
    disposición pedida  │
                  ┌─────▼──────┐        ┌────────┐
                  │ DISBURSING ├───────►│ FAILED │  línea insuficiente, SPEI rechazado,
                  └─────┬──────┘        └────────┘  buró indisponible, KYC fallido
                  ┌─────▼──────┐
                  │ DISBURSED  │
                  └────────────┘
```

**Por qué `DISBURSING` y `FAILED` no son opcionales:** la línea *no se aparta al invitar*
(regla 1 del diseño), así que dos colocaciones pueden aprobarse contra la misma línea y la
segunda encontrar saldo insuficiente. La autoridad del cupo es `credit-portfolio`, que
responde con `disposition-rejected`. Sin estos dos estados, "APPROVED" sería mentira.

**Transición → evento de dominio** (uno por arista, todos `@Externalized` a Kafka y por lo
tanto ingeridos automáticamente por `audit-service`):

| Transición | Evento | Consumidores |
|---|---|---|
| → `INVITED` | `beneficiary.placement-invited` | notifications (WhatsApp con la liga), audit |
| → `KYC_IN_PROGRESS` | `beneficiary.kyc-started` | notifications (aviso al distribuidor), audit |
| → `KYC_COMPLETED` | `beneficiary.kyc-completed` | notifications, audit |
| (dentro de KYC) | `beneficiary.bureau-consent-granted` | **audit** — la constancia: hora, IP, versión del texto |
| → `BUREAU_READY` | `beneficiary.bureau-ready` | notifications (push: *"ya está su historial"*), audit |
| → `APPROVED` | `beneficiary.placement-approved` | audit; dispara la disposición |
| → `REJECTED` | `beneficiary.placement-rejected` | notifications, audit |
| → `EXPIRED` | `beneficiary.placement-expired` | notifications, audit |
| → `CANCELLED` | `beneficiary.placement-cancelled` | notifications, audit |
| → `DISBURSED` | `beneficiary.placement-disbursed` | notifications (a ambos), commission, audit |
| → `FAILED` | `beneficiary.placement-failed` | notifications, audit |

**Consume:** `scoring.scoring-completed` (→ `BUREAU_READY`),
`credit-portfolio.disposition-completed` (→ `DISBURSED`),
`credit-portfolio.disposition-rejected` (→ `FAILED`),
`origination.prospect-created` (para confirmar el `partyId` de la beneficiaria).

**Un job, y sólo uno:** `InviteExpirySweeper`, nocturno, que mueve a `EXPIRED` lo que pasó de
7 días y emite el evento. La notificación de *"tu liga vence mañana"* sale de un evento que
emite este sweeper — **no** de un `@Scheduled` en notifications, que es la regla NT-12.

---

## 5. Modelo de datos — schema `beneficiary`

| Tabla | Qué guarda |
|---|---|
| `placements` | el agregado: distribuidor, borrador del beneficiario, `party_id` (null hasta KYC), monto, plazo, estado, `credit_account_id`, `disposition_id`, timestamps |
| `placement_transitions` | bitácora append-only de cada cambio de estado, con actor y motivo |
| `placement_invites` | `token_hash` (SHA-256, nunca el token), `expires_at`, `redeemed_at`, `revoked_at`, `resend_count`, `superseded_by` |
| `kyc_sessions` | avance de los 7 pasos, refs a OCR y prueba de vida, `session_token_hash`, expiración deslizante |
| `consent_records` | tipo, versión del texto, `accepted_at`, `ip` (INET), `user_agent`, `otp_verification_id`. **La constancia.** |
| `bureau_authorizations` | `consent_record_id` → `prospect_id` → cuándo se pidió el buró y con qué autorización |
| `risk_acknowledgements` | `placement_id`, distribuidor, `accepted_at`, `ip`, hash del reporte que vio (para que no pueda decir que vio otro) |
| `placement_contracts` | el contrato a nombre de la beneficiaria, su firma y su ref documental (§3.5) |
| `event_publication` | Spring Modulith |

Rate limiting y OTP viven en **Redis**, no en Postgres: son efímeros y de alta frecuencia.

---

## 6. API

### 6.1 Privada — el distribuidor, detrás del JWT del gateway

Espeja `KREDIUS_COLOCACION_PLAN.md` §6 path por path, para que el mock de Flutter conecte
borrando el interceptor. Rutas reales bajo `/api/v1/placements` y `/api/v1/beneficiaries`;
el BFF `channel-mobile` las republica con los paths cortos que ya espera la app.

```
POST   /placements                    { beneficiary{fullName,phone,relationship}, amount,
                                        termFortnights, verificationMode }
                                   →  { placementId, status:"INVITED", inviteExpiresAt,
                                        lineAvailableAfter }
GET    /placements?status=&query=&page=
GET    /placements/{id}               → + timeline[] + kycProgress
POST   /placements/{id}/resend
POST   /placements/{id}/cancel
GET    /placements/{id}/bureau        → sólo con BUREAU_READY. Reporte COMPLETO, sin filtrar.
POST   /placements/{id}/approve       { riskAcknowledged: true }
POST   /placements/{id}/reject        { reason? }
GET    /beneficiaries?query=
GET    /beneficiaries/{partyId}
GET    /distributor/line-summary
```

`GET /placements/{id}/bureau` **no consulta la decisión de scoring**. Lee el
`CirculoReport` y arma los cinco renglones de riesgo del diseño (peor atraso 24m, créditos
vigentes, deuda contra ingreso, consultas 6m, recomendación) más score y banda. La
`recommendation` es texto —*"Colocar con reserva"*— no una compuerta.

### 6.2 Pública — la beneficiaria, sin JWT

```
GET   /public/placements/{token}                  → nombre del distribuidor, monto, plazo, paso actual
POST  /public/placements/{token}/otp/send
POST  /public/placements/{token}/otp/verify       → acuña sessionToken de invitado (15 min)
POST  /public/kyc/{session}/ine                   → OCR frente + reverso
POST  /public/kyc/{session}/liveness
PUT   /public/kyc/{session}/data                  → datos verificados + CLABE obligatoria (§3.1)
POST  /public/kyc/{session}/consents              → 3 consentimientos; graba IP + hora
POST  /public/kyc/{session}/submit                → todo-o-nada: prospecto + Party + relación
```

**Seguridad — el punto más delicado del servicio:**

- Token de 32 bytes aleatorios URL-safe; en base sólo el SHA-256. Comparación en tiempo
  constante.
- `sessionToken` de invitado, opaco, en Redis, **TTL 15 minutos** (§3.2), ligado al
  `placementId` y al fingerprint del dispositivo. Es la única credencial que la beneficiaria
  llega a tener, y sólo abre este servicio.
- **Rate limit en tres ejes** (Redis, ventana deslizante): por token, por teléfono, por IP.
  OTP: 3 envíos / 15 min por teléfono, 10 verificaciones / 15 min por token, 5 intentos
  fallidos y el token se congela 1 hora.
- Bloque de servidor propio en el gateway (`kyc.localhost`) que **nunca** inyecta
  `X-User-Id` ni `X-Roles`, con sus propias `limit_req` — el mismo patrón que ya usan
  `/otp/send` y `/kyc/submit`.
- Enumeración: un token inválido y uno expirado devuelven la misma respuesta.
- Nada de PII en las respuestas públicas antes del OTP: sólo nombre del distribuidor,
  monto y plazo.

> **Desviación de convención, consciente:** el monorepo dice que los canales entran por un
> BFF y nunca tocan un servicio de dominio. Aquí el navegador de la beneficiaria pega
> directo a `beneficiary-service`. La alternativa —un `channel-kyc-service`— sería un BFF
> con un solo downstream que reimplementaría el token y la sesión que ya son el corazón de
> este agregado. El límite de canal lo pone el bloque del gateway. Queda documentado como
> excepción, no como precedente.

---

## 7. La bonificación va en `commission-service`, no aquí

**Recomendación firme.** El mecanismo ya existe y es exactamente el correcto:
`CommissionAccrualService` devenga `DISTRIBUTOR_INTEREST_SHARE` **por pago, contra lo
efectivamente cobrado, nunca por adelantado contra la colocación** (CM-01) — que es
literalmente la regla del diseño. Alrededor ya están la reversa por pago devuelto (CM-05),
las corridas de liquidación, `GET /beneficiaries/{partyId}/pending`, y la integración con
contabilidad vía `commission.commission-accrued`.

Implementarla en `beneficiary-service` crearía **un segundo libro de comisiones** que
accounting no conoce, sin reversa ni liquidación. Es el error clásico.

Lo único que le falta a commission son dos cosas:

1. **Política escalonada.** Hoy `CommissionPolicy.rate` es un escalar. Se agrega
   `commission_policy_tiers (policy_id, days_late_from, days_late_to, rate)` y se siembra la
   escalera del diseño: `0→0.20, 1→0.17, 2→0.14, 3→0.10, 4→0.06, 5+→0.00`. Más un campo
   `basis` por el hueco §3.3.
2. **El atraso al momento del cobro.** `balance-updated` trae `triggerEvent` y los deltas,
   pero no los días de mora.
   - *Correcto y caro:* que `credit-portfolio` agregue `daysLate` al `balance-updated` de
     `PAYMENT_APPLIED`.
   - *Pragmático y suficiente (recomendado):* que commission guarde `daysDelinquent` en su
     `AccountBalanceShadow`, alimentado de `credit-portfolio.delinquency-status-updated`
     —que ya trae el campo—, y lo lea al devengar. Mismo patrón de shadow que el servicio ya
     usa, sin tocar upstream.

     *Limitación conocida, y hay que escribirla en el código:* con varias cuotas vencidas o
     pagos parciales, el DPD de la cuenta no es exactamente el de la cuota que se está
     pagando. Es una aproximación conservadora y auditable, del mismo tenor que la
     limitación ya documentada en CM-05.

Nuevo `CommissionType.DISTRIBUTOR_PUNCTUALITY_SHARE`, para no cambiarle el significado al
que ya existe.

---

## 8. Plan de ejecución

Puerto `bootRun` **8102** · schema `beneficiary` · paquete `com.fintech.beneficiary`.

### Fase 0 — Decidir · ✅ cerrada 2026-08-16
Los cinco huecos de §3 quedaron resueltos, incluido §3.5. No hay decisión pendiente que
bloquee ninguna fase.

### Fase 1 — Esqueleto y agregado · 2 días
Registro en `settings.gradle.kts` y `docker-compose.yml`, `package-info.java`, Liquibase
(schema + `placements` + `placement_transitions` + `event_publication`), `SecurityConfig`,
`OpenApiConfig`, `Placement` con la máquina de estados **completa y pura** (sin una sola
llamada externa) y sus once eventos.
*Pruebas:* unitarias exhaustivas de la máquina — toda transición válida, toda inválida
rechazada con excepción de dominio. Es el test que más vale del servicio.

### Fase 2 — Invitación y liga · 2 días
`PlacementInvite`, generación y hash del token, vigencia de 7 días, reenvío (revoca el
anterior, no extiende la ventana, máximo 3), revocación, `InviteExpirySweeper`. OTP sobre
Redis con los tres ejes de rate limit. Cinco `EventType` nuevos en notifications con sus
políticas y plantillas de WhatsApp.
*Pruebas:* unitarias de vigencia y reenvío; de rate limit con reloj inyectado; WebMvcTest de
los endpoints privados.

### Fase 3 — Alta previa y carga del KYC · 3 días · **el corazón**
API pública de los 7 pasos. `KycSession` con avance persistido. Clientes HTTP a
`channel-mobile` (OCR), puerto `LivenessGateway` con adaptador stub, y a `origination`
(prospecto + documentos) y `party` (relación). `consent_records` con IP, hora y versión del
texto. `submit` es **todo o nada**: si algo falla, no queda ni medio expediente.
*Pruebas:* WebMvcTest de los 7 endpoints incluyendo token inválido, expirado y revocado;
unitarias del ensamblado del prospecto; Testcontainers del `submit` completo.

### Fase 4 — Buró y entrega sin filtro · 2 días
`bureau_authorizations`, listener de `scoring.scoring-completed` → `BUREAU_READY`,
`GET /placements/{id}/bureau` armando los cinco renglones desde `CirculoReport`, y
`RiskAcknowledgement` con hash del reporte visto.
*Pruebas:* una que fije la regla — **un reporte con decisión `REJECTED` de scoring se entrega
igual y el distribuidor puede aprobar**; y otra de que `approve` sin `riskAcknowledged`
devuelve 422.

### Fase 5 — Aprobación, disposición y desembolso · 2 días
`approve` → `WalletClient.requestDisposition(THIRD_PARTY_CREDIT, beneficiaryPartyId,
payeeAccount=CLABE, termPeriods)` → `DISBURSING`. Listeners de `disposition-completed` y
`disposition-rejected`. `PlacementContract` según §3.5.
*Pruebas:* unitaria de línea insuficiente → `FAILED` con motivo; Testcontainers del camino
`APPROVED → DISBURSING → DISBURSED`.

### Fase 6 — Consultas del distribuidor y BFF · 2 días
`GET /placements`, `/beneficiaries`, `/beneficiaries/{partyId}`, `/distributor/line-summary`
(compone `credit-portfolio` + `wallet` + `commission`). Republicación en `channel-mobile`
con los paths de §6 del plan de la app, y las rutas en el gateway (móvil protegido +
bloque `kyc.localhost` público).
*Pruebas:* WebMvcTest de filtros y búsqueda; contract test contra los paths del mock Flutter.

### Fase 7 — Bonificación en `commission-service` · 2 días
`commission_policy_tiers`, `basis`, `DISTRIBUTOR_PUNCTUALITY_SHARE`, `daysDelinquent` en el
shadow desde `delinquency-status-updated`, resolución de tarifa por escalón.
*Pruebas:* tabla completa de la escalera (0,1,2,3,4,5,10 días); reversa; sin política activa
no devenga.

### Fase 8 — Cierre · 1.5 días
Registro en `docs/IMPLEMENTATION_TRACKER.md` y en la tabla de servicios del `README.md` raíz.
README del servicio. Build de Docker **secuencial** (la VM no aguanta el stack completo en
paralelo). E2E de la colocación de punta a punta.
**Pruebas de performance** (obligatorias, no opcionales): carga sobre la API pública —
que es la única expuesta a internet abierto— midiendo el rate limit bajo concurrencia, el
p95 de `GET /placements` con 10 000 colocaciones, y el comportamiento del sweeper con
50 000 invitaciones vivas.

**Total: ~14.5 días**, de los cuales 7 son el camino crítico (Fases 1-5). Las fases 6 y 7
son paralelizables.

---

## 9. Riesgos

| Riesgo | Mitigación |
|---|---|
| La API pública es la primera superficie de Kredius en internet abierto sin JWT | Rate limit en tres ejes, token hasheado, respuestas indistinguibles, pruebas de carga en Fase 8 |
| OCR y prueba de vida son stubs | Puertos definidos desde el día uno; cambiar de proveedor es un adaptador |
| Dos aprobaciones concurrentes contra la misma línea | La autoridad es `credit-portfolio`; `DISBURSING`/`FAILED` lo hacen visible en vez de esconderlo |
| Prospectos fantasma en origination | El prospecto nace en `KYC_COMPLETED`, no al invitar |

---

## 10. Conformidad con `KREDIUS_COLOCACION_API.md` (2026-08-16)

La app publicó el contrato que **ya consume** contra su mock. Deja de ser una propuesta: es
la referencia, y donde el servicio no coincida, el que se mueve es el servicio. Esta sección
confronta lo construido en la Fase 1 contra esos nueve endpoints.

### 10.1 Lo que ya embona sin tocar nada

| Requisito del contrato | Estado |
|---|---|
| `beneficiaryPhoneMask` enmascarado **desde el servidor**, formato `55 •• •• 90 37` | ✅ produce exactamente ese formato |
| Nunca sale el celular completo de un tercero hacia la app | ✅ el campo crudo no se serializa |
| La línea no se aparta al invitar | ✅ `PlacementStatus.consumesLine()` sólo cierto en `DISBURSING`/`DISBURSED` |
| `riskAcknowledged: true` obligatorio, con timestamp | ✅ `approve()` lo exige; `decidedAt` + `PlacementTransition` + `PlacementApprovedEvent` |
| 409 al aprobar si no está en `bureauReady` | ✅ la máquina sólo admite `BUREAU_READY → APPROVED`, y el handler devuelve 409 |
| `verificationMode: SELF_SERVICE_LINK` | ✅ |
| Registro de la consulta de buró a nombre del beneficiario con fecha, hora e IP | ✅ diseñado (`BureauConsentGrantedEvent`), pendiente de Fase 4 |

### 10.2 Estados — 11 internos contra 9 de cable

Los estados internos son más ricos que los que la app pinta, y eso está bien: lo que hace falta
es la **proyección**. Pero hay dos huecos reales.

| Interno | De cable | Nota |
|---|---|---|
| `INVITED` | `invited` | |
| `KYC_IN_PROGRESS` | `kycInProgress` | |
| `KYC_COMPLETED` | `kycInProgress` | colapsa: para la app sigue verificándose hasta que hay buró |
| `BUREAU_READY` | `bureauReady` | |
| `APPROVED` | `approved` | |
| `DISBURSING` | `approved` | colapsa: la app ya lo describe como «aprobada, desembolsando» |
| `DISBURSED` | `active` | |
| **— falta —** | `paidOff` | ⚠️ **hueco 1** |
| `REJECTED` | `declined` | |
| `EXPIRED` | `expired` | |
| `CANCELLED` | `cancelled` | |
| `FAILED` | **— sin destino —** | ⚠️ **hueco 2** |

**Hueco 1 — `paidOff` no existe en la máquina.** Hoy `DISBURSED` es terminal. La colocación
liquidada es un estado que el contrato exige y que el dominio no puede representar. Hay que
agregar `PAID_OFF` alcanzable desde `DISBURSED`, disparado por credit-portfolio cuando el
calendario de la disposición queda saldado. Pasa de 15 a 16 aristas.

**Hueco 2 — `FAILED` no tiene a dónde caer, y el fallback lo vuelve peligroso.** El contrato
dice que *un valor desconocido cae a `invited`*. Una colocación que falló —prueba de vida
rechazada, buró indisponible, disposición rechazada— se le pintaría al distribuidor como
**«liga enviada, esperando»**. Es un dato incorrecto con cara de estado normal, y el
distribuidor esperaría indefinidamente algo que ya murió.

Dos salidas, y la primera es mejor:

1. **Agregar `failed` al enum de la app** (el contrato mismo pide avisar antes de sumar
   estados). Es una línea en `PlacementStatus` más su copy.
2. Mapear `FAILED → cancelled`. Es terminal y no consume línea, así que no miente sobre el
   dinero — pero sí sobre quién lo canceló: dice que fue el distribuidor cuando no lo fue.

### 10.3 Campos del objeto Colocación

| Campo del contrato | Estado en la Fase 1 |
|---|---|
| `placementId`, `amount`, `termFortnights`, `beneficiaryPhoneMask`, `beneficiaryPartyId` | ✅ |
| `beneficiaryName` | ⚠️ hoy se llama `beneficiaryFullName` — renombrar |
| `beneficiaryInitials` | ⚠️ **sobra**: la app las deriva. Se elimina, y con ella la heurística de apellidos |
| `relationship` | ❌ está en el agregado, no se serializa |
| `fortnightlyPayment` **(req)** | ❌ ver §10.7 — es el hallazgo grande |
| `commissionAccrued` | ❌ requiere commission-service (Fase 7) |
| `paymentsMade`, `daysPastDue` | ❌ ver §10.6 |
| `placedOn`, `inviteExpiresAt` | ❌ `placedOn` = `disbursedAt`; `inviteExpiresAt` llega con la Fase 2 |
| `status` en lowerCamelCase | ❌ hoy serializa el enum interno |

También: `GET /placements` devuelve `{"placements": [...]}` y `GET /beneficiaries`
`{"beneficiaries": [...]}` — envoltura, no arreglo pelón como hoy.

### 10.4 Errores — el BFF traduce, y por eso `errorCode` importa

El contrato pide `4xx` con `{"message": "..."}` **que se le muestra al usuario tal cual**. El
monorepo usa RFC 7807 (`ProblemDetail`) y no hay razón para romper eso en un servicio de
dominio. La traducción es exactamente para lo que existe el BFF.

Ahora bien, los mensajes del dominio están escritos para el log —*«no puede pasar de EXPIRED a
APPROVED. Destinos válidos: …»*— y esa frase no se le enseña a nadie. Así que el BFF **no debe
reenviar `detail`**: debe mapear `errorCode` → copy en español. Por eso el `ProblemDetail` de
este servicio ya expone `errorCode` como propiedad, y no sólo el `type`.

| `errorCode` | Status | `message` al usuario |
|---|---|---|
| `BENEFICIARY_PLACEMENT_INVALID` | 400/422 | Faltan el nombre o el celular del beneficiario |
| `BENEFICIARY_INSUFFICIENT_LINE` *(nuevo)* | 409 | El monto supera tu línea disponible |
| `BENEFICIARY_DUPLICATE_LIVE_PLACEMENT` *(nuevo)* | 409 | Ya le enviaste una liga a este número |
| `BENEFICIARY_INVALID_PLACEMENT_TRANSITION` | 409 | Esta colocación ya no está esperando tu decisión |
| `BENEFICIARY_BUREAU_NOT_READY` *(nuevo)* | 409 | Su historial todavía no está listo |

### 10.5 Dos validaciones de `POST /placements` que aún no existen

- **409 si `amount` supera `available`.** Obliga a consultar la línea **al crear**, aunque no se
  aparte. Se compone con `GET /accounts?partyId=` de credit-portfolio. La autoridad definitiva
  sigue siendo suya (`validateDisposition` ya devuelve `INSUFFICIENT_AVAILABLE_CREDIT`); esto es
  cortesía de UI para no invitar a alguien que nunca se va a poder aprobar.
- **409 si ese celular ya tiene una colocación viva.** Falta un índice único parcial sobre
  `(distributor_party_id, beneficiary_phone)` en los estados vivos. *Pregunta abierta:* ¿es por
  distribuidor o global? Se implementa por distribuidor —dos distribuidores distintos pueden
  querer colocarle a la misma persona— y queda anotado como posible vector si se quiere global.

### 10.6 `paymentsMade` y `daysPastDue` sí son derivables — pero falta el endpoint

Bajo «la deudora es la distribuidora» parecía que los pagos por colocación no fueran
observables. **Lo son:** credit-portfolio genera un calendario por disposición con
`scheduleId = dispositionId` (*«una revolvente no tiene plazo; lo tiene cada disposición»*). Con
las cuotas de esa disposición salen `paymentsMade` (cuotas `PAID`), `daysPastDue` y el disparo de
`paidOff`.

**Lo que falta:** `GET /accounts/{id}/amortization-schedule` asume `scheduleId = creditAccountId`,
que sirve para productos a plazo pero no para la revolvente. Hace falta un endpoint que consulte
el calendario **por disposición**. Es un entregable en credit-portfolio, chico y bien delimitado.

### 10.7 El hallazgo grande: el calendario de la disposición es mensual y a la tasa de la línea

`generarCalendarioDeDisposicion` hoy hace esto:

```java
amortizationEngine.generate(dispositionId, amount,
        account.getNominalRate(),   // ← la tasa de la LÍNEA (22 % sembrada)
        plazo, "FRENCH",
        "MONTHLY",                  // ← frecuencia FIJA en el código
        LocalDate.now().plusMonths(1), account.getVatRate());
```

El contrato de la app es **quincenal** de punta a punta: `termFortnights`, `fortnightlyPayment`,
*«EN CUÁNTAS QUINCENAS TE PAGA»*. Chocan en dos lugares:

1. **Frecuencia.** `"MONTHLY"` está hardcodeado. Mandar `termPeriods = 24` quincenas produciría
   24 cuotas **mensuales**: el doble de tiempo. Una colocación quincenal no se puede representar
   hoy. → hay que parametrizar la frecuencia de la disposición.
2. **Tasa.** El producto sembrado `DL-DIST-STD-V1` trae 20–36 % con rate cards de 22 % / 18 %
   por monto de línea; la app cotiza **28.9 % anual**. Y `capabilities` dice
   `hasAmortizationSchedule: false`, `BULLET`, `MONTHLY`.

**La pregunta que hay que responder, y no es de UI:** ¿el 28.9 % que se le muestra a la
beneficiaria es la tasa que se registra en la disposición, o un precio comercial que Kredius no
lleva en libros?

Hay una prueba que decide: **la escala 20/17/14/10/6/0 depende de los días de atraso de ella.**
Para calcularla, Kredius tiene que observar las fechas de pago de la beneficiaria. Si la
distribuidora le cobra en efectivo y Kredius no se entera, la escala es incalculable y
`commissionAccrued` no se puede devolver. Así que la escala misma implica que **Kredius
administra el plan de pagos de la beneficiaria** — es decir, el calendario de la disposición
*es* su plan, y va al 28.9 %.

Eso además cuadra con la tarjeta del home: *Total a cobrar 11,700 − Pago a Kredius 3,180 =
8,520*, contra la bonificación de 8,240. El distribuidor no cobra por su cuenta: recibe su parte.

**Recomendación:** que la tasa del beneficiario viva en el rate card de `DISTRIBUTOR_LINE` y que
`line-summary` la devuelva junto con `terms`, `minAmount` y `amountStep` (§11 del contrato), para
que la app deje de traer su propio *fallback*. Si el área de producto confirma otra cosa, lo que
cambia es de dónde sale la cifra, no la forma del contrato.

### 10.8 `resend` reinicia los 7 días — contradice §3.4 y gana el contrato

Yo había decidido que reenviar **no** extendiera la ventana, por miedo a una vigencia infinita a
punta de reenvíos. El contrato dice *«reiniciando los 7 días»*.

**Se alinea al contrato**, y el miedo se resuelve con el tope que ya estaba previsto: con
`max-invite-resends: 3`, el peor caso son 28 días, no infinito. §3.4 queda corregido.

### 10.9 Resto de brechas

| Endpoint | Falta |
|---|---|
| `GET /distributor/line-summary` | Todo. **Sin línea = 200 con `authorized: 0`, nunca 404** — un 404 le pinta un error a alguien que simplemente no coloca |
| `GET /placements/{id}/bureau` | Todo (Fase 4). **409 si no hay reporte**: nunca un objeto vacío ni ceros — un reporte en blanco se lee como historial limpio |
| `GET /beneficiaries` | Todo (Fase 6). `facts` con **valores ya formateados** (`$63,000`) ⇒ el locale `es-MX` se fija en el servidor, no en el cliente |
| `GET /beneficiaries/{id}/contract` y `/payments` | Fuera del contrato; las acciones están dibujadas y desactivadas |

### 10.10 Impacto en el plan

Ninguna fase se cae; tres crecen y aparece trabajo en otros dos servicios.

| Dónde | Trabajo que suma |
|---|---|
| **Fase 1** (hecha) | `PAID_OFF` (+1 arista), proyección a estados de cable, renombres y campos faltantes, índice único parcial |
| **Fase 2** | `resend` reinicia la ventana; `inviteExpiresAt` en la respuesta |
| **Fase 5** | Frecuencia quincenal en la disposición |
| **Fase 6** | Envolturas, `line-summary` completo con pricing, formateo `es-MX`, mapeo `errorCode` → copy en el BFF |
| **credit-portfolio** *(nuevo)* | Calendario por disposición: endpoint de consulta + frecuencia parametrizable + evento de liquidación para `paidOff` |
| **credit-product** *(nuevo)* | Rate card de `DISTRIBUTOR_LINE` alineado a la tasa del beneficiario y expuesto en `line-summary` |

Estimado: **+2 días** sobre los ~14.5, más lo que decida producto en §10.7.

---

## 11. Configuración de producto: plazo y monto por colocación

El plazo no es una constante: es un **rango configurable del producto**, igual que los
`installments` de cualquier otro. Lo mismo el monto — y en el caso del distribuidor, el monto
máximo que puede otorgarle **a cada beneficiario**, que es distinto del tamaño de su línea.

### 11.1 El esquema ya lo soporta — lo que está mal es la siembra

`credit_product_definitions` ya tiene las columnas. Están en `NULL` para `DISTRIBUTOR_LINE`
porque la siembra siguió la regla *«revolvente ⇒ sin plazo ni monto»*. Esa regla es correcta
para la **línea** y falsa para la **colocación**, y el propio credit-portfolio ya lo dice en
código: *«una revolvente no tiene plazo; lo tiene cada disposición»*.

Con eso, las columnas existentes cobran significado sin migrar nada:

| Columna | Qué significa en `DISTRIBUTOR_LINE` |
|---|---|
| `min_credit_line` / `max_credit_line` / `default_credit_line` | La **línea del distribuidor** — lo que autoriza el comité |
| `min_amount` / `max_amount` | **Lo que puede colocarle a cada beneficiario** ← el tope que faltaba |
| `amount_step` | Escalón del slider de colocación |
| `min_term` / `max_term` / `default_term` | **Rango de quincenas por colocación** |
| `default_payment_frequency` + `credit_product_payment_frequencies` | Cadencia de la colocación |
| `nominal_rate_annual` + `rate_cards` | Tasa de la colocación (§10.7) |

**Una columna nueva:** `term_step INTEGER NOT NULL DEFAULT 1`. El diseño ofrece
`COLT = [12, 24, 36, 48]` (escalón de 12) y el ejemplo de negocio es 8–16 (escalón de 1 o 2).
Con sólo `min`/`max` no se expresa ninguno de los dos. `amount_step` ya existe con esa misma
lógica; esto es su gemelo para el plazo.

### 11.2 Correcciones a `DL-DIST-STD-V1`

**Corrección a este documento:** la migración `014-distributor-line-disposition-terms.sql` ya
había resuelto plazo y amortización (`min_term 3`, `max_term 24`, `default_term 12`,
`amortization_type FRENCH`) por el mismo razonamiento. Lo que quedaba pendiente era el monto.

| Campo | Antes | Ahora | Por qué |
|---|---|---|---|
| `min_amount` / `max_amount` | `NULL` | `5000` / `60000` | ✅ **015** — el tope por beneficiario |
| `amount_step` | `5000` | `1000` | ✅ **015** — el `5000` acotaba la *línea*; el slider de colocación va de mil en mil |
| `term_step` | no existía | `1` | ✅ **015** — columna nueva |
| `min_term` / `max_term` / `default_term` | `NULL` → `3`/`24`/`12` meses | `6` / `48` / `24` quincenas | `014` los puso en meses; la `016` los pasa a quincenas |
| `amortization_type` | `BULLET` | `FRENCH` | ya resuelto en `014` |
| `default_payment_frequency` | `MONTHLY` | `BIWEEKLY` | ✅ **016** — ver §11.3 |

> **Unidad, resuelta en la `016`:** la `014` razonaba en **meses** y la app habla de
> **quincenas**. Con la cadencia ya en `BIWEEKLY`, los 3–24 meses se reexpresan como 6–48
> quincenas — la misma duración, dicha en la unidad del contrato.

`capabilities.hasAmortizationSchedule` se queda en `false` y **eso es correcto**: la que no
tiene calendario es la *cuenta*; cada disposición sí lo tiene, y lo genera
`generarCalendarioDeDisposicion` justamente cuando la cuenta es revolvente.

### 11.3 Cadencia: `BIWEEKLY` — ✅ decidido

La colocación se cobra **quincenalmente**, con la cadencia `BIWEEKLY` que **ya existía** en el
enum de `credit-product` y en el motor de `credit-portfolio`. No se agrega una cadencia nueva.

La `014` había dejado el producto en `MONTHLY` y razonaba los plazos en meses; la app habla de
quincenas de punta a punta (`termFortnights`, `fortnightlyPayment`, *«en cuántas quincenas te
paga»*). La `016` alinea las dos cosas: cadencia `BIWEEKLY` y los 3–24 **meses** reexpresados
como 6–48 **quincenas**, rango que además cubre los 12/24/36/48 del stepper del diseño.

> **Consecuencia a verificar con producto.** `Cadence.BIWEEKLY` son **26 períodos al año** (cada
> 14 días), no 24. El `$868.06` del contrato de la app sale de dividir la tasa entre 24; con 26,
> el pago de $18,000 a 24 períodos es **~$858.97**, y los vencimientos corren cada 14 días en vez
> de caer el 15 y el último. Si el cobro debe caer junto a la nómina, haría falta una cadencia
> semimensual; si 26 está bien, el número del contrato de la app se ajusta a $858.97. Hay un test
> (`biweekly_is_twentysix_periods_a_year_not_twentyfour`) que deja el supuesto fijado para que el
> día que se decida, cambiarlo sea visible.

### 11.4 El motor estaba bien; el llamador no — ✅ corregido

`AmortizationEngine` ya recibía `termPeriods` como **períodos** —no meses— y ya resolvía tasa y
fechas por cadencia. El defecto estaba en `generarCalendarioDeDisposicion`, que ignoraba la
configuración:

```java
"MONTHLY",                       // ← fija en el código
LocalDate.now().plusMonths(1),   // ← primer vencimiento mensual
```

Ahora la cadencia sale de `ProductConfigResolver.resolveForAccount(...)` —el pin
`(productCode, productVersion)` de la cuenta— y el primer vencimiento de
`AmortizationEngine.firstDueDate(hoy, cadencia)`. Si no hay configuración resoluble cae a
`MONTHLY`, que es el comportamiento previo: **no se materializa nada degradado** para una cuenta
que ya vive, porque inventarle términos escribiría un plan que nadie aprobó.

### 11.5 Estado de implementación (2026-08-17)

| Pieza | Dónde | Estado |
|---|---|---|
| Columna `term_step` + `CHECK` de rangos coherentes | `credit-product` `015` | ✅ |
| `min_amount`/`max_amount`/`amount_step` de `DISTRIBUTOR_LINE` | `credit-product` `015` | ✅ |
| `termStep` en entidad, request y response del catálogo | `credit-product` | ✅ |
| `PlacementLimits` + validación en el agregado | `beneficiary` | ✅ |
| `InsufficientLineException` → 409 | `beneficiary` | ✅ |
| Cliente HTTP que lee los límites del catálogo | `beneficiary` | ⬜ fase 6 |
| Cadencia `BIWEEKLY` en `DISTRIBUTOR_LINE` + plazos en quincenas | `credit-product` `016` | ✅ |
| Cadencia de la disposición desde la configuración (fin del `"MONTHLY"` fijo) | `credit-portfolio` | ✅ |
| `AmortizationEngine.firstDueDate(...)` | `credit-portfolio` | ✅ |
| Decidir 26 vs 24 períodos al año (§11.3) | producto | ⬜ |

### 11.6 Quién valida qué

| Capa | Papel |
|---|---|
| `credit-product` | **Dueño** de los rangos. Versionado, sin redeploy |
| `beneficiary-service` | Valida al crear la colocación — 4xx barato antes de mandarle la liga a alguien |
| `credit-portfolio` | **Autoridad final** en la disposición, junto al cupo (`validateDisposition`) |
| App | Los recibe de `line-summary` y deja de traer su propio *fallback* |

Que la app valide no exime al backend: la app es una copia de la regla, no la regla.

### 11.7 Lo que `GET /distributor/line-summary` debe devolver

Cierra el §11 del contrato de la app, que hoy vive como constantes en `PlacementPricing`:

```json
{
  "authorized": 250000, "placed": 45000, "available": 205000,
  "toCollect": 11700, "dueToKredius": 9360, "commissionAccrued": 8240,
  "nextDueDate": "2026-09-05T00:00:00-06:00", "daysPastDue": 0,

  "annualRate": 0.289,
  "minAmount": 5000, "maxAmount": 60000, "amountStep": 1000,
  "minTerm": 8, "maxTerm": 16, "termStep": 1, "defaultTerm": 12,
  "paymentFrequency": "SEMI_MONTHLY"
}
```

`maxAmount` es el mínimo entre el tope del producto y la línea disponible: no tiene caso ofrecer
un slider que llega a donde su línea ya no alcanza.
