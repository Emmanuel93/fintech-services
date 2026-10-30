#!/usr/bin/env bash
#
# Arma el video de demostración de un caso: portada, personajes, cada acto con su grabación a
# tamaño legible y un panel que narra el paso a paso sincronizado con la pantalla, el momento del
# depósito y el cierre, con música ambiental original (`musica.py`; MUSICA=<archivo> usa otra
# pista con licencia propia, MUSICA=no lo deja mudo).
#
#   ./une-video.sh ~/Documents/material-kredius/pruebas/<commit>/<corrida>/<caso> [short] [middle] [demo_completa]
#
# La composición vive en `video.py` (el guion está ahí) y las tarjetas en `tarjetas.cjs`. Los videos
# sueltos de cada acto siguen siendo la evidencia; éste es el que se le enseña a un cliente.
#
# ffmpeg sale de un entorno virtual local (.venv, imageio-ffmpeg con versión fija): no se instala
# nada en el sistema, y corre fuera de Docker, cuya máquina virtual no tiene memoria para componer
# 1080p con el stack levantado.
set -euo pipefail
# La carpeta se resuelve ANTES de moverse al directorio del script: una ruta relativa se lee desde
# donde la escribió quien llama.
CASO_DIR="$(cd "${1:?falta la carpeta del caso}" && pwd)"
cd "$(dirname "$0")"
source ./lib.sh
# Los ok/err de este script van a la bitácora de la corrida a la que pertenece el caso.
EVIDENCIA="$(dirname "$CASO_DIR")"

# numpy compone la música (musica.py); 2.0.2 es la última que corre en el Python 3.9 de macOS.
if ! .venv/bin/python -c 'import imageio_ffmpeg, numpy' >/dev/null 2>&1; then
  log "Preparando ffmpeg y numpy locales (una sola vez)"
  { [ -x .venv/bin/python ] || python3 -m venv .venv; } \
    && .venv/bin/pip install -q imageio-ffmpeg==0.6.0 numpy==2.0.2 >/dev/null 2>&1 \
    || die "no se pudo preparar ffmpeg y numpy en tools/regresion-e2e/.venv"
fi

# Tres versiones: short (sólo celular, ≤ 1:30), middle (journey completo) y demo_completa (además
# contabilidad, bancos, región y operación; sólo si esos actos se grabaron).
SALIDAS="$(.venv/bin/python -I ./video.py "$CASO_DIR" "$BO_DIR" "${@:2}")" || die "no se pudo armar el video de $(basename "$CASO_DIR")"
# El video terminado es material comercial, no evidencia: se entrega fuera del repo. Los actos
# sueltos se quedan en la corrida, que es lo que respalda lo que el video muestra.
MATERIAL="${MATERIAL_DIR:-$HOME/Documents/material-kredius}/comercial"
mkdir -p "$MATERIAL"
while IFS= read -r s; do
  [ -n "$s" ] || continue
  mv -f "$s" "$MATERIAL/" && ok "Video: $MATERIAL/$(basename "$s")"
done <<<"$SALIDAS"
