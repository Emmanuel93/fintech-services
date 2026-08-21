#!/usr/bin/env bash
# Respalda o restaura la base ya sembrada.
#
# Existe porque la siembra recorre el journey real —OTP, KYC, scoring, contrato, firma— y eso cuesta
# unas dos horas para mil clientes. Volver a pagarlas cada vez que hace falta un entorno limpio es el
# tipo de coste que se paga en silencio hasta que alguien lo suma.
#
# Respaldar NO sustituye a sembrar: si cambia una migración o un contrato, el respaldo queda viejo y
# restaurarlo siembra el esquema anterior con datos del nuevo. Para eso está `reinicia-y-siembra.sh`.
# Esto es para volver rápido a un punto conocido, no para saltarse el proceso.
#
#     ./scripts/respalda-siembra.sh guardar  [nombre]
#     ./scripts/respalda-siembra.sh restaurar [nombre]
#     ./scripts/respalda-siembra.sh listar
set -euo pipefail
cd "$(dirname "$0")/.."

DESTINO="${RESPALDOS:-./respaldos}"
NOMBRE="${2-siembra}"
ARCHIVO="$DESTINO/$NOMBRE.sql.gz"

case "${1:-}" in
  guardar)
    mkdir -p "$DESTINO"
    echo "▸ Respaldando la base a ${ARCHIVO}…"
    # --clean --if-exists para que restaurar sobre una base viva no falle por objetos que ya están.
    docker compose exec -T postgres pg_dump -U fintech -d fintech --clean --if-exists \
      | gzip > "$ARCHIVO"
    echo "  $(du -h "$ARCHIVO" | cut -f1)  guardado"
    docker compose exec -T postgres psql -U fintech -d fintech -t -A -c \
      "SELECT '  '||COUNT(*)||' créditos · '||(SELECT COUNT(*) FROM accounting.vouchers)||' pólizas'
         FROM credit_portfolio.credit_accounts;"
    ;;

  restaurar)
    [ -f "$ARCHIVO" ] || { echo "no existe $ARCHIVO" >&2; exit 1; }
    echo "▸ Restaurando ${ARCHIVO}…"
    # Los servicios se paran antes: restaurar bajo tráfico deja consumidores leyendo un esquema que
    # se está reescribiendo, y el fallo aparece después, disfrazado de dato corrupto.
    docker compose stop $(docker compose config --services | grep -vE 'postgres|kafka|zookeeper|redis') >/dev/null 2>&1 || true
    gunzip -c "$ARCHIVO" | docker compose exec -T postgres psql -U fintech -d fintech -q
    docker compose start $(docker compose config --services | grep -vE 'postgres|kafka|zookeeper|redis') >/dev/null 2>&1 || true

    echo "▸ Recargando el gateway (las IPs cambiaron al reiniciar)…"
    for _ in $(seq 1 20); do
      docker compose exec -T gateway-service openresty -s reload >/dev/null 2>&1 && break
      sleep 3
    done
    for _ in $(seq 1 60); do
      code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://backoffice.localhost:8090/auth/staff/login \
        -H 'Content-Type: application/json' \
        -d '{"email":"admin@kredius.mx","password":"Backoffice#2026"}' 2>/dev/null || true)
      [ "$code" = "200" ] && { echo "  backoffice ✓"; break; }
      sleep 3
    done
    ;;

  listar)
    ls -lh "$DESTINO"/*.sql.gz 2>/dev/null || echo "(sin respaldos en $DESTINO)"
    ;;

  *)
    sed -n '2,15p' "$0"
    exit 1
    ;;
esac
