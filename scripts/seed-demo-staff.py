#!/usr/bin/env python3
"""Da de alta el personal de roles funcionales que el acceso rápido de la consola ofrece.

La consola presenta diez empleados para entrar con un clic y ver el RBAC desde dentro. Cinco son
los responsables de la estructura comercial —los siembra `seed-commercial-staff.py`— y uno es el
administrador de arranque. **Los otros cuatro no existían en ninguna siembra**, así que la mitad de
los botones contestaba 401 y la lectura desde fuera era «sólo entra el admin».

Son justamente los que hacen interesante probar los permisos: el analista que puede estudiar una
solicitud pero no decidirla, el comité que sí decide, el product manager que toca el catálogo y
nadie más. Con sólo roles comerciales y un admin, la matriz de capacidades no se puede ejercitar:
el admin lo puede todo y los comerciales ven lo mismo entre sí.

Ana Torres va adscrita a una sucursal a propósito. Un ejecutivo sin unidad recibe 403 en las
pantallas de alcance comercial —correcto, pero no demuestra nada—; con unidad se ve lo que sí hace
un ejecutivo de a pie frente a quien dirige la rama.

    python3 scripts/seed-demo-staff.py

Los correos son los que manda el acceso rápido de la consola, y van por **puesto** y no por
persona (`analista@`, `ejecutivo@`, `comite@`, `producto@`) igual que los cinco comerciales
(`nacional@`, `region@`…). El nombre es de quien ocupa el puesto y puede cambiar; el correo
identifica el asiento, que es lo que el botón de la consola conoce.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

from _identidad import curp_demo

ADMIN = ("admin@kredius.mx", "Backoffice#2026")
# La misma de todo el personal sembrado. Ver seed-sales-org.py: una sola contraseña, porque el
# acceso rápido de la consola carga una y un empleado con otra rompe el atajo sin decir por qué.
PASSWORD = "Backoffice#2026"

# correo, nombre, roles, unidad comercial (o None si el puesto no es de la red de ventas)
#
# Los roles son los que muestra la consola junto a cada persona. No se reparten «por si acaso»:
# cada uno está para poder comprobar una frontera distinta de la matriz —analizar frente a
# decidir, ver frente a administrar—, y un rol de más borra justo la frontera que se quería ver.
ROSTER = [
    ("analista@kredius.mx", "Sandra Núñez Robles",
     ["CREDIT_ANALYST", "RISK_ANALYST"], None),
    ("ejecutivo@kredius.mx", "Ana Torres Ruiz",
     ["EXECUTIVE"], "E_CUL_2"),
    ("comite@kredius.mx", "Comité de Crédito",
     ["UNDERWRITER", "COMMITTEE"], None),
    ("producto@kredius.mx", "Pablo Vela Ontiveros",
     ["PRODUCT_MANAGER"], None),
]


def call(base, method, path, body=None, token=None):
    req = urllib.request.Request(
        f"{base}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gateway", default="http://backoffice.localhost:8090")
    args = ap.parse_args()
    base = args.gateway.rstrip("/")

    token = call(base, "POST", "/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]

    existentes = {s["email"]: s["staffUserId"]
                  for s in (call(base, "GET", "/staff", token=token) or [])}
    unidades = {u["code"]: u["unitId"]
                for u in (call(base, "GET", "/sales-org/units", token=token) or [])}

    for email, nombre, roles, unidad in ROSTER:
        staff_id = existentes.get(email)
        if not staff_id:
            try:
                staff_id = call(base, "POST", "/staff", {
                    "email": email, "fullName": nombre,
                    "curp": curp_demo(email, nombre),
                    "employeeType": "INTERNO", "distributorPartyId": None,
                    "roles": roles, "password": PASSWORD}, token=token)["staffUserId"]
                estado = "creado"
            except urllib.error.HTTPError as e:
                print(f"  ✗ {nombre}: {e.code} {e.read().decode()[:140]}", file=sys.stderr)
                continue
        else:
            # Ya existía: se le fijan roles y contraseña. Volver a correr el seed tiene que dejar
            # al empleado como dice este archivo, no como quedó de una corrida anterior.
            estado = "actualizado"
            for path, body in (("/roles", {"roles": roles}),
                               ("/password", {"password": PASSWORD})):
                try:
                    call(base, "PUT", f"/staff/{staff_id}{path}", body, token=token)
                except urllib.error.HTTPError as e:
                    print(f"  ⚠ {nombre}: no se pudo fijar {path} ({e.code})", file=sys.stderr)

        adscrito = ""
        if unidad:
            unit_id = unidades.get(unidad)
            if not unit_id:
                print(f"  ⚠ {nombre}: no existe la unidad {unidad} "
                      f"— corre antes seed-sales-org.py", file=sys.stderr)
            else:
                try:
                    call(base, "POST", f"/sales-org/units/{unit_id}/assignments",
                         {"assigneeType": "STAFF", "assigneeId": staff_id,
                          "assignmentRole": "OWNER"}, token=token)
                    adscrito = f" → {unidad}"
                except urllib.error.HTTPError as e:
                    # Ya asignado: reasignar no es un error, es el estado deseado.
                    if e.code not in (400, 409):
                        print(f"  ⚠ {nombre}: sin adscribir ({e.code})", file=sys.stderr)
                    else:
                        adscrito = f" → {unidad}"

        print(f"  ✓ {nombre:<26} {','.join(roles):<28} {estado}{adscrito}")

    print(f"\nContraseña de todos: {PASSWORD}")


if __name__ == "__main__":
    main()
