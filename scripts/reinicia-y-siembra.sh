#!/usr/bin/env bash
#
# Reinicia el stack desde cero y regenera toda la data de DEMOSTRACIÓN: datos mock para enseñarle al
# cliente, muchos y con historia, pero correctos: cada saldo explicado por sus asientos, cada póliza
# por su hecho, cada estado de cuenta por su libro. Es un objetivo distinto del de las pruebas E2E
# (`tools/ciclo-credito`), que sólo siembran configuración y hacen todo por las operaciones.
#
# Cómo consigue historia sin inventarla: pone el reloj de negocio MESES atrás, origina ahí la cartera
# por el journey real, y después la hace vivir día por día con la cadena nocturna real (foto, devengo,
# cierre, corte, estados de cuenta, riesgo, cobranza, facturación, cierre contable) hasta ayer. Nada
# se inserta, ningún calendario se retrocede: la mora nace de no pagar.
#
# ⚠️  BORRA LOS VOLÚMENES. Todo lo que haya en la base se pierde y no hay vuelta atrás.
#
#     ./scripts/reinicia-y-siembra.sh              # ciclo completo (~1 h 15 min)
#     ./scripts/reinicia-y-siembra.sh --mini       # siembra chica, auditable a mano (~25 min)
#     ./scripts/reinicia-y-siembra.sh --build      # reconstruye las imágenes antes (en serie)
#     ./scripts/reinicia-y-siembra.sh --clientes 20 --meses 4
#
# Sin `--build` se usan las imágenes como están. Las migraciones viajan dentro de la imagen: tras
# editar código o migraciones hay que reconstruir, o la siembra corre sobre la versión anterior.
#
set -euo pipefail
cd "$(dirname "$0")/.."

CLIENTES=100
# Meses de historia. Ocho alcanzan para que exista todo el recorrido: mora de 30, 60, 90 y 180+
# días, promesas, reestructuras y quebrantos reales (el umbral de quebranto son 181 días de atraso, y
# la primera cuota vence un mes después del alta).
MESES_HISTORIA=8
DISTRIBUIDORAS=10
BENEFICIARIOS=5
REVOLVENTES=12
BUILD=""
EQUILIBRADO=""
for arg in "$@"; do
  case "$arg" in
    --build)      BUILD="--build" ;;
    --sin-build)  BUILD="" ;;
    --clientes)   shift; CLIENTES="${1:-100}" ;;
    --clientes=*) CLIENTES="${arg#*=}" ;;
    --meses=*)    MESES_HISTORIA="${arg#*=}" ;;
    # Siembra chica, para auditar a mano: 5 distribuidoras x 5 clientes y unos cinco créditos de cada
    # producto, cuatro meses de vida. Sigue habiendo mora, pagos, cortes y cierres; no hay quebrantos
    # (necesitan 181 días de atraso).
    --mini)
      CLIENTES=30; DISTRIBUIDORAS=5; BENEFICIARIOS=5; REVOLVENTES=7
      MESES_HISTORIA=4; EQUILIBRADO="--equilibrado" ;;
    --distribuidoras=*) DISTRIBUIDORAS="${arg#*=}" ;;
    --beneficiarios=*)  BENEFICIARIOS="${arg#*=}" ;;
  esac
done

paso() { printf "\n\033[1m▸ %s\033[0m\n" "$1"; }
RELOJ="python3 tools/ciclo-credito/corre.py"

# El primer día del mes que arranca la historia, y ayer, en la zona de negocio.
read -r INICIO AYER <<<"$(python3 - "$MESES_HISTORIA" <<'EOF'
import datetime, sys
from zoneinfo import ZoneInfo
hoy = datetime.datetime.now(ZoneInfo("America/Mexico_City")).date()
primero = hoy.replace(day=1)
for _ in range(int(sys.argv[1]) - 1):
    primero = (primero - datetime.timedelta(days=1)).replace(day=1)
print(primero.isoformat(), (hoy - datetime.timedelta(days=1)).isoformat())
EOF
)"
echo "  historia: del $INICIO a $AYER · $CLIENTES clientes · $DISTRIBUIDORAS distribuidoras × $BENEFICIARIOS · $REVOLVENTES revolventes"

# El orden importa: identity antes que nadie (todo se autentica contra él), la estructura comercial
# antes que la cartera (cada cliente cuelga de un ejecutivo), el reloj antes de originar (la fecha
# de activación es la que decide desde cuándo devenga y cuándo corta).
./scripts/reinicia-entorno.sh $BUILD

paso "Poniendo el reloj de negocio en $INICIO para originar en el pasado"
$RELOJ reloj "$INICIO"
# If anything below dies, the services must not stay in the past.
trap '$RELOJ reloj-real >/dev/null 2>&1 || true' EXIT

paso "Clientes, solicitudes y cartera por el journey real (~15 min)"
# --mora 0: nada se envejece a mano; la mora la produce el ciclo de vida.
python3 scripts/seed-portfolio.py --clientes "$CLIENTES" --mora 0 $EQUILIBRADO

paso "Documentos del expediente"
python3 scripts/seed-expedientes.py --limit 12

paso "Distribuidoras B2B2C: $DISTRIBUIDORAS con $BENEFICIARIOS clientes cada una (~6 min)"
python3 scripts/seed-distribuidoras.py --distribuidoras "$DISTRIBUIDORAS" --clientes "$BENEFICIARIOS" --sin-envejecer

paso "Tarjetas y líneas revolventes: compras, meses sin intereses, BNPL, salto de pago"
python3 scripts/seed-revolventes.py --clientes "$REVOLVENTES" --hoy "$INICIO" || echo "    (la siembra revolvente terminó con avisos)"

# El perfil fiscal va ANTES del ciclo de vida: el ciclo corre la facturación, y sin RFC los CFDI
# salen todos como «PUBLICO EN GENERAL».
paso "Perfiles fiscales CFDI de los clientes"
python3 scripts/seed-perfiles-fiscales.py

paso "Ciclo de vida: $MESES_HISTORIA meses día por día (pagos, mora, cortes, estados de cuenta, riesgo, cobranza, facturación, cierres)"
python3 scripts/seed-ciclo-credito.py --desde "$INICIO" --hasta "$AYER"
$RELOJ reloj-real
trap - EXIT

# El canal cachea la identidad del personal diez minutos; reiniciarlo deja la navegación de abajo con
# la identidad completa. Reiniciarlo le cambia la IP: el gateway se recarga para no servir 502.
paso "Reiniciando el canal para vaciar la caché de identidad"
docker restart fintech-services-channel-backoffice-service-1 >/dev/null 2>&1
docker compose exec -T gateway-service openresty -s reload >/dev/null 2>&1 || true
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://backoffice.localhost:8090/auth/staff/login \
    -H 'Content-Type: application/json' -d '{"email":"admin@kredius.mx","password":"Backoffice#2026"}' 2>/dev/null || true)
  [ "$code" = "200" ] && break
  sleep 3
done

paso "Navegando con cada nivel comercial, para que la bitácora tenga de todos"
set -f
for u in admin@kredius.mx analista@kredius.mx ejecutivo@kredius.mx comite@kredius.mx \
         producto@kredius.mx nacional@kredius.mx region@kredius.mx \
         zona@kredius.mx sucursal@kredius.mx ejecutivo.cul@kredius.mx; do
  tok=$(curl -s -X POST http://backoffice.localhost:8090/auth/staff/login \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$u\",\"password\":\"Backoffice#2026\"}" \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)
  if [ -z "$tok" ]; then echo "    ✗ no entró $u" >&2; continue; fi
  for p in "/dashboard/summary" "/clients?page=0&size=5" "/portfolio?page=0&size=5" \
           "/origination/applications" "/sales-org/units" "/staff"; do
    curl -s -o /dev/null "http://backoffice.localhost:8090${p}" -H "Authorization: Bearer $tok" || true
  done
  echo "    ✓ $u"
done
set +f
sleep 4

paso "Verificando la identidad en la bitácora"
python3 scripts/verifica-identidad-auditoria.py

paso "Verificando la siembra de distribuidoras"
python3 scripts/verifica-distribuidoras.py

# Que el tablero, el árbol comercial y el listado digan el mismo número: atrapa la cartera huérfana.
paso "Verificando que el tablero y el árbol comercial cuadren"
python3 scripts/repara-cartera-sin-ejecutivo.py
# Origin units again, now that every credit has an executive: the sealing inside seed-ciclo-credito ran before
# the repair, and the revolving and distributor credits seeded after the portfolio's distribution were left out
# (2 Nov: 8 of 74 unsealed, the commercial tree short by the whole capital).
python3 - <<'EOF'
import sys; sys.path.insert(0, "tools/ciclo-credito")
import motor
r = motor.bo(motor.login_admin(), "POST", "/accounting/backfill-origin-units") or {}
print(f"  sucursal de origen: {r.get('sellados', 0)} sellados ahora · {r.get('yaSellados', 0)} ya sellados · {r.get('polizasAtribuidas', 0)} pólizas atribuidas")
EOF
python3 scripts/verifica-cuadre.py

# Y que la contabilidad diga lo mismo que la fuente de cada hecho, y que toda la cadena concilie:
# libro = saldos, cargos = cartera, foto = cargo, corte = foto, estado de cuenta = libro.
paso "Verificando la contabilidad contra la fuente de cada hecho"
python3 scripts/verifica-contabilidad.py

paso "Conciliando la cadena completa"
docker compose exec -T postgres psql -U fintech -d fintech < tools/ciclo-credito/concilia.sql
# The demo is a mock with history, but squared: every row must show zero, as the E2E's verdict asks.
FUERA=$(docker compose exec -T postgres psql -U fintech -d fintech -At -F'|' -v ON_ERROR_STOP=1 < tools/ciclo-credito/concilia.sql \
        | awk -F'|' 'NF >= 3 && $2 != "" && $2 + 0 != 0 { print "  ✗ " $1 ": " $2 " (" substr($3, 1, 160) ")" }')

# Respaldo automático: la siembra cuesta una hora y perderla por un `down -v` a destiempo es evitable.
paso "Respaldando la base recién sembrada"
./scripts/respalda-siembra.sh guardar "siembra-${CLIENTES}c" || echo "  (respaldo omitido)"

if [ -n "$FUERA" ]; then
  echo
  echo "✗ la demo no cuadra: la conciliación de la cadena tiene filas fuera de cero"
  echo "$FUERA"
  exit 1
fi

echo
echo "✓ demo sembrada: cartera originada el $INICIO y viva hasta $AYER"
