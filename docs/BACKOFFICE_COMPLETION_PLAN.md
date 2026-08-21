# Plan de completitud del backoffice — backend (fintech-services + BFF)

> Objetivo: conectar **fintech-backoffice-web** al 100% en modo *live*. Este documento es el
> contrato de backend que el front (Prompt 3) consume. Verificado contra el código el 2026-08-10.

> **DECISIÓN 2026-08-10 (supersede el ADR de más abajo en un punto):** se adopta el plan de 6
> entregables **E1–E6** (Solicitudes+buró · Auditoría total · **sales-org-service** · Permisos
> configurables · Dashboard con alcance · PENDING_DOCUMENTS). **`sales-org-service` SÍ se construye**
> como jerarquía interna con **niveles CONFIGURABLES** (hoy nacional→región→zona→sucursal; se pueden
> insertar posiciones intermedias sin migrar código — es la base de la **matriz de escalado
> operativo**). Los **distribuidores B2B2C** (rol en party, mi Slice 2/5) van **al FINAL** y se revisa
> su alcance entonces. E4 (permisos): se toma el mecanismo de caché por evento pero la matriz queda
> **solo-lectura / con maker-checker** — el hot-edit sin cuatro ojos NO entra sin decisión explícita.
> Orden de ejecución: E1 → E6 → E2 → E3 → E5 → (E4) → distribuidores.

## Invariante de composición (el guardrail que no se rompe)

Todo pasa por el BFF (`channel-backoffice-service`): web → gateway → BFF → servicios. El BFF compone;
el navegador nunca llama a un dominio. La composición tiene **tres patrones** — usar el correcto es
lo que evita el N+1:

- **(A) Detalle de UNA entidad → fan-out del BFF, OK.** Mesa de análisis (1 solicitud), expediente
  (1 cliente), encabezado del 360 (1 distribuidor). Acotado por definición.
- **(B) Una LISTA dentro de un detalle → consulta paginada/indexada en el servicio DUEÑO + `/batch`
  para enriquecer. Nunca un loop.** Ej.: cartera colocada del distribuidor = `commission`
  (indexado por beneficiario) + `credit-portfolio /accounts/batch`.
- **(C) Agregado que cruza TODAS las filas → ni loop en el BFF ni join cross-DB → denormalización
  dirigida por evento en el servicio dueño.** Ej.: `byExecutive` = evento `party.executive-assigned`
  → columna en la cuenta de `credit-portfolio` → agregado in-DB.

**Decisión de arquitectura:** con esto **no se requiere ningún microservicio nuevo** (ver ADR al
final). Un servicio de analítica/read-model queda diferido, con disparadores.

## Estado actual (HECHO — rama `feature/backoffice-query-mode`)

Commits: `4a5591c` (capa de salida + .gitignore) · `ec3dfc2` Slice 1 (modo consulta) ·
`2318944` origination BFF + shapes de clientes · `9e054fa` Products BFF · `9bcb405` asignación de
ejecutivo. Endpoints del BFF ya en vivo:

```
GET  /dashboard/summary                         GET  /dashboard/stats?groupBy=status|productType|dpdBucket
GET  /portfolio  (+status,productType,q,minDaysDelinquent,maxDaysDelinquent,executiveId,sort)
GET  /portfolio/{id}   (schedule + disposiciones[type] + provisión)
GET  /clients   (+q,type,status,executiveId,sort)     GET /clients/{id}  (cliente + accounts + applications)
POST /clients/{id}/assign-executive             GET  /executives
GET/POST /products, PUT /products/{id}/activate|retire
GET  /origination/applications?status            POST /origination/applications/{id}/decision
GET/POST /staff, PUT /staff/{id}/roles|password|suspend|reactivate, DELETE /staff/{id}
```

## Lo que falta (D1–D8) y a qué MFE desbloquea

| Dep | Ruta (BFF) | Dueño(s) | Desbloquea |
|---|---|---|---|
| D1 | `GET /origination/applications/{id}` (proyección) | origination + scoring (+party/portfolio/audit) | mfe-originations (detalle) |
| D2 | `GET /origination/applications` + productType/targetAudience/from/to/q | origination | mfe-originations (eje audiencia) |
| D3 | `beneficiaryPartyId` en solicitud + filtro | origination + party (códigos) | mfe-originations (eje origen comercial) |
| D4 | filtro `dispositionType` (SELF_USE/THIRD_PARTY) | credit-portfolio (capability de producto) | mfe-originations / cartera (eje destino) |
| D5 | `GET /clients/{id}/relationships` | party (tabla ya existe) | mfe-clients (vínculos) |
| D6 | `GET /clients/{id}/documents` (proyección) | audit T3 | mfe-clients (documentos) |
| D7 | `GET /distributors`, `GET /distributors/{id}` (360) | party rol + commission + portfolio | mfe-distributors |
| D8 | `GET` matriz efectiva rol×módulo (solo lectura) | BFF SecurityConfig (+configuration futuro) | mfe-config (auditoría) |
| **D9** | `byExecutive` + `pipeline` del dashboard | credit-portfolio (denorm. evento) + origination | mfe-dashboard (cortes) |

---

## PLAN POR SLICES (orden, dependencias, qué desbloquea)

```
Slice 1 (mesa de análisis: D1+D2) ── independiente ───────────────► mfe-originations (detalle+bandeja)
Slice 2 (red comercial base) ─┬─► Slice 3 (promoterCode + 3 ejes: D3/D4) ─► mfe-originations (filtros)
                              ├─► Slice 4 (expediente: D5+D6) ────────────► mfe-clients
                              └─► Slice 5 (distribuidores 360: D7) ────────► mfe-distributors
Slice 6 (dashboard byExecutive/pipeline: D9) ── casi independiente ───────► mfe-dashboard
Slice 7 (matriz permisos: D8) ── independiente ───────────────────────────► mfe-config
```

**Orden recomendado:** 1 → 2 → 3 → (4, 5, 6, 7 en paralelo). Slice 1 primero: es la mesa de análisis
(lo prioritario) y no depende de la red comercial. Slice 2 es la base de 3/4/5.

### Slice 1 — Mesa de análisis (D1 + D2)
- **origination (dominio):** `GET /api/v1/origination/applications/{id}` YA existe (getById). **NUEVO
  (verificado 2026-08-10): NO existe `GET /api/v1/origination/prospects/{prospectId}`** — el
  `ProspectController` solo tiene POST. Agregarlo (find + DTO de lectura con lo capturado: datos
  personales/negocio, contacto, domicilio, documentos declarados) para poder mostrar "lo que capturó
  el prospecto".
- **scoring (dominio):** la evaluación YA se expone en `GET /api/v1/scoring/evaluations/{prospectId}/latest`
  (por prospectId, con `ruleDetails`/factores, decision, riskLevel). No hay que crear endpoint.
  *OJO:* `scoring-service:compileTestJava` ya está roto desde antes — no mezclar.
- **BFF:** `ScoringClient` nuevo (bean `scoringWebClient` ya existe); `OriginationClient.getById` +
  `getProspect`; `OriginationController` gana `GET /origination/applications/{id}` que **proyecta**
  (patrón A, fan-out de UNA entidad): solicitud + prospecto capturado + evaluación de score + créditos
  vigentes del sujeto (`credit-portfolio /accounts/batch`) + documentos (stub hasta Slice 4). Extender
  `queue` para pasar productType/targetAudience/from/to/q (eje audiencia).
- Pruebas: unit del mapeo de proyección; contar llamadas (1 por sub-recurso, no por fila).

### Slice 2 — Red comercial base (Task 2)
- **party:** migración `party.party_roles(party_id, role, active, since)` con role IN
  (DISTRIBUTOR, GUARANTOR, BENEFICIARY) — **NO tocar `PartyType`** (I-01). Entidad + repo + servicio +
  `GET/POST/DELETE /parties/{id}/roles` + filtro `role=` en `party.search`. `GET /parties/{id}/relationships`
  sobre la tabla/entidad/repo que **ya existen**.
- **origination:** OA-02/AI-03 — originar `DISTRIBUTOR_LINE` exige rol DISTRIBUTOR → rechazo inmediato.
- Pruebas: rol duplicado, originación sin rol → rechazo, relationships de un party.

### Slice 3 — promoterCode + los 3 ejes (Task 3 + D3/D4)
- **party:** sembrar 2–3 distribuidores con rol DISTRIBUTOR y código legible (DIST-0001…);
  `GET /parties/by-promoter-code/{code}`.
- **origination:** `CreditApplication.beneficiaryPartyId`; resolver el código UNA vez al crear y
  guardarlo; **código que no resuelve RECHAZA** (CM-07). Exponer `beneficiaryPartyId` + filtro
  (directa = null / distribuidor = presente).
- **destino disposición (D4):** filtro SELF_USE/THIRD_PARTY_CREDIT por *capability* del producto
  (limpio, a nivel cuenta) — no por presencia de disposiciones (caro).
- **BFF:** cablear los 3 ejes en la bandeja.
- **Doc:** créditos ya activos con código no resuelto → barrido aparte (documentar en el tracker).

### Slice 4 — Expediente: vínculos + documentos (D5 + D6)
- **BFF:** `/clients/{id}` (o sub-rutas) suma vínculos (party, Slice 2) y documentos
  (**audit T3** — `DocumentFileRef`). **Proyección por rol:** `COLABORADOR_EMPRESARIAL` (con
  `distributorPartyId`) no recibe CURP/RFC completos, KYC, buró ni otros créditos — el dato no sale del
  dominio, no se oculta en el front.
- **audit:** exponer documentos por party/prospecto si no hay ruta.

### Slice 5 — Distribuidores 360 (Task 5 + D7)
- **commission:** `findByBeneficiaryPartyId` en `CreditPromoterAssignmentRepository` (hoy solo
  `findById(creditAccountId)`) + `GET /commissions/beneficiaries/{partyId}/credits`. Índice
  `idx_commission_records_beneficiary_period` **ya existe**.
- **BFF:** `GET /distributors` (parties con rol DISTRIBUTOR) + `GET /distributors/{id}` 360 =
  encabezado (party) + su línea (portfolio por obligor) + **cartera colocada = `commission` por
  beneficiario (paginado) + `credit-portfolio /accounts/batch` para el estado de pago** (patrón B, no
  loop; **no** leer `dispositions` por beneficiario: no hay query ni índice) + comisión contra pago.

### Slice 6 — Dashboard completo: byExecutive + pipeline (D9) *(delta del double-check)*
- **party:** emitir `party.executive-assigned` en `assignExecutive`.
- **credit-portfolio:** consumir ese evento (ya tiene 5 listeners) → columna `assigned_executive_id`
  en la cuenta (migración) + `GET /accounts/stats?groupBy=executive` **in-DB** (como `/product-mix`).
- **origination:** agregado in-DB de solicitudes por estatus → `pipeline`.
- **BFF:** `/dashboard/summary` llena `byExecutive` y `pipeline` desde esos agregados (sin loop).

### Slice 7 — Matriz de permisos, solo lectura (D8)
- **BFF:** `GET` que expone la matriz efectiva rol × módulo derivada de `SecurityConfig`. **No
  editable en caliente** (política). Editable → `configuration-service` con maker-checker + piso en
  código; fase siguiente, no improvisar.

---

## ADR — no se crea microservicio nuevo

- **Cada pendiente tiene dueño** en un dominio existente (ver tabla D1–D9). Ningún requisito queda
  huérfano.
- **El distribuidor es un party con rol** (I-01/I-03): su identidad/KYC/AML viven en `party`. Sacarlo
  a un servicio fragmentaría el agregado Party. Su atribución de comisión es de `commission`; su línea
  y disposiciones, de `credit-portfolio`. El 360 se **compone** en el BFF (patrones A+B).
- **`byExecutive`** se resuelve con **denormalización por evento** dentro de `credit-portfolio` (que ya
  consume eventos), no con un servicio nuevo ni un loop.
- **Costo operativo:** 19 servicios ya hacen OOM en la VM de Docker de 7.65 GB; un servicio 20 no
  aporta capacidad y empeora eso.
- **Candidatos DIFERIDOS (con disparador):** (1) `commercial-network-service` — solo si el distribuidor
  gana onboarding/jerarquía/territorios/términos versionados propios (referiría `partyId`, nunca
  duplicaría identidad); (2) `analytics/read-model-service` — solo si los agregados analíticos se
  multiplican más allá de `byExecutive`/`pipeline`.

## Comercialización por módulo (MFE ↔ servicio dueño)

Cada MFE se vende con su(s) servicio(s); el BFF (un controller por módulo) es infraestructura común.

| MFE | Dueño(s) | Composición |
|---|---|---|
| mfe-products | credit-product | 1:1 |
| mfe-portfolio | credit-portfolio (+risk) | byExecutive in-DB (denormalizado, Slice 6) |
| mfe-originations | origination (+scoring) | detalle de 1 entidad (Slice 1/3) |
| mfe-clients | party (+audit docs) | expediente de 1 entidad (Slice 4) |
| mfe-distributors | commission (+party rol, +portfolio /batch) | detalle 1 entidad + lista paginada+batch (Slice 5) |
| mfe-dashboard | agregados de portfolio/origination | in-DB, sin loop (Slice 6) |
| mfe-config | identity (+configuration futuro) | matriz solo lectura (Slice 7) |

## Verificación (por cada servicio tocado, secuencial)

```
# Build (macOS: ./gradlew falla por readlink -e)
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :<svc>:compileTestJava
# ITs Testcontainers (evitar cuelgue por docker.host obsoleto)
DOCKER_HOST=unix:///Users/<user>/.docker/run/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :<svc>:cleanTest :<svc>:test
```

Gotchas verificados: `(:param IS NULL OR …)` revienta con parámetros **temporales** en Postgres
(pásalos con límites por defecto); `(:ids IS NULL OR x IN :ids)` sí funciona para enums/UUID/colecciones
(normaliza vacío→null en el adapter); `scoring-service:compileTestJava` roto de antes (no mezclar);
cada endpoint nuevo: probar con/sin filtros, paginación real y **contar llamadas del BFF** (invariante).

Suspensión de empleado: el gateway valida la firma localmente y no consulta revocación → el access
token vive hasta 15 min; login/refresh sí se bloquean ya. La UI no debe decir "revocado" al instante.

## Cross-repo
El front consume estas rutas (D1–D9) desde `../fintech-backoffice-web` (Prompt 3). En cuanto existan,
generar `@bo/shared-types` desde el OpenAPI del BFF. Rutas de dominio: `docs/dominios/00_party_domain.md`
(I-01/I-03), `03_credit_origination_domain.md` (OA-02/AI-03, beneficiaryPartyId),
`01_channels_domain.md` (CH-02), `T6_commission.md` (comisión contra pago).
```

---

## ESTADO E-SERIES — ENTREGADO (rama `feature/backoffice-query-mode`, 2026-08-11)

Los seis entregables E1–E6 están **hechos y verificados** (pruebas funcionales + de rendimiento;
ITs Testcontainers donde aplica). Distribuidores B2B2C (Slice 5) queda al final, pendiente de
definición de alcance.

| Entregable | Commit | Servicios | Qué entregó |
|---|---|---|---|
| **E1** Mesa de análisis | `7f8153d` | origination, BFF | `GET /origination/prospects/{id}` (capturado) + BFF `GET /origination/applications/{id}` = proyección {solicitud, capturado, evaluación score+ruleDetails, créditos vigentes}; bandeja con 6 filtros. Patrón A (1 llamada/fuente, degradación por bloque). |
| **E1b** Buró expuesto | `aa1cff6` | scoring, BFF | `GET /scoring/reports/by-prospect/{id}`; BFF lo añade a la ficha **solo a roles analista** (ADMIN/CREDIT_ANALYST/UNDERWRITER/COMMITTEE/RISK_ANALYST/AUDITOR). |
| **E3** sales-org (NUEVO) | `12d2967`·`129f8b4`·`6868bb7` | **sales-org (8100)**, BFF | Jerarquía con niveles **configurables** (LTREE) + asignaciones append-only + alcance por subárbol + BFF/gateway. Ver `docs/dominios/10_sales_org_domain.md`. |
| **E6** PENDING_DOCUMENTS | `734834c` | origination, BFF | `requestDocuments`/`documents-received` + evento `origination.documents-requested` + TTL (`DocumentsExpirationJob`). |
| **E2** Auditoría total | `94f0c17` | audit, BFF | `AuditEntry.actor` (mig 006) + `PayloadSanitizer` (redacta secretos) + listener `identity.login-attempted` + filtro por actor + `/audit` en BFF. |
| **E5** Dashboard con alcance | `d613ac4` | BFF | `GET /dashboard/commercial?unitId=` (403 fuera de subárbol vía sales-org) + se retira `byExecutive` del summary. |
| **E4** Permisos read-only | `2260fb2` | BFF | `PermissionsService` (matriz capacidad→roles, cacheada) + `/permissions/me` + `/permissions/matrix`. Hot-edit **diferido**. |

## DIFERIDOS — contexto de ingeniería (para retomar)

Cuatro piezas se dejaron fuera **a propósito** (escala/gobernanza, no deuda urgente). Cada una está
anotada en el commit correspondiente y aquí con el detalle para retomarla.

### D-1 · Rollups de cartera por unidad — ✅ **CERRADO 2026-08-17**

**Resuelto por dos caminos, a propósito y con nombres distintos:**

- **Por ejecutivo actual** — `CommercialPortfolioService.rollup(...)`, dentro de
  `/dashboard/commercial`. Ya devolvía `byUnit`, `byChildUnit` y `byExecutive`; lo que estaba viejo
  era el comentario que decía que faltaba.
- **Por unidad de origen** — `GET /dashboard/commercial/by-origin-unit`, sobre
  `credit-portfolio /accounts/stats/by-origin-unit` (`GROUP BY origin_unit_code`, patrón C). Se
  eligió el **camino A** del análisis de abajo: `origin_unit_code` ya existía con índice parcial, así
  que el read-model por evento resultó innecesario.

**Por qué dos y no una:** son preguntas distintas —quién *produjo* la cartera contra quién la *lleva
hoy*— y dan cifras distintas en cuanto se reasigna una cartera. Medidas contra la base sembrada
**hoy coinciden al 100 %** (53 cuentas, $3,443,321, cero unidades con diferencia) porque nada se ha
reasignado: publicarlas sin distinguir se habría visto bien hasta la primera reasignación y luego
habría divergido en silencio. Esa igualdad es además la prueba de conciliación.

<details><summary>Análisis original (histórico)</summary>

- **Hoy:** `/dashboard/commercial` da el *alcance* (roster de la unidad + 403). Faltan los **números**
  (capital, mora, provisión) acotados al subárbol.
- **Por qué:** atribuir una cuenta a una unidad son 3 saltos entre 3 servicios —cuenta
  (`credit-portfolio.obligorPartyId`) → obligado (`party.assigned_executive_id`) → ejecutivo → unidad
  (`sales-org`)— y `credit-portfolio` no guarda ejecutivo ni unidad en la cuenta; sus agregados son
  globales. Sumar por fila en el BFF rompe el invariante.
- **Dos caminos:** **(A)** fan-out acotado — E5 ya resuelve los ejecutivos del subárbol; nuevo endpoint
  `credit-portfolio /accounts/stats?partyIds=`; nº de llamadas ∝ ejecutivos, no filas (~1 slice).
  **(B)** read-model por evento (patrón C) — agregado por `path` de unidad mantenido por eventos
  (saldo, reasignación de ejecutivo/unidad), consulta por `path <@` (~1.5–2 slices, escala mejor).
- Detalle en `docs/dominios/04b_credit_portfolio_domain.md`.

</details>

### D-2 · Particionado temporal de `audit.audit_entries` (E2)
- **Hoy:** tabla normal append-only. Crece sin techo (cada evento + cada login).
- **Qué:** `PARTITION BY RANGE (created_at)` mensual → poda por fecha + retención por `DROP PARTITION`.
- **Qué implica:** PK pasa a `(entry_id, created_at)`; gestión de particiones futuras (pg_partman o job).
  Puro escalado/ops, cero cambio funcional. Detalle en `docs/dominios/T3_audit_compliance.md`.

### D-3 · Invalidación por evento de la matriz de permisos (E4)
- **Hoy:** `PermissionsService` cachea la matriz (constante) con asa `reload()`.
- **Por qué diferido:** el BFF **no tiene bus de eventos** (compositor puro de WebClient) y la matriz
  es config estática → no hay nada que invalidar aún. Está **acoplado a D-4**: solo cobra sentido con
  la matriz editable.

### D-4 · Hot-edit de permisos con maker-checker (E4)
- **Qué:** editar la matriz en caliente desde el backoffice.
- **Por qué diferido (decisión explícita):** los permisos son críticos; sin cuatro ojos, un admin
  escala privilegios. *Maker-checker* = uno propone, **otro distinto** aprueba.
- **Feature completa:** almacén editable (configuration-service) + flujo propuesta/aprobación + rastro
  en auditoría (E2) + evento que invalida la caché del BFF (D-3). D-3 y D-4 son **una sola feature**.
  No entra sin decisión de gobernanza.

---

## ESTADO DEL BACKOFFICE — cierre 2026-08-17

Los siete huecos que quedaban del backend quedaron así:

| # | Hueco | Resultado |
|---|---|---|
| 1 | **Beneficiarios · KYC** | ✅ Bandeja transversal publicada (`GET /beneficiaries/placements` + ficha), capacidad **`beneficiaries.view`**. ⚠️ La ficha de identidad granular **no existe todavía**: responde `identityEvidence.available=false` con motivo, en vez de dibujar una sección vacía. |
| 2 | **Contacto y promesas** | ✅ `GET /collections/payment-promises` y `/contact-attempts` cruzando casos, con ventana de contacto y tope de intentos calculados en el dueño. Capacidad `portfolio.view` (existente). |
| 3 | **Contratos** | ❌ **No se hace.** `Contract` es `@Embeddable` sin id ni repositorio; la tabla de amortización es de credit-portfolio y su forma depende del producto. Lo que se vería ya está en la ficha de cuenta. **El front quita la entrada del menú.** |
| 4 | **Comisiones** | ⏸️ Fuera de alcance — se aborda por otra vía. `CommissionClient` ya existe en el BFF y `dashboard.commercial` ya está declarada. |
| 5 | **Configuración** | ❌ **No se hace, y no vuelve.** Lo que iba a ser esa pantalla ya existe repartido en Usuarios, Roles y Permisos. Configurar parámetros operativos es una conversación aparte y con su propio dueño. |
| 6 | **Rollups por unidad** | ✅ Ver D-1 arriba. Dos atribuciones con nombres distintos y cotejables entre sí. |
| 7 | **Notificaciones** | ✅ Campana publicada (`GET /notifications`, `PUT /notifications/read-all`), **sin capacidad** — es el buzón propio, como `/permissions/me`. ⚠️ **Funciona y está vacía**: falta que algún servicio publique avisos. Ver `docs/NOTIFICATIONS_EVENT_PLAN.md`. |

### Capacidad nueva que hay que sembrar

**`beneficiaries.view`** (ADMIN, OPS_SUPERVISOR, CREDIT_ANALYST, RISK_ANALYST, SUPPORT, AUDITOR).
Hasta que se siembre, nadie la tiene y el módulo **no aparece en ningún menú** — que es lo correcto,
pero explica por qué no se ve al terminar.

### Lo que el front tiene que hacer

1. **Beneficiarios:** consumir las dos rutas; la sección de identidad se pinta contra
   `identityEvidence.available`.
2. **Cobranza:** consumir las dos bandejas; `contactable` por fila decide si el botón de marcar se
   habilita.
3. **Rollups:** selector explícito «por origen / por ejecutivo actual», usando `attributionNote`
   como rótulo. **No** reemplazar la vista actual: hoy dan lo mismo y mañana no.
4. **Quitar del menú:** Contratos y Configuración.
5. **Campana:** consumir `unreadCount`; asumir que estará en cero hasta que existan emisores.

