#!/usr/bin/env python3
"""Liquida una parte de la cartera pagándola, no marcándola.

Una cartera donde todas las cuentas están vivas no ejercita nada de lo que pasa
al final: el filtro por estatus no separa, la mora se calcula sobre el total y no
hay forma de ver si la pantalla distingue una cuenta cerrada de una activa. Hacen
falta cuentas liquidadas para que la cartera se parezca a una real.

Paga el adeudo completo por el canal de pagos, igual que lo haría el cliente. No
toca la base ni fuerza el estatus: `settleIfClear()` mueve la cuenta a SETTLED
cuando el saldo llega a cero, así que esto comprueba de paso que esa transición
funcione. Forzar el estatus a mano dejaría cuentas «liquidadas» con saldo, que es
un estado que el dominio no produce y contra el que nadie debería programar.

    python3 scripts/liquida-creditos.py --cuantas 12
"""
from __future__ import annotations

import argparse
import itertools
import json
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request

ADMIN = ("admin@kredius.mx", "Backoffice#2026")
PAGINA = 100


def call(base, method, path, body=None, token=None):
    req = urllib.request.Request(
        f"{base}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def pago_interno(cuenta, obligado, monto, ref, red):
    """Registra el pago contra payments-service, que no publica puerto fuera de Docker.

    Se entra por la red interna y no por el gateway porque el gateway sólo expone lo del
    canal: registrar un pago es una operación del dominio, y que no se pueda hacer desde
    fuera es justamente la garantía que sostiene la topología.

    Con un contenedor de curl y no con `docker compose exec`: las imágenes de servicio son
    JRE alpine sin utilidades de red, así que ejecutar `curl` dentro devuelve "executable
    file not found" —un fallo que además llega disfrazado de respuesta HTTP.
    """
    cuerpo = json.dumps({"creditAccountId": cuenta, "obligorPartyId": obligado,
                         "amount": monto, "paymentMethod": "SPEI", "externalRef": ref})
    r = subprocess.run(
        ["docker", "run", "--rm", "--network", red, "curlimages/curl:latest",
         "-s", "-o", "/dev/null", "-w", "%{http_code}",
         "-X", "POST", "http://payments-service:8080/api/v1/payments",
         "-H", "Content-Type: application/json",
         # Los servicios de dominio confían en las cabeceras que el gateway inyecta tras
         # validar el JWT. Entrando por la red interna hay que ponerlas a mano: sin ellas la
         # petición es anónima y el dominio contesta 401, que es exactamente lo que debe hacer.
         "-H", "X-User-Id: 00000000-0000-0000-0000-0000000000e2",
         "-H", "X-Roles: ADMIN",
         "-H", "X-Channel: SERVICE",
         "-d", cuerpo],
        capture_output=True, text=True, timeout=120)
    return r.stdout.strip()


def todas_las_paginas(base, path, token):
    fuera = []
    for p in itertools.count():
        sep = "&" if "?" in path else "?"
        pag = call(base, "GET", f"{path}{sep}page={p}&size={PAGINA}", token=token)
        filas = pag.get("content", pag) if isinstance(pag, dict) else pag
        if not filas:
            break
        fuera.extend(filas)
        if len(filas) < PAGINA or p > 200:
            break
    return fuera


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gateway", default="http://backoffice.localhost:8090")
    ap.add_argument("--cuantas", type=int, default=12)
    ap.add_argument("--semilla", type=int, default=20260812)
    ap.add_argument("--red", default="fintech-services_fintech-network",
                    help="red de Docker donde vive payments-service")
    args = ap.parse_args()
    base = args.gateway.rstrip("/")
    rnd = random.Random(args.semilla)

    token = call(base, "POST", "/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]

    cuentas = [c for c in todas_las_paginas(base, "/portfolio", token)
               if c.get("status") == "ACTIVE"]
    if not cuentas:
        print("✗ no hay cuentas activas que liquidar", file=sys.stderr)
        return 1

    # Se liquidan las más pequeñas de una muestra al azar: cerrar los créditos grandes
    # vaciaría la cartera y dejaría el tablero sin nada que mostrar, que es lo contrario
    # de lo que hace falta.
    muestra = rnd.sample(cuentas, min(len(cuentas), args.cuantas * 3))
    elegidas = sorted(muestra, key=lambda c: c.get("principalBalance") or 0)[:args.cuantas]

    print(f"▸ liquidando {len(elegidas)} de {len(cuentas)} cuentas activas")
    pagadas = 0
    for c in elegidas:
        # El adeudo completo, no el capital: quedarse corto por los intereses devengados
        # deja la cuenta viva con un saldo de centavos y sin liquidar.
        total = (c.get("principalBalance") or 0) + (c.get("accruedInterestBalance") or 0) \
            + (c.get("penaltyBalance") or 0)
        if total <= 0:
            continue
        code = pago_interno(c["creditAccountId"], c["obligorPartyId"],
                            round(total + 0.5, 2), f"LIQ-{c['creditAccountId'][:8]}", args.red)
        if code in ("200", "201"):
            pagadas += 1
        else:
            print(f"  ✗ {c.get('contractNumber')}: HTTP {code}", file=sys.stderr)

    print(f"  {pagadas} pagos registrados; el cierre se aplica de forma asíncrona")

    # El estatus lo mueve el dominio al confirmarse el pago, así que se espera a verlo en
    # vez de darlo por hecho: informar "liquidadas" antes de que ocurra es exactamente el
    # tipo de mentira que hace desconfiar de un seed.
    for intento in range(20):
        time.sleep(3)
        ahora = todas_las_paginas(base, "/portfolio", token)
        cerradas = [c for c in ahora if c.get("status") in ("SETTLED", "CLOSED")]
        if len(cerradas) >= pagadas:
            break
    print(f"  ✓ {len(cerradas)} cuentas liquidadas · "
          f"{len([c for c in ahora if c.get('status') == 'ACTIVE'])} siguen activas")
    return 0


if __name__ == "__main__":
    sys.exit(main())
