# Regresión E2E — app · backoffice · servicios

Corre los journeys completos de punta a punta y deja **evidencia en video de las dos interfaces**
para cada caso. El plan y el análisis del flujo automático/manual están en
[`docs/PLAN_REGRESION_E2E.md`](../../docs/PLAN_REGRESION_E2E.md).

```
./run.sh                 # los tres casos
./run.sh --caso 1        # 1 uso propio · 2 distribuidora liquidada · 3 distribuidora en mora
./run.sh --quincenas 6   # cuántas quincenas devenga antes de cerrar (default 4)
./run.sh --sin-reporte
./run.sh --sin-video     # no une los actos en un solo MP4
```

Al terminar, además del reporte, cada caso deja **tres videos de demostración** en
`~/Documents/material-kredius/comercial/`, sin fechas en pantalla ni en el nombre; la evidencia de la corrida (actos
sueltos, bitácoras, reporte) queda en `~/Documents/material-kredius/pruebas/<commit>/<corrida>/<caso>/`:

| Archivo | Qué cuenta | Duración |
|---|---|---|
| `Kredius-credito-por-distribuidoras-short.mp4` | Sólo el celular: alta, vale, registro del cliente final, aprobación, depósito (acelerado) | ≤ 1:30 |
| `Kredius-credito-por-distribuidoras-middle.mp4` | El journey completo con el backoffice (actos 1–9) | ~6:30 |
| `Kredius-credito-por-distribuidoras-demo_completa.mp4` | Además, lo que pasa detrás: colocaciones, región, contabilidad, tesorería, accesos y auditoría (actos 10–14), con el backoffice ampliado y señalización | lo que dure |

- Abre con portada y personajes, intercala el momento del depósito y cierra con la conclusión.
- Cada acto va a tamaño legible dentro de un marco de teléfono o de ventana. Al lado, un panel
  narra el paso a paso, y el momento en curso se ilumina **sincronizado con la pantalla**: las
  pruebas marcan cada momento con `historia(clave)` y el orquestador anota el segundo exacto.
- El guion (titulares y momentos por acto) vive en [`video.py`](video.py); las tarjetas, en
  [`tarjetas.cjs`](tarjetas.cjs). Cambiar el copy no exige volver a grabar:
  `./une-video.sh ~/Documents/material-kredius/pruebas/<commit>/<corrida>/<caso>`.
- ffmpeg sale de `.venv` (imageio-ffmpeg, versión fija), que se crea solo la primera vez. Corre
  sobre la Mac y no en Docker: con el stack levantado, la máquina virtual no tiene memoria para
  componer 1080p.

La historia usa personas ficticias: **Laura Méndez Soto**, distribuidora en Culiacán (lo que
devuelve el OCR simulado y lo que captura la app), el cliente final (nombre aleatorio por corrida) y el
staff sembrado (Olivia Paz, Ulises Ward). Los documentos de muestra (INE, selfie ilustrada y
comprobante) se generan por corrida con esos datos y dicen «MUESTRA · SIN VALIDEZ»
([`documentos.cjs`](../../../fintech-backoffice-web/tools/e2e/browser/fixtures-kyc/documentos.cjs)).

## El caso de distribuidora, sin Incode

No hay proveedor de KYC: **toda identidad la comprueba una persona**, y la corrida lo graba.

| Acto | Dónde | Qué se ve |
|---|---|---|
| 01 | app | La distribuidora se da de alta. Su identidad queda `IN_REVIEW` y pedir la línea rebota con 422 |
| 02 | backoffice · Cumplimiento | `ops@` abre su ficha, mira la INE y la selfie, y comprueba su identidad a mano |
| 03 | backoffice · Solicitudes | El underwriter aprueba la línea |
| 04 | app | La distribuidora ve su línea y le presta al cliente final |
| 05 | página de alta (`:4300`) | El cliente final abre su liga: OTP, INE, selfie, datos con CLABE, autorización firmada con su código |
| 06 | backoffice · Beneficiarios | `ops@` coteja INE contra selfie y comprueba su identidad; el dictamen llega también a compliance |
| 07 | app | La distribuidora ve el historial completo y aprueba; sale la disposición |
| 08 | app | Seguimiento tras devengar quincenas: liquidación o mora |
| 09 | backoffice | Cartera o cobranza |
| 10 | backoffice · Beneficiarios / Cartera | *(demo completa)* la colocación depositada, quién firmó su identidad, el préstamo dentro de la línea |
| 11 | backoffice · Red comercial / Clientes | *(demo completa)* Región Norte › Zona Noroeste › Sucursal Culiacán; se le asigna su ejecutivo (**escribe**) |
| 12 | backoffice · Contabilidad | *(demo completa)* pólizas del crédito: 1201/1101 al desembolsar, 1203/4101/2110 al devengar, 1101/1201/1203 al pagar; la balanza |
| 13 | backoffice · Bancos y tesorería | *(demo completa)* la cuenta ordenante, el SPEI a la beneficiaria, su clave de rastreo y su liquidación |
| 14 | backoffice · Administración | *(demo completa)* usuarios, roles, matriz de permisos y la auditoría del caso |

Al terminar: `~/Documents/material-kredius/pruebas/<commit>/<corrida>/reporte.html` — la línea de tiempo de cada caso con sus
aserciones y los videos embebidos.

## Antes de correrlo

Sólo funciona desde la Mac: la app vive en el simulador de iOS y no hay camino headless.

| Requisito | Cómo se comprueba |
|---|---|
| Stack arriba | `curl -s http://localhost:8090/health` |
| `.env` con `TEST_SUPPORT_ENABLED=true` y `OTP_CODE_VALIDATION=false` | ya está en el `.env` local |
| Staff sembrado | `cd ../../../fintech-backoffice-web/tools/e2e && ./01-seed.sh` (incluye `ops@`, que trabaja las dos mesas de revisión manual) |
| compliance-service arriba | el preflight consulta su cola por la red interna |
| `.env` con `BENEFICIARY_DEV_OTP_CODE=123456` | el OTP de la página de alta (ya está en el compose) |
| Simulador booteado | `xcrun simctl boot 280BA0D6-…` (el script lo intenta solo) |
| `flutter`, `npx`, `jq`, `docker` en el PATH | el preflight aborta si falta alguno |

El backoffice en `localhost:4200` y la página de alta en `localhost:4300` los levanta el propio
script (`run-dev.sh` del backoffice) si no responden, y los baja al salir.

## Variables

Todas tienen default; se sobreescriben por entorno.

```
SERVICES_DIR APP_DIR BO_DIR          rutas a los tres repos (se deducen de la ubicación del script)
GATEWAY_PORT=8090  BO_HOST=backoffice.localhost  MOBILE_HOST=mobile.localhost
DOCKER_NETWORK=fintech-services_fintech-network  PG_CONTAINER=fintech-services-postgres-1
SIM_ID=280BA0D6-F4F9-4B61-A71C-299EAF739917
STAFF_PASSWORD=Backoffice#2026  UNDERWRITER_EMAIL=underwriter@kredius.mx
```

## Cómo está armado

Tres procesos que no pueden correr uno dentro de otro —simulador, Chromium y el reloj del
backend—, así que cada caso se parte en **actos** y el orquestador mueve el mundo entre uno y otro.
Cada acto graba su propio video.

```
run.sh          orquesta; decide qué se fuerza y qué se comprueba
lib.sh          los tres canales (svc / bo / bff), el reloj, la grabación y las aserciones
reporte.py      arma el HTML final
casos/          reservado para casos añadidos a mano
```

El handoff entre procesos es por **stdout**: los tests de Flutter imprimen
`##HANDOFF## clave=valor` y `run.sh` los lee con `grep`. El simulador no comparte disco con la Mac,
así que un archivo temporal no serviría.

Tres canales, tres razones:

- `svc()` → red interna de Docker. Único camino a `/internal/test-support/*` con `?date=`,
  a `rewind-accrual-schedules` y a `age-schedule`, que el proxy del BFF no expone.
- `bo()` → `backoffice.localhost:8090`. Lo que consulta la consola.
- `bff()` → `mobile.localhost:8090`. Lo que llama la app. Los pagos van por aquí a propósito: si el
  cobro se rompe en el canal, la prueba tiene que enterarse.

## Lo que toca de la base y devuelve

Para forzar revisión manual por el camino real, el caso 1 sube el `min_score` de la banda BAJO de
la política activa de `PERSONAL_LOAN`. El valor original se guarda en
`<corrida>/scoring-threshold.bak` y se restaura en un `trap` (cubre Ctrl-C y error).
Si matas el proceso con `kill -9`, restaura a mano:

```sql
UPDATE scoring.risk_thresholds t SET min_score = <el del .bak>
  FROM scoring.scoring_policies p
 WHERE p.policy_id = t.policy_id AND p.active
   AND p.product_type_intent = 'PERSONAL_LOAN' AND t.risk_level = 'BAJO';
```

## Huecos que la corrida documenta en vez de esconder

1. `default_approval_flow` del catálogo **no se lee** en origination: la ruta la decide el score.
2. Sin política de scoring o sin buró, la solicitud se queda en `PENDING_SCORING` para siempre —
   no cae a revisión manual.
3. Tras una aprobación manual **la app no retoma la oferta**: el polling se detiene al entrar en
   revisión y el home oculta la puerta al catálogo. El orquestador formaliza por API.
4. Una distribuidora recién dada de alta **no puede solicitar su línea desde la app**. Igual: la
   solicitud se crea por el BFF, como hace `scripts/seed-distribuidoras.py`.
5. El interés moratorio no se genera: `activateMoratorium(...)` no se invoca en producción. El
   caso 3 comprueba la mora contable (DPD, tramos, caso de cobranza), no el interés.
6. **La liga no se envía por WhatsApp**: notifications no consume `beneficiary.placement-invited`.
   El orquestador la acuña con el soporte de pruebas (`/invite-link`, que rota el token) y la abre.
7. **El caso 1 no pasa la compuerta de KYC**: la app crea la solicitud dentro del mismo acto del
   alta, antes de que una persona pueda dictaminar la identidad. Rebota con 422 y la app dice «No
   pudimos enviar tu solicitud». Partir ese acto en dos es trabajo pendiente.

Los siete están escritos en el reporte como avisos, no como verdes.

**Copy del video.** Está en dos lugares: el guion de los paneles y las tarjetas en `video.py`
(`STORY`, `VERSIONES`; se cambia y se recompone sin grabar), y las anotaciones con flecha de los
actos 10–14 en los specs del backoffice (`senala(...)`; quedan grabadas en el video, así que
cambiarlas exige volver a grabar esos actos). Reglas: presente y voz activa, una línea por momento
(≈ 35 caracteres), sin jerga interna, y neutro en género: los roles se nombran por función
(«Cliente final», «Distribución»), no por quien los ocupa.

**Música.** Cada versión lleva música ambiental **original**, compuesta por `musica.py` a la medida
del corte. Como no viene de un catálogo, no hay licencia que revisar ni atribución que poner. El
estilo es ambient corporativo cálido: 80 BPM, Re mayor con acordes de novena y suspendidos, pads,
piano de fieltro, bajo discreto y un pulso apenas audible, a nivel de fondo (≈ −19 LUFS) para que
se pueda presentar hablando encima. La pieza sigue la historia: entra suave en la portada, toma
pulso en el journey, resuelve con una cadencia justo en la tarjeta del depósito, cambia de
progresión en lo que pasa detrás (actos 10–14) y se posa en la tónica con el eslogan. Con la misma
entrada sale el mismo audio. Para usar otra pista: `MUSICA=<archivo con licencia> ./une-video.sh …`.
Sin música: `MUSICA=no`. Pruebas: `.venv/bin/python -I musica_test.py`.

## El video de arquitectura

La misma receta, para un equipo técnico y de arquitectura: portada, partes con panel narrativo,
un momento, el cierre y la misma música; pero cada parte es un **diagrama animado** en vez de una
grabación. No hace falta levantar el stack: sólo Node (el Playwright del backoffice) y el `.venv`.

```bash
./une-arquitectura.sh [short] [middle] [completa]   # → ~/Documents/material-kredius/arquitectura/
```

Los videos terminados —comerciales y de arquitectura— se entregan **fuera del repo**, en
`~/Documents/material-kredius/{comercial,arquitectura}` (`MATERIAL_DIR` lo cambia). Ver
[Material fuera del repo](#material-fuera-del-repo).

- `arquitectura.py` es el guion: las escenas (zonas, nodos, aristas) y sus momentos, con un número
  en el diagrama que coincide con el del panel. Se corrige ahí y se regenera.
- `diagramas.cjs` las dibuja y las captura cuadro a cuadro con un reloj propio: la misma escena da
  los mismos cuadros. Una escena sin cambios no se vuelve a capturar (caché en el temporal del sistema),
  y un texto que no cabe en su nodo se avisa en la salida en vez de partirse en dos líneas.
- Cada parte dice qué de lo que muestra **corre hoy** y qué es **propuesta** (borde punteado). La
  fuente es este repo y el diseño de `fintech-infraestructure`, que hoy no tiene Terraform aplicado;
  la última parte, la ruta, lista lo que falta para producción. Si una afirmación deja de ser cierta
  en el código, se corrige el guion: el video no puede prometer lo que el sistema no hace.

Versiones: **short** (≤ 1:30: mapa, proxy, eventos, nube y pilares), **middle** (capa por capa, un
SPEI de punta a punta, los cinco pilares del Well-Architected y la ruta) y **completa** (además el
servicio por dentro, la plataforma y un pilar por parte).

## Capacidad y tiempos: de dónde salen las cifras

Las partes «Originación» y «Capacidad» del video de arquitectura no tienen una sola cifra escrita a
mano: las lee de `~/Documents/material-kredius/pruebas/capacidad/capacidad.json`, que escribe `capacidad.py` contra el
stack levantado.

```bash
./scripts/respalda-siembra.sh guardar antes-de-carga     # escribe prospectos y créditos de prueba
.venv/bin/python -I capacidad.py                          # ≈ 8 min; --solo lectura|originacion, --rutas …
.venv/bin/python -I capacidad_test.py                     # el generador no es el cuello de botella
```

- **Lectura**: por el gateway, como un cliente real, en escalones de 1 a 64 clientes con conexión
  persistente. «Tolerado» = el escalón con más req/s que cumple p95 ≤ 300 ms y < 1 % de errores.
  Cada escalón anota el CPU de los contenedores y si alguno **se reinició**.
- **Originación**: el journey de la app con cronómetro por salto, en serie (tiempo limpio) y con 6
  en paralelo (originaciones por minuto). El dictamen de identidad y la decisión de mesa son de una
  persona: se ejecutan, pero no se cuentan como tiempo de sistema.

Lo que dejó la medición del 2026-10-30 (12 CPU, una réplica por servicio, 512 MB cada uno):
~1,200 req/s por ruta de lectura con p95 ≤ 25 ms; ~150 originaciones por minuto; ~0.9 s de sistema
por originación. Y dos caídas que la ruta a producción atiende: a 64 clientes en cartera,
**credit-portfolio muere por OOM** (512 MB, código 137); y con ~1,300 req/s de consultas del staff
**se reinicia audit-service**, porque la bitácora de accesos es un POST por consulta, sin cola.

## Material fuera del repo

**Todo el material de pruebas, comercial y de arquitectura vive en `~/Documents/material-kredius`**, no en el repo. El repo
es código: videos, imágenes, grabaciones, reportes, mediciones y respaldos de base están en
`.gitignore` y las herramientas los escriben directamente afuera (`MATERIAL_DIR` cambia la raíz).

```
~/Documents/material-kredius/
├── comercial/      videos para clientes (une-video.sh)
├── arquitectura/   videos técnicos (une-arquitectura.sh)
├── pruebas/        evidencia de cada corrida E2E por commit (run.sh) y mediciones (capacidad/)
└── respaldos/      respaldos de la base local (scripts/respalda-siembra.sh)
```
