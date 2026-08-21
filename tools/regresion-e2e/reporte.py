#!/usr/bin/env python3
"""Arma el reporte HTML de una corrida: línea de tiempo por caso y los videos embebidos.

Un video suelto en una carpeta no es evidencia de nada: no se sabe qué caso es, ni en qué punto
falló, ni si el verde de al lado corresponde a esa toma. Esto los ata a las aserciones.

    python3 reporte.py .evidencia/<corrida>
"""
import html
import json
import sys
from pathlib import Path

VIDEO_EXT = {".mp4", ".webm", ".mov"}


def pasos_por_caso(raiz: Path):
    casos: dict[str, list[dict]] = {}
    archivo = raiz / "pasos.jsonl"
    if archivo.exists():
        for linea in archivo.read_text(encoding="utf-8").splitlines():
            linea = linea.strip()
            if not linea:
                continue
            try:
                p = json.loads(linea)
            except json.JSONDecodeError:
                continue
            casos.setdefault(p.get("caso", "general"), []).append(p)
    return casos


def videos_de(carpeta: Path, raiz: Path):
    if not carpeta.is_dir():
        return []
    vids = [v for v in sorted(carpeta.rglob("*")) if v.suffix.lower() in VIDEO_EXT]
    return [(v.name, v.relative_to(raiz).as_posix()) for v in vids]


def main() -> int:
    raiz = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    casos = pasos_por_caso(raiz)

    # Las carpetas de caso son las de primer nivel; el resto (api.log, pasos.jsonl) no cuenta.
    carpetas = sorted(d for d in raiz.iterdir() if d.is_dir())
    orden = [d.name for d in carpetas] or sorted(casos)

    total = sum(len(v) for v in casos.values())
    fallos = sum(1 for v in casos.values() for p in v if p["estado"] == "fail")
    avisos = sum(1 for v in casos.values() for p in v if p["estado"] == "warn")

    partes = [f"""<!doctype html><html lang="es"><meta charset="utf-8">
<title>Regresión E2E · {html.escape(raiz.name)}</title>
<style>
  :root {{ color-scheme: light dark; --ok:#1a7f43; --fail:#b3261e; --warn:#8a6100; --line:#0000001f; }}
  * {{ box-sizing:border-box }}
  body {{ font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
          margin:0 auto; max-width:1080px; padding:32px 24px 96px }}
  h1 {{ font-size:26px; margin:0 0 4px }} h2 {{ font-size:19px; margin:40px 0 8px }}
  .sub {{ opacity:.65; margin:0 0 24px }}
  .tot {{ display:flex; gap:10px; flex-wrap:wrap; margin:20px 0 0 }}
  .tot span {{ border:1px solid var(--line); border-radius:999px; padding:5px 14px; font-size:13px }}
  .ok span.n {{ color:var(--ok) }} .fail span.n {{ color:var(--fail) }}
  ol {{ list-style:none; padding:0; margin:12px 0 }}
  li {{ display:flex; gap:10px; padding:6px 0; border-bottom:1px solid var(--line); align-items:baseline }}
  li .m {{ flex:0 0 16px; font-weight:700 }}
  li.ok .m {{ color:var(--ok) }} li.fail .m {{ color:var(--fail) }} li.warn .m {{ color:var(--warn) }}
  li .h {{ flex:0 0 68px; opacity:.5; font-variant-numeric:tabular-nums; font-size:12px }}
  .vids {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(320px,1fr)); gap:18px; margin:16px 0 }}
  figure {{ margin:0 }} figcaption {{ font-size:12px; opacity:.7; margin-top:6px }}
  video {{ width:100%; border-radius:10px; background:#000 }}
  .nada {{ opacity:.55; font-style:italic }}
</style>
<h1>Regresión E2E · app · backoffice · servicios</h1>
<p class="sub">Corrida <code>{html.escape(raiz.name)}</code></p>
<div class="tot">
  <span>{total} aserciones</span>
  <span class="ok">✓ <span class="n">{total - fallos - avisos}</span></span>
  <span class="fail">✗ <span class="n">{fallos}</span></span>
  <span>! {avisos}</span>
</div>"""]

    for nombre in orden:
        pasos = casos.get(nombre, [])
        partes.append(f"<h2>{html.escape(nombre)}</h2>")

        vids = videos_de(raiz / nombre, raiz)
        if vids:
            partes.append('<div class="vids">')
            for titulo, ruta in vids:
                partes.append(
                    f'<figure><video controls preload="metadata" src="{html.escape(ruta)}"></video>'
                    f"<figcaption>{html.escape(titulo)}</figcaption></figure>"
                )
            partes.append("</div>")
        else:
            partes.append('<p class="nada">Sin video para este caso.</p>')

        if pasos:
            partes.append("<ol>")
            for p in pasos:
                marca = {"ok": "✓", "fail": "✗", "warn": "!"}.get(p["estado"], "·")
                hora = p["t"][11:19]
                partes.append(
                    f'<li class="{p["estado"]}"><span class="m">{marca}</span>'
                    f'<span class="h">{hora}</span><span>{html.escape(p["texto"])}</span></li>'
                )
            partes.append("</ol>")
        else:
            partes.append('<p class="nada">Sin aserciones registradas.</p>')

    partes.append("</html>")
    (raiz / "reporte.html").write_text("\n".join(partes), encoding="utf-8")
    print(f"reporte.html · {total} aserciones · {fallos} fallidas")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
