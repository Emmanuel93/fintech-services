#!/usr/bin/env python3
"""Da de alta un responsable por cada nivel comercial, para validar el alcance.

El alcance del backoffice no se prueba con el admin: los roles transversales
—ADMIN, riesgo, finanzas, auditoría— ven cualquier unidad por diseño. Lo que hay
que poder comprobar es lo contrario: que quien manda una región ve su región y lo
que cuelga de ella, y **no** la de al lado.

Para eso hace falta una persona parada en cada escalón del árbol. Este script las
crea y las asigna:

    Nacional   → EXECUTIVE, asignado a MX          → ve todo el país
    Región     → EXECUTIVE, asignado a R_NORTE     → su región y abajo
    Zona       → EXECUTIVE, asignado a Z_NOROESTE  → su zona y abajo
    Sucursal   → EXECUTIVE, asignado a S_CUL       → su sucursal y sus ejecutivos
    Ejecutivo  → EXECUTIVE, asignado a E_CUL_1     → sólo lo suyo

Todos con el rol EXECUTIVE a propósito: el rol dice *qué* puede hacer y la unidad
*sobre qué*. Son dos preguntas distintas y el sistema las responde por separado;
mezclarlas —un rol por nivel— duplicaría la jerarquía en la matriz de permisos.

    python3 scripts/seed-commercial-staff.py
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

from _identidad import curp_demo

ADMIN = ("admin@kredius.mx", "Backoffice#2026")
# La misma que el resto del personal sembrado: el acceso rápido del login carga una
# contraseña única (VITE_DEV_PASSWORD), y un empleado con otra rompe el atajo sin
# decir por qué —carga las credenciales y el botón de entrar contesta 401—.
PASSWORD = "Backoffice#2026"

# nivel → (código de unidad, correo, nombre, rol)
#
# Los cuatro que dirigen una rama llevan COMMERCIAL_MANAGER; el de a pie, EXECUTIVE. La
# diferencia no es de nivel —el rol es el mismo para los cuatro escalones— sino de puesto:
# dirigir incluye reorganizar lo que cuelga de uno, atender no. Con todos en EXECUTIVE no había
# forma de comprobar el alcance de escritura, porque nadie por debajo de los roles transversales
# podía escribir nada.
NIVELES = [
    ("Nacional", "MX",         "nacional@kredius.mx", "Rodrigo Castañeda Vega", "COMMERCIAL_MANAGER"),
    ("Región",   "R_NORTE",    "region@kredius.mx",   "Paulina Serrano Ríos",   "COMMERCIAL_MANAGER"),
    ("Zona",     "Z_NOROESTE", "zona@kredius.mx",     "Emilio Ávalos Nieto",    "COMMERCIAL_MANAGER"),
    ("Sucursal", "S_CUL",      "sucursal@kredius.mx", "Marisol Cuevas Paredes", "COMMERCIAL_MANAGER"),
    ("Ejecutivo", "E_CUL_1",   "ejecutivo.cul@kredius.mx", "Diego Lozano Fuentes", "EXECUTIVE"),
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

    unidades = {u["code"]: u["unitId"] for u in call(base, "GET", "/sales-org/units", token=token)}
    existentes = {s["email"]: s["staffUserId"] for s in (call(base, "GET", "/staff", token=token) or [])}

    for nivel, code, email, nombre, rol in NIVELES:
        unit_id = unidades.get(code)
        if not unit_id:
            print(f"  ✗ {nivel}: no existe la unidad {code} — corre antes seed-sales-org.py", file=sys.stderr)
            continue

        staff_id = existentes.get(email)
        if not staff_id:
            try:
                creado = call(base, "POST", "/staff", {
                    "email": email, "fullName": nombre, "employeeType": "INTERNO",
                    "curp": curp_demo(email, nombre),
                    "distributorPartyId": None, "roles": [rol], "password": PASSWORD},
                    token=token)
                staff_id = creado["staffUserId"]
            except urllib.error.HTTPError as e:
                print(f"  ✗ {nivel}: {e.code} {e.read().decode()[:120]}", file=sys.stderr)
                continue
        else:
            # Ya existía con otro rol: corregirlo aquí evita que volver a correr el seed deje a
            # los responsables como EXECUTIVE —sin poder reorganizar— y el alcance sin probar.
            try:
                call(base, "PUT", f"/staff/{staff_id}/roles", {"roles": [rol]}, token=token)
            except urllib.error.HTTPError as e:
                print(f"  ⚠ {nivel}: no se pudo fijar el rol {rol} ({e.code})", file=sys.stderr)

        # La asignación anterior se cierra antes: una persona tiene una unidad, no
        # un historial de unidades vigentes a la vez.
        try:
            call(base, "DELETE", f"/sales-org/assignments/STAFF/{staff_id}", token=token)
        except urllib.error.HTTPError:
            pass
        try:
            call(base, "POST", f"/sales-org/units/{unit_id}/assignments",
                 {"assigneeType": "STAFF", "assigneeId": staff_id, "assignmentRole": "OWNER"},
                 token=token)
        except urllib.error.HTTPError as e:
            print(f"  ✗ {nivel}: no se pudo asignar — {e.code}", file=sys.stderr)
            continue

        print(f"  ✓ {nivel:<10} {email:<28} → {code}")

    print(f"\nContraseña de todos: {PASSWORD}")


if __name__ == "__main__":
    main()
