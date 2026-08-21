#!/usr/bin/env python3
"""Deja la red sin cartera huérfana: todo cliente con ejecutivo, repartido por sucursal.

Existe porque «sembrar cartera» y «que la cartera cuelgue de alguien» son dos cosas
distintas, y la segunda se caía en silencio. `seed-portfolio.py` asigna ejecutivo
sólo a los clientes que él mismo creó y sólo si vuelve a encontrarlos buscando por
apellido y CURP; cuando esa búsqueda no acierta —o el cliente entró por otra vía—
el crédito queda vivo y sin dueño. El resultado era un tercio de la cartera
invisible para la estructura comercial: $1.7M que no aparecían en la meta de
ninguna sucursal y que hacían que el tablero comercial y el panorama de cartera
dieran cifras distintas del mismo negocio.

Esto no repara el seed, lo **reconcilia**: parte de la lista real de clientes —no
de lo que una corrida anterior creyó crear— y le pone ejecutivo a todo el que no
tenga. Por eso es idempotente y se puede correr cuantas veces haga falta: si no
hay nada suelto, no hace nada.

El reparto es por sucursal y en round-robin dentro de ella, no aleatorio sobre la
red entera. Repartir al azar produce sucursales con quince clientes y otras con
uno, y entonces las comparaciones entre ramas —que es para lo que existe el
tablero— no dicen nada del negocio, sólo del sorteo.

    python3 scripts/asigna-cartera.py                  # asigna lo que falte
    python3 scripts/asigna-cartera.py --dry-run        # sólo dice qué haría
"""
from __future__ import annotations

import argparse
import itertools
import json
import sys
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


def todas_las_paginas(base, path, token):
    """Recorre las páginas hasta agotarlas.

    Los servicios topan la página en cien filas y no avisan: pedir quinientas
    devuelve cien, con un 200 y sin señal de que falta el resto. Leer una sola
    página deja fuera justo a los clientes que hay que arreglar.
    """
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
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    base = args.gateway.rstrip("/")

    token = call(base, "POST", "/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]

    # Los ejecutivos, agrupados por la sucursal de la que cuelgan. La unidad hoja *es* el
    # ejecutivo, así que su sucursal es el padre de esa unidad.
    unidades = {u["unitId"]: u for u in call(base, "GET", "/sales-org/units", token=token)}
    raiz = next(u for u in unidades.values() if not u.get("parentUnitId"))
    asignaciones = call(base, "GET", f"/sales-org/units/{raiz['unitId']}/scope", token=token)

    por_sucursal: dict[str, list[str]] = {}
    for a in asignaciones:
        if a.get("assigneeType") != "STAFF" or not a.get("active", True):
            continue
        unidad = unidades.get(str(a.get("unitId")))
        if not unidad:
            continue
        sucursal = unidad.get("parentUnitId") or unidad["unitId"]
        por_sucursal.setdefault(sucursal, []).append(str(a["assigneeId"]))

    ejecutivos = [e for lista in por_sucursal.values() for e in lista]
    if not ejecutivos:
        print("✗ no hay ejecutivos asignados en la estructura", file=sys.stderr)
        return 1

    clientes = todas_las_paginas(base, "/clients", token)
    sueltos = [c for c in clientes if not c.get("assignedExecutiveId")]

    print(f"▸ {len(clientes)} clientes · {len(sueltos)} sin ejecutivo · "
          f"{len(ejecutivos)} ejecutivos en {len(por_sucursal)} sucursales")
    if not sueltos:
        print("  ✓ nada que asignar: toda la cartera cuelga de alguien")
        return 0
    if args.dry_run:
        print("  (dry-run: no se asigna nada)")
        return 0

    # Round-robin por sucursal: se recorren las sucursales en orden y dentro de cada una sus
    # ejecutivos, para que el reparto quede parejo entre ramas y entre personas.
    sucursales = sorted(por_sucursal)
    turnos = itertools.cycle(
        [(s, e) for s in sucursales for e in sorted(por_sucursal[s])]
    )

    ok = fallos = 0
    for c in sueltos:
        _, ejecutivo = next(turnos)
        try:
            call(base, "POST", f"/clients/{c['partyId']}/assign-executive",
                 {"executiveId": ejecutivo}, token=token)
            ok += 1
        except urllib.error.HTTPError as e:
            fallos += 1
            if fallos <= 3:
                print(f"  ✗ {c.get('fullName', c['partyId'])}: {e.code} "
                      f"{e.read().decode()[:120]}", file=sys.stderr)

    print(f"  ✓ {ok} asignados" + (f" · ✗ {fallos} fallaron" if fallos else ""))

    # Se vuelve a preguntar en vez de confiar en el contador: lo que importa no es cuántas
    # llamadas salieron con 200, es que no quede nada suelto.
    restantes = [c for c in todas_las_paginas(base, "/clients", token)
                 if not c.get("assignedExecutiveId")]
    if restantes:
        print(f"  ✗ siguen sin ejecutivo: {len(restantes)}", file=sys.stderr)
        return 1
    print("  ✓ comprobado: ningún cliente queda sin ejecutivo")
    return 0


if __name__ == "__main__":
    sys.exit(main())
