#!/usr/bin/env bash
# Helpers de la regresión E2E. Se carga con `source`, no se ejecuta.
#
# Tres canales, tres razones:
#   svc()  → red interna de Docker. Es el único camino a /internal/test-support/*, porque el
#            gateway (nginx) no enruta /internal — a propósito.
#   bo()   → backoffice.localhost:8090, el BFF de la consola. Lo que aprieta un analista.
#   bff()  → mobile.localhost:8090, el BFF móvil. Lo que llama la app.
# Mezclarlos es lo que hace que un fallo no signifique nada: si el pago entra por el servicio de
# dominio en vez de por el BFF, un rojo no distingue "el cobro está mal" de "el canal está mal".

set -uo pipefail

# ── Configuración ────────────────────────────────────────────────────────────
SERVICES_DIR="${SERVICES_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
APP_DIR="${APP_DIR:-$SERVICES_DIR/../fintech-app}"
BO_DIR="${BO_DIR:-$SERVICES_DIR/../fintech-backoffice-web}"

GATEWAY_PORT="${GATEWAY_PORT:-8090}"
BO_HOST="${BO_HOST:-backoffice.localhost}"
MOBILE_HOST="${MOBILE_HOST:-mobile.localhost}"
DOCKER_NETWORK="${DOCKER_NETWORK:-fintech-services_fintech-network}"
CURL_IMAGE="${CURL_IMAGE:-curlimages/curl:8.10.1}"
PG_CONTAINER="${PG_CONTAINER:-fintech-services-postgres-1}"
PG_USER="${PG_USER:-fintech}"
PG_DB="${PG_DB:-fintech}"

SIM_ID="${SIM_ID:-280BA0D6-F4F9-4B61-A71C-299EAF739917}"
BFF_URL="${BFF_URL:-http://$MOBILE_HOST:$GATEWAY_PORT}"
BO_URL="${BO_URL:-http://$BO_HOST:$GATEWAY_PORT}"

STAFF_PASSWORD="${STAFF_PASSWORD:-Backoffice#2026}"
UNDERWRITER_EMAIL="${UNDERWRITER_EMAIL:-underwriter@kredius.mx}"
ANALISTA_EMAIL="${ANALISTA_EMAIL:-analista@kredius.mx}"

ORIGINATION="http://origination-service:8080"
SCORING="http://scoring-service:8080"
CHARGES="http://charges-service:8080"
PORTFOLIO="http://credit-portfolio-service:8080"
PAYMENTS="http://payments-service:8080"
BENEFICIARY="http://beneficiary-service:8080"

# EVIDENCIA lo fija run.sh antes de llamar a nada de aquí.
EVIDENCIA="${EVIDENCIA:-$SERVICES_DIR/tools/regresion-e2e/evidencia/suelta/corrida}"

# ── Salida ───────────────────────────────────────────────────────────────────
if [ -t 1 ]; then C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_INF=$'\033[36m'; C_WRN=$'\033[33m'; C_OFF=$'\033[0m'
else C_OK=; C_ERR=; C_INF=; C_WRN=; C_OFF=; fi

FALLOS=0
PASOS_JSON="" # se acumula y lo lee reporte.py

log()  { printf '%s▸%s %s\n' "$C_INF" "$C_OFF" "$*"; }
ok()   { printf '%s✓%s %s\n' "$C_OK"  "$C_OFF" "$*"; _paso "ok"   "$*"; }
warn() { printf '%s!%s %s\n' "$C_WRN" "$C_OFF" "$*"; _paso "warn" "$*"; }
err()  { printf '%s✗%s %s\n' "$C_ERR" "$C_OFF" "$*"; FALLOS=$((FALLOS+1)); _paso "fail" "$*"; }
die()  { err "$*"; exit 1; }

_paso() { # estado, texto → línea JSON en pasos.jsonl
  local estado="$1"; shift
  local texto; texto=$(printf '%s' "$*" | sed 's/\\/\\\\/g; s/"/\\"/g')
  printf '{"t":"%s","caso":"%s","estado":"%s","texto":"%s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${CASO_ACTUAL:-general}" "$estado" "$texto" \
    >> "$EVIDENCIA/pasos.jsonl" 2>/dev/null || true
}

afirma() { # afirma "descripción" "esperado" "obtenido"
  if [ "$2" = "$3" ]; then ok "$1 ($3)"; else err "$1 — esperaba «$2», llegó «$3»"; fi
}

afirma_contiene() { # afirma_contiene "descripción" "aguja" "pajar"
  case "$3" in *"$2"*) ok "$1" ;; *) err "$1 — no encontré «$2»" ;; esac
}

# ── Canales HTTP ─────────────────────────────────────────────────────────────

# svc MÉTODO URL [BODY] [HEADER…] — red interna de Docker.
svc() {
  local metodo="$1" url="$2" body="${3:-}"; shift 3 2>/dev/null || shift 2
  local args=(-sS -X "$metodo" "$url" -H 'Content-Type: application/json')
  [ -n "$body" ] && args+=(-d "$body")
  # bash 3.2 (el de macOS) revienta con "$@" vacío bajo `set -u`.
  local trae_id=0 h
  if [ $# -gt 0 ]; then
    for h in "$@"; do
      case "$h" in [Xx]-[Uu]ser-[Ii]d:*) trae_id=1 ;; esac
      args+=(-H "$h")
    done
  fi
  # Los servicios de dominio exigen identidad: sin `X-User-Id`, origination responde **401 con
  # cuerpo vacío**, y entonces las aserciones comparaban contra «» sin decir por qué. Las rutas
  # `/internal/test-support/*` no lo piden, pero mandarlo de más no les molesta.
  [ "$trae_id" -eq 0 ] && args+=(-H "X-User-Id: ${SVC_USER:-regresion-e2e}")
  docker run --rm --network "$DOCKER_NETWORK" "$CURL_IMAGE" "${args[@]}" 2>>"$EVIDENCIA/api.log"
}

# svc_code MÉTODO URL → sólo el código HTTP. Sirve para distinguir "no existe la ruta" (404,
# test-support apagado) de "existe pero le falta un parámetro" (400).
svc_code() {
  docker run --rm --network "$DOCKER_NETWORK" "$CURL_IMAGE" \
    -sS -o /dev/null -w '%{http_code}' -X "$1" "$2" 2>>"$EVIDENCIA/api.log"
}

# svc_sh "comando sh dentro de un curlimages" — para bucles sin pagar un contenedor por llamada.
svc_sh() {
  docker run --rm --network "$DOCKER_NETWORK" --entrypoint sh "$CURL_IMAGE" -c "$1" 2>>"$EVIDENCIA/api.log"
}

bo_login() { # email → token
  local email="$1"
  curl -sS -X POST "$BO_URL/auth/staff/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$STAFF_PASSWORD\"}" 2>>"$EVIDENCIA/api.log" \
    | jq -r '.accessToken // .token // empty'
}

bo_get()  { curl -sS "$BO_URL$2" -H "Authorization: Bearer $1" 2>>"$EVIDENCIA/api.log"; }
bo_post() { curl -sS -X POST "$BO_URL$2" -H "Authorization: Bearer $1" \
              -H 'Content-Type: application/json' -d "${3:-{}}" 2>>"$EVIDENCIA/api.log"; }

# Sin `-i`: dentro de un `while read … done < archivo`, docker se comería el stdin del bucle
# y sólo se procesaría la primera línea.
psql_q() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" </dev/null; }

# espera "descripción" intentos pausa "comando que debe devolver algo no vacío"
espera() {
  local desc="$1" intentos="$2" pausa="$3" cmd="$4" salida=""
  for _ in $(seq 1 "$intentos"); do
    salida=$(eval "$cmd" 2>/dev/null)
    if [ -n "$salida" ] && [ "$salida" != "null" ]; then printf '%s' "$salida"; return 0; fi
    sleep "$pausa"
  done
  return 1
}

# ── Reloj del backend ────────────────────────────────────────────────────────

# devengar_quincenas N — retrocede el reloj de devengo y lo corre día por día.
# El devengo es idempotente por día: sin `?date=`, treinta llamadas producen un día de interés.
# Por eso esto no se puede hacer por el BFF (su proxy no acepta `?date=`).
devengar_quincenas() {
  # Dos `local` separados a propósito: bash expande todas las palabras del `local` antes de
  # asignar ninguna, así que `dias=$((quincenas * 14))` en la misma línea mira una `quincenas`
  # que aún no existe y aborta con "unbound variable" bajo `set -u`.
  local quincenas="$1"
  local dias=$((quincenas * 14))
  local inicio; inicio=$(date -v-"${dias}"d +%Y-%m-%d)
  log "Devengo: $quincenas quincenas ($dias días) desde $inicio"

  svc POST "$CHARGES/internal/test-support/rewind-accrual-schedules?date=$inicio" >/dev/null

  # Un solo contenedor para los N días. Fechas precalculadas en el host porque BusyBox date
  # no tiene aritmética de fechas.
  local fechas="" d
  for d in $(seq 0 $((dias - 1))); do
    fechas="$fechas $(date -v-$((dias - d))d +%Y-%m-%d)"
  done
  svc_sh "for f in $fechas; do
            curl -sS -o /dev/null -X POST '$CHARGES/internal/test-support/run-daily-accrual?date='\$f;
          done; echo listo" >/dev/null

  svc POST "$PORTFOLIO/internal/test-support/run-installment-due-job" >/dev/null
  ok "Reloj avanzado $quincenas quincenas y corte ejecutado"
}

# provocar_mora scheduleId dias [disposicion|cuenta]
provocar_mora() {
  local id="$1" dias="$2" tipo="${3:-cuenta}"
  if [ "$tipo" = "disposicion" ]; then
    svc POST "$PORTFOLIO/internal/test-support/dispositions/$id/age-schedule?daysAgo=$dias" >/dev/null
  else
    svc POST "$PORTFOLIO/internal/test-support/accounts/$id/installments/1/shift-due-date?daysFromToday=-$dias" >/dev/null
  fi
  # Sin esto la cuota queda vencida y days_delinquent sigue en 0: la app la muestra al corriente.
  svc POST "$PORTFOLIO/internal/test-support/run-delinquency-job" >/dev/null
  ok "Mora forzada ($dias días) y recálculo de DPD ejecutado"
}

# ── Grabación ────────────────────────────────────────────────────────────────
_REC_PID=""
grabar_inicio() { # grabar_inicio nombre.mp4
  local destino="$EVIDENCIA/${CASO_ACTUAL:-general}/$1"
  mkdir -p "$(dirname "$destino")"
  xcrun simctl io "$SIM_ID" recordVideo --codec h264 --force "$destino" >/dev/null 2>&1 &
  _REC_PID=$!
  sleep 2   # simctl tarda en abrir el archivo; sin esto se pierden los primeros segundos
  log "Grabando → $(basename "$destino")"
}
grabar_fin() {
  [ -z "$_REC_PID" ] && return 0
  kill -INT "$_REC_PID" 2>/dev/null || true   # SIGINT cierra el .mp4 bien; SIGKILL lo deja corrupto
  wait "$_REC_PID" 2>/dev/null || true
  _REC_PID=""
  sleep 1
}

# ── Flutter ──────────────────────────────────────────────────────────────────
# correr_app archivo.dart salida.log [--dart-define=…]…
# Deja el stdout completo en la evidencia y devuelve 0/1 según el resultado del test.
correr_app() {
  local archivo="$1" logfile="$2"; shift 2
  mkdir -p "$(dirname "$logfile")"
  ( cd "$APP_DIR/packages/fa_shell" \
      && flutter test "integration_test/$archivo" -d "$SIM_ID" "$@" 2>&1 ) | tee "$logfile"
  return "${PIPESTATUS[0]}"
}

# handoff clave archivo.log — lee las líneas «##HANDOFF## clave=valor» que imprime el test.
# El simulador está en su propio sandbox: no comparte disco con el host, así que stdout es
# el único canal de vuelta que no obliga a montar nada.
handoff() {
  grep -m1 "##HANDOFF## $1=" "$2" 2>/dev/null | sed "s/.*##HANDOFF## $1=//" | tr -d '\r'
}

# ── Playwright ───────────────────────────────────────────────────────────────
# correr_bo spec.ts subcarpeta [VAR=valor]…
correr_bo() {
  local spec="$1" sub="$2"; shift 2
  local salida="$EVIDENCIA/${CASO_ACTUAL:-general}/$sub"
  mkdir -p "$salida"
  ( cd "$BO_DIR" && env "$@" \
      PLAYWRIGHT_HTML_REPORT="$salida/report" \
      npx playwright test "tools/e2e/browser/$spec" \
        --output "$salida/artefactos" 2>&1 ) | tee "$salida/salida.log"
  return "${PIPESTATUS[0]}"
}

# ── Canal móvil (el mismo que usa la app) ────────────────────────────────────
bff_login() { # phone password → token
  curl -sS -X POST "$BFF_URL/auth/login" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$1\",\"password\":\"$2\",\"deviceId\":\"regresion-e2e\"}" \
    2>>"$EVIDENCIA/api.log" | jq -r '.token // empty'
}
bff_post() { curl -sS -X POST "$BFF_URL$2" -H "Authorization: Bearer $1" \
               -H 'Content-Type: application/json' -d "${3:-{}}" 2>>"$EVIDENCIA/api.log"; }
bff_get()  { curl -sS "$BFF_URL$2" -H "Authorization: Bearer $1" 2>>"$EVIDENCIA/api.log"; }
