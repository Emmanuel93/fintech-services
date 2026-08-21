#!/usr/bin/env python3
"""Siembra una estructura comercial completa: Nacional → Región → Zona → Sucursal → Ejecutivo.

Existe porque los seis niveles estaban dados de alta pero **sin una sola unidad
por debajo de la raíz**: el módulo de estructura y el tablero comercial salían
en ceros y no había forma de saber si estaban mal o simplemente vacíos.

Todo pasa por el BFF (`backoffice.localhost`), igual que lo haría la consola:
si un endpoint no acepta lo que le mandamos aquí, tampoco lo va a aceptar
desde la UI. Es idempotente por código de unidad —volver a correrlo no duplica—
y los empleados que crea llevan el sufijo del código de su sucursal.

Los códigos van con guion bajo, no con guion: sales-org los usa como etiqueta
LTREE para el `path` del árbol y sólo acepta `[A-Za-z0-9_]+`.

    python3 scripts/seed-sales-org.py [--gateway http://backoffice.localhost:8090]
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import urllib.error
import urllib.request

from _identidad import curp_demo

ADMIN = ("admin@kredius.mx", "Backoffice#2026")
# La misma para todo el personal sembrado, y la misma del admin de arranque. El acceso rápido
# de la consola carga una sola contraseña (VITE_DEV_PASSWORD): un empleado con otra rompe el
# atajo sin decir por qué —carga las credenciales y el botón de entrar contesta 401—, que es
# exactamente lo que pasaba con estos 52 mientras aquí decía otra cosa.
STAFF_PASSWORD = "Backoffice#2026"

# Estructura real de una financiera mexicana: cuatro regiones, sus zonas y las
# sucursales donde de verdad se coloca crédito.
# Regiones con IVA distinto del nacional. La franja fronteriza norte tiene estímulo fiscal.
IVA_POR_REGION = {"NORTE": "0.0800"}

REGIONES = {
    "NORTE": {
        "nombre": "Región Norte",
        "zonas": {
            "NOROESTE": ("Zona Noroeste", [("CUL", "Culiacán"), ("HMO", "Hermosillo"), ("LMO", "Los Mochis")]),
            "NORESTE": ("Zona Noreste", [("MTY", "Monterrey Centro"), ("SAP", "San Pedro"), ("SAL", "Saltillo")]),
        },
    },
    "OCCIDENTE": {
        "nombre": "Región Occidente",
        "zonas": {
            "BAJIO": ("Zona Bajío", [("LEO", "León"), ("QRO", "Querétaro"), ("AGS", "Aguascalientes")]),
            "PACIFICO": ("Zona Pacífico", [("GDL", "Guadalajara Centro"), ("ZAP", "Zapopan"), ("PVR", "Puerto Vallarta")]),
        },
    },
    "CENTRO": {
        "nombre": "Región Centro",
        "zonas": {
            "VALLE": ("Zona Valle de México", [("CDMX", "CDMX Reforma"), ("POL", "Polanco"), ("SAT", "Satélite")]),
            "ORIENTE": ("Zona Oriente", [("PUE", "Puebla"), ("PAC", "Pachuca"), ("TOL", "Toluca")]),
        },
    },
    "SURESTE": {
        "nombre": "Región Sureste",
        "zonas": {
            "GOLFO": ("Zona Golfo", [("VER", "Veracruz"), ("VIL", "Villahermosa")]),
            "PENINSULA": ("Zona Península", [("MER", "Mérida"), ("CUN", "Cancún")]),
        },
    },
}

NOMBRES = ["Alejandra", "Bruno", "Carolina", "Diego", "Elena", "Fernando", "Gabriela", "Héctor",
           "Itzel", "Joaquín", "Karla", "Luis", "Mariana", "Néstor", "Olivia", "Pablo",
           "Renata", "Sergio", "Tania", "Ulises", "Valeria", "Ximena", "Yahir", "Zaira"]
APELLIDOS = ["Aguilar", "Beltrán", "Carrillo", "Domínguez", "Escobar", "Fuentes", "Guzmán",
             "Herrera", "Ibarra", "Juárez", "Lozano", "Mendoza", "Navarro", "Ochoa",
             "Peralta", "Quintero", "Rangel", "Salazar", "Treviño", "Uribe", "Vázquez", "Zamora"]


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token = self._login()

    def _raw(self, method: str, path: str, body=None, token=None):
        req = urllib.request.Request(
            f"{self.base}{path}", method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"Content-Type": "application/json",
                     **({"Authorization": f"Bearer {token}"} if token else {})})
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return json.loads(raw) if raw else None

    def _login(self) -> str:
        return self._raw("POST", "/auth/staff/login",
                         {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]

    def get(self, path):
        return self._raw("GET", path, token=self.token)

    def put(self, path, body):
        """Actualiza sin ruido: reparar algo ya sembrado no es un error digno de tumbar la corrida."""
        try:
            return self._raw("PUT", path, body, token=self.token)
        except urllib.error.HTTPError as e:
            print(f"  ✗ {e.code} {path}: {e.read().decode()[:160]}", file=sys.stderr)
            return None

    def patch_vat(self, code, rate):
        """
        Fija el IVA de una unidad. Su subárbol lo hereda.

        La tasa va como parámetro de consulta y el cuerpo vacío porque es un solo valor escalar:
        un DTO de un campo sólo añade ceremonia.
        """
        return self.put(f"/sales-org/units/by-code/{code}/vat-rate?rate={rate}", None)

    def post(self, path, body):
        """Devuelve `None` cuando el backend rechaza por duplicado: sembrar dos veces no es un error."""
        try:
            return self._raw("POST", path, body, token=self.token)
        except urllib.error.HTTPError as e:
            detail = e.read().decode()[:200]
            if e.code in (409, 400) and ("existe" in detail or "duplic" in detail or "already" in detail):
                return None
            print(f"  ✗ {e.code} {path}: {detail}", file=sys.stderr)
            return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gateway", default="http://backoffice.localhost:8090")
    args = ap.parse_args()
    random.seed(20260811)  # mismos nombres en cada corrida: la evidencia es comparable

    api = Api(args.gateway)

    niveles = {l["code"]: l["levelId"] for l in api.get("/sales-org/levels")}
    for req in ("NATIONAL", "REGION", "ZONE", "BRANCH", "EXECUTIVE"):
        if req not in niveles:
            sys.exit(f"Falta el nivel {req} en sales-org")

    unidades = {u["code"]: u["unitId"] for u in api.get("/sales-org/units")}

    def unidad(level: str, code: str, name: str, parent: str | None,
               party_ref: str | None = None) -> str:
        """
        Crea o recupera una unidad.

        `party_ref` enlaza el nodo con la persona detrás: el staffUserId para un ejecutivo, el
        partyId para un distribuidor. La migración `007` lo declara justamente para eso y hasta ahora
        se mandaba siempre nulo, así que el árbol sabía que existía «Ejecutivo 1 de Culiacán» y no
        sabía **quién** era. Todo lo que quiera ir del nodo a la persona —el alcance del tablero, la
        atribución contable— tenía que rodearlo por las asignaciones.
        """
        if code in unidades:
            return unidades[code]
        r = api.post("/sales-org/units", {
            "levelId": niveles[level], "parentUnitId": parent,
            "code": code, "name": name, "partyRef": party_ref})
        if r is None:  # ya existía con otro nombre: se relee
            unidades.update({u["code"]: u["unitId"] for u in api.get("/sales-org/units")})
            return unidades[code]
        unidades[code] = r["unitId"]
        return r["unitId"]

    raiz = next((u["unitId"] for u in api.get("/sales-org/units") if u["parentUnitId"] is None), None)
    if not raiz:
        sys.exit("No hay unidad raíz (nacional) sembrada")
    print(f"raíz: {raiz}")

    staff_existente = {s["email"]: s["staffUserId"] for s in (api.get("/staff") or [])}
    creados = {"unidades": 0, "empleados": 0, "asignaciones": 0}

    for rcode, region in REGIONES.items():
        rid = unidad("REGION", f"R_{rcode}", region["nombre"], raiz)
        creados["unidades"] += 1
        # El IVA de la Región Norte, por el estímulo fiscal de la franja fronteriza.
        #
        # Se declara en la región y no en cada sucursal: el árbol lo hereda hacia abajo, así que la
        # próxima sucursal que se abra nace con la tasa de su plaza sin que nadie tenga que
        # acordarse. Y se pone aquí, no en una migración, porque la región no existe hasta que esta
        # siembra la crea — un UPDATE en la migración no encontraría la fila y no lo diría.
        if rcode in IVA_POR_REGION:
            api.patch_vat(f"R_{rcode}", IVA_POR_REGION[rcode])
        for zcode, (znombre, sucursales) in region["zonas"].items():
            zid = unidad("ZONE", f"Z_{zcode}", znombre, rid)
            creados["unidades"] += 1
            for scode, snombre in sucursales:
                sid = unidad("BRANCH", f"S_{scode}", f"Sucursal {snombre}", zid)
                creados["unidades"] += 1

                # Dos o tres ejecutivos por sucursal, cada uno su propia unidad
                # hoja: es lo que permite que la cartera cuelgue de una persona.
                for i in range(random.randint(2, 3)):
                    nombre = f"{random.choice(NOMBRES)} {random.choice(APELLIDOS)} {random.choice(APELLIDOS)}"
                    ecode = f"E_{scode}_{i + 1}"
                    email = f"{ecode.lower()}@kredius.mx"

                    # El empleado primero, el nodo después: así el nodo nace con su `partyRef` puesto.
                    # Al revés habría que actualizarlo, y no hay endpoint para eso — que es
                    # exactamente por lo que los 52 ejecutivos quedaron con el enlace vacío.
                    staff_id = staff_existente.get(email)
                    if staff_id:
                        # Ya existía: se le fija la contraseña común. Sin esto, cambiar
                        # STAFF_PASSWORD no arregla a nadie —el alta se salta y el empleado se
                        # queda con la de la corrida anterior—, que es como los 52 ejecutivos
                        # acabaron con una distinta a la de todos los demás.
                        if api.put(f"/staff/{staff_id}/password",
                                   {"password": STAFF_PASSWORD}) is not None:
                            creados["contraseñas"] = creados.get("contraseñas", 0) + 1
                    else:
                        # La CURP va en el alta: sin ella la bitácora identifica al ejecutivo
                        # por un UUID, y la trazabilidad exige la persona, no el identificador.
                        r = api.post("/staff", {
                            "email": email, "fullName": nombre, "employeeType": "INTERNO",
                            "curp": curp_demo(email, nombre),
                            "distributorPartyId": None, "roles": ["EXECUTIVE"],
                            "password": STAFF_PASSWORD})
                        if r:
                            staff_id = r.get("staffUserId")
                            staff_existente[email] = staff_id
                            creados["empleados"] += 1

                    eid = unidad("EXECUTIVE", ecode, nombre, sid, party_ref=staff_id)
                    creados["unidades"] += 1

                    # La asignación se conserva además del `partyRef`: el nodo dice de quién es la
                    # plaza, la asignación dice quién la ocupa hoy y lleva su historial. No son lo
                    # mismo y perder la segunda borra el rastro de las reasignaciones.
                    if staff_id:
                        if api.post(f"/sales-org/units/{eid}/assignments", {
                                "assigneeType": "STAFF", "assigneeId": staff_id,
                                "assignmentRole": "OWNER"}) is not None:
                            creados["asignaciones"] += 1

    total = len(api.get("/sales-org/units"))
    print(f"unidades en el árbol: {total}")
    print(f"altas de esta corrida: {creados}")


if __name__ == "__main__":
    main()
