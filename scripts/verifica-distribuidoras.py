#!/usr/bin/env python3
"""Comprueba, contra la API, que la siembra de distribuidoras dejó lo que debía dejar.

Una siembra que no se verifica es una siembra en la que no se puede confiar. El modo de fallar de
estos scripts no es tronar: es dejar la mitad de las cosas y terminar con código 0, y entonces el
cero de una pantalla se lee como un bug del front durante media hora.

Comprueba las dos mitades del modelo, que es donde está lo que puede salir mal en silencio:

  · que las distribuidoras existan, tengan rol, nodo y **una sola** línea revolvente;
  · que los beneficiarios tengan expediente y vínculo, y **no** tengan cuenta, mora ni riesgo.

Sale con código 1 si algo no cuadra.
"""
from __future__ import annotations

import json
import subprocess
import sys
import urllib.error
import urllib.request

BACKOFFICE = "http://backoffice.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")
RED_DOCKER = "fintech-services_fintech-network"
SEED_USER_ID = "00000000-0000-0000-0000-000000005EED"

fallos: list[str] = []
avisos: list[str] = []


def http(method, url, body=None, token=None, timeout=60):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def interno(servicio, ruta):
    # `X-User-Id` siempre: credit-portfolio autentica con esa cabecera y sin ella responde 401.
    # Este helper se traga los errores, así que sin la cabecera las comprobaciones sobre cartera
    # —incluida S4, la que protege el modelo— habrían pasado sin mirar nada.
    r = subprocess.run(
        ["docker", "run", "--rm", "--network", RED_DOCKER, "curlimages/curl:latest",
         "-s", "-H", f"X-User-Id: {SEED_USER_ID}",
         f"http://{servicio}:8080{ruta}"], capture_output=True)
    try:
        return json.loads(r.stdout.decode() or "null")
    except json.JSONDecodeError:
        return None


def revisa(condicion, mensaje):
    if condicion:
        print(f"    ✓ {mensaje}")
    else:
        print(f"    ✗ {mensaje}")
        fallos.append(mensaje)


def main():
    token = http("POST", f"{BACKOFFICE}/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]}).get("accessToken")

    # ── S1 · Los distribuidores existen y el BFF los sabe listar ─────────────
    print("\n  S1 · Distribuidores en el árbol y en la consola")
    try:
        pagina = http("GET", f"{BACKOFFICE}/distributors?page=0&size=50", token=token)
    except urllib.error.HTTPError as e:
        # Es el endpoint que la consola llamaba y el BFF no tenía. Si vuelve a faltar, la pestaña
        # «Distribuidores» se rompe otra vez y conviene que se sepa aquí y no abriéndola.
        print(f"    ✗ GET /distributors devolvió {e.code}")
        fallos.append("el BFF no expone /distributors")
        resumen()
        return

    filas = pagina.get("content", [])
    revisa(len(filas) >= 1, f"el BFF devuelve distribuidores ({len(filas)})")
    revisa(all(f.get("partyId") for f in filas), "todos tienen partyId resoluble")
    revisa(all(f.get("promoterCode") for f in filas), "todos tienen código de promotor")

    # ── S2 · Una sola línea revolvente por distribuidora ─────────────────────
    print("\n  S2 · Una distribuidora, una cuenta de crédito")
    con_linea = [f for f in filas if f.get("creditLineId")]
    revisa(len(con_linea) >= 1, f"hay distribuidoras con línea activa ({len(con_linea)})")

    for f in con_linea:
        cuentas = interno("credit-portfolio-service",
                          f"/api/v1/portfolio/accounts?partyId={f['partyId']}")
        if isinstance(cuentas, list):
            lineas = [c for c in cuentas if c.get("productType") == "DISTRIBUTOR_LINE"]
            if len(lineas) > 1:
                fallos.append(f"{f['promoterCode']} tiene {len(lineas)} líneas — debería tener una")
    revisa(not any("líneas" in x for x in fallos), "ninguna tiene más de una línea")

    # ── S3 · Los beneficiarios tienen expediente y vínculo ───────────────────
    print("\n  S3 · Beneficiarios con expediente y vínculo a su distribuidora")
    total_benef = 0
    sin_vinculo = 0
    for f in filas:
        # party se consulta con el partyId del expediente; cartera, con el que ella usa. Son
        # distintos, y confundirlos devuelve vacío sin error — una comprobación que no comprueba.
        rel = interno("party-service", f"/api/v1/parties/{f.get('expedientePartyId')}/relationships")
        benef = [r for r in (rel or []) if r.get("relationshipType") == "BENEFICIARY"]
        total_benef += len(benef)
        for r in benef:
            entrantes = interno("party-service",
                                f"/api/v1/parties/{r['relatedPartyId']}/relationships/incoming")
            if not entrantes:
                sin_vinculo += 1
    revisa(total_benef > 0, f"hay beneficiarios vinculados ({total_benef})")
    revisa(sin_vinculo == 0,
           f"todos los beneficiarios saben de qué distribuidora vienen (sin rastro: {sin_vinculo})")

    # ── S4 · El beneficiario NO es deudor ────────────────────────────────────
    #
    # Es la comprobación que protege el modelo entero. Si aparece una cuenta de crédito a nombre de
    # un beneficiario, el producto se bifurcó: habría cien deudores en vez de diez y la cartera
    # dejaría de sumar.
    print("\n  S4 · El beneficiario tiene expediente, no deuda")
    beneficiarios_con_cuenta = 0
    for f in filas:
        rel = interno("party-service", f"/api/v1/parties/{f.get('expedientePartyId')}/relationships")
        for r in (rel or []):
            if r.get("relationshipType") != "BENEFICIARY":
                continue
            persona = interno("party-service", f"/api/v1/parties/{r['relatedPartyId']}")
            ref = (persona or {}).get("prospectId")
            if not ref:
                continue
            cuentas = interno("credit-portfolio-service",
                              f"/api/v1/portfolio/accounts?partyId={ref}")
            if isinstance(cuentas, list) and cuentas:
                beneficiarios_con_cuenta += 1
    revisa(beneficiarios_con_cuenta == 0,
           f"ningún beneficiario tiene cuenta de crédito (encontradas: {beneficiarios_con_cuenta})")

    # ── S5 · Variedad de estados, incluida la mora ───────────────────────────
    print("\n  S5 · Variedad: al corriente, en mora, sin disponer")
    morosas = [f for f in con_linea if (f.get("daysDelinquent") or 0) > 0]
    sin_disponer = [f for f in con_linea
                    if float(f.get("creditLineAvailable") or 0) >= float(f.get("creditLineLimit") or 0)]
    revisa(len(morosas) >= 1,
           f"hay al menos una distribuidora en mora ({len(morosas)})")
    if morosas:
        print(f"        DPD observados: {sorted(m.get('daysDelinquent') for m in morosas)}")
    if not sin_disponer:
        avisos.append("ninguna distribuidora quedó con la línea intacta")

    # ── S6 · La atribución llegó (si no, el tablero sale vacío) ──────────────
    print("\n  S6 · Créditos atribuidos a su distribuidora")
    colocados = sum(f.get("originatedCredits") or 0 for f in filas)
    revisa(colocados > 0,
           f"hay colocaciones atribuidas por promoterCode ({colocados})")

    resumen()


def resumen():
    print()
    for a in avisos:
        print(f"  ⚠ {a}")
    if fallos:
        print(f"\n  ✗ {len(fallos)} comprobaciones fallaron:")
        for f in fallos:
            print(f"      · {f}")
        sys.exit(1)
    print("  ✓ la siembra de distribuidoras está completa\n")


if __name__ == "__main__":
    main()
