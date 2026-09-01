#!/usr/bin/env bash
#
# La siembra entera, en orden y de una vez.
#
# El orden no estaba escrito en ningún sitio: había que deducirlo leyendo el encabezado de cada
# script y saber, por ejemplo, que la cartera necesita sucursales y ejecutivos que la siembra
# comercial crea antes. Sembrar en desorden no falla ruidosamente — produce cartera sin ejecutivo,
# tableros en cero y escenarios a medias, que es peor porque parece que funcionó.
#
# Cada paso se detiene si el anterior falló: un paso que se apoya en datos que no existen deja
# huecos silenciosos, y perseguirlos después cuesta más que volver a empezar.
#
#   ./scripts/siembra-completa.sh              siembra estándar
#   ./scripts/siembra-completa.sh --rapida     menos volumen, para iterar
set -euo pipefail
cd "$(dirname "$0")/.."

RAPIDA=""
[ "${1:-}" = "--rapida" ] && RAPIDA="1"

paso() {
  local titulo="$1"; shift
  echo
  echo "═══ $(date +%H:%M:%S) · $titulo"
  if ! "$@"; then
    echo "❌ falló: $titulo" >&2
    echo "   Los pasos siguientes se apoyan en esto; se detiene aquí en vez de sembrar a medias." >&2
    exit 1
  fi
}

# ── 1. La estructura comercial ────────────────────────────────────────────────
# Va primera porque todo lo demás cuelga de ella: sin sucursales y ejecutivos, la cartera nace
# sin dueño y el tablero comercial sale en ceros sin forma de distinguirlo de un error.
paso "Estructura comercial (Nacional → Región → Zona → Sucursal → Ejecutivo)" \
     python3 scripts/seed-sales-org.py

# ── 2. El personal ────────────────────────────────────────────────────────────
# Antes de la cartera: los créditos se asignan a ejecutivos, y el maker-checker de los programas
# de apoyo necesita DOS personas distintas — con una sola, la siembra ejercita el rechazo.
paso "Responsables de cada nivel comercial" python3 scripts/seed-commercial-staff.py
paso "Personal de roles funcionales (riesgo, finanzas, auditoría…)" python3 scripts/seed-demo-staff.py

# ── 3. La cartera ─────────────────────────────────────────────────────────────
paso "Cartera amortizable repartida por la red" \
     python3 scripts/seed-portfolio.py ${RAPIDA:+--clientes 20}

# ── 4. El crédito de distribuidora ────────────────────────────────────────────
paso "Distribuidoras, beneficiarias y colocaciones (B2B2C)" \
     python3 scripts/seed-distribuidoras.py ${RAPIDA:+--distribuidoras 2}

# ── 5. Que la cartera cuelgue de alguien ──────────────────────────────────────
# «Sembrar cartera» y «que la cartera tenga dueño» son dos cosas distintas, y la segunda no ocurre
# sola: sin esto quedan créditos huérfanos y el árbol comercial suma menos que la cartera.
paso "Asignar ejecutivo a toda la cartera" python3 scripts/asigna-cartera.py

# ── 6. Lo que la facturación necesita ─────────────────────────────────────────
# Sin perfil fiscal la facturación no falla: falla peor, cayendo al RFC genérico. Va ANTES de correr
# el ciclo, que es quien dispara las facturas.
paso "Perfiles fiscales CFDI" python3 scripts/seed-perfiles-fiscales.py
paso "Expedientes documentales" python3 scripts/seed-expedientes.py

# ── 7. Los revolventes ────────────────────────────────────────────────────────
# Al final: S7 otorga un programa de apoyo sobre la cartera que ya existe, así que sembrarlo antes
# lo dejaría alcanzando a casi nadie.
paso "Escenarios revolventes S1–S7 (tarjeta, línea, MSI, BNPL, salto, apoyo)" \
     python3 scripts/seed-revolventes.py ${RAPIDA:+--clientes 7}

# ── 8. Correr la vida ─────────────────────────────────────────────────────────
# Lo último y lo más importante. Sin esto la siembra deja créditos **vivos y sanos**, que alcanza
# para ver cartera y no alcanza para ver el sistema: cero devengo, cero mora, cero estimación
# preventiva, cero facturas, y el auxiliar de intereses por cobrar sin un solo abono. El mayor
# tiene altas y desembolsos, y nada de lo que pasa después.
paso "Avanzar el ciclo de vida mes a mes (devengo, cobro, mora, facturación, cierre)" \
     python3 scripts/seed-ciclo-credito.py ${RAPIDA:+--meses 2}

echo
echo "═══ $(date +%H:%M:%S) · siembra terminada"
echo
echo "Verifica con:"
echo "  python3 scripts/verifica-carril-del-dinero.py"
echo "  python3 scripts/verifica-cuadre.py"
echo "  python3 scripts/verifica-distribuidoras.py"
