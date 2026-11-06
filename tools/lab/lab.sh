#!/usr/bin/env bash
# Laboratorio del cierre nocturno sobre una copia de la base. Ver docker-compose.yml de esta carpeta.
#
#   lab.sh free-memory            apaga servicios del stack local que el laboratorio no usa (reversible)
#   lab.sh up | down              levanta / tira el laboratorio (down borra la copia de la base)
#   lab.sh clone                  copia public + credit_portfolio + charges + closing + statement del stack local
#   lab.sh clock 2026-11-05       pone «hoy» en esa fecha en los cuatro servicios
#   lab.sh run 2026-11-01 2026-12-31 [pagos.csv]
#                                 corre la cadena noche por noche: pagos del día, foto, devengo, cierre, corte
#   lab.sh trace CUENTA FECHA     la vista paso a paso: foto de cartera, unidad de cargos con su base, cargos
#   lab.sh statements FECHA DIR   baja los PDF de los estados de cuenta de un corte
#   lab.sh restore-live           vuelve a levantar lo que apagó free-memory
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
COMPOSE=(docker compose -p lab -f "$HERE/docker-compose.yml")
PORTFOLIO=http://localhost:18087
CHARGES=http://localhost:18088
CLOSING=http://localhost:18103
STATEMENT=http://localhost:18106
AUTH=(-H "X-User-Id: lab" -H "X-Roles: SERVICE,ADMIN,OPS")
# Servicios del stack local que la cadena no necesita y liberan memoria para el laboratorio.
SPARE=(channel-mobile-service channels-service invoicing-service sales-org-service scoring-service wallet-service stp-service)
SCHEMAS=(-n public -n credit_portfolio -n charges -n closing -n statement)

log() { printf '\033[1;34m▸\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

psql_lab() { docker exec -i lab-postgres-1 psql -U fintech -d fintech -v ON_ERROR_STOP=1 -At "$@"; }

free_memory() {
  log "Apagando del stack local: ${SPARE[*]}"
  (cd "$ROOT" && docker compose stop "${SPARE[@]}")
}

restore_live() {
  log "Levantando de nuevo: ${SPARE[*]}"
  (cd "$ROOT" && docker compose start "${SPARE[@]}")
}

up() {
  "${COMPOSE[@]}" up -d postgres zookeeper kafka redis party-service
  log "Esperando a Postgres y Kafka del laboratorio"
  for i in $(seq 1 60); do
    docker exec lab-postgres-1 pg_isready -U fintech -d fintech >/dev/null 2>&1 && break; sleep 2
  done
}

services_up() {
  "${COMPOSE[@]}" up -d credit-portfolio-service charges-service closing-service statement-service
  log "Esperando a los cuatro servicios"
  # Health answers 503 when an optional component (Redis) is down; what matters here is that the app answers.
  for url in "$PORTFOLIO" "$CHARGES" "$CLOSING" "$STATEMENT"; do
    for i in $(seq 1 90); do
      code=$(curl -s -o /dev/null -w '%{http_code}' "$url/actuator/health" || true)
      { [ "$code" = 200 ] || [ "$code" = 503 ]; } && break; sleep 3
      [ "$i" = 90 ] && die "no levantó $url"
    done
    ok "$url arriba (health $code)"
  done
}

clone() {
  log "Copiando esquemas del stack local (${SCHEMAS[*]})"
  docker exec fintech-services-postgres-1 pg_dump -U fintech -d fintech -Fc "${SCHEMAS[@]}" > /tmp/lab-dump.pgc
  ls -la /tmp/lab-dump.pgc | awk '{print "  respaldo:", $5, "bytes"}'
  docker ps --format '{{.Names}}' | grep -q '^lab-postgres-1$' || die "el Postgres del laboratorio no está arriba (lab.sh infra)"
  for i in $(seq 1 30); do docker exec lab-postgres-1 pg_isready -U fintech -d fintech >/dev/null 2>&1 && break; sleep 2; done
  # The portfolio's trigram index needs the extension before the restore creates it.
  docker exec -i lab-postgres-1 psql -U fintech -d fintech -c "CREATE EXTENSION IF NOT EXISTS pg_trgm" >/dev/null
  # Errors other than "already exists" (the lab's own public schema) abort: a half restore is worse than none.
  docker exec -i lab-postgres-1 pg_restore -U fintech -d fintech --no-owner --no-privileges < /tmp/lab-dump.pgc 2> /tmp/lab-restore.err || true
  if grep -v "already exists" /tmp/lab-restore.err | grep -q "error:"; then grep -v "already exists" /tmp/lab-restore.err | head; die "la restauración falló"; fi
  ok "Copia restaurada en el laboratorio"
  psql_lab -c "select 'cuentas: ' || count(*) from credit_portfolio.credit_accounts; select 'calendarios: ' || count(*) from charges.accrual_schedules where status='ACTIVE';"
}

clock() {
  local d="$1"
  for url in "$PORTFOLIO" "$CHARGES" "$CLOSING" "$STATEMENT"; do
    curl -sf -X POST "$url/internal/test-support/clock?today=$d" >/dev/null || die "no pude mover el reloj de $url"
  done
}

pay() { # cuenta monto referencia
  curl -sf -X POST "$PORTFOLIO/internal/test-support/accounts/$1/payments?amount=$2&reference=$3" | tr -d '\n'; echo
}

# Corre la cadena de un día: la foto la pide cargos; cartera y closing siguen solos por Kafka.
close_day() {
  local d="$1"
  local r; r=$(curl -sf -X POST "$CHARGES/internal/test-support/run-day-close?date=$d")
  local outcome; outcome=$(echo "$r" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("outcome",""))')
  case "$outcome" in
    CLOSED|ALREADY_CLOSED) ;;
    *) echo "$r"; die "cargos no cerró el $d ($outcome)";;
  esac
  for i in $(seq 1 120); do
    local st; st=$(curl -s "${AUTH[@]}" "$CLOSING/api/v1/closing/cutoff-runs/$d" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("status",""))
except Exception: print("")')
    [ "$st" = "SEALED" ] && return 0
    [ "$st" = "FAILED" ] && die "el corte del $d quedó FAILED"
    sleep 2
  done
  die "el corte del $d no selló en 4 minutos"
}

run() {
  local from="$1" to="$2" csv="${3:-}"
  local d="$from"
  while [ "$(date -j -f %Y-%m-%d "$d" +%s)" -le "$(date -j -f %Y-%m-%d "$to" +%s)" ]; do
    local next; next=$(date -j -v+1d -f %Y-%m-%d "$d" +%Y-%m-%d)
    # 1) El día D: los pagos de ese día, con el reloj en D para que su fecha valor sea D.
    clock "$d"
    if [ -n "$csv" ] && [ -f "$csv" ]; then
      while IFS=, read -r fecha cuenta monto ref; do
        [ "$fecha" = "$d" ] || continue
        log "  pago $monto a $cuenta ($ref)"; pay "$cuenta" "$monto" "$ref"
      done < "$csv"
    fi
    # 2) Las 00:0x de D+1: la cadena del día D.
    clock "$next"
    local t0=$(date +%s)
    close_day "$d"
    local resumen; resumen=$(curl -s "${AUTH[@]}" "$CHARGES/api/v1/charges/day-close/$d" | python3 -c 'import json,sys
j=json.load(sys.stdin); a=j["accounts"]; c=j["controlTotals"]
print(str(a["done"]) + " cuentas, " + str(c["ordinaryCharges"]) + " intereses por " + str(c["ordinaryAmount"]) + ", " + str(c["moratoriumCharges"]) + " moratorios, cuarentena " + str(a["quarantined"]) + ", retenidas " + str(a["held"]))')
    local cortes; cortes=$(curl -s "${AUTH[@]}" "$CLOSING/api/v1/closing/cutoff-runs/$d" | python3 -c 'import json,sys
j=json.load(sys.stdin); c=j.get("counts",{}); print("cortes " + str(c.get("done",0)) + " sellados, " + str(c.get("failed",0)) + " fallidos, " + str(c.get("held",0)) + " retenidos")')
    ok "$d · $resumen · $cortes · $(( $(date +%s) - t0 )) s"
    d="$next"
  done
}

trace() {
  local cuenta="$1" d="$2"
  echo "── Foto de cartera ($d)"; curl -s "${AUTH[@]}" "$PORTFOLIO/api/v1/portfolio/accounts/$cuenta/day-snapshots?from=$d&to=$d" | python3 -m json.tool
  echo "── Cargos: unidad, base y cargos del día"; curl -s "${AUTH[@]}" "$CHARGES/api/v1/charges/day-close/$d/accounts/$cuenta" | python3 -m json.tool
}

statements() {
  local d="$1" dir="$2"; mkdir -p "$dir"
  local ids; ids=$(psql_lab -c "select statement_id || '|' || credit_account_id || '|' || coalesce(status,'') from statement.statements where cutoff_date = '$d' order by generated_at")
  local n=0
  while IFS='|' read -r id cuenta status; do
    [ -n "$id" ] || continue
    if [ "$status" = "GENERATED" ]; then
      curl -sf "${AUTH[@]}" "$STATEMENT/api/v1/statements/$id/pdf" -o "$dir/$d-${cuenta:0:8}.pdf" && n=$((n+1))
    fi
  done <<< "$ids"
  ok "$n PDF del corte del $d en $dir"
}

case "${1:-}" in
  free-memory) free_memory ;;
  restore-live) restore_live ;;
  up) up; services_up ;;
  infra) up ;;
  services) services_up ;;
  down) "${COMPOSE[@]}" down -v ;;
  clone) clone ;;
  clock) clock "$2" ;;
  pay) pay "$2" "$3" "${4:-lab}" ;;
  run) run "$2" "$3" "${4:-}" ;;
  trace) trace "$2" "$3" ;;
  statements) statements "$2" "$3" ;;
  *) sed -n 2,14p "$0"; exit 1 ;;
esac
