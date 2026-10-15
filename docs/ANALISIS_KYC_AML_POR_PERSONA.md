# KYC/AML evalúa personas — análisis del as-is

> Verificado leyendo código. Cada afirmación lleva archivo y línea.
>
> **Es una foto del 2026-09-14, antes de construir D16, y se conserva como tal.** Sus 🔴 describen
> el estado de **entonces** — varios ya están cerrados: `channels` fallaba abierto y ahora detiene,
> `origination` no tenía compuerta y ahora la tiene, la colocación aprobaba sin mirar listas y ahora
> no. No se actualiza porque su valor es justamente ser el antes: es lo que permite comprobar que el
> después existe. El estado vigente está en
> [`BRIEF_CONTROLES_KYC_AML.md`](BRIEF_CONTROLES_KYC_AML.md) §5.

## La pregunta

> *«El componente está evaluando KYC/AML de personas. Origination debería discriminar si es self
> use, beneficiario o distribuidor —o sea, que se mande evaluar a la persona sin importar cuál sea—.
> El scoring es el que podría ser diferente.»*

## La respuesta corta

**Es correcto, y el modelo del repo ya lo dice.** Lo que falta no es el diseño: es que el KYC no
cuelga de donde debería.

Hay tres ejes, y están bien separados en el modelo:

| Eje | Dónde vive | ¿Varía? |
|---|---|---|
| **Quién es la persona** | `Party` + `PartyType` (`INDIVIDUAL` \| `BUSINESS`) | No. Es la misma identidad siempre |
| **Qué rol juega** | `PartyRole` (`DISTRIBUTOR` \| `BENEFICIARY` \| `GUARANTOR`) | Aditivo, no excluyente |
| **Qué está pidiendo** | `productTypeIntent` | Sí — y es lo único que cambia la decisión |

El invariante **I-03** del dominio de party lo declara textualmente: *«un distribuidor es party + rol
`DISTRIBUTOR`, no un `PartyType` aparte — así el mismo sujeto puede ser cliente y distribuidor sin
duplicarse»* (`docs/dominios/00_party_domain.md:20`). `PartyRoleType` lo repite en el código:
*«capacidades aditivas, no tipos»* (`PartyRoleType.java:4-6`).

---

## 1 · Lo que el código ya hace bien: el scoring varía sólo por producto

Y aquí hay una corrección que conviene registrar: **el documento de dominio está desactualizado y el
código tiene razón.**

`docs/dominios/02_scoring_domain.md:32` dice que `ScoringPolicy` se indexa por
`(prospectType, productTypeIntent)`. **Ya no.** La migración `013-rekey-scoring-policies-by-product.sql`
la re-indexó por producto: el único índice es `(product_type_intent) WHERE active` (`:53-55`), y
`prospect_type` quedó como columna **descriptiva**, fuera de la llave (`:1-26`). La propia migración
argumenta por qué: *ningún `product_type` aparece bajo dos `prospect_type` distintos* (`:10-14`).

Los tres puntos de resolución de política **reciben `prospectType` y no lo usan**:

| Método | Archivo:línea | Qué resuelve |
|---|---|---|
| `evaluateForApplication(…, prospectType, productType)` | `ScoringEvaluationService.java:77` | `findActiveBy(productType)` |
| `evaluateForProspect(…, prospectType, productTypeIntent)` | `:99` | `findActiveBy(productTypeIntent)` |
| `prequalifyOne(report, prospectType, productType)` | `:156` | `findActiveBy(productType)` |

Las nueve políticas sembradas confirman que la diferencia real es el producto: `DISTRIBUTOR_LINE`,
`SME_LOAN` y `BUSINESS_REVOLVING_LINE` tienen la banda de aprobación automática puesta en **100000**
—inalcanzable a propósito— para que todo caiga a comité (`008-seed-multi-segment-scoring-policies.sql:81-83`,
`:65-67`, `013-…:117-119`). Un préstamo personal aprueba solo con score ≥ 200.

> **Conclusión del eje:** la variación por producto ya está donde debe, y el `prospectType` que
> viaja en `ScoreRequestedPayload.java:13` hoy sólo se registra en el log
> (`ScoreRequestedEventListener.java:69-70`). Es un parámetro vestigial, no una dimensión de decisión.

---

## 2 · Dónde se rompe: el KYC no cuelga de la persona

El evento **`origination.prospect-created` ya es el disparador universal de «se registró una
persona»**, y ya reparte a cinco servicios:

| Consumidor | Qué hace | Archivo:línea |
|---|---|---|
| `party` | crea el `Party` en estatus PROSPECT | `ProspectCreatedEventListener.java:21-28` |
| `scoring` | dispara el **prefetch de buró** | `ProspectCreatedEventListener.java:29-52` |
| `identity` | crea la credencial | `KafkaProspectCreatedConsumer.java:20-32` |
| `notifications` | bienvenida | `ProspectCreatedListener.java:19` |
| `audit` | bitácora | `OriginationEventListener.java:23` |

**El scoring ya cuelga del alta de la persona. El KYC no.** `PartyService.createFromProspect`
(`:47-72`) crea el `Party` y **no crea ninguna `KycVerification`**.

Y la consecuencia se puede medir:

| Hecho | Evidencia |
|---|---|
| `party.kyc_verifications` **no la puebla ningún flujo** | `PartyService.addKycVerification` (`:79`) sólo lo llama `PartyController.java:116` (`PUT /kyc-status`), y **ningún cliente del monorepo llama ese endpoint** |
| `KycVerification.verifyWithProvider(...)` | **cero llamadores en producción** |
| El único KYC vivo está en **otro agregado y otro servicio** | `Placement.identityDecision` (`Placement.java:120-122`), con su propia compuerta `Placement.approve(...)` (`:274-276`) y su `CHECK` en base (`006-identity-review.sql:50-53`) |

Es decir: hay **dos verdades paralelas sobre la identidad de la misma persona** —una en la colocación
y una en el party, esta última vacía— y la que existe está atada a un rol (beneficiaria) en vez de a
la persona.

### Y el camino de la beneficiaria sólo funciona con la bandera de simulación

`lifecycle.startKyc` / `completeKyc` **sólo se alcanzan desde `KycSimulationController`**
(`:74`, `:105`), que existe únicamente si `kyc-simulation-enabled=true` (`:39-40`). El default es
`false`; `docker-compose.yml:618` y `:1014` lo encienden. Con la bandera apagada **ninguna
colocación puede salir de `INVITED`**.

Lo mismo con el rol: `PartyRole.BENEFICIARY` y `PartyRole.DISTRIBUTOR` **no los otorga ningún
servicio**. Su única invocación en todo el repo está en `scripts/seed-distribuidoras.py:483` y `:337`,
y entra **por la red interna de Docker saltándose el gateway** (`:139-156`).

---

## 3 · Los cuatro caminos de alta convergen, y origination no sabe cuál es cuál

| Camino | `prospectType` que llega | `channelType` | Marcador de origen |
|---|---|---|---|
| App móvil (uso propio) | `INDIVIDUAL` **hardcodeado** (`channel-mobile/OriginationClient.java:280`) | `MOBILE_APP` (`:300`) | ninguno |
| Beneficiaria de distribuidor | `INDIVIDUAL` **hardcodeado** (`beneficiary/OriginationClient.java:40`) | `WHATSAPP` (`:62`) | ninguno |
| Distribuidor | `INDIVIDUAL` — **es el camino de la app móvil** (`seed-distribuidoras.py:231`) | `MOBILE_APP` | ninguno; el rol se otorga después y sólo por script |
| HTTP directo | cualquiera — `POST /origination/prospects` es **`permitAll()`** (`SecurityConfig.java:33`) | cualquiera | — |

**Un prospecto creado por beneficiary es indistinguible de uno creado por la app móvil**, salvo por
`channelType`. Y `channelType` es el canal por el que entró, no el papel que va a jugar: el propio
código lo dice (`beneficiary/OriginationClient.java:59-61`).

Así que hoy origination **no puede discriminar** self-use / beneficiaria / distribuidor: no tiene el
dato. La ligadura existe, pero **fuera** de origination, en tres sitios distintos:
`beneficiary.placements.beneficiary_party_id` (`Placement.java:73`), `party.party_relationships`
tipo `BENEFICIARY` (`PartyClient.java:81-82`) y el `OrgUnit.partyRef` de sales-org.

### 🔴 Y hay una trampa armada: `ProspectType.DISTRIBUTOR`

El enum tiene tres valores —`INDIVIDUAL, BUSINESS, DISTRIBUTOR`— (`ProspectType.java:3-7`) y el
`CHECK` de la base los admite (`011-extend-product-and-prospect-types.sql:18-21`).

Pero `PartyType` **sólo tiene dos** (`PartyType.java:3-6`), y party hace la conversión sin red:

```java
PartyType partyType = PartyType.valueOf(payload.prospectType());   // PartyService.java:53
```

**Un prospecto con `prospectType=DISTRIBUTOR` revienta el listener de party con
`IllegalArgumentException`.** No ocurre hoy sólo porque ningún código de producción produce ese
valor — pero el endpoint que lo acepta es **público** (`permitAll()`), y party corre con **régimen de
reintentos por defecto, sin DLT** (`docs/dominios/00_party_domain.md:41`). Una sola petición con ese
valor deja el partición del topic reintentando un mensaje que nunca va a pasar.

Que el valor exista en el enum y en el `CHECK` es además la señal equivocada: sugiere que
«distribuidor» es un tipo de persona, justo lo que I-03 prohíbe.

---

## 4 · AML no existe — y la bandera que F3b agregó no hace nada

Esto hay que decirlo sin adornos, porque es trabajo propio de esta sesión.

F3b añadió `amlRequired` a `Capabilities.kyc` (`KycOptions.java:34`), se sembró en `true` para los
nueve productos (`020-kyc-por-producto.sql:32-41`), y se propaga hasta `KycPolicy.java:24` en
beneficiary. **Ahí muere.** La única decisión que toma la compuerta es `attemptsProvider()`, que es
`kycRequired && automationEnabled && hasProvider()` (`KycPolicy.java:48`): **`amlRequired` no se
evalúa en ningún punto.** Es una bandera declarativa sin efecto.

Y no es que le falte un adaptador: **no hay nada de AML en toda la plataforma.**

| Pieza | Estado real |
|---|---|
| `PartyStatus.BLACKLISTED` | existe como estado (`PartyStatus.java:7`) |
| `PartyService.blacklistParty(partyId, reason, sourceList)` | `sourceList` **se recibe, se publica y nunca se consulta contra ninguna lista** (`:108-123`) |
| `POST /parties/{id}/blacklist` | **sin un solo llamador** en el monorepo |
| `party.party-blacklisted` | se publica; el único consumidor efectivo aborta intents en channels (`IntentService.java:76-88`) |
| `UIFReport` (INUSUAL / RELEVANTE / INTERNO) | el agregado existe y **no tiene escritor**: `AuditController` sólo lee (`:142-157`) |
| Screening contra OFAC / CNBV / UIF / PEP | **no existe** — cero resultados funcionales en todo el repo |
| Marca de PEP | sólo un javadoc (`ApprovalFlow.java:10`) |

`RECHAZO_POR_PLD` existe, pero es un **código de respuesta de Banxico** al dispersar
(`BanxicoResponseCode.java:60`): es el PLD de la contraparte, no el nuestro.

> **Conclusión del eje:** hoy nadie es evaluado contra ninguna lista al registrarse. El KYC que sí
> corre es verificación de identidad —y débil: OTP con código de ambiente, OCR *stub* con datos
> fijos, sin contrato de proveedor—. Llamarlo «KYC/AML» describe la intención, no lo que ocurre.

---

## 5 · Qué implicaría hacerlo como dices

El cambio es de **dónde cuelga**, no de qué se hace. Tres movimientos, en este orden:

### M1 · Origination declara el origen, y no decide nada con él

Un campo nuevo en `Prospect` —llámese `registrationPurpose` o `intendedRole`: `SELF_USE` ·
`DISTRIBUTOR_BENEFICIARY` · `DISTRIBUTOR` · `GUARANTOR`— que **viaje en `prospect-created`**.

Dos propiedades que tiene que cumplir:

1. **No condiciona el KYC.** Se evalúa a la persona igual en los cuatro casos. Sirve para trazar, para
   otorgar el `PartyRole` correcto automáticamente (hoy sólo lo hace un script), y para que el
   backoffice pueda separar colas.
2. **No se confunde con `prospectType`.** `prospectType` es INDIVIDUAL/BUSINESS —qué clase de sujeto
   es— y debe **perder** su valor `DISTRIBUTOR`, que es la trampa de §3. Un distribuidor es una
   persona física con un rol, no un tipo de persona.

### M2 · El KYC/AML cuelga de `prospect-created`, en `party`

`party` ya es dueño de `KycVerification`, de `PartyStatus.BLACKLISTED` y de los roles. Al crear el
`Party` crea también la verificación en `PENDING`, y **ahí** se resuelve: manual o con proveedor,
según la política del producto que F3b ya sabe resolver.

Esto convierte a `party.kyc_verifications` en lo que su nombre dice que es y hoy no es: el registro
de que una persona fue verificada, una vez, independiente del rol y del producto.

### M3 · La colocación deja de tener su propia verdad

`Placement.identityDecision` pasa a ser **proyección** del veredicto del party —o la compuerta
`approve(...)` lo consulta— en vez de un segundo dictamen sobre la misma persona. La cola del
analista y el modelo de umbrales que ya existen en beneficiary **no se tiran**: se mueven al lado de
la persona, que es donde sirven para los cuatro caminos y no sólo para uno.

### Lo que NO cambia

El scoring. Ya varía por producto y está bien: `findActiveBy(productType)`, con las bandas de
`DISTRIBUTOR_LINE` y los productos de empresa puestas para que todo caiga a comité. Ese eje está
resuelto y no se toca.

---

## 6 · Lo que hay que decidir antes de escribir código

| # | Decisión | Por qué no la puedo tomar yo |
|---|---|---|
| 1 | ¿El KYC bloquea el alta o corre en paralelo? | Si bloquea, una beneficiaria no puede ver su oferta hasta que un analista firme. Si corre en paralelo, hay ventana entre «registrada» y «verificada» — y hay que decidir qué se le permite hacer en esa ventana |
| 2 | ¿`amlRequired` se implementa o se retira? | Una bandera sembrada en `true` que no hace nada es peor que no tenerla: dice que hay screening |
| 3 | ¿Se mueve el motor de umbrales de beneficiary a party, o party lo consulta? | Mover toca un servicio probado; consultar mete una llamada síncrona en el alta |
| 4 | ¿Se quita `DISTRIBUTOR` de `ProspectType`? | Es un cambio de enum + `CHECK` con datos existentes; y hay que confirmar que ninguna fila lo usa |

---

## Anexo · Los caminos de alta, verificados

| Camino | Crea Prospect | Crea Party | Verificación de identidad real |
|---|---|---|---|
| HTTP directo (`permitAll`) | sí | sí (Kafka) | **ninguna** |
| App móvil — uso propio | sí | sí | OTP con código de ambiente + OCR *stub* + documentos a dictamen manual |
| Beneficiaria de distribuidor | sí | sí | ninguna en el simulador; la compuerta cae siempre a revisión manual |
| Distribuidor | sí (como `INDIVIDUAL`) | sí | **idéntica a la de cualquier cliente** — ningún control adicional |
| Empleado / colaborador (`StaffUser`) | **no** | **no** | contraseña; CURP opcional |

---

# Parte II · Las decisiones tomadas, y dónde caen

> Decidido por el dueño del producto (2026-09-14). Esta parte ya no analiza opciones: describe qué
> implica lo decidido y qué hay que construir.

| # | Decisión |
|---|---|
| **D1** | **El KYC bloquea el flujo.** No se avanza sin veredicto de identidad |
| **D2** | Dos modos: **automático** —proveedor tipo Incode/Jumio, y **las excepciones van a revisión manual**— o **manual**, donde todas pasan por revisión |
| **D3** | **AML es un servicio propio** que responde si la persona está en listas negras o es PEP, sincronizado con listas de gobierno vía **CSV con layout** |
| **D4** | La colocación de un beneficiario la decide el **distribuidor**, *si y sólo si* la identidad se pudo validar **y** la persona no está en listas |
| **D5** | Esos procesos corren **al alta de la persona**, no al colocar. Si queda marcada, no se permite la colocación — **igual para self-use que para distribuidor** |

## 1 · D2 ya está construido, y F3b lo dejó configurable por producto

El comportamiento que D2 describe es exactamente el de `ConfigurableIdentityVerificationGateway`:

```
sin automatización → todo a revisión humana. No se llama a nadie.
con automatización → se pregunta al proveedor:
                       · caído / sin contrato / timeout → revisión humana
                       · no pudo validar un documento   → revisión humana
                       · algún umbral no alcanzado      → revisión humana
                       · todo por encima de umbral      → VERIFIED automático
```

Y desde F3b, **cuál de los dos modos aplica lo decide el producto**, no una variable de entorno
(`Capabilities.kyc.automationEnabled` + `provider`). Lo que falta para D2 no es diseño: es el
adaptador de Incode, que hoy es `UnavailableKycProviderAdapter` (`:22`) y siempre reporta
indisponible — por eso todo cae a manual. Es la fase **F5** del plan de proveedores.

## 2 · D5 cambia dónde vive el veredicto

Hoy el veredicto de identidad vive en la **colocación** (`Placement.identityDecision`). D5 dice que
los procesos corren **al alta de la persona**. Son dos cosas distintas y la segunda es la correcta:
una persona se verifica una vez, no una vez por cada crédito que le coloquen.

Consecuencia: `Placement.identityDecision` deja de ser la fuente y pasa a **consultar** el veredicto
de la persona. Lo mismo el screening de listas — que nunca estuvo en la colocación.

## 3 · Dónde cae cada compuerta

| Camino | Qué se bloquea | Dónde está hoy |
|---|---|---|
| **Distribuidor → beneficiaria** | la aprobación del distribuidor | `Placement.approve(...)` (`Placement.java:270-291`) — **ya bloquea por identidad**; falta la condición de listas |
| **Self-use** | la oferta / el avance de la solicitud | **no existe ninguna compuerta** en origination (ver Parte I §2) |

El comentario que ya está escrito en `approve(...)` explica por qué el corte va ahí y no más
adelante: *«aprobar dispara el depósito, y lo único que detiene un depósito es una identidad no
comprobada — rebotar en la disposición le daría a la distribuidora un fallo mudo en vez de un
motivo»*. Con D4 lo que detiene un depósito pasan a ser **dos** cosas, y el mismo argumento aplica a
la segunda.

### D4 es una conjunción de responsables distintos

```
puede_colocar  =  distribuidor_acepta_el_riesgo
               ∧  identidad_verificada        (Kredius)
               ∧  sin_coincidencia_en_listas  (Kredius / regulador)
```

Las tres son de dueños distintos y ninguna sustituye a otra. La primera ya está en el código
(`riskAcknowledged` explícito, y el comentario dice que una aprobación sin esa casilla *«no es una
aprobación incompleta, es una que no existe»*). La segunda existe. **La tercera no existe en
absoluto.**

## 4 · 🔴 El único control de listas que existe hoy falla ABIERTO

`channels-service` comprueba si la parte está en lista negra antes de dejar pasar un intent
(`IntentService.java:76-88`, invariante IR-02). Pero:

```java
} catch (Exception ex) {
    log.warn("Party status check failed for partyId={}, proceeding without blacklist check: {}", …);
}
```

**Si party no contesta, se continúa sin comprobar.** Para un control regulatorio esa polaridad está
invertida: la indisponibilidad de la comprobación tiene que **detener**, no dejar pasar. Es el mismo
error de polaridad que F3b evitó en el default de KYC, aquí ya cometido y en producción.

Cuando el screening sea una condición para colocar (D4), fallar abierto significaría **dispersar
dinero a alguien sin haber comprobado que no está en una lista**, porque un servicio estaba caído.

## 5 · El screening NO es un booleano, y ése es el problema operativo real

Un match contra listas de gobierno se hace **por nombre**, porque el CURP o el RFC casi nunca vienen
en ellas. Y el nombre no identifica: «JUAN PÉREZ GARCÍA» coincide con decenas de personas.

Por eso el resultado de un screening no es «sí/no» sino **una lista de candidatos con un puntaje**, y
alguien de cumplimiento la despeja. Modelarlo como booleano tiene dos salidas y las dos son malas:
con el umbral bajo se bloquea a media cartera; con el umbral alto no se bloquea a nadie y el control
es decorativo.

Esto obliga a **tres estados y no dos** en la persona:

| Estado | Qué significa | Qué permite |
|---|---|---|
| `CLEARED` | se corrió el screening y no hay coincidencia | colocar / originar |
| `POTENTIAL_MATCH` | hay candidatos por encima del umbral, **sin despejar** | **nada** — espera adjudicación |
| `CONFIRMED_MATCH` | cumplimiento confirmó la coincidencia | nada, y es definitivo |

`POTENTIAL_MATCH` es el estado que hace que el control sea operable: bloquea igual que un
confirmado, pero es resoluble por una persona y deja rastro de quién lo despejó.

> **Un `CLEARED` sin fecha no sirve.** Las listas cambian: alguien despejado hace seis meses puede
> estar hoy. El veredicto lleva la **versión de lista** contra la que se corrió, y un cambio de
> versión obliga a re-correr a la cartera viva.

## 6 · Y KYC y AML no son el mismo veredicto

| | KYC | AML |
|---|---|---|
| Qué pregunta | ¿eres quien dices ser? | ¿estás en una lista? |
| Quién lo resuelve si falla | **la persona**, con mejor evidencia | **nadie del lado de la persona** — cumplimiento adjudica |
| Reintentable | sí | no: la lista no cambia porque insistas |
| Falso positivo típico | foto borrosa | **homonimia** — y es lo común, no la excepción |

Colapsarlos en una bandera «verificado» pierde la distinción que importa: a una le mandas al cliente
a repetir su selfie; a la otra, no.

---

# Parte III · El servicio de listas — alcance de lo que se construye ahora

D3 lo define: un servicio que responde si una persona está en listas o es PEP, sincronizado desde
CSV de gobierno. **Lo que se desarrolla ahora es la carga**, con mocks.

## Qué es «cargar» bien una lista

| Regla | Por qué |
|---|---|
| **Una carga es una versión, no una mutación** | Nunca se edita la lista viva. Se carga una versión nueva y se activa al terminar. Una lista a medio cargar es peor que la de ayer completa: los que faltan salen «limpios» |
| **Idempotente por hash del archivo** | El mismo archivo dos veces no hace nada. Las sincronizaciones se reintentan y se solapan |
| **Normalización al cargar, no al consultar** | El nombre se guarda ya normalizado —mayúsculas, sin acentos, sin puntuación, orden estable—. Si se normaliza al consultar, dos consultas con distinto código dan distinto resultado |
| **Una fila mala no tumba la carga; una carga sin filas buenas sí falla** | Los CSV de gobierno vienen sucios. Se registra el rechazo con su número de línea y su motivo, y se sigue. Pero un archivo del que no se pudo leer nada es un error de verdad, no una lista vacía |
| **El rechazo se cuenta y se compara** | Si una carga rechaza el 40 % de las filas cuando la anterior rechazó el 2 %, el layout cambió. Eso tiene que avisar, no pasar |

## El mock se comporta como el proveedor, incluido fallar

Misma regla que el resto de la plataforma: un mock que siempre entrega un CSV perfecto no prueba
nada. El generador produce **datos sucios a propósito** — acentos inconsistentes, fechas en dos
formatos, filas truncadas, nombres con ruido, duplicados, y encoding latino — porque eso es lo que
llega de verdad.
