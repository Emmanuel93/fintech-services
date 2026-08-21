# Regresión E2E — app · backoffice · servicios

Corre los journeys completos de punta a punta y deja **evidencia en video de las dos interfaces**
para cada caso. El plan y el análisis del flujo automático/manual están en
[`docs/PLAN_REGRESION_E2E.md`](../../docs/PLAN_REGRESION_E2E.md).

```
./run.sh                 # los tres casos
./run.sh --caso 1        # 1 uso propio · 2 distribuidora liquidada · 3 distribuidora en mora
./run.sh --quincenas 6   # cuántas quincenas devenga antes de cerrar (default 4)
./run.sh --sin-reporte
```

Al terminar: `.evidencia/<fecha-hora>/reporte.html` — la línea de tiempo de cada caso con sus
aserciones y los videos embebidos.

## Antes de correrlo

Sólo funciona desde la Mac: la app vive en el simulador de iOS y no hay camino headless.

| Requisito | Cómo se comprueba |
|---|---|
| Stack arriba | `curl -s http://localhost:8090/health` |
| `.env` con `TEST_SUPPORT_ENABLED=true` y `OTP_CODE_VALIDATION=false` | ya está en el `.env` local |
| Staff sembrado | `cd ../../../fintech-backoffice-web/tools/e2e && ./01-seed.sh` |
| Simulador booteado | `xcrun simctl boot 280BA0D6-…` (el script lo intenta solo) |
| `flutter`, `npx`, `jq`, `docker` en el PATH | el preflight aborta si falta alguno |

El backoffice en `localhost:4200` lo levanta el propio script si no responde, y lo baja al salir.

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
`.evidencia/<corrida>/scoring-threshold.bak` y se restaura en un `trap` (cubre Ctrl-C y error).
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

Los cinco están escritos en el reporte como avisos, no como verdes.
