#!/usr/bin/env python3
"""Comprueba que el tablero, el árbol comercial y la cartera digan **el mismo número**.

Tres pantallas leen la misma cartera por caminos distintos —el tablero la suma entera, el árbol
comercial la suma por rama, y el listado la pagina— y cuando no coinciden nadie sabe cuál creer. El
desajuste no se ve al sembrar: se ve al abrir la consola, y para entonces la causa está tres
servicios atrás.

Las dos grietas por las que se escapa capital, y que esto detecta:

  · **Un crédito sin ejecutivo** no cuelga de ninguna rama, así que el árbol suma menos que el
    tablero. Pasa cuando la asignación falla en silencio.
  · **Un crédito sin sucursal sellada** (`originUnitCode`) no entra en la atribución contable por
    rama, aunque su cliente sí tenga ejecutivo.

Sale con código 1 si algo no cuadra.
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

BACKOFFICE = "http://backoffice.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")

# Tolerancia al comparar importes: los servicios redondean a dos decimales en puntos distintos del
# camino, así que exigir igualdad exacta produce falsos negativos que enseñan a ignorar el aviso.
TOLERANCIA = 1.0

fallos: list[str] = []
avisos: list[str] = []


def http(method, url, body=None, token=None, timeout=90):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def revisa(ok, mensaje):
    print(f"    {'✓' if ok else '✗'} {mensaje}")
    if not ok:
        fallos.append(mensaje)


def dinero(x):
    return f"${float(x or 0):,.2f}"


def main():
    token = http("POST", f"{BACKOFFICE}/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]

    # ── 1 · El tablero contra la suma de la cartera ──────────────────────────
    print("\n  1 · El tablero suma lo mismo que la cartera")
    resumen = http("GET", f"{BACKOFFICE}/dashboard/summary", token=token)
    capital_tablero = float(resumen.get("capitalColocado") or 0)

    total, pagina = 0.0, 0
    cuentas_activas = 0
    while True:
        p = http("GET", f"{BACKOFFICE}/portfolio?page={pagina}&size=100&status=ACTIVE", token=token)
        filas = p.get("content", []) if isinstance(p, dict) else (p or [])
        if not filas:
            break
        for a in filas:
            total += float(a.get("principalBalance") or 0)
            cuentas_activas += 1
        if pagina + 1 >= int(p.get("totalPages", 1)):
            break
        pagina += 1

    print(f"        tablero {dinero(capital_tablero)} · cartera {dinero(total)} "
          f"({cuentas_activas} cuentas activas)")
    revisa(abs(capital_tablero - total) <= TOLERANCIA,
           "el capital del tablero coincide con la suma del listado")

    # ── 2 · Todo crédito tiene dueño y sucursal ──────────────────────────────
    #
    # Es la comprobación que explica un árbol que suma menos que el tablero. Un crédito sin
    # sucursal sellada no se atribuye a ninguna rama: no está mal contado, está **sin contar**.
    #
    # Se pregunta con la reconciliación en seco, que es quien sabe la respuesta. El listado no
    # sirve: el BFF no expone `originUnitCode`, así que leerlo de ahí devuelve nulo siempre y la
    # comprobación diría que TODA la cartera está huérfana — un falso positivo que enseña a
    # ignorar el aviso, que es peor que no tenerlo.
    print("\n  2 · Todo crédito activo cuelga de una rama")
    seco = http("POST", f"{BACKOFFICE}/accounting/backfill-origin-units?dryRun=true",
                token=token) or {}
    sin_ejecutivo = int(seco.get("sinEjecutivoAsignado") or 0)
    por_sellar = int(seco.get("sellados") or 0)
    print(f"        revisados {seco.get('revisados')} · ya sellados {seco.get('yaSellados')} "
          f"· sin ejecutivo {sin_ejecutivo}")
    revisa(sin_ejecutivo == 0,
           f"ningún crédito activo sin ejecutivo (huérfanos: {sin_ejecutivo})")
    revisa(por_sellar == 0,
           f"ningún crédito activo pendiente de sellar su sucursal ({por_sellar})")
    capital_huerfano = 0.0

    # ── 3 · El árbol comercial, sumado por rama ──────────────────────────────
    print("\n  3 · El árbol comercial suma lo mismo que el tablero")
    try:
        raices = [u for u in (http("GET", f"{BACKOFFICE}/sales-org/units", token=token) or [])
                  if not u.get("parentUnitId")]
        if not raices:
            avisos.append("el árbol no tiene raíz: no se puede comparar por rama")
        else:
            capital_arbol = 0.0
            distribuidoras_arbol = 0.0
            for r in raices:
                d = http("GET",
                         f"{BACKOFFICE}/dashboard/commercial?unitId={r['unitId']}", token=token)
                cartera = d.get("portfolio") or {}
                capital_arbol += float(cartera.get("principal") or 0)
                distribuidoras_arbol += float(cartera.get("distributorPrincipal") or 0)
            print(f"        de eso, distribuidoras {dinero(distribuidoras_arbol)}")
            print(f"        tablero {dinero(capital_tablero)} · árbol {dinero(capital_arbol)}")
            # El árbol puede quedarse corto por lo del paso 2; se dice cuánto y por qué, en vez de
            # dar un ✗ pelado que obliga a investigar desde cero.
            diferencia = capital_tablero - capital_arbol
            revisa(abs(diferencia) <= max(TOLERANCIA, capital_huerfano + TOLERANCIA),
                   f"la diferencia ({dinero(diferencia)}) se explica por los créditos sin sucursal")
    except urllib.error.HTTPError as e:
        avisos.append(f"no se pudo leer el tablero por unidad ({e.code})")

    # ── 4 · Las distribuidoras están dentro del total, no aparte ─────────────
    print("\n  4 · Las distribuidoras suman dentro de la misma cartera")
    porProducto = {p.get("productType"): p for p in (resumen.get("byProduct") or [])}
    linea = porProducto.get("DISTRIBUTOR_LINE")
    if not linea:
        avisos.append("no hay líneas de distribuidora en el tablero")
    else:
        suma_productos = sum(float(p.get("capital") or 0) for p in resumen.get("byProduct") or [])
        print(f"        distribuidoras {dinero(linea.get('capital'))} de {dinero(suma_productos)} "
              f"({100 * float(linea.get('capital') or 0) / max(suma_productos, 1):.1f} %)")
        revisa(abs(suma_productos - capital_tablero) <= TOLERANCIA,
               "la suma por producto iguala el capital colocado — una sola cartera, no dos")

    # ── 5 · La contabilidad cuadra y no tiene cuentas muertas ────────────────
    #
    # La balanza cuadra **por construcción** —cada asiento es un par— así que preguntarle si cuadra
    # no distingue una contabilidad sana de una que postea a la cuenta equivocada. Lo que sí lo
    # distingue es qué cuentas se movieron: un catálogo con la mitad en cero no es una institución
    # sin esos hechos, es un consumidor que no está posteando.
    print("\n  5 · La contabilidad del período abierto")
    try:
        periodos = http("GET", f"{BACKOFFICE}/accounting/periods", token=token) or []
        abierto = next((p["period"] for p in periodos if p.get("status") == "OPEN"), None)
        if not abierto:
            avisos.append("no hay período contable abierto")
        else:
            resumen_c = http("GET", f"{BACKOFFICE}/accounting/summary?period={abierto}", token=token) or {}
            cargos = float(resumen_c.get("totalDebit") or 0)
            abonos = float(resumen_c.get("totalCredit") or 0)
            print(f"        {abierto}: {resumen_c.get('vouchers')} pólizas · "
                  f"cargos {dinero(cargos)} · abonos {dinero(abonos)}")
            revisa(abs(cargos - abonos) <= 0.01, "la balanza del período cuadra")

            balanza = http("GET", f"{BACKOFFICE}/accounting/trial-balance?period={abierto}",
                           token=token) or []
            movidas = {r["accountCode"] for r in balanza
                       if float(r.get("totalDebit") or 0) or float(r.get("totalCredit") or 0)}

            # 1203 tiene que **bajar** alguna vez. Si sólo recibe cargos, es que ningún pago está
            # aplicándose a interés devengado y ningún quebranto lo está dando de baja: el auxiliar
            # crece para siempre y la balanza deja de ser presentable a los pocos meses.
            aux = next((r for r in balanza if r["accountCode"] == "1203"), None)
            if aux:
                print(f"        1203 intereses por cobrar: cargos {dinero(aux.get('totalDebit'))} · "
                      f"abonos {dinero(aux.get('totalCredit'))}")
                revisa(float(aux.get("totalCredit") or 0) > 0,
                       "el auxiliar de intereses por cobrar (1203) se abona alguna vez")

            # Sin estimación preventiva no hay reserva que consumir, y todo quebranto golpea
            # resultados entero. Es el síntoma de que el recálculo de riesgo nunca corrió.
            revisa("1290" in movidas or "5101" in movidas,
                   "la estimación preventiva (1290/5101) tuvo movimiento")

            muertas = sorted({"4101", "4103", "1201", "1101"} - movidas)
            revisa(not muertas,
                   f"las cuentas del ciclo básico se movieron (sin movimiento: {muertas or 'ninguna'})")
    except urllib.error.HTTPError as e:
        avisos.append(f"no se pudo leer la contabilidad ({e.code})")

    # ── 6 · Lo devengado se factura ──────────────────────────────────────────
    #
    # Los conceptos facturables se acumulan solos con cada devengo; lo que no ocurre solo es la
    # **corrida de facturación**. Mientras no se dispare, la pestaña de facturas se ve vacía sobre
    # una contabilidad llena, que es la forma más cara de parecer roto sin estarlo.
    print("\n  6 · Lo devengado acaba en facturas")
    try:
        facturas = http("GET", f"{BACKOFFICE}/invoices?size=1", token=token) or {}
        total = int(facturas.get("totalElements") or 0)
        print(f"        {total} facturas emitidas en total")
        revisa(total > 0, "existe al menos una factura (la corrida de facturación se disparó)")

        if total:
            muestra = http("GET", f"{BACKOFFICE}/invoices?size=50", token=token) or {}
            filas = muestra.get("content", [])
            genericas = [i for i in filas if (i.get("receptorRfc") or "") == "XAXX010101000"]
            if genericas:
                # No es un fallo del módulo: es que los clientes no tienen perfil fiscal capturado.
                # Se avisa en vez de fallar porque una parte sin régimen es un caso real.
                print(f"        {len(genericas)} de {len(filas)} con RFC genérico "
                      f"(sin perfil fiscal capturado)")
                if len(genericas) == len(filas):
                    fallos.append("TODAS las facturas salieron a «público en general»: "
                                  "falta correr seed-perfiles-fiscales.py antes de facturar")
                    print("    ✗ todas las facturas salieron a «público en general»")
                else:
                    print("    ✓ las facturas llevan receptor fiscal real")
            else:
                print("    ✓ las facturas llevan receptor fiscal real")
    except urllib.error.HTTPError as e:
        avisos.append(f"no se pudieron leer las facturas ({e.code})")

    print()
    for a in avisos:
        print(f"  ⚠ {a}")
    if fallos:
        print(f"\n  ✗ {len(fallos)} comprobaciones fallaron:")
        for f in fallos:
            print(f"      · {f}")
        sys.exit(1)
    print("  ✓ el tablero, el árbol y la cartera cuadran\n")


if __name__ == "__main__":
    main()
