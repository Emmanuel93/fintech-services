# Los controles de KYC y AML: qué corre, dónde y por qué

> Brief de la regla decidida por el negocio (2026-09-14), con el as-is verificado línea a línea y lo
> que falta construir. Complementa [`ANALISIS_KYC_AML_POR_PERSONA.md`](ANALISIS_KYC_AML_POR_PERSONA.md).

---

## 1 · La regla, en una tabla

| Control | Qué pregunta | Sobre quién | Originación | Disposición |
|---|---|---|---|---|
| **KYC** | ¿eres quien dices ser? | la persona que se registra | ✅ **una vez** | ❌ **no se repite** — lo sustituye la autenticación |
| **Autenticación** (NIP + OTP) | ¿eres tú **ahora**, y te haces responsable? | **el responsable de la línea** — quien autoriza | — | ✅ **en cada una** |
| **AML** (listas y PEP) | ¿estás en una lista? | **el responsable Y quien recibe el dinero** | ✅ | ✅ **en cada una** |

> La columna del medio es la que se presta a confusión y por eso está. **Quien autoriza y quien
> recibe no son el mismo** en un producto de distribuidor: la distribuidora autoriza la colocación y
> la beneficiaria recibe el dinero. En uso propio sí coinciden — y es la coincidencia, no la regla,
> lo que hace fácil pensar que hay un solo papel. Detalle en §4.

### Por qué el KYC no se repite, y no es un atajo

El KYC establece la identidad **una vez**: documento, rostro, prueba de vida. Repetir eso en cada
disposición sería pedirle a alguien una selfie para sacar dinero de su propia línea todos los meses —
no aporta seguridad y sí garantiza que la gente deje de usar el producto.

Lo que sí hace falta en cada disposición es saber que **quien la está pidiendo es esa misma persona**,
y eso no se resuelve con biometría sino con **autenticación**: algo que sabe (el NIP) y algo que
tiene (el teléfono registrado, vía OTP). El KYC ató la identidad a esas credenciales; a partir de
ahí, usarlas **es** ser esa persona, y quien las usa responde por ellas.

**Y «esa persona» es el responsable de la línea, no quien recibe el dinero.** En uso propio son la
misma y la distinción no se nota; en una colocación de distribuidor, quien autoriza es la
distribuidora y quien recibe es la beneficiaria — así que las credenciales que se piden son las de la
distribuidora. A la beneficiaria no se le pide nada en ese momento: no está autorizando nada.

> Dicho de otro modo: **el KYC es el enrolamiento, la autenticación es el uso.** Confundirlos lleva a
> pedir enrolamiento en cada uso, que es la forma más común de que un control se apague «por
> fricción».

### Por qué el AML sí se repite, y es lo más importante de todo esto

Porque **el AML no depende de la persona, depende de la lista — y la lista cambia.**

Alguien limpio al abrir su línea puede aparecer publicado seis meses después. La originación ocurre
**una vez**; las disposiciones ocurren **decenas de veces durante años** — una línea revolvente
dispone cada mes, una de distribuidor en cada colocación. Así que:

```
originación = control de punto en el tiempo
disposición = el control RECURRENTE, y el último antes de que el dinero salga
```

Y no tiene sentido aprobar crédito ni línea a alguien en listas: por eso el AML también corre al
originar. Pero si sólo corriera ahí, el control caducaría el día después de la aprobación.

---

## 2 · El as-is: qué hay hoy de esto

### 🔴 Una disposición hoy no prueba absolutamente nada

`POST /credit/dispose` (`channel-mobile/CreditController.java:285-299`) resuelve al usuario así:

```java
String userId = httpRequest.getHeader("X-User-Id");
```

**Una cabecera.** Sin NIP, sin OTP, sin segundo factor de ningún tipo. Y
`ProcessDispositionCommand` —lo que llega a `credit-portfolio`— no tiene un solo campo donde pudiera
viajar evidencia de autenticación: `sourceEventId`, `creditAccountId`, `obligorPartyId`, `amount`,
`beneficiaryPartyId`, `payeeAccount`, `termPeriods`.

De modo que hoy **no existe nada que ate una disposición a la persona**, ni forma de demostrarlo
después.

### Lo que sí existe y sirve

| Pieza | Dónde | Estado |
|---|---|---|
| `CredentialType.NIP` | `identity-service/domain/CredentialType.java:4` | existe: `NIP` y `PASSWORD` |
| Login con NIP, con 2FA | `identity/AuthController.java:56` | existe, y contempla 2FA |
| Alta de credencial NIP | `identity/AuthController.java:127` | existe (ADMIN / bootstrap) |
| OTP con expiración | `channel-mobile/OtpService.java` | existe |
| **`otpVerificationId` como evidencia** | `beneficiary/BureauConsentGrantedEvent.java:26` | **el precedente**: ata un consentimiento a un teléfono demostrablemente suyo |

El precedente del consentimiento de buró es exactamente el patrón que hace falta: el evento lleva el
`otpVerificationId` *«porque es lo que ata el consentimiento a un teléfono demostrablemente suyo»*.
Una disposición necesita lo mismo.

### 🔴 Dos huecos que hay que nombrar

**El OTP no deja constancia consultable.** En `channel-mobile` el código se genera y se verifica en
vuelo, pero **no se persiste ningún `otpVerificationId`**. Sin eso no hay qué adjuntar a la
disposición ni qué mostrar cuando alguien pregunte «demuéstrame que fue él».

**Y en ambientes bajos el OTP es un código fijo** (`OTP_DEV_CODE`,
`OtpService.java:40-42`). Está bien para la demo y está bien argumentado en el código, pero conviene
decirlo: en esos ambientes la evidencia no prueba nada, y el control descrito aquí sólo existe de
verdad donde `otp-code-validation` está encendida.

---

## 3 · La decisión técnica que hace barato «AML en cada disposición»

Correr el cruce completo contra las listas en cada disposición sería caro y, casi siempre,
**inútil**: si las listas no cambiaron, el resultado es el mismo que ayer.

La clave está en algo que ya se construyó: **el veredicto guarda contra qué versiones de lista se
calculó** (`compliance.person_verifications.screened_list_versions`). Con eso, «AML en cada
disposición» se convierte en:

```
¿el veredicto de esta persona se calculó contra las listas VIGENTES?
    sí  → vale, y comprobarlo cuesta una lectura local
    no  → está caduco: hay que volver a cruzar antes de dejar salir el dinero
```

Así el control corre **en todas** las disposiciones y sólo hace trabajo real cuando las listas
cambiaron — que es exactamente cuando el resultado puede haber cambiado.

### De dónde sale la actualización

Cuando se activa una versión nueva de lista, **`compliance` vuelve a cruzar a la población viva y
republica los veredictos**. `credit-portfolio` mantiene una proyección local
(`credit_portfolio.party_verifications`) y la disposición la lee. Sin llamada síncrona en el camino
del dinero: fallar cerrado ahí sería correcto y significaría que un servicio caído detiene todos los
desembolsos.

> ⚠️ **Hay una ventana, y está decidida: se asume.** Entre que una lista se activa y que el re-cruce
> termina, la proyección está caduca y alguien recién publicado podría disponer. La alternativa
> —marcar todo caduco al activar— detendría **todos** los desembolsos mientras dura el re-cruce.
> Se eligió asumir la ventana y mitigarla con la ventana de mantenimiento. Ver §6.

### Y con qué se cruza: el CURP, incluso donde la lista no lo trae

El cruce es **por CURP**. Es el identificador que una persona tiene desde que nace, y a diferencia
del nombre **identifica**: no produce homónimos.

El obstáculo es medible y grande: **las listas casi nunca lo publican**. `SAT-69B` ni siquiera
declara la columna —trae RFC—, y una lista de sanciones internacional está llena de extranjeros que
no tienen CURP en absoluto. Un control que sólo cruzara por CURP publicado estaría, sobre algunas
fuentes, **apagado**.

Lo que lo destraba es una propiedad del propio CURP: **sus diez primeros caracteres no los asigna
nadie, se calculan** de los apellidos, el nombre y la fecha de nacimiento. Eso permite
**reconstruirlos para las entradas que no los traen** y compararlos contra la raíz del CURP real de
la persona.

| Lista | CURP publicado | Con raíz derivada | **Alcance del cruce por CURP** |
|---|---|---|---|
| `OFAC-SDN` | 36.51 % | +25 entradas | **76.19 %** |
| `PEP-MX` | 54.84 % | +14 entradas | **77.42 %** |
| `SAT-69B` | 0.00 % | 0 | **0.00 %** |

**Lo que gana.** Absorbe las variantes de escritura de forma determinista y **sin umbral que
calibrar** —`GONZÁLEZ` y `GONZALES` dan la misma raíz, `JOSÉ` y `JOSE LUIS` también— y a la vez
separa homónimos, porque incorpora la fecha de nacimiento exacta.

**Lo que no hace, y hay que decirlo.** Los caracteres 11 a 16 llevan sexo y estado, que las listas no
publican, y los dos últimos los asigna RENAPO: **no son derivables**. Una raíz derivada es por tanto
una **hipótesis fuerte, no una identificación** — vale menos que un documento y más que un nombre, y
así está ponderada en la evidencia (`CURP_ROOT`, fuerza 75, entre `NAME_AND_DATE_OF_BIRTH` y
`NAME_EXACT`). Y **no rescata a una lista sin fechas de nacimiento**: el renglón de `SAT-69B` es
exactamente eso, y por eso la cobertura se guarda por versión y se publica en cada carga.

---

## 4 · Qué se bloquea, exactamente

```
ORIGINAR (crédito o línea):
    identidad = VERIFIED          ∧   listas = CLEARED

DISPONER:
    autenticación válida del RESPONSABLE de la línea (NIP + OTP del teléfono registrado)
  ∧ listas = CLEARED del responsable      ∧ veredicto VIGENTE
  ∧ listas = CLEARED de QUIEN RECIBE      ∧ veredicto VIGENTE
  ∧ identidad del responsable no revocada

    ·  uso propio      → quien recibe ES el responsable; una sola persona, una comprobación
    ·  distribuidor    → quien recibe es la beneficiaria; nunca el responsable
```

Tres precisiones sobre la disposición:

1. **No se exige `VERIFIED` otra vez**: ya lo estaba al originar, y repetirlo sería re-enrolar.
   Lo que sí bloquea es que la identidad se haya **revocado** después —fraude comprobado, por
   ejemplo—, que es un hecho distinto de «no se ha verificado».
2. **La autenticación no sustituye al AML.** Que sea demostrablemente él no dice nada sobre si está
   en una lista. Son controles de preguntas distintas y ninguno cubre al otro.
3. **Una disposición tiene dos papeles, y en un producto de distribuidor nunca son la misma
   persona.** Toda disposición tiene beneficiario; lo que cambia con el producto es quién es:

   | Producto | Responsable de la línea | Quien recibe el dinero |
   |---|---|---|
   | Uso propio · nómina | el titular | **el mismo titular** — no falta beneficiario, es él |
   | Línea de distribuidor | la distribuidora | **la beneficiaria — nunca la distribuidora** |

   El AML corre sobre **los dos**: sobre el receptor porque es a quien se le paga, y sobre el
   responsable porque no tiene sentido mover dinero de la línea de alguien en listas aunque el
   destino sea otro. En uso propio es una sola comprobación porque es una sola persona.

   La decisión comercial sigue siendo del distribuidor; la compuerta sólo decide si *puede* decidir.

---

## 5 · Qué está construido y qué falta

> Revisado contra el código el **2026-10-11**. La tabla anterior listaba como pendiente cosa que ya
> estaba hecha, que es la forma más fácil de que un pendiente real se pierda entre los falsos.

### Hecho

| # | Qué | Dónde |
|---|---|---|
| ✅ | Compuerta al **originar**: identidad `VERIFIED` **y** listas `CLEARED` | `CreditApplicationService.start` → `party.isVerificationClear()` |
| ✅ | **Proyección del veredicto** y compuerta de **AML en la disposición** | `credit-portfolio` · `PartyVerification.allowsDisposition()`, `validateAml(...)` |
| ✅ | **Re-cruce al activar una lista** + republicación de los que cambiaron | `compliance` · `RescreenService` |
| ✅ | El veredicto viaja con **sus versiones de lista**, para detectar caducidad | `person_verifications.screened_list_versions` |
| ✅ | Veredicto por persona con identidad y listas **separadas**, tres estados y discriminadores | `compliance` · `PersonVerification`, `ScreeningService` |
| ✅ | **Las dos colas tienen salida**: adjudicar listas *y* dictaminar identidad, con autor y argumento | `POST /verifications/{id}/adjudicate` · `POST /verifications/{id}/identity` |
| ✅ | **Evaluación bajo demanda** para la cartera que existía antes del control | `POST /verifications` + `scripts/remedia-verificaciones.py` |
| ✅ | Compuerta de **colocación**: identidad verificada **y** listas despejadas | `beneficiary` · `Placement.approve(riskAck, screening)` |
| ✅ | Listas de gobierno cargadas, **versionadas** y con detalle por actualización | `compliance` · `WatchlistLoadService` |
| ✅ | Una coincidencia confirmada **deja constancia**: abre un `UIFReport` con el argumento de quien adjudicó | `audit` · `ComplianceEventListener` |
| ✅ | **Bitácora append-only de cada consulta**: fecha, resultado de ese momento, versiones de lista y datos consultados — al alta, en cada re-cruce y bajo demanda | `compliance.screening_checks` · `ScreeningJournal` |

### Falta

| # | Qué | Dónde | Tamaño |
|---|---|---|---|
| 1 | **El OTP deja constancia**: persistir `otpVerificationId` con teléfono, momento y para qué se emitió | `channel-mobile` | S |
| 2 | **La disposición exige NIP + OTP** y los transporta | `channel-mobile` → `wallet` → `credit-portfolio` | M |
| 3 | `ProcessDispositionCommand` gana la evidencia de autenticación, y se **exige**: sin ella se rechaza | `credit-portfolio` | S |
| 4 | **Adaptador de Incode/Jumio.** Sin él toda identidad va a revisión manual — es el modo vigente y está asumido, pero significa que el 100 % del volumen pasa por un analista | `compliance` | M |
| 5 | **Presentar** el reporte UIF ante la autoridad. El expediente ya se abre al confirmar —nace `PENDING`— pero el envío no existe | `audit` | M |

> Los tres primeros son **la misma pieza**: hoy `POST /credit/dispose` sólo lleva `X-User-Id`, así
> que la disposición comprueba listas de los dos papeles y **no comprueba que quien la autoriza sea
> quien dice ser**. Quien tiene que autenticarse es el **responsable de la línea** —el titular en uso
> propio, la distribuidora en una colocación—, porque es quien autoriza el movimiento; la beneficiaria
> recibe el dinero y no autoriza nada, así que no hay nada que pedirle. El AML está puesto; la
> autenticación, no.

---

## 6 · Las decisiones

### Decididas

| # | Decisión | Lo elegido |
|---|---|---|
| **A** | **La ventana del re-cruce** | **(b) Los veredictos siguen válidos mientras se re-cruza.** Nada se detiene y existe la ventana: entre que una lista se activa y que el re-cruce termina, alguien recién publicado podría disponer. **El riesgo se asume a propósito** y se mitiga corriendo el re-cruce en ventana de mantenimiento. Está implementado así en `RescreenService`, que deliberadamente **no** invalida veredictos |
| **PEP** | **¿Qué hace una persona políticamente expuesta?** | **Bloquea, igual que un sancionado.** Es decisión de negocio y va contra la práctica habitual —que es diligencia reforzada—; queda escrita en `ScreeningDecisionPolicy` con el contraargumento conservado, porque el día que alguien la revise va a necesitar saber que fue deliberada |
| **Distribuidor** | **¿Puede ser persona moral?** | **No por ahora: todos son personas físicas con actividad empresarial.** Por eso `prospectType=DISTRIBUTOR` se traduce a `PartyType.INDIVIDUAL`, y su condición de distribuidor es un **rol** (I-03), no un tipo de persona |
| **D** | **La cartera existente** (117 personas, 0 verificaciones) | **Queda bloqueada hasta verificarse** — el default `PENDING` de la migración no se toca. Lo que faltaba no era la decisión sino **la salida**: ahora existe `POST /verifications` y `scripts/remedia-verificaciones.py`, que cruza a la población contra las listas vigentes y **reporta el padrón** de quién espera dictamen. El script **no firma identidades**: eso lo hace un analista, y un script firmando por él sería fabricar la evidencia del control |

| **Rastro** | **¿Basta el veredicto vigente para auditar?** | **No, y por eso existe la bitácora.** El veredicto se sobrescribe en cada re-cruce, así que por sí solo no distingue «no lo sabíamos» de «lo dejamos pasar». `screening_checks` guarda cada consulta —**también las que no cambiaron nada**, porque lo que hay que demostrar es que se miró— con las versiones de lista contra las que corrió, que es lo que la hace reproducible |

### Todavía abiertas

| # | Decisión | Opciones |
|---|---|---|
| **B** | **Si el titular queda en listas, ¿qué pasa con su línea viva?** | ¿se bloquean sólo las disposiciones nuevas, o además se suspende la línea? Lo primero ya es la compuerta y está puesto; lo segundo es una decisión de cartera y no está |
| **C** | **¿El NIP se exige en toda disposición o sólo por encima de un monto?** | Exigirlo siempre es más seguro y más fricción; por umbral es lo habitual. **Moot hasta que exista la pieza 1-3 de §5**: hoy no se exige en ninguna |
| **E** | **La lista fiscal del SAT (69-B): ¿bloquea o pesa en el crédito?** | Quedó pendiente a propósito. Hoy `ScreeningDecisionPolicy` **no** la hace bloquear — se registra y no detiene— y eso está escrito en el código como decisión pendiente, no como olvido |

---

## Anexo · Por qué «NIP + OTP» es el control correcto aquí, y qué no cubre

**Lo que prueba:** que quien pide la disposición sabe algo que sólo **el responsable de la línea**
sabe (NIP) y tiene algo que sólo él tiene (el teléfono registrado). Dos factores de naturaleza
distinta, que es lo que hace que robar uno no baste.

**Sobre quién va:** el responsable de la línea, porque es quien autoriza el movimiento y quien
responde por la deuda — el titular en uso propio, la distribuidora en una colocación. **No** sobre
quien recibe el dinero: la beneficiaria de una colocación no autoriza nada, y pedirle un segundo
factor en ese momento sería pedirle que autorice algo que no es su decisión.

**Lo que no prueba:**

- **No prueba que no esté en una lista.** Por eso el AML corre aparte y en cada disposición.
- **No prueba que no esté coaccionado.** Ningún factor lo hace; eso se detecta por patrón de
  comportamiento, que es otro control y no está en este alcance.
- **No prueba nada si el OTP es un código fijo.** En ambientes con `otp-code-validation` apagada, la
  evidencia existe pero no demuestra nada — y conviene que eso esté dicho y no supuesto.
