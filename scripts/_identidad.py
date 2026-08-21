#!/usr/bin/env python3
"""CURP de demostración para el personal sembrado.

La bitácora de auditoría debe identificar plenamente a quien actúa, y para una persona física eso
incluye la CURP. El personal se creaba sin ella —no existía la columna—, así que toda entrada
atribuida a un empleado quedaba identificada por un UUID y un correo. Ahora `POST /staff` la acepta
y estos scripts la mandan.

Se genera **derivada del correo**, no al azar: los seeds son idempotentes y volver a correrlos tiene
que producir la misma persona. Una CURP distinta en cada pasada convertiría el mismo empleado en dos
sujetos distintos para quien lea la bitácora, que es justo lo contrario de identificar a alguien.

No son CURPs reales ni pretenden serlo: cumplen el formato que valida el dominio
(`^[A-Z]{4}\\d{6}[HM][A-Z]{5}[A-Z0-9]\\d$`) y nada más. Es data de demostración, y usar CURPs de
personas reales en un entorno de pruebas sería exactamente el problema que la auditoría existe para
prevenir.
"""
from __future__ import annotations

import hashlib
import unicodedata

CONSONANTES = "BCDFGHJKLMNPQRSTVWXYZ"
# Claves de entidad federativa del registro civil. Se usa el conjunto real para que la CURP
# generada sea plausible de leer, no para que corresponda a nadie.
ESTADOS = ["AS", "BC", "BS", "CC", "CL", "CM", "CS", "CH", "DF", "DG", "GT", "GR",
           "HG", "JC", "MC", "MN", "MS", "NT", "NL", "OC", "PL", "QT", "QR", "SP",
           "SL", "SR", "TC", "TS", "TL", "VZ", "YN", "ZS"]


def _sin_acentos(texto: str) -> str:
    """«Ávalos» → «Avalos». La Ñ se conserva como N: en ASCII no hay otra cosa que poner."""
    return "".join(c for c in unicodedata.normalize("NFD", texto)
                   if unicodedata.category(c) != "Mn")


def curp_demo(email: str, nombre: str = "", sexo: str | None = None) -> str:
    """CURP con formato válido, estable para un mismo correo."""
    h = hashlib.sha256(email.strip().lower().encode()).digest()

    # 4 letras: iniciales del nombre cuando se puede leerlo, rellenadas del hash si no.
    #
    # Se quitan los acentos antes de tomar la inicial. El formato CURP es ASCII y el dominio lo
    # valida así, de modo que un «Ávalos» producía una CURP con Á que identity rechazaba con 400 —y
    # el alta de ese empleado se perdía sin que el resto del seed se enterara.
    partes = [p for p in _sin_acentos(nombre).upper().split() if p.isalpha()]
    letras = "".join(p[0] for p in partes)[:4]
    letras += "".join(CONSONANTES[h[i] % len(CONSONANTES)] for i in range(4 - len(letras)))

    # 6 dígitos: una fecha de nacimiento verosímil (1965-1999) derivada del hash.
    anio = 65 + h[4] % 35
    mes = 1 + h[5] % 12
    dia = 1 + h[6] % 28

    sexo_letra = sexo if sexo in ("H", "M") else ("H", "M")[h[7] % 2]
    estado = ESTADOS[h[8] % len(ESTADOS)]
    consonantes = "".join(CONSONANTES[h[9 + i] % len(CONSONANTES)] for i in range(3))
    homoclave = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"[h[12] % 36]
    verificador = h[13] % 10

    return (f"{letras}{anio:02d}{mes:02d}{dia:02d}{sexo_letra}"
            f"{estado}{consonantes}{homoclave}{verificador}")


if __name__ == "__main__":
    import re
    patron = re.compile(r"^[A-Z]{4}\d{6}[HM][A-Z]{5}[A-Z0-9]\d$")
    muestras = [("nacional@kredius.mx", "Rodrigo Castañeda Vega"),
                ("e_cul_1@kredius.mx", "Ana Torres Ruiz"),
                # Acentos y Ñ: el caso que rompía el alta con un 400 de identity.
                ("zona@kredius.mx", "Emilio Ávalos Nieto"),
                ("x@kredius.mx", "Íñigo Ñúñez Óscar"),
                ("admin@kredius.mx", "Administrador de arranque")]
    for correo, nombre in muestras:
        c = curp_demo(correo, nombre)
        assert patron.match(c), f"formato inválido: {c}"
        assert c == curp_demo(correo, nombre), "no es estable"
        print(f"{correo:32s} {nombre:28s} {c}")
    print("formato y estabilidad: ok")
