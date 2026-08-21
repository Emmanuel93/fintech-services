#!/usr/bin/env bash
#
# Regresión E2E — app · backoffice · servicios, con evidencia en video de las dos interfaces.
#
#   ./run.sh                      # los tres casos
#   ./run.sh --caso 1             # sólo uno (1, 2 o 3)
#   ./run.sh --quincenas 6        # cuántas quincenas devenga antes de cerrar
#   ./run.sh --sin-reporte
#
# Requiere, en la Mac: docker compose arriba en fintech-services, simulador de iOS booteado,
# flutter, node/npx, jq. Ver README.md.

set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh

QUINCENAS=4
CASOS="1 2 3"
CON_REPORTE=1

# La evidencia se abre antes de parsear argumentos: si `die` salta ahí, `_paso` ya tiene dónde
# escribir en vez de reventar por una redirección a un directorio inexistente.
# La evidencia se archiva por commit y, dentro, por corrida: `evidencia/<commit>/<fecha>/`.
# Un video sin saber contra qué código se grabó no prueba nada —el mismo caso pasa o falla según
# el commit—, así que el sha va en la ruta y no en un README que nadie actualiza.
COMMIT="$(git -C "$PWD" rev-parse --short HEAD 2>/dev/null || echo sin-commit)"
git -C "$PWD" diff --quiet HEAD 2>/dev/null || COMMIT="$COMMIT-sucio"
CORRIDA="$(date +%Y%m%d-%H%M%S)"
EVIDENCIA="$PWD/evidencia/$COMMIT/$CORRIDA"
mkdir -p "$EVIDENCIA"
: > "$EVIDENCIA/pasos.jsonl"
: > "$EVIDENCIA/api.log"

necesita_valor() { [ $# -ge 2 ] || die "«$1» necesita un valor"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --caso)       necesita_valor "$@"; CASOS="$2"; shift 2 ;;
    --quincenas)  necesita_valor "$@"; QUINCENAS="$2"; shift 2 ;;
    --sin-reporte) CON_REPORTE=0; shift ;;
    -h|--help)    sed -n '2,12p' "$0"; exit 0 ;;
    *) die "opción desconocida: $1" ;;
  esac
done


# ── Restauración garantizada ─────────────────────────────────────────────────
# La banda de scoring se toca para poder forzar revisión manual por el camino real. Si no se
# restaura, la base local queda mintiendo: todo lo que se pida después cae en manual y nadie sabrá
# por qué. El trap cubre Ctrl-C y el error; `kill -9` no, y por eso el valor original también se
# escribe a disco.
BANDA_BAK="$EVIDENCIA/scoring-threshold.bak"
restaurar_banda() {
  [ -f "$BANDA_BAK" ] || return 0
  local prod valor
  while IFS='|' read -r prod valor; do
    [ -z "$prod" ] && continue
    psql_q "UPDATE scoring.risk_thresholds t SET min_score = $valor
              FROM scoring.scoring_policies p
             WHERE p.policy_id = t.policy_id AND p.active
               AND p.product_type_intent = '$prod' AND t.risk_level = 'BAJO';" >/dev/null
    log "Banda BAJO de $prod restaurada a $valor"
  done < "$BANDA_BAK"
  rm -f "$BANDA_BAK"
}
trap restaurar_banda EXIT
# Sin salir explícitamente, un SIGINT restaura y **sigue corriendo** desde donde estaba.
trap 'restaurar_banda; exit 130' INT TERM

forzar_banda_manual() { # producto — sube BAJO a un valor inalcanzable
  local prod="$1" actual
  actual=$(psql_q "SELECT t.min_score FROM scoring.risk_thresholds t
                     JOIN scoring.scoring_policies p ON p.policy_id = t.policy_id
                    WHERE p.active AND p.product_type_intent = '$prod' AND t.risk_level = 'BAJO';")
  [ -z "$actual" ] && die "no encontré la banda BAJO de $prod — ¿está sembrada la política?"
  echo "$prod|$actual" >> "$BANDA_BAK"
  psql_q "UPDATE scoring.risk_thresholds t SET min_score = 1000000
            FROM scoring.scoring_policies p
           WHERE p.policy_id = t.policy_id AND p.active
             AND p.product_type_intent = '$prod' AND t.risk_level = 'BAJO';" >/dev/null
  ok "Banda BAJO de $prod elevada (era $actual) — todo cae en MEDIO → MANUAL_REVIEW"
}

# ── Preflight ────────────────────────────────────────────────────────────────
preflight() {
  log "Comprobando el entorno"
  for bin in docker jq flutter npx xcrun; do
    command -v "$bin" >/dev/null || die "falta «$bin» en el PATH"
  done
  [ -d "$APP_DIR/packages/fa_shell" ] || die "no encuentro la app en $APP_DIR"
  [ -d "$BO_DIR/tools/e2e" ]          || die "no encuentro el backoffice en $BO_DIR"

  local health; health=$(curl -sS -m 5 "http://localhost:$GATEWAY_PORT/health" 2>/dev/null)
  afirma_contiene "El gateway responde" '"status":"ok"' "$health"
  [ "$FALLOS" -gt 0 ] && die "levanta el stack: (cd $SERVICES_DIR && docker compose up -d)"

  local tok; tok=$(bo_login "$UNDERWRITER_EMAIL")
  [ -n "$tok" ] || die "no pude entrar como $UNDERWRITER_EMAIL — corre tools/e2e/01-seed.sh del backoffice"
  # El agente de cobranza lo siembra 01-seed.sh del backoffice, no seed-demo-staff.py de este
  # repo; sin él el acto 5 del caso 3 falla al final, después de media hora de corrida.
  [ -n "$(bo_login cobranza@kredius.mx)" ] \
    || die "falta cobranza@kredius.mx — corre tools/e2e/01-seed.sh del backoffice"
  ok "Login de staff correcto (underwriter y cobranza)"

  # `rewind-accrual-schedules` exige `date`: con test-support encendido responde 400 (falta el
  # parámetro) y apagado responde 404 (el bean no existe). Un 404 en `route-to-review` no
  # distinguiría las dos cosas, que es el falso verde clásico de este preflight.
  local ts; ts=$(svc_code POST "$CHARGES/internal/test-support/rewind-accrual-schedules")
  case "$ts" in
    400) ok "test-support habilitado (charges responde 400 por el parámetro que falta)" ;;
    404) die "test-support apagado — pon TEST_SUPPORT_ENABLED=true en .env y recrea los contenedores" ;;
    "" ) die "no alcancé la red de Docker ($DOCKER_NETWORK) — revisa DOCKER_NETWORK" ;;
    *  ) warn "respuesta inesperada de test-support: HTTP $ts" ;;
  esac

  xcrun simctl bootstatus "$SIM_ID" -b >/dev/null 2>&1 \
    || { log "Booteando el simulador $SIM_ID"; xcrun simctl boot "$SIM_ID" 2>/dev/null; open -a Simulator; sleep 12; }
  ok "Simulador listo"

  # El servidor de desarrollo del backoffice, si no está ya arriba.
  if ! curl -sS -m 3 -o /dev/null "http://localhost:4200"; then
    log "Levantando el backoffice (run-dev.sh)"
    ( cd "$BO_DIR" && ./run-dev.sh > "$EVIDENCIA/bo-dev.log" 2>&1 & echo $! > "$EVIDENCIA/bo-dev.pid" )
    for _ in $(seq 1 60); do curl -sS -m 2 -o /dev/null "http://localhost:4200" && break; sleep 2; done
  fi
  curl -sS -m 3 -o /dev/null "http://localhost:4200" \
    && ok "Backoffice en http://localhost:4200" \
    || die "el backoffice no levantó"
}

# ── Utilidades de dominio ────────────────────────────────────────────────────
estado_solicitud() { svc GET "$ORIGINATION/api/v1/origination/applications/$1" | jq -r '.status'; }

# El backoffice no pinta el creditAccountId en ningún sitio: la columna es «Contrato» y el
# subtítulo del modal es el contractNumber. Identificar la cuenta por su UUID sería abrir cuarenta
# modales para acabar verificando la primera fila.
contrato_de() { psql_q "SELECT contract_number FROM credit_portfolio.credit_accounts
                         WHERE credit_account_id = '$1';" | tr -d ' '; }

# El handoff del acto 1 no puede traer los identificadores: `GET /credit/applications` del BFF
# móvil responde **500** siempre —el cliente de origination deserializa como lista
# (OriginationClient.java:167) lo que origination devuelve como página
# (CreditApplicationController.java:91, `Page<CreditApplicationResponse>`)—. Es un bug de producto,
# no de la prueba, y no se parchea desde aquí: se anota y se resuelve por base, que es de donde
# este orquestador ya saca la cuenta, el contrato y las disposiciones.
ids_por_telefono() { # phone → "prospectId|applicationId" (applicationId vacío si aún no hay)
  psql_q "SELECT p.prospect_id || '|' || COALESCE(a.application_id::text, '')
            FROM origination.prospects p
            LEFT JOIN origination.credit_applications a ON a.prospect_id = p.prospect_id
           WHERE p.phone LIKE '%$1'
           ORDER BY a.created_at DESC NULLS LAST LIMIT 1;" | tr -d ' '
}

cuenta_de_prospecto() { # prospectId → creditAccountId
  # `credit_accounts.obligor_party_id` guarda el **prospectId**, no el `party.parties.party_id`
  # (comprobado sobre las cuentas existentes: ninguna casa por party, todas por prospecto). El
  # join por party devolvía siempre vacío y el caso reportaba "no apareció la cuenta en cartera"
  # con la cuenta ya creada y ACTIVE. Se aceptan los dos por si algún flujo guardara el party.
  psql_q "SELECT ca.credit_account_id FROM credit_portfolio.credit_accounts ca
           WHERE ca.obligor_party_id = '$1'
              OR ca.obligor_party_id = (SELECT party_id FROM party.parties WHERE prospect_id = '$1' LIMIT 1)
           ORDER BY ca.created_at DESC LIMIT 1;" | tr -d ' '
}

folio_de() { svc GET "$ORIGINATION/api/v1/origination/applications/$1" | jq -r '.folio // empty'; }

# Formaliza por API lo que la app todavía no sabe retomar tras una aprobación manual:
# oferta → aceptar → contrato → firma. Es el mismo camino de 03-journey.sh y de
# seed-distribuidoras.py. Queda anotado como hueco de producto en el reporte.
activar_por_api() { # applicationId
  # En un mismo `local`, bash expande **todas** las palabras antes de asignar ninguna: escribir
  # `local id="$1" base="…$id"` deja `base` mirando un `id` que todavía no existe y, con `set -u`,
  # aborta la función entera con "id: unbound variable".
  local id="$1"
  local base="$ORIGINATION/api/v1/origination/applications/$id"
  local detalle prod monto plazo tipo
  detalle=$(svc GET "$base")
  prod=$(jq -r '.productCode // empty' <<<"$detalle")
  tipo=$(jq -r '.productType // empty' <<<"$detalle")
  monto=$(jq -r '.requestedAmount // 500000' <<<"$detalle")
  plazo=$(jq -r '.requestedTerm // 24' <<<"$detalle")
  if [ -z "$prod" ]; then
    # El catálogo es la fuente: se pregunta por el producto activo de ese tipo en vez de
    # adivinar un código, que es como se acaba firmando un contrato con la tasa de otro producto.
    prod=$(psql_q "SELECT product_code FROM credit_product.credit_product_definitions
                    WHERE product_type = '$tipo' AND status = 'ACTIVE' LIMIT 1;" | tr -d ' ')
  fi
  [ -z "$prod" ] && { err "no pude resolver el productCode de $id"; return 1; }
  svc POST "$base/offer" "{\"productCode\":\"$prod\",\"offeredAmount\":$monto,\"offeredTerm\":$plazo}" >/dev/null
  svc POST "$base/offer/accept" >/dev/null
  svc POST "$base/contract/generate" '{"signatureMethod":"ELECTRONIC"}' >/dev/null
  svc POST "$base/contract/sign" \
    '{"clabeAccount":"646180157098765432","signatureProof":"regresion-e2e","documentRef":"regresion-e2e"}' >/dev/null
  # Firmar dispara el desembolso por Kafka, así que para cuando se lee el estado la solicitud ya
  # suele estar en DISBURSED — el siguiente eslabón del ciclo
  # (… → PENDING_SIGNATURE → CONTRACT_SIGNED → DISBURSED), no un fallo. Se aceptan los dos.
  local st_final; st_final=$(estado_solicitud "$id")
  case "$st_final" in
    CONTRACT_SIGNED|DISBURSED) ok "Formalizada por API (oferta → contrato → firma) ($st_final)" ;;
    *) err "Formalizada por API — esperaba CONTRACT_SIGNED o DISBURSED, llegó «$st_final»" ;;
  esac
}

# ═════════════════════════════════════════════════════════════════════════════
# CASO 1 — Uso propio · revisión manual · aprobado · liquidado
# ═════════════════════════════════════════════════════════════════════════════
caso1() {
  export CASO_ACTUAL="caso-1-uso-propio-manual-liquidado"
  local dir="$EVIDENCIA/$CASO_ACTUAL"; mkdir -p "$dir"
  log "════ CASO 1 — uso propio, revisión manual, liquidado ════"

  # La app no precalifica sólo el producto que se va a pedir: el BFF manda los seis productos
  # B2C de una (CREDIT_CARD, GROUP_LOAN, MICRO_LOAN, PERSONAL_LOAN, PAYROLL_LOAN,
  # REVOLVING_LINE — ver el log de ScoringClient). Y las dos decisiones de la app miran el
  # conjunto, no el elegido: `postAnalysisRoute` manda al catálogo si hay **algo** aprobado
  # (credit_application_provider.dart:143) y el home sólo dice "en revisión" si no hay **nada**
  # aprobado (`isPendingReview`, :148 → credit_module.dart:233).
  #
  # Subir sólo la banda de PERSONAL_LOAN dejaba a los otros cinco auto-aprobando en BAJO=400:
  # el alta aterrizaba en el catálogo y el home nunca decía "en revisión". Se suben las seis.
  for _p in CREDIT_CARD GROUP_LOAN MICRO_LOAN PERSONAL_LOAN PAYROLL_LOAN REVOLVING_LINE; do
    forzar_banda_manual "$_p"
  done

  # ── ACTO 1 · app: alta y solicitud ──────────────────────────────────────
  grabar_inicio "app-acto1-alta-y-solicitud.mp4"
  correr_app "regresion/c1_solicitud_test.dart" "$dir/acto1.log"
  local r1=$?
  grabar_fin
  [ $r1 -eq 0 ] && ok "ACTO 1 — alta y solicitud en la app" || err "ACTO 1 falló (ver acto1.log)"

  local phone password app_id prospect_id
  phone=$(handoff PHONE "$dir/acto1.log")
  password=$(handoff PASSWORD "$dir/acto1.log")
  app_id=$(handoff APPLICATION_ID "$dir/acto1.log")
  prospect_id=$(handoff PROSPECT_ID "$dir/acto1.log")
  if [ -z "$app_id" ] || [ -z "$prospect_id" ]; then
    warn "HUECO: GET /credit/applications del BFF móvil responde 500 (página vs lista) — resuelvo los identificadores por base"
    local par; par=$(ids_por_telefono "$phone")
    [ -z "$prospect_id" ] && prospect_id="${par%%|*}"
    [ -z "$app_id" ]      && app_id="${par##*|}"
  fi
  [ -z "$app_id" ] && die "no pude resolver el APPLICATION_ID ni por handoff ni por base — revisa $dir/acto1.log"
  log "Solicitud $app_id · prospecto $prospect_id · $phone"

  # ── Verificación de servicios ───────────────────────────────────────────
  local detalle; detalle=$(svc GET "$ORIGINATION/api/v1/origination/applications/$app_id")
  printf '%s' "$detalle" > "$dir/estado-tras-solicitud.json"
  afirma "La solicitud quedó en revisión manual" "UNDER_MANUAL_REVIEW" "$(jq -r '.status' <<<"$detalle")"
  afirma "approvalFlow etiquetado como MANUAL"   "MANUAL"              "$(jq -r '.approvalFlow' <<<"$detalle")"
  afirma "riskLevel MEDIO"                       "MEDIO"               "$(jq -r '.riskLevel' <<<"$detalle")"

  # ── ACTO 2 · backoffice: el analista decide ─────────────────────────────
  correr_bo "regresion-decision.spec.ts" "bo-acto2-decision" \
    "RE2E_APP_ID=$app_id" "RE2E_FOLIO=$(folio_de "$app_id")" "RE2E_ACCION=aprobar" \
    && ok "ACTO 2 — aprobación desde la consola" || err "ACTO 2 falló"

  afirma "Tras la aprobación la solicitud está APPROVED" "APPROVED" "$(estado_solicitud "$app_id")"

  # ── Formalización ────────────────────────────────────────────────────────
  # HALLAZGO: tras una aprobación manual la app no tiene camino para retomar la oferta —el
  # polling de la solicitud se detiene al entrar en revisión y el home oculta la puerta al
  # catálogo—. Se formaliza por API y se deja escrito.
  warn "HUECO: la app no retoma la oferta tras la aprobación manual — se formaliza por API"
  activar_por_api "$app_id"

  # La cuenta la crea credit-portfolio al consumir `contract-signed` por Kafka: leerla en el acto
  # siguiente a la firma es una carrera que se pierde casi siempre.
  local cuenta
  cuenta=$(espera "cuenta en cartera" 30 2 "cuenta_de_prospecto '$prospect_id'")
  [ -n "$cuenta" ] && ok "Cuenta en cartera: $cuenta" || err "no apareció la cuenta en cartera"

  # ── Reloj ────────────────────────────────────────────────────────────────
  devengar_quincenas "$QUINCENAS"
  svc GET "$PORTFOLIO/api/v1/portfolio/accounts/$cuenta" "" "X-User-Id: regresion" \
    > "$dir/estado-tras-devengo.json"

  # ── ACTO 3 · app: avance del plan y liquidación ─────────────────────────
  grabar_inicio "app-acto3-avance-y-liquidacion.mp4"
  correr_app "regresion/c1_seguimiento_test.dart" "$dir/acto3.log" \
    --dart-define="RE2E_PHONE=$phone" --dart-define="RE2E_PASSWORD=$password" \
    --dart-define="RE2E_DESENLACE=liquidar"
  local r3=$?
  grabar_fin
  [ $r3 -eq 0 ] && ok "ACTO 3 — avance visible y crédito liquidado en la app" || err "ACTO 3 falló"

  # ── ACTO 4 · backoffice: cartera ────────────────────────────────────────
  correr_bo "regresion-cartera.spec.ts" "bo-acto4-cartera" \
    "RE2E_CUENTA=$cuenta" "RE2E_CONTRATO=$(contrato_de "$cuenta")" "RE2E_ESPERADO=liquidado" \
    && ok "ACTO 4 — la cuenta se ve en cartera con su calendario y sus pagos" || err "ACTO 4 falló"

  restaurar_banda
}

# ═════════════════════════════════════════════════════════════════════════════
# CASO 2 y 3 — Distribuidora · revisión manual · aprobado · liquidado / mora
# ═════════════════════════════════════════════════════════════════════════════
caso_distribuidora() { # $1 = liquidar | mora
  local desenlace="$1"
  export CASO_ACTUAL="caso-$([ "$desenlace" = liquidar ] && echo 2 || echo 3)-distribuidora-manual-$desenlace"
  local dir="$EVIDENCIA/$CASO_ACTUAL"; mkdir -p "$dir"
  log "════ CASO distribuidora — desenlace: $desenlace ════"

  # DL-DIST-STD-V1 ya tiene la banda BAJO inalcanzable: cae en manual por el camino natural.

  # ── ACTO 1 · app: alta de distribuidora ─────────────────────────────────
  grabar_inicio "app-acto1-alta-distribuidora.mp4"
  correr_app "regresion/c2_solicitud_test.dart" "$dir/acto1.log"
  local r1=$?; grabar_fin
  [ $r1 -eq 0 ] && ok "ACTO 1 — alta de distribuidora y solicitud de línea" || err "ACTO 1 falló"

  local phone password prospect_id
  phone=$(handoff PHONE "$dir/acto1.log")
  password=$(handoff PASSWORD "$dir/acto1.log")
  prospect_id=$(handoff PROSPECT_ID "$dir/acto1.log")
  if [ -z "$prospect_id" ]; then
    warn "HUECO: GET /credit/applications del BFF móvil responde 500 (página vs lista) — resuelvo el prospecto por base"
    local par; par=$(ids_por_telefono "$phone")
    prospect_id="${par%%|*}"
  fi
  [ -z "$prospect_id" ] && die "no pude resolver el PROSPECT_ID ni por handoff ni por base — revisa $dir/acto1.log"

  # HALLAZGO: una distribuidora recién dada de alta no tiene cómo pedir su línea desde la app
  # (su bottom nav no incluye «Oportunidades» y el home oculta la puerta al catálogo mientras
  # está en revisión). La solicitud se crea por el BFF —el mismo canal de la app— igual que hace
  # hoy scripts/seed-distribuidoras.py.
  warn "HUECO: la app no permite solicitar la línea de distribuidor — se crea por el BFF"
  local tok_mov; tok_mov=$(bff_login "$phone" "$password")
  [ -z "$tok_mov" ] && die "no pude entrar al BFF móvil como $phone"
  local creada; creada=$(bff_post "$tok_mov" "/credit/applications" \
      "{\"prospectId\":\"$prospect_id\",\"productType\":\"DISTRIBUTOR_LINE\"}")
  local app_id; app_id=$(jq -r '.applicationId // .id // empty' <<<"$creada")
  [ -z "$app_id" ] && die "no se creó la solicitud de línea: $creada"
  ok "Solicitud de línea creada: $app_id"
  sleep 8   # el scoring viaja por Kafka; sin esta espera se lee el estado antes de la decisión

  local detalle; detalle=$(svc GET "$ORIGINATION/api/v1/origination/applications/$app_id")
  printf '%s' "$detalle" > "$dir/estado-tras-solicitud.json"
  local st; st=$(jq -r '.status' <<<"$detalle")
  case "$st" in
    UNDER_MANUAL_REVIEW|COMMITTEE_REVIEW) ok "La línea quedó en revisión humana ($st)" ;;
    *) err "esperaba revisión humana, llegó $st" ;;
  esac
  afirma "Producto correcto" "DISTRIBUTOR_LINE" "$(jq -r '.productType' <<<"$detalle")"

  # ── ACTO 2 · backoffice ─────────────────────────────────────────────────
  correr_bo "regresion-decision.spec.ts" "bo-acto2-decision" \
    "RE2E_APP_ID=$app_id" "RE2E_FOLIO=$(folio_de "$app_id")" "RE2E_ACCION=aprobar" \
    && ok "ACTO 2 — línea aprobada desde la consola" || err "ACTO 2 falló"

  # Oferta/contrato/firma de la línea: por API. La app todavía no tiene esas pantallas para
  # DISTRIBUTOR_LINE (ver PLAN_REGRESION_E2E.md §4.1). Es hueco de producto, no de la prueba.
  activar_por_api "$app_id"
  local linea
  linea=$(espera "línea en cartera" 30 2 "cuenta_de_prospecto '$prospect_id'")
  [ -n "$linea" ] && ok "Línea activa en cartera: $linea" || err "la línea no apareció en cartera"

  # ── ACTO 3 · app: la línea y la colocación ──────────────────────────────
  grabar_inicio "app-acto3-linea-y-colocacion.mp4"
  correr_app "regresion/c2_colocacion_test.dart" "$dir/acto3.log" \
    --dart-define="RE2E_PHONE=$phone" --dart-define="RE2E_PASSWORD=$password"
  local r3=$?; grabar_fin
  [ $r3 -eq 0 ] && ok "ACTO 3 — línea visible y colocación enviada" || err "ACTO 3 falló"

  local placement; placement=$(handoff PLACEMENT_ID "$dir/acto3.log")
  # KYC de la beneficiaria simulado — si no, la colocación nunca llega a disposición.
  if [ -n "$placement" ]; then
    # La respuesta se mira: si el KYC falla a media carga —por ejemplo con un CURP sintético
    # duplicado— la colocación queda en KYC_IN_PROGRESS y ya no avanza, pero descartando la
    # salida esto se anunciaba como un ✓ y el rojo aparecía tres pasos después, en la disposición.
    local kyc kyc_status
    kyc=$(svc POST "$BENEFICIARY/api/v1/internal/test-support/placements/$placement/complete-kyc" '{}')
    # El BFF serializa el estado en camelCase («bureauReady»), no con el nombre del enum
    # («BUREAU_READY»): se normaliza antes de comparar para no volver a inventar un rojo.
    kyc_status=$(jq -r '.status // empty' <<<"$kyc" 2>/dev/null)
    case "$(printf '%s' "$kyc_status" | tr '[:lower:]' '[:upper:]' | tr -d '_')" in
      BUREAUREADY|KYCCOMPLETED|APPROVED|DISBURSING|DISBURSED)
        ok "KYC de la beneficiaria simulado ($kyc_status)" ;;
      *)
        err "el KYC de la beneficiaria no avanzó: ${kyc:-sin respuesta}" ;;
    esac
    # El KYC **no** desemboca en disposición: deja la colocación en BUREAU_READY, que la propia
    # app rotula «ESPERA TU OK» / "terminó su verificación. Revisa su historial para decidir"
    # (DistributorQueryController.java:146). Quien decide es la distribuidora, y hasta que
    # aprueba no hay disposición ni calendario — por eso el acto 4, el acto 5 y la liquidación
    # caían todos por lo mismo. Se aprueba por el BFF móvil, el mismo canal que usaría la app.
    # La mesa de KYC dictamina la identidad ANTES de que la distribuidora pueda aprobar: es lo
    # único que habilita el depósito (`Placement.approve` lanza IdentityNotVerifiedException si
    # sigue en PENDING). El `complete-kyc` deja el expediente armado, no dictaminado — el juicio
    # es de una persona, y por eso vive en el backoffice y no en el simulador de KYC.
    local dictamen
    dictamen=$(svc POST "$BENEFICIARY/api/v1/backoffice/placements/$placement/identity-review" \
        '{"decision":"VERIFIED","decidedBy":"regresion-e2e","verificationSource":"MANUAL"}')
    case "$(jq -r '.placement.identityDecision // empty' <<<"$dictamen" 2>/dev/null)" in
      VERIFIED) ok "Identidad de la beneficiaria dictaminada por la mesa de KYC" ;;
      *)        err "la mesa de KYC no dictaminó la identidad: ${dictamen:-sin respuesta}" ;;
    esac

    # El approve también se mira: antes se daba por bueno sin leer la respuesta, y el rojo
    # aparecía tres pasos después, en la disposición que nunca llegaba.
    local aprobada
    aprobada=$(bff_post "$tok_mov" "/placements/$placement/approve" '{"riskAcknowledged":true}')
    case "$(printf '%s' "$(jq -r '.status // empty' <<<"$aprobada" 2>/dev/null)" | tr '[:lower:]' '[:upper:]' | tr -d '_')" in
      APPROVED|DISBURSING|DISBURSED|ACTIVE)
        ok "Colocación aprobada por la distribuidora (canal móvil)" ;;
      *) err "la distribuidora no pudo aprobar la colocación: ${aprobada:-sin respuesta}" ;;
    esac
  else
    warn "el acto 3 no dejó PLACEMENT_ID — sigo con la última disposición de la línea"
  fi

  local disp
  disp=$(espera "disposición creada" 30 2 \
    "psql_q \"SELECT disposition_id FROM credit_portfolio.dispositions
               WHERE credit_account_id = '$linea' ORDER BY created_at DESC LIMIT 1;\" | tr -d ' '")
  [ -n "$disp" ] && ok "Disposición: $disp" || err "no se creó la disposición"

  # ── Reloj y desenlace ───────────────────────────────────────────────────
  devengar_quincenas "$QUINCENAS"
  if [ "$desenlace" = "liquidar" ]; then
    local adeudo; adeudo=$(bff_get "$tok_mov" "/credit/account" | jq -r '(.[0].totalDebt // .totalDebt // 0)')
    if [ -n "$adeudo" ] && [ "$adeudo" != "0" ] && [ "$adeudo" != "null" ]; then
      bff_post "$tok_mov" "/credit/payment" "{\"amount\":$adeudo}" >/dev/null
      sleep 4
      ok "Adeudo pagado por el canal móvil ($adeudo)"
    else
      warn "el BFF no reportó adeudo — no hay nada que liquidar"
    fi
  fi

  if [ "$desenlace" = "mora" ]; then
    provocar_mora "$disp" 95 disposicion
    local dpd; dpd=$(psql_q "SELECT days_delinquent FROM credit_portfolio.credit_accounts
                              WHERE credit_account_id = '$linea';" | tr -d ' ')
    [ "${dpd:-0}" -gt 0 ] && ok "DPD calculado: $dpd días" || err "days_delinquent siguió en 0"
  fi

  # ── ACTO 4 · app ────────────────────────────────────────────────────────
  grabar_inicio "app-acto4-$desenlace.mp4"
  correr_app "regresion/c2_seguimiento_test.dart" "$dir/acto4.log" \
    --dart-define="RE2E_PHONE=$phone" --dart-define="RE2E_PASSWORD=$password" \
    --dart-define="RE2E_DESENLACE=$desenlace"
  local r4=$?; grabar_fin
  [ $r4 -eq 0 ] && ok "ACTO 4 — la app refleja el desenlace ($desenlace)" || err "ACTO 4 falló"

  # ── ACTO 5 · backoffice ─────────────────────────────────────────────────
  if [ "$desenlace" = "mora" ]; then
    correr_bo "regresion-cobranza.spec.ts" "bo-acto5-cobranza" \
      "RE2E_CUENTA=$linea" "RE2E_CONTRATO=$(contrato_de "$linea")" \
      && ok "ACTO 5 — el caso aparece en cobranza con su tramo" || err "ACTO 5 falló"
  else
    correr_bo "regresion-cartera.spec.ts" "bo-acto5-cartera" \
      "RE2E_CUENTA=$linea" "RE2E_CONTRATO=$(contrato_de "$linea")" "RE2E_ESPERADO=liquidado" \
      && ok "ACTO 5 — la línea y su disposición se ven en cartera" || err "ACTO 5 falló"
  fi
}

# ── Orquestación ─────────────────────────────────────────────────────────────
preflight
for c in $CASOS; do
  case "$c" in
    1) caso1 ;;
    2) caso_distribuidora liquidar ;;
    3) caso_distribuidora mora ;;
    *) warn "caso desconocido: $c" ;;
  esac
done
restaurar_banda

[ -f "$EVIDENCIA/bo-dev.pid" ] && kill "$(cat "$EVIDENCIA/bo-dev.pid")" 2>/dev/null

if [ "$CON_REPORTE" = 1 ]; then
  python3 ./reporte.py "$EVIDENCIA" && log "Reporte: $EVIDENCIA/reporte.html"
fi

echo
if [ "$FALLOS" -eq 0 ]; then
  printf '%s✓ regresión completa sin fallos%s — evidencia en %s\n' "$C_OK" "$C_OFF" "$EVIDENCIA"
else
  printf '%s✗ %d aserciones fallidas%s — evidencia en %s\n' "$C_ERR" "$FALLOS" "$C_OFF" "$EVIDENCIA"
fi
exit "$FALLOS"
