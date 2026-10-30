#!/usr/bin/env bash
#
# Arma el video de la arquitectura de referencia: diagramas animados con el panel narrativo, el
# momento del desembolso, el cierre y la música original de `musica.py` (MUSICA=<archivo>|no, como
# en une-video.sh). No necesita el stack levantado.
#
#   ./une-arquitectura.sh [short] [middle] [completa]     → ~/Documents/material-kredius/arquitectura
#
# Las cifras de capacidad y de tiempos salen de capacidad.json (`capacidad.py`, con el stack arriba).
#
# El guion vive en `arquitectura.py` y los diagramas se dibujan con `diagramas.cjs`.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh
# La bitácora de pasos de esta herramienta es desechable: va al temporal del sistema.
EVIDENCIA="${TMPDIR:-/tmp}/kredius-arquitectura"
mkdir -p "$EVIDENCIA"
# Los videos son material para presentar, no código: van fuera del repo.
MATERIAL="${MATERIAL_DIR:-$HOME/Documents/material-kredius}/arquitectura"

if ! .venv/bin/python -c 'import imageio_ffmpeg, numpy' >/dev/null 2>&1; then
  log "Preparando ffmpeg y numpy locales (una sola vez)"
  { [ -x .venv/bin/python ] || python3 -m venv .venv; } \
    && .venv/bin/pip install -q imageio-ffmpeg==0.6.0 numpy==2.0.2 >/dev/null 2>&1 \
    || die "no se pudo preparar ffmpeg y numpy en tools/regresion-e2e/.venv"
fi

SALIDAS="$(.venv/bin/python -I ./arquitectura.py "$MATERIAL" "$BO_DIR" "$@")" || die "no se pudo armar el video de arquitectura"
while IFS= read -r s; do [ -n "$s" ] && ok "Video: $s"; done <<<"$SALIDAS"
