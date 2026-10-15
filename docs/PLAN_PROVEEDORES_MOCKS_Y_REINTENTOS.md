# Proveedores en ambientes bajos: mocks, reintentos y el modelo canónico

**As-is medido contra el código y la base corriendo**, y el plan para manejarlo. Donde algo no
existe, lo dice.

---

# Parte I · El as-is

## 1. Los tres proveedores, y cómo fallan distinto

Esto es lo primero porque **cambia todo lo demás**: los tres no se tratan igual porque su fallo no
significa lo mismo.

| Proveedor | Para qué | Si no responde | **Quién puede suplirlo** |
|---|---|---|---|
| **Incode** | KYC + firma | el expediente no se resuelve solo | **una persona** — el analista de crédito |
| **Círculo** | buró | **no hay decisión de crédito** | **nadie**: esa información no existe fuera del buró |
| **STP** | desembolso | el dinero no sale | **nadie**, pero la disposición revierte y el cliente no queda debiendo |

> **La consecuencia de diseño:** para Incode el reintento es una comodidad —siempre hay una persona
> detrás—. Para **Círculo el reintento *es* el mecanismo**: si se agota, no hay plan B humano, sólo
> una cola de intervención manual que alguien tiene que trabajar. Y para STP el reintento protege
> dinero que ya salió del balance.

## 2. Incode — no existe

`grep -ri incode` devuelve **cuatro comentarios** y cero integración. No hay endpoints, método ni
payload que mapear. Es hoy el **nombre de referencia** del proveedor de KYC.

### Lo que sí existe, y está bien hecho

`beneficiary` ya tiene la mitad del diseño que hace falta:

| Pieza | Estado |
|---|---|
| `IdentityVerificationMode` | ✅ `MANUAL` / automático, por configuración |
| `VerificationSource` | ✅ `MANUAL` · `PROVIDER` · `PROVIDER_ESCALATED`, **guardado con el veredicto** |
| `KycProviderPort` | ✅ puerto neutral, con `Assessment.unavailable(...)` en el contrato |
| `IdentityVerificationGateway` | ✅ la **política** separada de la **integración** |
| Flujo manual completo | ✅ `POST /beneficiaries/placements/{id}/identity-review` |
| Adaptador de proveedor | 🔴 sólo `UnavailableKycProviderAdapter` |

El motivo por el que `VerificationSource` se guarda y no se deduce está escrito en el código, y es
el correcto: *«un expediente de hace seis meses tiene que poder decir quién lo revisó entonces;
deducirlo del flag actual reescribiría la historia cada vez que se toca una variable de entorno»*.

### 🔴 Lo que falta en el modelo canónico

`VerificationSource.PROVIDER` dice **que** lo resolvió un proveedor, no **cuál**. Con Jumio en el
horizonte eso deja de bastar. Y no hay dónde guardar lo que el proveedor devuelve para poder
auditarlo o reabrirlo:

```
party.kyc_verifications
  verification_id · party_id · document_type · verification_status
  verified_by · verified_at · rejection_reason · document_ref · expires_at
```

**Ni `provider`, ni `interview_id`, ni `session_id`, ni metadata.** Si mañana Incode pregunta «¿qué
entrevista fue?», no hay respuesta.

### Y hay **dos** modelos de KYC — que deben ser uno

| | `party.kyc_verifications` | `beneficiary.placements.identity_*` |
|---|---|---|
| A quién cubre | la persona registrada | la beneficiaria de una colocación B2B2C |
| Modo (auto/manual) | 🔴 no tiene | ✅ `IdentityVerificationMode` |
| Origen del veredicto | 🔴 no tiene | ✅ `VerificationSource` |
| Proveedor | 🔴 no | 🔴 no (sólo «hubo uno») |
| Metadata del proveedor | 🔴 no | 🔴 no |

**Son el mismo proceso hecho dos veces, y el más completo es el que cubre a menos gente.**

> **Decisión: un solo proceso de KYC/AML para todo el que quede registrado**, sea beneficiaria del
> modelo distribuidor o cliente de uso propio. La forma correcta ya está en `beneficiary`; lo que
> falta es subirla al nivel donde vive todo el mundo.

Es estructuralmente posible y está verificado: `beneficiary.placements` tiene
`beneficiary_party_id`, así que **toda beneficiaria es —o llega a ser— un `party`**. El modelo
canónico es el de `party`, y la colocación lo referencia en vez de llevar su propia copia.

#### El AML de hoy es una bandera, no un proceso

`PartyService.blacklistParty(partyId, reason, sourceList)` marca a alguien contra una lista, y
publica `party.party-blacklisted`. **No hay screening**: nadie consulta listas al registrar. El
campo `sourceList` está preparado para decir de qué lista vino, y hoy lo llena quien marca a mano.

Unificar KYC sin unificar AML dejaría el proceso a medias: se verifica que la persona es quien dice
ser, y no que se le pueda dar crédito.

### ✅ La configuración depende del PRODUCTO, no del entorno — resuelto en F3b

`IdentityVerificationMode` **era** una bandera global del servicio. Eso no aguantaba el negocio
real: una línea de distribuidor y una tarjeta de uso propio no exigen lo mismo, y el día que un
producto pidiera verificación reforzada, cambiar la bandera se la aplicaba a todos.

> **La configuración vive con el producto; el resultado, con la persona.**

Hecho: la política es `Capabilities.kyc` del catálogo y viaja por `product-catalog.product-activated`.
El enum y la variable de entorno se retiraron. Las dos tablas de arriba describen el as-is anterior
a F3b y se dejan como estaban: son el punto de partida contra el que se midió el cambio.

Y hay una secuencia que complica el mapeo, y conviene decirla en vez de descubrirla:

```
uso propio     registro → KYC → solicitud → PRODUCTO        ← el KYC ocurre ANTES del producto
distribuidor   línea (producto) → beneficiaria → KYC        ← el producto ya se conoce
```

En uso propio el KYC base ocurre antes de que haya producto. Así que el modelo no es «el producto
decide el KYC» sino **«hay un KYC base para registrarse, y un producto puede exigir más»**. Quién
resuelve esa exigencia es el subdominio que conoce el producto —originación o colocación—, y
`party` **guarda lo que ocurrió** sin saber de productos.

Es el mismo principio que ya sostiene `VerificationSource`: se guarda con el veredicto y no se
deduce de la bandera vigente, porque la bandera cambia y el expediente tiene que seguir diciendo la
verdad de entonces.

## 3. Círculo — existe, y su mock es mejor de lo que reporté

> ⚠️ **Corrijo dos cosas que dije antes.** Escribí que el buró es «síncrono» y que
> `MockCirculoAdapter` tiene «0 escenarios». Las dos son falsas.

### El flujo real es asíncrono

```
origination.prospect-created → ProspectCreatedEventListener (scoring)
                             → BureauPrefetchService.initiate() ──HTTP──▶ Círculo
```

`scoring` **no llama al buró al evaluar**: lee lo que el prefetch dejó. La frontera asíncrona ya
existe.

### El adaptador real existe

`CirculoCreditoAdapter`, apuntando al sandbox, con timeout de 30 s. Hoy está apagado
(`SCORING_CIRCULO_MOCK_ENABLED=true`) y corre el mock.

### El mock tiene 2 escenarios de negocio y **0 de fallo**

```java
boolean forceReject = curp.startsWith("XEXX");
int fico = forceReject ? 480 : 720 + Math.floorMod(curp.hashCode(), 100);
…
.status(CirculoReportStatus.SUCCESS)     // ← SIEMPRE
```

Devuelve los identificadores de la petición —nombre, apellidos— que es exactamente lo que se le
pide a un mock. Varía el FICO por CURP de forma determinista. Tiene camino de rechazo.

**Lo que no tiene: `status` distinto de `SUCCESS`.** No puede simular buró caído, timeout ni reporte
parcial — que es justo el camino que deja a un prospecto sin decisión.

## 4. STP — el único completo

`StubStpGateway` con **11 escenarios** seleccionables por los centavos del importe, incluidos
`REJECTED_PLD`, `RETURNED`, `NEVER_SETTLES`, `INVALID_SIGNATURE` y `TIMEOUT`. Es la plantilla.

## 5. Los reintentos, hoy

**`spring-retry` no es dependencia de ningún servicio.** Lo que hay:

| Dónde | Forma | Veredicto |
|---|---|---|
| `stp` · `OutboxMessage.scheduleRetry` | backoff exponencial acotado, **sin bloquear hilo** | ✅ el patrón correcto, y el código lo dice: *«el legado hacía `Thread.sleep(5 min)`»* |
| `scoring` · `ScoreRequestedEventListener:63` | `Thread.sleep(600)` × 5 **dentro del listener** | 🔴 bloquea la partición 3 s por solicitud |
| `beneficiary` · `PartyClient:54` | `Thread.sleep` en bucle | 🔴 |
| `beneficiary` · `DispositionCompletedListener:110` | `Thread.sleep(400)` | 🔴 |

### 🔴 Y el reintento de scoring cubre la carrera, no el fallo

Cinco intentos por 600 ms son **tres segundos**: sirven para «el prefetch va en camino». Si el
prefetch terminó en `FAILED` durante el onboarding —hace horas—, tres segundos no cambian nada.
Agotados: `WARN`, mensaje consumido, solicitud en `PENDING_SCORING` **sin dueño**.

Y las dos causas —«no hay reporte» y «no hay política activa»— salen por el mismo `Optional.empty()`
y el mismo log. Son problemas distintos, con dueños distintos.

---

# Parte II · Cómo manejarlo

## 6. Principio: un mock responde como el proveedor, incluido fallar

> **Ningún `Noop` fuera de producción.** Un `Noop` no es un mock incompleto: **miente en la
> dirección peligrosa**, porque siempre dice que sí.

Reglas para los tres mocks:

1. **Payload válido del proveedor**, con su forma real — no un objeto vacío.
2. **Ecos de los identificadores recibidos** (`prospectId`, CURP, nombre): lo que el llamador mandó
   vuelve, para que la traza cuadre de punta a punta. `MockCirculoAdapter` ya lo hace.
3. **El resto, fijo y determinista**: cuentas, montos, `interviewId`, `sessionId`. Determinista para
   que una prueba que pasa hoy pase mañana.
4. **Escenarios de fallo seleccionables**, incluido el silencio. Un mock que no puede fallar no
   prueba la resiliencia: la esconde.
5. **`WARN` al arrancar** diciendo que es un mock, como ya hace el del buró.

## 7. El modelo canónico: KYC es nuestro, no del proveedor

`kyc` es la representación del hecho —una identidad verificada— y **puede resolverse automática o
manualmente**. El proveedor es un detalle de cómo se resolvió, no la definición.

### Lo que hay que añadir

| Columna | Para qué |
|---|---|
| `provider` | **cuál** proveedor, no sólo que hubo uno. `INCODE`, `JUMIO`, `NINGUNO` |
| `provider_reference` | el identificador de la sesión — `interviewId` de Incode |
| `provider_metadata` (JSONB) | lo que cada proveedor devuelve y no cabe en columnas: scores por métrica, checks fallidos, versión del modelo |
| `verification_mode` en `party.kyc_verifications` | hoy sólo lo tiene `beneficiary` |
| `verification_source` en `party.kyc_verifications` | ídem |

**JSONB y no columnas por proveedor**: Incode devuelve `liveness` y `facialMatch`, Jumio otra cosa.
Modelar la unión de todos los proveedores en columnas es acoplarse a todos a la vez.

### La configuración que decide

```
Capabilities.kyc.kycRequired           ← ¿se exige?
Capabilities.kyc.automationEnabled     ← ¿se intenta automático?
Capabilities.kyc.provider              ← INCODE | JUMIO | NONE
Capabilities.kyc.manualFallbackAllowed ← ¿hay salida humana si falla?
```

Dos banderas y no una para el proveedor: *«no hay automatización»* y *«hay automatización con
Incode»* son estados distintos de *«hay automatización y no sé con quién»* — y ese tercero el
catálogo lo rechaza al declararlo.

**Y va en el producto, no en el servicio** (F3b, hecho). Una propiedad de `beneficiary-service`
obligaba a que todos los productos de un ambiente compartieran regla, y aflojar la verificación era
un redeploy sin autor ni fecha.

## 8. Los reintentos: Spring Retry, y qué pasa al agotarse

**Spring Retry con backoff exponencial**, y `Thread.sleep` fuera de los listeners.

| Proveedor | Política | Al agotarse |
|---|---|---|
| **Círculo** | reintento del **prefetch** con backoff | `WARN` + **cola de intervención manual**. No hay fallback humano: alguien tiene que reintentar o rechazar a mano |
| **Incode** | reintento de la sesión | degrada a **revisión manual**, que ya existe y está probada |
| **STP** | el outbox ya lo hace bien | ya resuelto |

> **Para Círculo el reintento no es una comodidad: es el único mecanismo.** Por eso su cola de
> pendientes tiene que ser visible, no un `WARN` en un log.

### Y el reintento de scoring hay que separarlo en dos

Hoy un solo bucle cubre dos cosas distintas:

- **la carrera** — el prefetch va en camino: eso es esperar, y 3 s está bien;
- **el fallo** — el prefetch terminó en `FAILED`: eso es **volver a pedirlo**, y hoy no ocurre nunca.

## 9. El flujo completo, y dónde está cada fallback

```
  KYC ──────────────▶ aprobación ──────▶ scoring ──────▶ decisión ──────▶ desembolso
   │                                        │                                 │
   ▼                                        ▼                                 ▼
 automático (Incode)                   Círculo                              STP
 o MANUAL  ← fallback humano          SIN fallback                     SIN fallback
                                      (nadie tiene el dato)          (pero revierte)
```

**KYC es una aprobación con salida humana. Scoring no la tiene.** Ningún analista puede inventar un
FICO: si el buró no contesta, la solicitud espera o se rechaza — y esa es una decisión de negocio,
no técnica.

---

# Parte III · El plan

| Fase | Qué | Coste |
|---|---|---|
| **F1** | `spring-retry` + sacar los tres `Thread.sleep` de los listeners | ~0.5 d |
| **F2** | Separar en scoring la **carrera** del **fallo**; reintento real del prefetch con backoff | ~1 d |
| **F3** | Modelo canónico **unificado** en `party`: `provider`, `provider_reference`, `provider_metadata`, `mode` y `source`. La colocación lo referencia en vez de duplicarlo | ~1.5 d |
| **F3b** | ✅ **Hecho.** La exigencia de KYC es del **producto** (`Capabilities.kyc`), no de `BENEFICIARY_IDENTITY_VERIFICATION_MODE`. Proyección en beneficiary por `product-catalog.product-activated`; default seguro «exigido y manual»; el catálogo rechaza las combinaciones incoherentes al declararlas | ~0.5 d |
| **F4** | `MockCirculoAdapter` gana escenarios de **fallo** (caído, timeout, parcial) | ~0.5 d |
| **F5** | `MockIncodeAdapter` — KYC y firma, payload real, escenarios | ~1.5 d |
| **F6** | Cola de intervención manual del buró, visible en el backoffice | ~1 d |
| **F7** | Pruebas: unitarias, de aceptación e integración de todo lo anterior | ~1.5 d |

**F1 y F2 van primero** porque no dependen de ningún proveedor y arreglan un fallo que ya ocurre.
