#!/usr/bin/env python3
"""Le devuelve su ejecutivo a los clientes que se quedaron sin uno, y vuelve a atribuir su contabilidad.

Un cliente sin ejecutivo es cartera que no cuelga de ninguna rama: el árbol comercial suma menos que
el tablero y no hay forma de ver por qué. El síntoma sale en una pantalla y la causa está tres
servicios atrás.

**La geografía se recupera de la CURP.** Sus posiciones 12–13 llevan la clave del estado de
nacimiento (`...H DF ...`, `...M JC ...`), que es la misma que la siembra usó para decidir la
sucursal. Así el crédito vuelve a la rama que le tocaba en vez de repartirse con una heurística que
nadie podría auditar después.

Idempotente: sólo toca a quien no tiene ejecutivo. Usa los mismos endpoints que la consola.
"""
from __future__ import annotations

import json
import sys
import urllib.parse
import urllib.request
from collections import Counter

BACKOFFICE = "http://backoffice.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")

# Clave de estado en la CURP → sucursales de esa plaza, en el mismo orden que usa la siembra.
SUCURSALES_POR_ESTADO = {
    "SL": ["S_CUL", "S_LMO"], "SR": ["S_HMO"], "NL": ["S_MTY", "S_SAP"],
    "CL": ["S_SAL"], "GT": ["S_LEO"], "QT": ["S_QRO"], "AS": ["S_AGS"],
    "JC": ["S_GDL", "S_ZAP", "S_PVR"], "DF": ["S_CDMX", "S_POL"],
    "MC": ["S_SAT", "S_TOL"], "PL": ["S_PUE"], "HG": ["S_PAC"],
    "VZ": ["S_VER"], "TC": ["S_VIL"], "YN": ["S_MER"], "QR": ["S_CUN"],
}


def http(method, url, body=None, token=None, timeout=90):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def ejecutivos_por_sucursal(token):
    """Sucursal → ejecutivos que cuelgan de ella (sus nodos hijos, vía `partyRef`)."""
    unidades = http("GET", f"{BACKOFFICE}/sales-org/units", token=token) or []
    niveles = {n["code"]: n["levelId"]
               for n in (http("GET", f"{BACKOFFICE}/sales-org/levels", token=token) or [])}
    por_id = {u["unitId"]: u for u in unidades}

    mapa = {}
    for u in unidades:
        if u.get("levelId") != niveles.get("EXECUTIVE") or not u.get("partyRef"):
            continue
        sucursal = por_id.get(u.get("parentUnitId")) or {}
        mapa.setdefault(sucursal.get("code"), []).append(str(u["partyRef"]))
    return mapa


def main():
    token = http("POST", f"{BACKOFFICE}/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]

    ejecutivos = ejecutivos_por_sucursal(token)
    if not ejecutivos:
        sys.exit("No hay ejecutivos en el árbol: nada que reparar")

    # Todos los clientes, paginando.
    huerfanos, pagina = [], 0
    while True:
        p = http("GET", f"{BACKOFFICE}/clients?page={pagina}&size=100", token=token)
        filas = p.get("content", []) if isinstance(p, dict) else (p or [])
        if not filas:
            break
        huerfanos += [c for c in filas if not c.get("assignedExecutiveId")]
        if pagina + 1 >= int(p.get("totalPages", 1)):
            break
        pagina += 1

    print(f"  {len(huerfanos)} clientes sin ejecutivo")
    if not huerfanos:
        print("  ✓ nada que reparar\n")
        return

    turno, reparados = Counter(), 0
    sin_plaza = []
    for c in huerfanos:
        curp = (c.get("curp") or "").upper()
        clave = curp[11:13] if len(curp) >= 13 else ""
        plazas = SUCURSALES_POR_ESTADO.get(clave, [])
        candidatas = [s for s in plazas if ejecutivos.get(s)]
        if not candidatas:
            sin_plaza.append((c.get("partyId"), curp, clave))
            continue

        sucursal = candidatas[turno[clave] % len(candidatas)]
        plantilla = ejecutivos[sucursal]
        ejecutivo = plantilla[turno[sucursal] % len(plantilla)]
        turno[clave] += 1
        turno[sucursal] += 1

        try:
            http("POST", f"{BACKOFFICE}/clients/{c['partyId']}/assign-executive",
                 {"executiveId": ejecutivo}, token=token)
            reparados += 1
        except Exception as e:
            sin_plaza.append((c.get("partyId"), curp, str(e)[:50]))

    print(f"  {reparados} reasignados a la sucursal de su plaza")
    if sin_plaza:
        print(f"  ⚠ {len(sin_plaza)} sin plaza resoluble:")
        for pid, curp, motivo in sin_plaza[:5]:
            print(f"      {curp}  {motivo}")

    print("\n  ▸ Reconciliando la sucursal de origen en contabilidad")
    r = http("POST", f"{BACKOFFICE}/accounting/backfill-origin-units", token=token) or {}
    print(f"    sellados={r.get('sellados')} yaSellados={r.get('yaSellados')} "
          f"sinEjecutivo={r.get('sinEjecutivoAsignado')} pólizas={r.get('polizasAtribuidas')}")


if __name__ == "__main__":
    main()
