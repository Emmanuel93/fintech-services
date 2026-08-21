#!/usr/bin/env python3
"""Captura el perfil fiscal CFDI de los clientes, que es lo que le da receptor a las facturas.

Sin esto la facturación no falla: **falla peor**. `InvoiceService.resolveReceptor` cae al RFC
genérico del SAT y timbra todo a «PUBLICO EN GENERAL», así que la pantalla de facturas se llena de
CFDI idénticos y sin dueño. Nadie ve un error —hay facturas, tienen folio, cuadran— y la conclusión
natural es que el módulo está mal hecho, cuando lo que falta es un dato de alta del cliente.

El RFC ya viene del KYC. Lo que falta es el resto del perfil (razón social, régimen, CP y uso), que
en la vida real se captura aparte porque el RFC lo trae la identidad y el régimen lo declara el
cliente. Se manda por el mismo `PUT` que usaría la consola, así que party publica
`party.fiscal-profile-updated` y facturación se entera; escribirlo en la base dejaría a facturación
sin enterarse y el síntoma sería idéntico.

    python3 scripts/seed-perfiles-fiscales.py
    python3 scripts/seed-perfiles-fiscales.py --sin-regimen 0.1   # deja 10% sin perfil, a propósito
"""
from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys

RED = "fintech-services_fintech-network"
PARTY = "http://party-service:8080/api/v1/parties"

# Cabeceras que el gateway inyecta tras validar el JWT. Por dentro de la red hay que ponerlas a mano.
CABECERAS = [
    "-H", "Content-Type: application/json",
    "-H", "X-User-Id: 00000000-0000-0000-0000-0000000000e2",
    "-H", "X-Roles: ADMIN",
    "-H", "X-Channel: SERVICE",
]

# Regímenes del SAT que de verdad aplican a esta cartera. No es decorado: el régimen decide si el
# CFDI puede llevar IVA acreditable, y mezclar personas físicas con 601 produce facturas que un
# contador rechaza de un vistazo.
REGIMEN_PERSONA = ["605", "612", "626"]   # sueldos, actividad empresarial, RESICO
REGIMEN_EMPRESA = ["601"]                 # general de ley personas morales


def curl(args, timeout=60):
    r = subprocess.run(["docker", "run", "--rm", "--network", RED, "curlimages/curl:latest", "-s", *args],
                       capture_output=True, text=True, timeout=timeout)
    return r.stdout


def parties(size=500):
    """Todos los parties, paginando. Sin paginar sólo se ven los primeros 20 y el resto queda sin perfil."""
    out, pagina = [], 0
    while True:
        raw = curl([f"{PARTY}?page={pagina}&size=100", *CABECERAS])
        try:
            datos = json.loads(raw)
        except Exception:
            break
        filas = datos.get("content", [])
        if not filas:
            break
        out.extend(filas)
        if pagina + 1 >= int(datos.get("totalPages", 1)) or len(out) >= size:
            break
        pagina += 1
    return out


def razon_social(p):
    """
    La razón social del CFDI va en mayúsculas y sin acentos, como la registra el SAT.

    No es cosmético: el timbrado compara contra la lista del SAT y una «ñ» o un acento hacen que el
    PAC rechace el comprobante. Con PAC simulado no se nota, y por eso conviene que el dato ya nazca
    bien: el día que se conecte uno de verdad, el error saldría en producción y no aquí.
    """
    if p.get("partyType") == "BUSINESS":
        nombre = p.get("legalName") or p.get("firstName") or ""
    else:
        nombre = " ".join(filter(None, [p.get("firstName"), p.get("lastName1"), p.get("lastName2")]))
    tabla = str.maketrans("áéíóúÁÉÍÓÚñÑüÜ", "aeiouAEIOUnNuU")
    return nombre.translate(tabla).upper().strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sin-regimen", type=float, default=0.08,
                    help="proporción que se deja sin perfil fiscal a propósito")
    ap.add_argument("--semilla", type=int, default=7)
    args = ap.parse_args()
    rnd = random.Random(args.semilla)

    filas = parties()
    print(f"  {len(filas)} parties encontrados")
    if not filas:
        print("  ! no hay parties: corre primero seed-portfolio.py", file=sys.stderr)
        return 1

    puestos, omitidos, fallos = 0, 0, []
    for p in filas:
        # Una parte se queda sin perfil deliberadamente. Es el caso real —clientes que nunca
        # declararon régimen— y es lo que hace visible el fallback a «público en general»: si todos
        # tuvieran perfil, esa rama del código no se ejercitaría nunca y nadie sabría que existe.
        if rnd.random() < args.sin_regimen:
            omitidos += 1
            continue

        nombre = razon_social(p)
        if not nombre:
            omitidos += 1
            continue

        empresa = p.get("partyType") == "BUSINESS"
        cuerpo = json.dumps({
            "taxName": nombre,
            "taxRegime": rnd.choice(REGIMEN_EMPRESA if empresa else REGIMEN_PERSONA),
            "taxZipCode": f"{rnd.randint(1000, 99999):05d}",
            # G03 «gastos en general» es el uso que corresponde a intereses y comisiones de crédito.
            "cfdiUse": "G03",
        })
        salida = curl(["-o", "/dev/null", "-w", "%{http_code}", "-X", "PUT",
                       f"{PARTY}/{p['partyId']}/fiscal-profile", *CABECERAS, "-d", cuerpo])
        if salida.strip() == "200":
            puestos += 1
        else:
            fallos.append(f"{p['partyId']}: HTTP {salida.strip()}")

    print(f"  {puestos} perfiles fiscales capturados · {omitidos} sin régimen (a propósito)")
    if fallos:
        print(f"  ! {len(fallos)} rechazados; el primero: {fallos[0]}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
