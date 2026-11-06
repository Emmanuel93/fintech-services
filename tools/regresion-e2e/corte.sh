#!/usr/bin/env bash
#
# El corte de un día y sus estados de cuenta, contra el stack de docker compose.
#
#   ./corte.sh 2026-11-30              # cierra los días hasta ése, espera el corte y los PDF
#   ./corte.sh 2026-11-30 --muestras 5 # cuántos PDF descarga como evidencia (3 por omisión)
#
# El corte ya no se pide a closing directo: sin el cierre de cartera del día, closing lo rechaza. Se
# corre la cadena noche por noche —cargos devenga, cartera cierra, closing corta— desde el primer día
# que cargos tenga pendiente hasta la fecha pedida, por las mismas compuertas que el cron de las 00:05.
# Cada noche espera a que la anterior termine. Después sigue la corrida hasta que sella, sigue la
# generación en statement-service y descarga muestras. La evidencia va a la carpeta de material.
#
# Requiere TEST_SUPPORT_ENABLED=true si la fecha todavía no llega (en local corre el reloj).

set -uo pipefail
cd "$(dirname "$0")/../.."

FECHA="${1:?uso: ./corte.sh AAAA-MM-DD [--muestras N]}"
MUESTRAS=3
[ "${2:-}" = "--muestras" ] && MUESTRAS="${3:?--muestras necesita un número}"

CLOSING="http://localhost:${CLOSING_PORT:-8103}/api/v1/closing/cutoff-runs"
HDR=(-H "X-User-Id: operacion-corte" -H "X-Roles: OPS")
PG=(docker compose exec -T postgres psql -U "${POSTGRES_USER:-fintech}" -d fintech -tA -c)

COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo sin-commit)"
git diff --quiet HEAD 2>/dev/null || COMMIT="$COMMIT-sucio"
EVIDENCIA="${MATERIAL_DIR:-$HOME/Documents/material-kredius}/pruebas/$COMMIT/corte-$FECHA-$(date +%H%M%S)"
mkdir -p "$EVIDENCIA"

statement() {   # statement-service no publica puerto en el host: se le pregunta desde su contenedor
  docker compose exec -T statement-service wget -qO- --header "X-User-Id: operacion-corte" \
    "http://localhost:8080$1" 2>/dev/null
}

cargos() {   # charges-service tampoco publica puerto: el soporte de pruebas se llama desde su contenedor
  docker compose exec -T charges-service wget -qO- --header "X-User-Id: operacion-corte" --post-data="" \
    "http://localhost:8080$1" 2>/dev/null
}

siguiente_dia() {   # $1 + 1 día, en el formato de date de macOS
  date -j -v+1d -f %Y-%m-%d "$1" +%Y-%m-%d
}

echo "▶ Cierre del día, noche por noche, hasta el $FECHA"
inicio=$(date +%s)
ultimo=$("${PG[@]}" "SELECT max(business_date) FROM charges.accrual_days WHERE status = 'DONE'")
if [ -n "$ultimo" ]; then dia=$(siguiente_dia "$ultimo"); else dia=$(TZ=America/Mexico_City date -v-1d +%Y-%m-%d); fi
while [[ ! "$dia" > "$FECHA" ]]; do
  r=$(cargos "/internal/test-support/run-day-close?date=$dia")
  resultado=$(echo "$r" | jq -r .outcome 2>/dev/null)
  echo "  $dia  cargos: $resultado — $(echo "$r" | jq -r .detail 2>/dev/null)"
  case "$resultado" in
    CLOSED|ALREADY_CLOSED) ;;
    *) echo "✗ el cierre del $dia no pasó sus compuertas"; echo "$r" > "$EVIDENCIA/cierre-$dia.json"; exit 1 ;;
  esac
  dia=$(siguiente_dia "$dia")
done

echo "▶ Corte del $FECHA"
for _ in $(seq 1 300); do   # cartera y closing siguen solos con los avisos
  curl -fsS "${HDR[@]}" "$CLOSING/$FECHA" > "$EVIDENCIA/corte-plan.json" 2>/dev/null && break
  sleep 2
done
[ -s "$EVIDENCIA/corte-plan.json" ] || { echo "✗ closing no planificó el corte del $FECHA:"; \
  "${PG[@]}" "SELECT waiting_for FROM closing.portfolio_day_closes WHERE business_date = '$FECHA'"; \
  "${PG[@]}" "SELECT status, waiting_for FROM credit_portfolio.day_closes WHERE business_date = '$FECHA'"; exit 1; }
jq -r '"  \(.plannedUnits) cuentas · " + ([.breakdown[] | "\(.holderKind)/\(.productType)=\(.units)"] | join(", "))' \
  "$EVIDENCIA/corte-plan.json"

for _ in $(seq 1 120); do
  curl -fsS "${HDR[@]}" "$CLOSING/$FECHA" > "$EVIDENCIA/corte.json"
  estado=$(jq -r .status "$EVIDENCIA/corte.json")
  [ "$estado" = "SEALED" ] || [ "$estado" = "FAILED" ] && break
  sleep 2
done
jq -r '"  corte \(.status): \(.counts.done) cortadas · \(.counts.skipped) omitidas · \(.counts.failed) fallidas"' \
  "$EVIDENCIA/corte.json"
[ "$estado" = "SEALED" ] || { echo "✗ el corte no selló"; jq '.failures' "$EVIDENCIA/corte.json"; exit 1; }
echo "  en $(( $(date +%s) - inicio )) s"

echo "▶ Estados de cuenta"
for _ in $(seq 1 150); do
  statement "/api/v1/statements/batches?businessDate=$FECHA" > "$EVIDENCIA/estados.json"
  if [ -s "$EVIDENCIA/estados.json" ]; then
    hechos=$(jq -r '.generated + .failed' "$EVIDENCIA/estados.json")
    esperados=$(jq -r .expected "$EVIDENCIA/estados.json")
    [ "$hechos" -ge "$esperados" ] && break
  fi
  sleep 2
done
jq -r '"  \(.generated) de \(.expected) generados · \(.failed) fallidos · \(.inFlight) en curso"' "$EVIDENCIA/estados.json"
echo "  en $(( $(date +%s) - inicio )) s desde el disparo"

echo "  por réplica:"
"${PG[@]}" "SELECT '    ' || generated_by || ': ' || COUNT(*) FROM statement.statements
            WHERE cutoff_date = '$FECHA' AND status = 'GENERATED' GROUP BY generated_by ORDER BY 1" \
  | tee "$EVIDENCIA/por-replica.txt"

echo "▶ Muestras"
"${PG[@]}" "SELECT layout, statement_id, COALESCE(contract_number, credit_account_id::text) FROM (
              SELECT *, ROW_NUMBER() OVER (PARTITION BY layout ORDER BY generated_at) n
                FROM statement.statements WHERE cutoff_date = '$FECHA' AND status = 'GENERATED') x
            WHERE n <= $MUESTRAS ORDER BY layout, n" |
while IFS='|' read -r forma id contrato; do
  [ -n "$id" ] || continue
  archivo="$EVIDENCIA/$(echo "$forma" | tr '[:upper:]' '[:lower:]')-$contrato.pdf"
  docker compose exec -T statement-service wget -qO- --header "X-User-Id: operacion-corte" \
    "http://localhost:8080/api/v1/statements/$id/pdf" > "$archivo" < /dev/null   # sin stdin: no se come el resto del while
  echo "  $forma  $(basename "$archivo")  $(wc -c < "$archivo" | tr -d ' ') bytes"
done

echo "✓ Evidencia en $EVIDENCIA"
