#!/usr/bin/env python3
"""Remedia la cartera que existía antes de que existiera el control de KYC/AML.

## El problema que esto resuelve

`party.parties` ganó `identity_status` y `screening_status` con default `PENDING`, y **PENDING
bloquea**. Es deliberado: un default permisivo habría dejado a toda la cartera histórica marcada
como comprobada sin que nadie la comprobara, que es exactamente el dato que un revisor pediría.

Pero bloquear sin dar salida no es un control, es una pared. Las personas que ya existían no tienen
verificación y **no hay nada que se la cree**: el disparador es `origination.prospect-created`, y
para ellas ese evento ya pasó. Quedarían bloqueadas para siempre, no por una decisión sino por una
ausencia.

Esto les da la salida: pide la evaluación bajo demanda —que corre el cruce real contra las listas
vigentes— y reporta el padrón resultante.

## Lo que hace y lo que NO hace

* **Sí** cruza a cada persona contra las listas negras y de PEP vigentes. Eso es trabajo real.
* **No** dictamina identidades. Ni una. El dictamen de identidad lo firma un analista, y un script
  firmando por él sería fabricar la evidencia del control. Lo que hace es dejar la cola lista y
  decir cuánta gente hay en ella.
* **No** toca a quien ya tiene veredicto: la evaluación es idempotente por prospecto, así que
  volver a correrlo no pisa ninguna adjudicación firmada.

## Cómo leer su salida

Al final imprime el padrón en tres cubetas. La que importa es la última: son las personas que un
analista tiene que mirar antes de que puedan volver a operar, y su tamaño es la deuda real de la
migración — no una estimación.
"""
from __future__ import annotations

import json
import subprocess
import sys

# Por la red de Docker, no por el gateway: ni el padrón de personas ni la cola de cumplimiento
# están enrutados hacia fuera, y está bien que no lo estén.
RED_DOCKER = "fintech-services_fintech-network"
PAGINA = 100
SEED_USER_ID = "00000000-0000-0000-0000-000000000001"


def http(servicio, method, ruta, body=None):
    """Devuelve el cuerpo, o None si no fue 2xx. El código se conserva en `http.ultimo_codigo`."""
    cmd = ["docker", "run", "--rm", "--network", RED_DOCKER, "curlimages/curl:latest",
           "-s", "-w", "\n%{http_code}", "-X", method,
           "-H", f"X-User-Id: {SEED_USER_ID}",
           f"http://{servicio}:8080{ruta}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    r = subprocess.run(cmd, capture_output=True)
    salida = r.stdout.decode().strip().rsplit("\n", 1)
    codigo = salida[-1] if salida else ""
    cuerpo = salida[0] if len(salida) > 1 else ""
    http.ultimo_codigo = codigo
    if codigo not in ("200", "201", "204"):
        return None
    try:
        return json.loads(cuerpo) if cuerpo else {}
    except json.JSONDecodeError:
        return {}


http.ultimo_codigo = ""


def personas():
    """Todas las personas, paginando. Es el padrón sobre el que hay que remediar."""
    pagina = 0
    while True:
        r = http("party-service", "GET",
                 f"/api/v1/parties?page={pagina}&size={PAGINA}")
        contenido = (r or {}).get("content") or []
        if not contenido:
            return
        yield from contenido
        if len(contenido) < PAGINA:
            return
        pagina += 1


def evalua(p):
    """Pide el cruce de una persona. Devuelve su veredicto, o None si no se pudo."""
    nombre = " ".join(filter(None, [p.get("firstName"), p.get("lastName1"), p.get("lastName2")])).strip()
    if not nombre:
        return None
    v = http("compliance-service", "POST", "/api/v1/compliance/verifications", {
        "prospectId": p.get("prospectId"),
        "partyId": p.get("partyId"),
        "fullName": nombre,
        "dateOfBirth": p.get("dateOfBirth"),
        "curp": p.get("curp"),
        "rfc": p.get("rfc"),
        "country": "MX",
    })
    if v is None:
        print(f"  ! {nombre}: compliance respondió {http.ultimo_codigo}", file=sys.stderr)
    return v


def main():
    listas_ok, en_cola, bloqueadas, sin_evaluar = [], [], [], []

    for p in personas():
        nombre = " ".join(filter(None, [p.get("firstName"), p.get("lastName1")])).strip()

        # Quien ya puede operar no se toca: su veredicto lo firmó alguien.
        if p.get("verificationClear"):
            listas_ok.append(nombre)
            continue

        v = evalua(p)
        if v is None:
            sin_evaluar.append(nombre)
            continue

        if v.get("screeningStatus") == "CONFIRMED_MATCH":
            bloqueadas.append(f"{nombre} — coincidencia CONFIRMADA de listas")
        else:
            en_cola.append(f"{nombre} — identidad {v.get('identityStatus')}, "
                           f"listas {v.get('screeningStatus')}")

    print()
    print("═══ PADRÓN DE REMEDIACIÓN ═══")
    print(f"  Ya podían operar (sin tocar) ....... {len(listas_ok)}")
    print(f"  Cruzadas y en cola de identidad .... {len(en_cola)}")
    print(f"  BLOQUEADAS por listas .............. {len(bloqueadas)}")
    print(f"  No se pudieron evaluar ............. {len(sin_evaluar)}")
    print()

    if bloqueadas:
        print("Coincidencias confirmadas — NO se desbloquean desde aquí:")
        for b in bloqueadas:
            print(f"  · {b}")
        print()

    if en_cola:
        print(f"{len(en_cola)} personas esperan dictamen de identidad de un analista.")
        print("Esa es la deuda real de la migración, y la firma una persona, no este script.")
        print()

    if sin_evaluar:
        print("Sin evaluar (revisar a mano):")
        for s in sin_evaluar:
            print(f"  · {s}")


if __name__ == "__main__":
    main()
