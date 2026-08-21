#!/usr/bin/env python3
"""Sube documentos al expediente de los prospectos que ya tienen solicitud.

La app todavía no manda los archivos que captura —la pantalla los toma y se
quedan en el teléfono—, así que `origination.prospect_document_files` estaba
vacía y la ficha de análisis mostraba "INE_FRONT · CAPTURED" sin nada que abrir.
Esto llena el hueco con documentos generados, para que el visor se pueda usar y
probar mientras se conecta la subida real desde el canal móvil.

Los archivos son sintéticos y lo dicen en su propia cara: llevan impreso
«DOCUMENTO DE PRUEBA». Un expediente que parece real y no lo es es peor que uno
vacío.

    python3 scripts/seed-expedientes.py [--limit 12]
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import sys
import subprocess
import urllib.error
import urllib.request

from PIL import Image, ImageDraw

ADMIN = ("admin@kredius.mx", "Backoffice#2026")

# Paleta por tipo de documento: se distinguen de un vistazo en la tira de miniaturas.
DOCS = {
    "INE_FRONT":    ("Credencial para votar — FRENTE", (30, 58, 95),  (700, 440)),
    "INE_BACK":     ("Credencial para votar — REVERSO", (52, 40, 88), (700, 440)),
    "ADDRESS_PROOF": ("Comprobante de domicilio",       (20, 70, 60),  (620, 800)),
    "INCOME_PROOF": ("Comprobante de ingresos",         (90, 55, 20),  (620, 800)),
    "SELFIE":       ("Prueba de vida",                   (70, 30, 70),  (520, 640)),
}


def documento(titulo: str, color, tam, nombre: str, curp: str) -> bytes:
    """Una imagen que se ve como un documento y se lee como una prueba."""
    img = Image.new("RGB", tam, (245, 245, 242))
    d = ImageDraw.Draw(img)
    w, h = tam

    d.rectangle([0, 0, w, 64], fill=color)
    d.text((22, 24), titulo, fill=(255, 255, 255))

    d.rectangle([18, 82, w - 18, h - 60], outline=(200, 200, 195))
    y = 108
    for etiqueta, valor in [("NOMBRE", nombre), ("CURP", curp),
                            ("VIGENCIA", "2026-2036"), ("FOLIO", curp[-8:] if curp else "SIN FOLIO")]:
        d.text((36, y), etiqueta, fill=(120, 120, 118))
        d.text((36, y + 16), valor, fill=(25, 25, 25))
        y += 52

    # La foto: un recuadro, no una cara. No hace falta más para probar el visor.
    d.rectangle([w - 190, 108, w - 40, 300], fill=(225, 225, 220), outline=(190, 190, 185))
    d.text((w - 168, 196), "FOTOGRAFÍA", fill=(150, 150, 145))

    d.text((28, h - 44), "DOCUMENTO DE PRUEBA · generado para el ambiente local", fill=(190, 70, 70))

    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def put_interno(network: str, url: str, payload: dict) -> tuple[int, str]:
    """PUT a un servicio interno, desde dentro de la red de Docker."""
    r = subprocess.run(
        ["docker", "run", "--rm", "-i", "--network", network, "curlimages/curl:latest",
         "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", "PUT",
         "-H", "Content-Type: application/json",
         # Los servicios de dominio confían en la identidad que propaga el canal; aquí se
         # suplanta a propósito, y por eso esto es un script de siembra local y no una ruta.
         "-H", "X-User-Id: 00000000-0000-4000-8000-000000000001",
         "-H", "X-Roles: ADMIN", "-H", "X-Channel: BACKOFFICE",
         "--data-binary", "@-", url],
        input=json.dumps(payload).encode(), capture_output=True)
    return (int(r.stdout.decode().strip() or 0), r.stderr.decode()[:120])


def call(base, method, path, body=None, token=None):
    req = urllib.request.Request(
        f"{base}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gateway", default="http://backoffice.localhost:8090")
    # La subida va directo a origination —el backoffice sólo *lee* el expediente; subirlo es
    # cosa del canal del cliente—, y origination no se publica fuera de la red de Docker. Se
    # entra por ella, con un curl efímero, en vez de abrirle un puerto al host que después
    # nadie cierra.
    ap.add_argument("--network", default="fintech-services_fintech-network")
    ap.add_argument("--limit", type=int, default=12)
    args = ap.parse_args()
    base = args.gateway.rstrip("/")

    token = call(base, "POST", "/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]

    solicitudes = call(base, "GET", "/origination/applications", token=token) or []
    if not solicitudes:
        sys.exit("No hay solicitudes: nada que documentar")

    vistos, subidos = set(), 0
    for sol in solicitudes[: args.limit]:
        detalle = call(base, "GET", f"/origination/applications/{sol['applicationId']}", token=token)
        prospecto = (detalle or {}).get("prospect") or {}
        pid = prospecto.get("prospectId")
        if not pid or pid in vistos:
            continue
        vistos.add(pid)

        nombre = " ".join(filter(None, [prospecto.get("firstName"), prospecto.get("lastName1"),
                                        prospecto.get("lastName2")])) or "SIN NOMBRE"
        curp = prospecto.get("curp") or "SIN CURP"

        for tipo, (titulo, color, tam) in DOCS.items():
            png = documento(titulo, color, tam, nombre, curp)
            code, err = put_interno(
                args.network,
                f"http://origination-service:8080/api/v1/origination/prospects/{pid}/documents/{tipo}/file",
                {"fileName": f"{tipo.lower()}.png", "contentType": "image/png",
                 "contentBase64": base64.b64encode(png).decode()})
            if code == 200:
                subidos += 1
            else:
                print(f"  ✗ {tipo} de {nombre}: HTTP {code} {err}", file=sys.stderr)

        print(f"  ✓ {nombre[:38]:<38} {len(DOCS)} documentos")

    print(f"\n{subidos} archivos en {len(vistos)} expedientes")


if __name__ == "__main__":
    main()
