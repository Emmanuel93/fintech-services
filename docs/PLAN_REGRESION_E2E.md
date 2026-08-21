# Plan de regresión E2E — app · backoffice · servicios

> Alcance: cerrar el flujo de punta a punta desde la app (`fintech-app`) hasta la consola
> (`fintech-backoffice-web`) pasando por los servicios (`fintech-services`), con **evidencia en
> video de cada caso, en las dos interfaces**. Foco: happy path.
>
> Fecha del análisis: 2026-08-19. Repos leídos en el estado de esa fecha.

---

## Parte 1 — El flujo automático / manual: qué encontré

### 1.1 Sí existe una bandera, pero está muerta

El catálogo de productos tiene la bandera que buscabas:

```sql
default_approval_flow VARCHAR(20) NOT NULL
  CHECK (default_approval_flow IN ('AUTOMATIC','MANUAL','COMMITTEE'))
```
`services/credit-product-service/src/main/resources/db/changelog/creditproduct/002-create-credit-product-definitions.sql:47,79-80`

Viaja hasta origination: `services/origination-service/.../application/CreditProductDefinition.java:35` la recibe
como `defaultApprovalFlow`. Y se pinta en la consola como *"flujo de aprobación"*
(`services/channel-backoffice-service/.../BackofficeViews.java:233`).

**Pero `CreditApplicationService` nunca la lee.** El `grep` de `defaultApprovalFlow` dentro de
`services/origination-service/src/main` devuelve exactamente dos cosas: la declaración del record y nada más.
Quien decide la ruta es esto y sólo esto
(`services/origination-service/.../application/service/CreditApplicationService.java:181-231`):

```java
switch (cmd.decision()) {                       // viene de scoring, por Kafka
  case "AUTO_APPROVED" -> app.approve(...);     // → approvalFlow = AUTOMATIC
  case "MANUAL_REVIEW" -> {
      boolean isCommittee = app.getRequestedAmount() != null
          && app.getRequestedAmount().compareTo(properties.getCommitteeAmountThreshold()) > 0;
      if (isCommittee) app.sendToCommitteeReview(...);   // → COMMITTEE
      else             app.sendToManualReview(...);      // → MANUAL
  }
  case "REJECTED"      -> app.reject(...);
}
```

`approvalFlow` no es una entrada del sistema: es una **etiqueta que se escribe después**, derivada
de la decisión del motor de scoring más el umbral de monto
(`fintech.origination.committee-amount-threshold`, $500,000 MXN,
`services/origination-service/src/main/resources/application.yml:102`).

### 1.2 Consecuencia práctica, con nombre y apellido

| Producto | `default_approval_flow` | Banda BAJO de su política | Qué pasa de verdad |
|---|---|---|---|
| `PL-IND-STD-V1` (Préstamo Personal) | AUTOMATIC | ≥ 400 | Auto-aprueba. Coincide por casualidad. |
| **`GL-IND-STD-V1` (Crédito Grupal)** | **MANUAL** | ≥ 400 | **Auto-aprueba.** El catálogo dice MANUAL y nadie lo revisa. |
| **`BRL-BUS-STD-V1` (Línea revolvente empresa)** | **MANUAL** | inalcanzable (100000) | Cae en manual — pero por la política de scoring, no por la bandera. |
| `DL-DIST-STD-V1` (Línea distribuidor) | COMMITTEE | inalcanzable | Cae en manual/comité por la política y por el monto, no por la bandera. |
| `SME-LOAN-STD-V1` | COMMITTEE | inalcanzable | Igual. |

Es decir: **hoy la bandera del producto no gobierna nada.** Que algunos productos "funcionen" es
coincidencia entre dos configuraciones que nadie mantiene juntas —
`credit_product.default_approval_flow` y `scoring.risk_thresholds`. El día que alguien suba la banda
BAJO de la línea empresarial, ese producto empezará a auto-aprobarse sin que nadie toque la bandera
que dice MANUAL.

`min_approval_score` del catálogo está en la misma situación: se muestra, no se consume.

### 1.3 Y si no hay bandera ni política, ¿va a manual?

**No.** Ésta es la parte que importa de tu pregunta. Cuando falta la configuración, la solicitud
**no cae a revisión humana: se queda muda.**

```java
Optional<ScoringPolicy> policyOpt = policyRepository.findActiveBy(productType);
if (policyOpt.isEmpty()) {
    log.warn("No active scoring policy for productType={} — skipping applicationId={}");
    return Optional.empty();          // ← no se publica ScoringCompleted
}
```
`services/scoring-service/.../application/service/ScoringEvaluationService.java:69-73`

Y lo mismo si el buró no llegó (`:59-66`), con el comentario ya escrito en el código:
*"Por ahora se omite — la aplicación queda PENDING_SCORING."*

No hay job de barrido, ni TTL, ni reintento para `PENDING_SCORING`
(el `grep` de `PENDING_SCORING` en todo `*/src/main` no encuentra ningún `@Scheduled`).
La solicitud queda en ese estado **indefinidamente**: el cliente ve "en revisión" en la app para
siempre y nadie la ve en la bandeja del backoffice, porque la bandeja filtra por
`UNDER_MANUAL_REVIEW` y `COMMITTEE_REVIEW`
(`services/origination-service/.../UnderwritingController.java:155`).

### 1.4 Recomendación (fuera del alcance de esta corrida, sin tocar código todavía)

1. Que `CreditApplicationService.apply` lea `defaultApprovalFlow` del catálogo y **degrade**:
   `AUTO_APPROVED` + producto `MANUAL` → `UNDER_MANUAL_REVIEW`. Nunca al revés.
2. Fallback explícito: sin política activa o sin buró tras N minutos → `UNDER_MANUAL_REVIEW`, no
   `PENDING_SCORING` eterno. "No sé decidir" es exactamente el caso para el que existe un analista.
3. Alerta operativa sobre `PENDING_SCORING` con antigüedad > 1 h.

Los casos de prueba de la Parte 3 **dejan evidencia grabada de (1) y (2)** sin cambiar código.

---

## Parte 2 — Cómo se corre y cómo se graba

### 2.1 El problema de coreografía

Un journey E2E real cruza tres procesos que no se pueden ejecutar dentro de uno solo:

- la **app** corre en el simulador de iOS (`flutter test integration_test/…`),
- la **consola** corre en Chromium (`npx playwright test`),
- el **reloj del backend** (devengo, corte, mora) sólo se mueve por endpoints internos
  `/internal/test-support/*` que **el gateway no enruta** — hay que entrar por la red de Docker.

La solución es un orquestador que parte cada caso en **actos**, y entre acto y acto mueve el mundo.
Cada acto es una invocación separada, y por eso cada acto tiene **su propio video**.

```
ACTO 1  app        alta → producto → solicitud → "Tu solicitud pasa a comité"       [video app]
        ↓  handoff: el test imprime PHONE / PASSWORD / APPLICATION_ID / PROSPECT_ID a stdout
ACTO 2  backoffice analista entra → bandeja "Por decidir" → abre ficha → APRUEBA    [video BO]
        ↓  orquestador: oferta/contrato/firma por API (hueco de producto, ver Parte 4)
        ↓  orquestador: avanza el reloj N quincenas (rewind + devengo día a día + corte)
ACTO 3  app        login → crédito activo → avance del plan → pago o mora           [video app]
ACTO 4  backoffice cartera (calendario, pagos, DPD) o cobranza (tramo, caso)        [video BO]
```

El handoff entre procesos es por **stdout**: los tests de Flutter imprimen líneas
`##HANDOFF## clave=valor` y el orquestador las lee con `grep`. Sin archivos compartidos ni base de
datos intermedia — el simulador está en su propio sandbox y no comparte disco con el host.

### 2.2 Cómo se fuerza la revisión manual (sin trampas y con trampa)

Hay dos maneras y el plan usa **las dos, a propósito**, porque prueban cosas distintas:

**(A) Camino natural — bajando la banda de la política.**
Con el mock de buró activo (`SCORING_CIRCULO_MOCK_ENABLED=true`, el default) sólo existen tres
scores posibles: 280 (CURP `XEXX…`), 430 y 630. La banda MEDIO de `PERSONAL_LOAN` es 300–399:
**inalcanzable**. Así que el orquestador sube temporalmente el `min_score` de la banda BAJO de la
política activa de `PERSONAL_LOAN` a un valor inalcanzable, corre el caso, y lo restaura al final.
El resultado es un `MANUAL_REVIEW` emitido por el motor real, indistinguible de producción.

```sql
UPDATE scoring.risk_thresholds t SET min_score = 1000000
  FROM scoring.scoring_policies p
 WHERE p.policy_id = t.policy_id AND p.active
   AND p.product_type_intent = 'PERSONAL_LOAN' AND t.risk_level = 'BAJO';
```

**(B) Camino determinista — `route-to-review`.**
`POST http://origination-service:8080/internal/test-support/applications/{id}/route-to-review?committee=false&riskLevel=MEDIO`
(`services/origination-service/.../TestSupportController.java:50-73`). Usa las transiciones reales del
dominio. Sirve de red de seguridad si (A) no aplica y para el caso de distribuidora.

> El javadoc de ese controller dice *"score entre 100 y 199"*: quedó desactualizado con
> `012-rescale-risk-bands.sql`. Hoy MEDIO es 300–399.

### 2.3 El reloj: quincenas, devengo y mora

- **Quincena = `BIWEEKLY`.** No existe `QUINCENAL` ni `SEMI_MONTHLY`.
  `DL-DIST-STD-V1` es BIWEEKLY, 6–48 quincenas, default 24
  (`creditproduct/016-distributor-line-biweekly.sql`).
  *(Nota heredada, no de esta corrida: `BIWEEKLY` avanza 14 días → 26 períodos/año, mientras que el
  cálculo de la cuota divide entre 24. El propio changeset lo documenta sin resolver.)*
- **El devengo es idempotente por día.** Llamar `run-daily-accrual` treinta veces produce **un** día
  de interés. Para correr el reloj hay que `rewind-accrual-schedules?date=<inicio>` y luego
  `run-daily-accrual?date=<d>` día por día. El proxy del BFF **no** acepta `?date=`, así que esto va
  por la red de Docker contra `charges-service:8080`.
- **La mora es un entero, no un estado.** No existe `CreditAccountStatus.DELINQUENT`. Vive en
  `credit_accounts.days_delinquent`, y **sólo la escribe** `run-delinquency-job`. Mover el
  vencimiento sin correr ese job deja la cuota vencida y la app mostrándola al corriente.
  Secuencia obligatoria: `shift-due-date` (o `age-schedule` para revolventes) **y después**
  `run-delinquency-job`.
- **Liquidar** no tiene atajo: se paga el `totalDebt` real y `CreditAccount.settleIfClear()` mueve a
  `SETTLED` al consumir `payment-applied`.

### 2.4 Evidencia

| Capa | Cómo se graba | Dónde queda |
|---|---|---|
| App (simulador iOS) | `xcrun simctl io booted recordVideo --codec h264 --force` alrededor de cada acto | `.evidencia/<caso>/app-actoN.mp4` |
| Backoffice (Chromium) | Playwright con `video:'on'`, `trace:'on'`, `screenshot:'on'` (ya configurado en `playwright.config.ts:29-31`) | `.evidencia/<caso>/bo-actoN/` |
| Servicios | log JSON de cada llamada + snapshot del estado de la solicitud/cuenta en cada acto | `.evidencia/<caso>/api.log`, `estado-*.json` |
| Resumen | `reporte.py` arma un HTML con la línea de tiempo, los videos embebidos y el resultado por aserción | `.evidencia/reporte.html` |

Los tests de app ya están escritos para que el video se pueda ver: `framePolicy = fullyLive` y una
pausa deliberada (`pump(2–5 s)`) en cada checkpoint.

---

## Parte 3 — Los casos

Nomenclatura: **A** = app, **B** = backoffice, **S** = servicios.

### CASO 1 — Uso propio · revisión manual · aprobado · liquidado

**Objetivo.** Un cliente nuevo se da de alta desde la app, pide un préstamo personal, cae en revisión
manual, un analista lo aprueba desde la consola, el crédito se activa, devenga varias quincenas, se
paga y queda liquidado.

**Precondición.** Banda BAJO de `PERSONAL_LOAN` elevada (2.2-A). Restaurada al terminar.

| # | Capa | Paso | Aserción |
|---|---|---|---|
| 1.1 | A | Splash → Welcome → propósito *Para mí* → celular + correo → OTP `123456` | Llega a captura de identidad |
| 1.2 | A | Captura los 4 documentos → "Leer mis documentos" | Aparece `EXTRAÍDO`; pestaña `IDENTIDAD` |
| 1.3 | A | Datos personales y domicilio (CURP única) → "Todo está correcto" | — |
| 1.4 | A | Contraseña → "Crear cuenta" | Aterriza en `Tus oportunidades` |
| 1.5 | A | Elige *Préstamo Personal* → configurador → "Revisar solicitud" | Solicitud creada |
| 1.6 | A | Espera resolución | Home muestra **`Tu solicitud está en revisión`** (no oferta) |
| 1.7 | S | `GET /origination/applications/{id}` | `status = UNDER_MANUAL_REVIEW`, `approvalFlow = MANUAL`, `riskLevel = MEDIO` |
| 1.8 | B | `underwriter@kredius.mx` entra → `/solicitudes` → botón **Por decidir** | La solicitud aparece en la tabla con estatus *Revisión manual* |
| 1.9 | B | Abre la ficha | Ve `Evaluación`, `Capturado por el solicitante`, `Buró de crédito`; el bloque `Decisión` está habilitado |
| 1.10 | B | Verifica el gate de rol: `analista@` ve la ficha pero **no** puede decidir | Texto *"Tu rol puede analizar esta solicitud, pero no decidirla."* |
| 1.11 | B | Verifica que **Rechazar** está deshabilitado sin motivo | `disabled` |
| 1.12 | B | Pulsa **Aprobar** | Modal cierra; la fila sale de "Por decidir"; estatus → *Aprobada* |
| 1.13 | S | Oferta → aceptar → contrato → firmar **por API** (ver 4.1 — la app no retoma la oferta tras una aprobación manual) | `status = CONTRACT_SIGNED` → cuenta en cartera |
| 1.14 | A | Re-login → home | Tarjeta de crédito activa con el monto; ya no dice "en revisión" |
| 1.15 | S | Reloj: rewind + devengo día a día de **4 quincenas** + corte | `accruedInterestBalance > 0`; cuotas vencidas generadas |
| 1.16 | A | Refresca el home | Avance del plan se movió (`PAGO n DE m`); saldo cambió |
| 1.17 | B | `/cartera` → busca la cuenta → tab `Calendario` y `Pagos` | Calendario con filas; `Seguimiento del plan` visible |
| 1.18 | A | Paga el `totalDebt` completo | `status = SETTLED` |
| 1.19 | A | Notificaciones | Aviso de crédito liquidado |
| 1.20 | B | `/cartera` de nuevo | La cuenta ya no suma a capital colocado / aparece liquidada |

**Evidencia:** `app-acto1.mp4` (1.1–1.6), `bo-acto2` (1.8–1.12), `app-acto4.mp4` (1.14–1.19),
`bo-acto5` (1.17, 1.20).

---

### CASO 2 — Distribuidora · revisión manual · aprobado · colocación liquidada

**Objetivo.** Una distribuidora se da de alta desde la app, su línea cae a revisión, se aprueba desde
la consola, coloca un préstamo a una beneficiaria, la colocación devenga quincenas y se liquida.

**Precondición.** Ninguna: `DL-DIST-STD-V1` ya tiene banda BAJO inalcanzable → cae en manual por el
camino natural. La beneficiaria se verifica con la simulación de KYC
(`BENEFICIARY_KYC_SIMULATION_ENABLED=true`).

| # | Capa | Paso | Aserción |
|---|---|---|---|
| 2.1 | A | Alta con propósito ***Para colocar a terceros*** | Aterriza en `Inicio`; el bottom nav **no** tiene *Oportunidades* |
| 2.1b | S | La solicitud de línea se crea **por el BFF** (ver 4.2 — la app no tiene camino para pedirla) | `DISTRIBUTOR_LINE` creada, `PENDING_SCORING` |
| 2.2 | A | Home | **`Tu solicitud está en revisión`** con el copy de comité |
| 2.3 | S | Estado de la solicitud | `UNDER_MANUAL_REVIEW` o `COMMITTEE_REVIEW`, `productType = DISTRIBUTOR_LINE` |
| 2.4 | B | Bandeja → filtro producto *Línea de distribuidor* → abre ficha → **Aprobar** | Estatus → *Aprobada* |
| 2.5 | S | Oferta → contrato → firma de la línea (por API) | Cuenta `DISTRIBUTOR_LINE` `ACTIVE` con `available_credit` |
| 2.6 | A | Re-login → home | **`MI LÍNEA DE DISTRIBUIDOR`** con disponible; desaparece "en revisión" |
| 2.7 | A | `Colocaciones` → `Enviar préstamo` → datos de la beneficiaria → `Continuar` → monto/plazo → `Enviarle la liga por WhatsApp` | `Le mandamos la liga`; colocación creada |
| 2.8 | S | KYC de la beneficiaria simulado → disposición | Disposición con calendario `BIWEEKLY` |
| 2.9 | A | `Ver mis colocaciones` | La colocación aparece con su monto |
| 2.10 | B | `/cartera` → cuenta de la línea → tab `Disposiciones` | La disposición se ve con su calendario |
| 2.11 | S | Reloj: **6 quincenas** devengadas + corte | Interés acumulado > 0 |
| 2.12 | A | Colocaciones | Avance del plan movido |
| 2.13 | S | Pago del adeudo total de la disposición | Disposición saldada |
| 2.14 | A/B | App y consola | Colocación liquidada en ambas |

---

### CASO 3 — Distribuidora · revisión manual · aprobado · colocación en mora

Idéntico a CASO 2 hasta 2.10. A partir de ahí:

| # | Capa | Paso | Aserción |
|---|---|---|---|
| 3.11 | S | `age-schedule?daysAgo=95` sobre la disposición **y después** `run-delinquency-job` | `days_delinquent ≈ 95` |
| 3.12 | S | Devengo de las quincenas vencidas | Interés acumulado > 0 |
| 3.13 | A | Home / colocaciones | Marca de atraso visible (`EN MORA` / `venció el`) |
| 3.14 | A | Notificaciones | Aviso de pago vencido |
| 3.15 | B | `/cartera` | Columna `DPD` en rojo; `Con algún atraso` incrementó; tramo `61 a 90` / `91 a 120` |
| 3.16 | B | `/cobranza` → bandeja | El caso aparece con su tramo, deuda y "qué toca" |
| 3.17 | B | Abre el caso → tab `Resumen` | `{estado} · {tramo} · {n} días` coherente con el DPD |

> Nota honesta: `run-moratorium-accrual` existe pero hoy **no devenga nada**, porque
> `AccrualSchedule.activateMoratorium(...)` no se invoca desde ningún punto del código de producción
> (`services/charges-service/.../domain/AccrualSchedule.java:88`; único llamador: un test unitario). El caso 3
> comprueba la **mora contable** (DPD, tramos, caso de cobranza), no el **interés moratorio**, y así
> queda anotado en el reporte para no vender cobertura que no existe.

---

## Parte 4 — Huecos de producto que la corrida documenta

Éstos no son limitaciones de la prueba: son cosas que hoy no existen en el producto y que el
E2E deja escritas en el reporte como avisos, no como verdes. Los dos primeros aparecieron
justamente al intentar cerrar el journey.

1. **Tras una aprobación manual, la app no retoma la oferta.**
   `CreditApplicationProvider._poll()` se detiene en cuanto la solicitud entra en revisión
   (`fa_credit/.../providers/credit_application_provider.dart:228-236`: `else if (!updated.isPending) _stopPolling()`),
   y el home oculta la tarjeta que lleva al catálogo mientras `isPendingReview` es cierto
   (`fa_credit/lib/credit_module.dart:279`). Resultado: el cliente recibe la aprobación y **no
   tiene por dónde continuar**. El orquestador formaliza por API (oferta → contrato → firma), el
   mismo camino de `03-journey.sh` y `seed-distribuidoras.py`.
2. **Una distribuidora recién dada de alta no puede solicitar su línea desde la app.**
   El alta no crea la solicitud (`post_analysis_screen.dart:68-80` sólo precalifica y rutea), su
   bottom nav no incluye *Oportunidades*, y el home oculta la puerta al catálogo. La solicitud se
   crea por el BFF, con su propio token.
3. **La aprobación sí se hace desde la UI del backoffice** — es el punto del ejercicio y no se
   atajó en ningún caso.
4. **La app corre en el simulador de iOS**, así que la corrida completa sólo se puede lanzar desde la
   Mac. No hay camino headless para esta parte.
5. `fixtures.ts` del backoffice está desincronizado con `nav.ts` (espera `Dashboard`, `Cartera`,
   `Clientes y distribuidores`; hoy son `Panorama`, `Cuentas de crédito`, `Clientes`,
   `Distribuidores`). Los specs nuevos leen `.bo-nav-subitem` en vez de `.bo-nav-item`.
6. El interés moratorio no se genera (ver nota del CASO 3).
7. Cambiar la banda de scoring toca datos de la base local; el orquestador la restaura en un `trap`,
   pero si lo matas con `kill -9` hay que restaurarla a mano (el valor original queda escrito en
   `.evidencia/<corrida>/scoring-threshold.bak`).


---

## Parte 5 — Qué se entrega

| Ruta | Qué es |
|---|---|
| `fintech-services/docs/PLAN_REGRESION_E2E.md` | este documento |
| `fintech-services/tools/regresion-e2e/run.sh` | orquestador de un comando |
| `fintech-services/tools/regresion-e2e/lib.sh` | canales, reloj, grabación y aserciones |
| `fintech-services/tools/regresion-e2e/reporte.py` | reporte HTML con los videos embebidos |
| `fintech-services/tools/regresion-e2e/README.md` | cómo correrlo y qué toca de la base |
| `fintech-app/…/integration_test/regresion/c1_solicitud_test.dart` | CASO 1 · acto 1 |
| `fintech-app/…/integration_test/regresion/c1_seguimiento_test.dart` | CASO 1 · acto 3 |
| `fintech-app/…/integration_test/regresion/c2_solicitud_test.dart` | CASOS 2 y 3 · acto 1 |
| `fintech-app/…/integration_test/regresion/c2_colocacion_test.dart` | CASOS 2 y 3 · acto 3 |
| `fintech-app/…/integration_test/regresion/c2_seguimiento_test.dart` | CASOS 2 y 3 · acto 4 |
| `fintech-app/…/integration_test/regresion/{soporte,gestos}.dart` | handoff por stdout y gestos tolerantes |
| `fintech-backoffice-web/tools/e2e/browser/regresion-decision.spec.ts` | bandeja, gate de rol y decisión |
| `fintech-backoffice-web/tools/e2e/browser/regresion-cartera.spec.ts` | cuenta, calendario, pagos, avance |
| `fintech-backoffice-web/tools/e2e/browser/regresion-cobranza.spec.ts` | DPD, tramos y caso de cobranza |

Correr todo:

```bash
cd fintech-services/tools/regresion-e2e && ./run.sh
```

### Calibración de la primera corrida

Los selectores de la app se tomaron del código, no de una ejecución: los tests nuevos imprimen los
textos visibles (`volcarPantalla()`) justo antes de cada `expect` que puede fallar, para que el
primer rojo diga en qué pantalla se quedó en vez de sólo que no encontró algo. Espera una ronda de
ajuste de etiquetas en el primer paso por pantallas que ningún E2E recorría —el catálogo con la
sección «PUEDES SOLICITARLAS» y el estado «Tu solicitud pasa a comité»—.
