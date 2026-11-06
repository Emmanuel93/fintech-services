#!/usr/bin/env bash
#
# Deja el stack local en un entorno virgen con SÓLO configuración: catálogo, políticas y calendarios
# (viajan en las migraciones), estructura comercial y personal. Ni un cliente, ni un crédito.
#
# Es la base de las dos siembras, que tienen objetivos distintos y no se mezclan:
#   - pruebas E2E del ciclo de crédito: `tools/ciclo-credito/corre.py preparar` llama a este script con
#     `--solo-plataforma` y siembra SU propia configuración (`tools/ciclo-credito/configura.py`), fija y
#     determinista; después todo ocurre por las operaciones reales, con el reloj de negocio día por día;
#   - demo para el cliente: `scripts/reinicia-y-siembra.sh` (datos mock, muchos, con historia), que usa la
#     configuración de demo de abajo (estructura comercial completa, personal de demostración).
#
# ⚠️  BORRA LOS VOLÚMENES del stack. Lo que haya en la base se pierde.
#
#     ./scripts/reinicia-entorno.sh            # imágenes como están
#     ./scripts/reinicia-entorno.sh --build    # reconstruye antes, en serie, todo servicio con `build`
#     ./scripts/reinicia-entorno.sh --solo-plataforma   # sin la configuración de demo (la usa el E2E)
#
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD=0
SOLO_PLATAFORMA=0
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --solo-plataforma) SOLO_PLATAFORMA=1 ;;
  esac
done

paso() { printf "\n\033[1m▸ %s\033[0m\n" "$1"; }
login() {
  curl -s -X POST http://backoffice.localhost:8090/auth/staff/login -H 'Content-Type: application/json' \
    -d '{"email":"admin@kredius.mx","password":"Backoffice#2026"}' 2>/dev/null \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true
}

if [ "$BUILD" = "1" ]; then
  paso "Reconstruyendo las imágenes, en serie"
  # Parallel Gradle builds exhaust the Docker VM's memory; the list comes from the compose file.
  SERVICIOS=$(docker compose config --format json 2>/dev/null \
    | python3 -c "import sys,json;c=json.load(sys.stdin);print(' '.join(sorted(k for k,v in c.get('services',{}).items() if v.get('build'))))")
  for s in $SERVICIOS; do
    printf "    %s… " "$s"
    if docker compose build "$s" >"/tmp/build-$s.log" 2>&1 || docker compose build "$s" >>"/tmp/build-$s.log" 2>&1; then
      echo ok
    else
      echo "FALLÓ"; tail -25 "/tmp/build-$s.log" >&2; exit 1
    fi
  done
fi

paso "Bajando el stack y BORRANDO los volúmenes"
docker rm -f ciclo-credito-puente >/dev/null 2>&1 || true
docker compose down -v >/dev/null 2>&1
echo "    volúmenes eliminados"

paso "Levantando Kafka y creando los tópicos con particiones"
# A consumer that subscribes before the owner creates its topic makes the broker auto-create it with one
# partition; the owner then raises it to 12 and the consumer does not see the new partitions until its next
# metadata refresh (5 min). On 2 Nov the portfolio billed the first distributor cutoffs a minute late and the
# statement asked for the same installments twice. In production the topics are provisioned beforehand; here too.
docker compose up -d zookeeper kafka >/dev/null 2>&1
for i in $(seq 1 60); do
  docker compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list >/dev/null 2>&1 && break
  sleep 2
done
for t in closing.cutoff-due closing.cutoff-closed charges.accrual-due charges.account-accrued statement.statement-generated; do
  docker compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic "$t" --partitions 12 --replication-factor 1 >/dev/null 2>&1 || { echo "    ✗ no se pudo crear el tópico $t"; exit 1; }
done
echo "    $(docker compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --describe 2>/dev/null | grep -c 'PartitionCount: 12') tópicos con 12 particiones"

paso "Levantando el stack"
docker compose up -d >/dev/null 2>&1

# nginx resolves every upstream when it loads and caches the IPs: after a recreate it points to the old
# ones and the whole backoffice answers 502 with every service up. A reload is cheap.
paso "Esperando al gateway y al backoffice"
for i in $(seq 1 20); do
  docker compose exec -T gateway-service openresty -s reload >/dev/null 2>&1 && break
  sleep 3
done
token=""
for i in $(seq 1 100); do
  token=$(login)
  if [ -n "$token" ]; then echo "    login listo tras $((i * 5))s"; break; fi
  if [ $((i % 6)) = 0 ]; then docker compose exec -T gateway-service openresty -s reload >/dev/null 2>&1 || true; fi
  if [ "$i" = "100" ]; then echo "    ✗ el stack no respondió en 500s" >&2; exit 1; fi
  sleep 5
done

# Identity answering does not mean the rest is up; the seeds talk to sales-org, party and the catalog.
echo "    esperando a los servicios que la configuración necesita…"
set -f
for ruta in "/sales-org/levels" "/products" "/clients?page=0&size=1" "/accounting/periods"; do
  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://backoffice.localhost:8090${ruta}" -H "Authorization: Bearer $token" 2>/dev/null || true)
    if [ "$code" = "200" ]; then printf "      %-28s ok\n" "$ruta"; break; fi
    if [ "$i" = "60" ]; then echo "      ✗ $ruta sigue en $code tras 300s" >&2; exit 1; fi
    sleep 5
  done
done
set +f
# Every service with test support must answer before a run shifts its clock.
for s in credit-portfolio-service charges-service closing-service statement-service accounting-service payments-service \
         origination-service collections-service risk-service commission-service invoicing-service scoring-service \
         beneficiary-service wallet-service; do
  for i in $(seq 1 60); do
    code=$(docker compose exec -T "$s" wget -qO- --server-response "http://localhost:8080/internal/test-support/clock" 2>&1 | grep -c "HTTP/1.1 200" || true)
    [ "$code" != "0" ] && break
    [ "$i" = "60" ] && { echo "      ✗ $s no expone el reloj de pruebas (¿TEST_SUPPORT_ENABLED=true en .env? ¿imagen vieja?)" >&2; exit 1; }
    sleep 3
  done
done
echo "      reloj de pruebas en los 14 servicios  ok"

if [ "$SOLO_PLATAFORMA" = "1" ]; then
  # The catalog, the policies and the calendars came with the migrations; the structure and the staff are the
  # caller's to seed. The E2E seeds its own, fixed (tools/ciclo-credito/configura.py).
  echo
  echo "✓ plataforma virgen: migraciones aplicadas, sin estructura comercial ni personal de demo"
  exit 0
fi

paso "Configuración: estructura comercial (regiones, zonas, sucursales, ejecutivos)"
python3 scripts/seed-sales-org.py

paso "Configuración: un responsable por nivel comercial"
python3 scripts/seed-commercial-staff.py

paso "Configuración: los roles funcionales (analista, ejecutivo, comité, producto)"
python3 scripts/seed-demo-staff.py

BO_SEED="$(dirname "$0")/../../fintech-backoffice-web/tools/e2e/01-seed.sh"
if [ -x "$BO_SEED" ]; then
  paso "Configuración: un empleado por rol (ops, underwriter, cobranza, finanzas, auditor…)"
  (cd "$(dirname "$BO_SEED")" && ./01-seed.sh) || echo "    (la siembra de personal del backoffice terminó con avisos)"
fi

paso "Lo que quedó"
docker compose exec -T postgres psql -U fintech -d fintech -tA -c "
  SELECT 'productos activos: ' || count(*) FROM credit_product.credit_product_definitions WHERE status = 'ACTIVE';
  SELECT 'políticas de scoring: ' || count(*) FROM scoring.scoring_policies WHERE active;
  SELECT 'políticas de reserva: ' || count(*) FROM risk.provision_policies;
  SELECT 'políticas de corte: ' || count(*) FROM closing.close_cycle_policies;
  SELECT 'reglas de posteo: ' || count(*) FROM accounting.posting_rules;
  SELECT 'unidades comerciales: ' || count(*) FROM sales_org.org_units;
  SELECT 'personal: ' || count(*) FROM identity.staff_users;
  SELECT 'clientes: ' || count(*) FROM party.parties;
  SELECT 'créditos: ' || count(*) FROM credit_portfolio.credit_accounts;" 2>/dev/null | sed 's/^/    /'
echo
echo "✓ entorno virgen con configuración"
