#!/usr/bin/env bash
#
# Reinicia el stack desde cero y regenera toda la data de demostración.
#
# Un backoffice contra una base a medias no se puede revisar, y una bitácora sembrada a medias es
# peor: parece que el sistema no identifica a nadie cuando lo que pasa es que la data se creó antes
# de que existiera el campo. Este script deja el entorno en un estado del que uno se puede fiar,
# recorriendo el mismo camino que recorrería una persona.
#
# ⚠️  BORRA LOS VOLÚMENES. Todo lo que haya en la base se pierde y no hay vuelta atrás.
#
#     ./scripts/reinicia-y-siembra.sh              # ciclo completo (~30 min)
#     ./scripts/reinicia-y-siembra.sh --mini       # siembra chica, auditable a mano (~12 min)
#     ./scripts/reinicia-y-siembra.sh --sin-build  # si las imágenes ya están al día
#     ./scripts/reinicia-y-siembra.sh --clientes 20
#
# `--sin-build` sólo vale si no tocaste código **ni migraciones**. Las migraciones viajan dentro de
# la imagen, así que saltarse el build tras editar una siembra la base con la versión anterior: el
# esquema queda bien y el dato que la migración debía dejar, no. Cuesta un rato de build y ahorra
# media hora de perseguir un campo vacío que en el fuente sí está.
#
set -euo pipefail
cd "$(dirname "$0")/.."

CLIENTES=100
# Meses de historia contable, contando el actual. Tres es el mínimo que enseña algo: dos cerrados
# para comparar y el corriente abierto para trabajar.
MESES_HISTORIA=3
DISTRIBUIDORAS=10
BENEFICIARIOS=5
BUILD=1
EQUILIBRADO=""
for arg in "$@"; do
  case "$arg" in
    --sin-build) BUILD=0 ;;
    --clientes)  shift; CLIENTES="${1:-100}" ;;
    --clientes=*) CLIENTES="${arg#*=}" ;;
    --meses=*)    MESES_HISTORIA="${arg#*=}" ;;
    # Siembra chica, para auditar a mano.
    #
    # Con cien clientes y tres meses de devengo diario salen ~15 000 pólizas: la consola se ve bien
    # y ningún número se puede verificar con una calculadora. Este preset baja el volumen a un orden
    # que una persona puede recorrer entero —5 distribuidoras x 5 clientes, y unos cinco créditos de
    # cada producto B2C— sin quitar ningún caso: sigue habiendo mora, quebranto, pagos y cierres.
    #
    # Dos meses en vez de tres porque el tercero multiplica las pólizas sin añadir un caso nuevo:
    # con uno cerrado y uno abierto ya se puede comparar y ver un cierre.
    --mini)
      CLIENTES=30; DISTRIBUIDORAS=5; BENEFICIARIOS=5
      MESES_HISTORIA=2; EQUILIBRADO="--equilibrado" ;;
    --distribuidoras=*) DISTRIBUIDORAS="${arg#*=}" ;;
    --beneficiarios=*)  BENEFICIARIOS="${arg#*=}" ;;
  esac
done

paso() { printf "\n\033[1m▸ %s\033[0m\n" "$1"; }

# El orden importa y no es arbitrario: identity tiene que estar en pie antes que nadie porque todo
# lo demás se autentica contra él, y la estructura comercial antes que la cartera porque cada
# cliente se cuelga de un ejecutivo que debe existir.

if [ "$BUILD" = "1" ]; then
  paso "Reconstruyendo las imágenes de los servicios tocados"
  # Secuencial a propósito: en paralelo, cuatro builds de Gradle a la vez agotan la memoria de la
  # VM de Docker y el fallo se manifiesta como un compilador que muere sin explicar por qué.
  # La lista se queda corta sola, y falla de la peor manera: el servicio arranca, contesta, y sirve
  # código viejo. Un endpoint agregado hace un rato devuelve 404 y parece un problema de rutas; un
  # campo nuevo del evento llega nulo y el consumidor cae a su valor por defecto sin quejarse. Se
  # perdió una tarde en eso con `wallet` y `commission`, que no estaban aquí.
  #
  # Ante la duda, agrega el servicio: reconstruir de más cuesta minutos, y depurar una imagen
  # rezagada cuesta la confianza en todo lo que se vio en pantalla.
  #
  # Por eso **ya no hay lista**. La había, escrita a mano, y se quedó corta exactamente como
  # advertía el párrafo de arriba: faltaban `beneficiary-service` —que llevaba semanas con una
  # imagen que ni siquiera compilaba—, `payments-service`, `channels-service`,
  # `configuration-service` y el propio `gateway-service`, cuya imagen era de junio contra un
  # fuente de agosto. Se deriva de `docker-compose.yml`: todo servicio con sección `build` entra,
  # y agregar un servicio nuevo al compose lo mete aquí sin que nadie se acuerde de tocar esto.
  #
  # No cuesta lo que parece: Docker cachea por contenido, así que un servicio sin cambios se
  # resuelve en segundos y sólo recompila lo que de verdad se movió.
  SERVICIOS=$(docker compose config --format json 2>/dev/null \
    | python3 -c "import sys,json;c=json.load(sys.stdin);print(' '.join(sorted(k for k,v in c.get('services',{}).items() if v.get('build'))))" 2>/dev/null || true)
  if [ -z "$SERVICIOS" ]; then
    echo "  ✗ no se pudo leer la lista de servicios de docker-compose.yml" >&2
    exit 1
  fi
  echo "    $(echo "$SERVICIOS" | wc -w | tr -d ' ') servicios con imagen propia"
  for s in $SERVICIOS; do
    printf "    %s… " "$s"
    # `cmd && echo ok` NO aborta con `set -e`: la excepción POSIX dice que en una lista `&&` sólo
    # cuenta el último comando. Así estaba escrito, y un build que moría se tragaba en silencio —
    # el script seguía, levantaba el stack con la imagen anterior, y el síntoma aparecía horas
    # después como un endpoint que responde 404 con el código delante. Exactamente lo que este
    # bloque advierte en el comentario de arriba y no hacía cumplir.
    #
    # El reintento es porque el fallo típico no es de código: cuatro builds de Gradle seguidos
    # agotan la memoria de la VM de Docker y el compilador muere sin explicar por qué. A la segunda
    # pasa. Si falla dos veces, se para y se enseña el error en vez de sembrar sobre código viejo.
    if docker compose build "$s" >"/tmp/build-$s.log" 2>&1; then
      echo "ok"
    elif docker compose build "$s" >>"/tmp/build-$s.log" 2>&1; then
      echo "ok (al segundo intento)"
    else
      echo "FALLÓ"
      echo "  ✗ $s no compiló. Últimas líneas de /tmp/build-$s.log:" >&2
      tail -25 "/tmp/build-$s.log" >&2
      exit 1
    fi
  done
fi

paso "Bajando el stack y BORRANDO los volúmenes"
docker compose down -v >/dev/null 2>&1
echo "    volúmenes eliminados"

paso "Levantando el stack"
docker compose up -d >/dev/null 2>&1

# nginx resuelve TODOS los upstreams al cargar la configuración y cachea las IPs. Al recrear
# contenedores las IPs cambian, y el gateway sigue apuntando a las viejas: el síntoma es un 502 en
# todo el backoffice con los servicios perfectamente arriba, que se diagnostica como «el BFF está
# caído» y no lo está. Recargar es barato; perseguir ese 502 no.
paso "Recargando el gateway para que resuelva las IPs nuevas"
for i in $(seq 1 20); do
  docker compose exec -T gateway-service openresty -s reload >/dev/null 2>&1 && break
  sleep 3
done

# Y se comprueba que de verdad contesta antes de seguir. Sembrar contra un gateway que no resuelve
# produce cientos de fallos que parecen de la siembra.
paso "Esperando a que el gateway conteste"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST http://backoffice.localhost:8090/auth/staff/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"admin@kredius.mx","password":"Backoffice#2026"}' 2>/dev/null || true)
  if [ "$code" = "200" ]; then echo "    gateway ✓"; break; fi
  if [ "$code" = "502" ] && [ $((i % 5)) = 0 ]; then
    docker compose exec -T gateway-service openresty -s reload >/dev/null 2>&1 || true
  fi
  sleep 3
done

echo "    esperando a que el backoffice acepte credenciales…"
token=""
for i in $(seq 1 80); do
  token=$(curl -s -X POST http://backoffice.localhost:8090/auth/staff/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"admin@kredius.mx","password":"Backoffice#2026"}' 2>/dev/null \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)
  if [ -n "$token" ]; then echo "    login listo tras $((i * 5))s"; break; fi
  if [ "$i" = "80" ]; then echo "    ✗ el stack no respondió en 400s" >&2; exit 1; fi
  sleep 5
done

# Que identity conteste no significa que el resto esté en pie. Los seeds hablan con sales-org,
# party y el catálogo de productos, y cada uno arranca a su ritmo —migraciones incluidas—. Esperar
# sólo al login hacía que la siembra empezara contra un sales-org a medio levantar y muriera con un
# 500 a los diez segundos, después de haber borrado ya los volúmenes: lo peor de los dos mundos.
echo "    esperando a los servicios que la siembra necesita…"
set -f
for ruta in "/sales-org/levels" "/products" "/clients?page=0&size=1"; do
  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w "%{http_code}" \
      "http://backoffice.localhost:8090${ruta}" -H "Authorization: Bearer $token" 2>/dev/null || true)
    if [ "$code" = "200" ]; then printf "      %-28s ok\n" "$ruta"; break; fi
    if [ "$i" = "60" ]; then echo "      ✗ $ruta sigue en $code tras 300s" >&2; exit 1; fi
    sleep 5
  done
done
set +f

paso "Estructura comercial (4 regiones, 8 zonas, 22 sucursales, 52 ejecutivos)"
python3 scripts/seed-sales-org.py

paso "Un responsable por nivel, para validar el alcance"
python3 scripts/seed-commercial-staff.py

paso "Los roles funcionales del acceso rápido (analista, ejecutivo, comité, producto)"
python3 scripts/seed-demo-staff.py

paso "Clientes, solicitudes y cartera por el journey real (~15 min)"
python3 scripts/seed-portfolio.py --clientes "$CLIENTES" $EQUILIBRADO

paso "Documentos del expediente"
python3 scripts/seed-expedientes.py --limit 12

# Las distribuidoras van después de la cartera y antes del ciclo de vida. Después de la cartera
# porque cuelgan de ejecutivos que tienen que existir; antes del ciclo, porque sus cortes impagos
# tienen que estar puestos cuando corra el envejecido — si no, las diez salen con cero días de
# atraso y el caso «distribuidora morosa» no se puede enseñar.
paso "Distribuidoras B2B2C: $DISTRIBUIDORAS con $BENEFICIARIOS clientes cada una (~6 min)"
python3 scripts/seed-distribuidoras.py \
  --distribuidoras "$DISTRIBUIDORAS" --clientes "$BENEFICIARIOS"

# El perfil fiscal va ANTES del ciclo de vida, porque el ciclo es quien corre la facturación. Al
# revés, los CFDI se timbran contra el RFC genérico y salen todos como «PUBLICO EN GENERAL»: no
# falla nada, y por eso es peor — la pantalla de facturas se ve llena y sin dueño.
paso "Perfiles fiscales CFDI de los clientes"
python3 scripts/seed-perfiles-fiscales.py

# El ciclo de vida, después de que exista cartera y antes de la navegación: sin devengo no hay
# ingreso que contabilizar, sin pagos no baja el auxiliar de intereses, sin mora no hay estimación
# preventiva, sin quebranto nada consume la reserva y sin corrida de facturación no existe una sola
# factura. Una base sin esto enseña media contabilidad en ceros y no se puede distinguir «esta
# cuenta no se usa» de «esta cuenta no se está posteando».
#
# Construye MESES de historia, no días: retrocede el reloj del devengo y lo corre fecha por fecha,
# cerrando cada período y facturándolo. Es lo que hace que junio, julio y agosto tengan cifras
# distintas en vez de un único día apilado en el mes corriente.
paso "Ciclo de vida: $MESES_HISTORIA meses de devengo, pagos, mora, riesgo, facturación y cierre (~12 min)"
python3 scripts/seed-ciclo-credito.py --meses "$MESES_HISTORIA"

# El canal cachea la identidad del personal diez minutos, así que las entradas escritas durante la
# siembra llevan lo que se supiera al empezarla. Reiniciarlo vacía esa caché y la navegación de
# abajo queda con la identidad ya completa — que es lo que se quiere enseñar.
paso "Reiniciando el canal para vaciar la caché de identidad"
docker restart fintech-services-channel-backoffice-service-1 >/dev/null 2>&1
# Reiniciar el canal le cambia la IP: sin recargar, el gateway sigue apuntando a la anterior y todo
# lo que viene después de este paso responde 502.
docker compose exec -T gateway-service openresty -s reload >/dev/null 2>&1 || true
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST http://backoffice.localhost:8090/auth/staff/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"admin@kredius.mx","password":"Backoffice#2026"}' 2>/dev/null || true)
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

# Que el tablero, el árbol comercial y el listado digan el mismo número. Es la comprobación que
# atrapa la cartera huérfana: un crédito sin ejecutivo no cuelga de ninguna rama, y entonces el
# árbol suma menos que el tablero sin que nada falle.
paso "Verificando que el tablero y el árbol comercial cuadren"
python3 scripts/repara-cartera-sin-ejecutivo.py
python3 scripts/verifica-cuadre.py

# Y que la contabilidad diga lo mismo que la fuente de cada hecho. Es una comprobación distinta de
# la de arriba: la balanza cuadra igual cuando el importe está mal —cada asiento es un par que
# cuadra consigo mismo—, así que sólo salirse del mayor y preguntarle a `charges` detecta un devengo
# multiplicado. Es el error que estuvo quince veces inflado sin que ninguna pantalla lo delatara.
paso "Verificando la contabilidad contra la fuente de cada hecho"
python3 scripts/verifica-contabilidad.py

# Respaldo automático: la siembra cuesta horas y perderla por un `down -v` a destiempo es evitable.
paso "Respaldando la base recién sembrada"
./scripts/respalda-siembra.sh guardar "siembra-${CLIENTES}c" || echo "  (respaldo omitido)"
