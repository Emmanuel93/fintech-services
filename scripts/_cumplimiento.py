#!/usr/bin/env python3
"""Cerrar la cola de cumplimiento para la gente que estos scripts crean.

## Por qué los seeds tienen que hacer esto ahora

Desde que existe `compliance-service` (D16), toda persona registrada obtiene un veredicto de KYC y
de listas, y las compuertas de originación y de disposición lo exigen. Mientras no haya adaptador de
Incode/Jumio, la identidad de **todos** cae en `IN_REVIEW` —es el modo vigente, no un fallo— y la
resuelve un analista.

Un script de siembra que no cierre esa cola no siembra nada: la primera solicitud rebota con
`PersonNotVerifiedException` y el resto del flujo no ocurre. Así que el script **hace lo que haría el
analista**, por la misma puerta que usaría él.

## Y lo deja dicho en el dato

El dictamen se firma como `siembra-demo`, no con el nombre de una persona que no lo revisó. Quien
mire la tabla después tiene que poder distinguir una adjudicación real de una sembrada; firmar con un
nombre plausible sería fabricar la evidencia de un control que nadie ejecutó.

## Y por la red de Docker, no por el gateway

La cola de cumplimiento **no está enrutada hacia fuera** —y está bien que no lo esté: adjudicar es
una facultad de backoffice, no una API pública—. Se llama por la red interna, igual que
`enviar_a_mesa` y los demás `test-support`.
"""
from __future__ import annotations

import json
import subprocess
import time

RED_DOCKER = "fintech-services_fintech-network"
RUTA = "/api/v1/compliance"

AUTOR = "siembra-demo"
NOTA_IDENTIDAD = ("Entorno de demostración: expediente sembrado por script, sin revisión humana. "
                  "No es evidencia de un cotejo de identidad real.")
NOTA_LISTAS = ("Entorno de demostración: homonimia descartada por el script sobre datos "
               "sintéticos. No es una adjudicación real.")


def _llama(method, ruta, body=None):
    """Una petición a compliance por la red de Docker. Devuelve el cuerpo, o None si no fue 2xx.

    El 404 entra por aquí como None y es un caso **normal**: la verificación la crea un listener de
    Kafka y durante unos cientos de milisegundos todavía no existe.
    """
    cmd = ["docker", "run", "--rm", "--network", RED_DOCKER, "curlimages/curl:latest",
           "-s", "-w", "\n%{http_code}", "-X", method,
           f"http://compliance-service:8080{RUTA}{ruta}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    r = subprocess.run(cmd, capture_output=True)
    salida = r.stdout.decode().strip().rsplit("\n", 1)
    codigo = salida[-1] if salida else ""
    cuerpo = salida[0] if len(salida) > 1 else ""
    if codigo not in ("200", "201", "204"):
        return None
    try:
        return json.loads(cuerpo) if cuerpo else {}
    except json.JSONDecodeError:
        return {}


def veredicto(prospect_id, intentos=20, pausa=0.5):
    """La verificación del prospecto, esperando a que el listener la haya creado."""
    for _ in range(intentos):
        v = _llama("GET", f"/verifications/by-prospect/{prospect_id}")
        if v:
            return v
        time.sleep(pausa)
    return None


def despeja(prospect_id, datos=None) -> bool:
    """Lleva a una persona hasta «puede operar»: identidad comprobada y listas despejadas.

    `datos` sólo se usa si la verificación no existe —una persona creada antes de que el control
    existiera—; entonces se pide la evaluación bajo demanda, que es idempotente por prospecto.

    Devuelve `False` si quedó bloqueada **de verdad**. Una coincidencia confirmada de listas no se
    despeja desde aquí: hacerlo convertiría al seed en una forma de saltarse el control que estamos
    sembrando, y entonces la demo demostraría lo contrario de lo que queremos demostrar.
    """
    v = veredicto(prospect_id)

    if v is None and datos:
        v = _llama("POST", "/verifications", {"prospectId": str(prospect_id), **datos})
    if v is None:
        return False

    vid = v.get("verificationId")

    # ── Listas ──────────────────────────────────────────────────────────────────────────────
    if v.get("screeningStatus") == "CONFIRMED_MATCH":
        return False                      # es el caso que el control existe para detener
    if v.get("screeningStatus") in ("POTENTIAL_MATCH", "PENDING"):
        v = _llama("POST", f"/verifications/{vid}/adjudicate",
                   {"decision": "CLEARED", "decidedBy": AUTOR, "notes": NOTA_LISTAS}) or v

    # ── Identidad ───────────────────────────────────────────────────────────────────────────
    if v.get("identityStatus") != "VERIFIED":
        v = _llama("POST", f"/verifications/{vid}/identity",
                   {"decision": "VERIFIED", "decidedBy": AUTOR, "notes": NOTA_IDENTIDAD}) or v

    return v.get("identityStatus") == "VERIFIED" and v.get("screeningStatus") == "CLEARED"
