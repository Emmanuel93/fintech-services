#!/usr/bin/env python3
"""Hace avanzar el ciclo de vida de la cartera **mes a mes**: devenga, cobra, deteriora, factura y cierra.

`seed-portfolio.py` deja créditos vivos y sanos. Eso alcanza para ver cartera y no alcanza para ver
**contabilidad**: sin devengo no hay ingreso, sin pagos no baja el auxiliar de intereses, sin
deterioro no hay estimación preventiva, sin quebranto no se consume la reserva y sin corrida de
facturación no existe una sola factura.

**No inserta filas: orquesta llamadas.** Cada paso entra por el mismo endpoint que usaría una
persona —el job de devengo, el canal de pagos, la mesa de cobranza, la corrida de facturación— así
que si algo no pasa por aquí, tampoco pasaría en producción.

## Por qué el reloj se corre día a día

El devengo es **idempotente por día**: `AccrualSchedule.needsAccrual(fecha)` sólo deja devengar una
vez por fecha. La versión anterior llamaba treinta veces al job sin fecha y creía estar acumulando un
mes; acumulaba **un día**, y la cartera entera quedaba con un interés de 24 horas apilado en el mes
corriente. De ahí salía la sensación de que la contabilidad no cuadraba: cuadraba, pero contaba un
día de una cartera de doce millones.

Ahora se retrocede el reloj de los calendarios (`rewind-accrual-schedules`) y se devenga fecha por
fecha (`run-daily-accrual?date=`). Cada día produce sus pólizas con **su** fecha, así que el interés
cae en el período al que pertenece y junio, julio y agosto tienen cifras distintas y comparables.

## Lo que este script NO puede backdatear

El **alta y la disposición** de cada crédito se asientan cuando origination los crea, que es hoy. Un
crédito originado en el mes en curso con intereses devengados desde junio es la única costura que
queda a la vista, y se prefiere dejarla visible antes que escribir pólizas a mano. Cerrarla del todo
exige que el flujo de originación acepte una fecha valor y la propague hasta
`CreditAccountActivatedEvent` — el evento ya toma su `occurredOn` de `activatedAt`, así que falta
sólo el tramo de origination.

    python3 scripts/seed-ciclo-credito.py                  # tres meses de historia
    python3 scripts/seed-ciclo-credito.py --meses 5
    python3 scripts/seed-ciclo-credito.py --sin-historia   # comportamiento viejo: sólo hoy
"""
from __future__ import annotations

import argparse
import datetime
import json
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request

BACKOFFICE = "http://backoffice.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")
RED = "fintech-services_fintech-network"

CABECERAS_INTERNAS = [
    "-H", "X-User-Id: 00000000-0000-0000-0000-0000000000e2",
    "-H", "X-Roles: ADMIN",
    "-H", "X-Channel: SERVICE",
]


def http(method, url, body=None, token=None, timeout=90):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        detalle = e.read().decode()[:160]
        raise RuntimeError(f"{e.code} {method} {url.split('/')[-1]}: {detalle}") from None


def interno(servicio, ruta, method="POST", esperar_cuerpo=False):
    """
    Llama a un servicio de dominio por dentro de la red de Docker.

    Los jobs de devengo, riesgo y morosidad no salen por el gateway a propósito: no son operaciones
    de usuario, son el reloj de la institución. Se disparan desde dentro, que es de donde los
    dispararía el planificador.
    """
    salida = ["-s"] if esperar_cuerpo else ["-s", "-o", "/dev/null", "-w", "%{http_code}"]
    r = subprocess.run(
        ["docker", "run", "--rm", "--network", RED, "curlimages/curl:latest",
         *salida, "-X", method, f"http://{servicio}:8080{ruta}"],
        capture_output=True, text=True)
    return r.stdout.strip()


def login():
    r = http("POST", f"{BACKOFFICE}/auth/staff/login",
             {"email": ADMIN[0], "password": ADMIN[1]})
    return r["accessToken"]


def paso(texto):
    print(f"\n\033[1m▸ {texto}\033[0m")


def periodo_de(fecha):
    return f"{fecha.year}{fecha.month:02d}"


# ── 1 · El reloj ───────────────────────────────────────────────────────────

def retroceder_reloj(desde):
    """
    Pone el último devengo de todos los calendarios en `desde`, para poder correr el reloj desde ahí.

    Sin esto, `needsAccrual` rechaza cualquier fecha anterior a hoy y la historia es inalcanzable:
    el job está diseñado para no devengar dos veces el mismo día, y esa misma guarda impide sembrar
    hacia atrás.
    """
    salida = interno("charges-service",
                     f"/internal/test-support/rewind-accrual-schedules?date={desde.isoformat()}",
                     esperar_cuerpo=True)
    try:
        return json.loads(salida).get("schedulesRewound", 0)
    except Exception:
        print(f"    ! no se pudo retroceder el reloj: {salida[:120]}", file=sys.stderr)
        return 0


def devengar_dia(fecha):
    """Un día de devengo ordinario y su moratorio, con la fecha del hecho."""
    d = fecha.isoformat()
    interno("charges-service", f"/internal/test-support/run-daily-accrual?date={d}")
    interno("charges-service", f"/internal/test-support/run-moratorium-accrual?date={d}")


def correr_mes(inicio, fin):
    """
    Devenga día por día entre dos fechas. Es el corazón de la historia contable.

    Se hace en serie y no en paralelo: cada día publica cargos que contabilidad tiene que consumir
    antes de que llegue el siguiente, y adelantarse produce pólizas fuera de orden en la misma serie
    de folios.
    """
    dias = 0
    fecha = inicio
    while fecha <= fin:
        devengar_dia(fecha)
        dias += 1
        # Cada tanto se le da aire a los consumidores: contabilidad, cartera y facturación van
        # detrás por Kafka, y saturar la cola hace que el mes siguiente empiece sobre saldos viejos.
        if dias % 7 == 0:
            time.sleep(2)
        fecha += datetime.timedelta(days=1)
    return dias


# ── 2 · Pagos ──────────────────────────────────────────────────────────────

def cuentas_activas(token, size=200):
    r = http("GET", f"{BACKOFFICE}/portfolio?status=ACTIVE&size={size}", token=token) or {}
    return r.get("content", r) if isinstance(r, dict) else r


def pagar(cuenta, obligado, monto, ref):
    """
    Registra un pago contra payments-service.

    **Por la red interna, no por el gateway.** El BFF no expone ningún endpoint de pago y no debe:
    registrar un pago es una operación del dominio, y que no se pueda hacer desde fuera es la
    garantía que sostiene la topología.
    """
    cuerpo = json.dumps({"creditAccountId": cuenta, "obligorPartyId": obligado,
                         "amount": monto, "paymentMethod": "SPEI", "externalRef": ref})
    r = subprocess.run(
        ["docker", "run", "--rm", "--network", RED, "curlimages/curl:latest",
         "-s", "-o", "/dev/null", "-w", "%{http_code}",
         "-X", "POST", "http://payments-service:8080/api/v1/payments",
         "-H", "Content-Type: application/json", *CABECERAS_INTERNAS,
         "-d", cuerpo],
        capture_output=True, text=True, timeout=120)
    return r.stdout.strip() in ("200", "201", "202")


def liquidar(cuentas):
    """
    Liquida pagando el adeudo completo, no marcando la cuenta.

    `settleIfClear()` mueve la cuenta a SETTLED cuando el saldo llega a cero, así que esto comprueba
    de paso esa transición. Forzar el estatus dejaría cuentas «liquidadas» con saldo — un estado que
    el dominio no produce y contra el que nadie debería programar.
    """
    liquidadas = 0
    for c in cuentas:
        cid = c.get("creditAccountId") or c.get("id")
        deuda = float(c.get("totalDebt") or 0)
        if not cid or deuda <= 0:
            continue
        # Exacto, no de más: pagar un peso extra hacía que cartera lo devolviera acto seguido y cada
        # liquidación dejaba una póliza de «devolución de pago» de $1.00 que no significa nada.
        if pagar(cid, c.get("obligorPartyId"), round(deuda, 2), f"LIQ-{cid[:8]}"):
            liquidadas += 1
    return liquidadas


def abonar_parcial(cuentas, fraccion, etiqueta):
    """Pagos parciales: mueven el auxiliar sin cerrar la cuenta, que es el caso más común."""
    hechos = 0
    for c in cuentas:
        cid = c.get("creditAccountId") or c.get("id")
        deuda = float(c.get("totalDebt") or 0)
        if not cid or deuda <= 0:
            continue
        if pagar(cid, c.get("obligorPartyId"), round(deuda * fraccion, 2), f"{etiqueta}-{cid[:8]}"):
            hechos += 1
    return hechos


# ── 3 · Mora ───────────────────────────────────────────────────────────────

def calendario(cuenta):
    """
    El calendario de una cuenta, leído del dominio.

    **No por el BFF**: `/portfolio/{id}/amortization-schedule` no existe ahí y devuelve 404. Pedirlo
    igual dejaba la lista de mensualidades siempre vacía, así que todas las cuentas parecían «sin
    pendientes» y no se atrasaba ninguna — un 404 tragado que se manifestaba tres pasos después como
    «cobranza no abre casos».
    """
    r = subprocess.run(
        ["docker", "run", "--rm", "--network", RED, "curlimages/curl:latest", "-s",
         f"http://credit-portfolio-service:8080/api/v1/portfolio/accounts/{cuenta}/amortization-schedule",
         *CABECERAS_INTERNAS],
        capture_output=True, text=True, timeout=60)
    try:
        datos = json.loads(r.stdout)
        return datos if isinstance(datos, list) else []
    except Exception:
        return []


# Cupos por tramo de mora, como **proporción de las cuentas que se van a atrasar**. El último tramo
# cruza los 181 días a propósito: es el umbral de quebranto del dominio, y sin cuentas por encima no
# hay nada que castigar — la estimación preventiva sólo crece y el otro lado nunca se ve.
TRAMOS = [
    ("DPD 30",   0.30, (25, 34)),
    ("DPD 60",   0.25, (55, 68)),
    ("DPD 90",   0.25, (88, 105)),
    ("DPD 180+", 0.20, (185, 240)),
]


def atrasar(cuentas, rnd):
    """
    Reparte el atraso por **cupos explícitos**, no al azar.

    Elegir la profundidad al azar por cuenta dejaba tramos enteros vacíos con pocas cuentas, y sin
    el tramo de 181+ no hay quebrantos que sembrar. El síntoma llegaba tres pasos después, como
    «cobranza no produce nada», y no señalaba aquí.
    """
    aptas, sin_pendientes = [], 0
    for c in cuentas:
        cid = c.get("creditAccountId") or c.get("id")
        if not cid:
            continue
        plan = calendario(cid)
        pendientes = [i["installmentNumber"] for i in plan
                      if str(i.get("status", "")).upper() not in ("PAID", "WAIVED")]
        if pendientes:
            aptas.append((cid, pendientes))
        else:
            sin_pendientes += 1

    if sin_pendientes:
        print(f"    {sin_pendientes} cuentas sin mensualidades pendientes: no se pueden atrasar")
    if not aptas:
        return {}

    rnd.shuffle(aptas)
    repartidas, i = {}, 0
    # Se reparte del tramo MÁS PROFUNDO al más superficial.
    #
    # El cupo es proporcional, y con pocas cuentas los primeros tramos se llevan todas: en una
    # siembra chica quedaba «DPD 180+: 0 cuentas» y sin cuentas por encima del umbral no hay
    # quebrantos que sembrar — que es justo el caso más difícil de producir y el que más falta hace
    # para revisar el ciclo completo. Los tramos superficiales, en cambio, salen solos con cualquier
    # cartera. Cuando sobran cuentas el orden da igual; cuando faltan, decide qué caso existe.
    for nombre, proporcion, (lo, hi) in reversed(TRAMOS):
        cupo = max(1, round(len(aptas) * proporcion)) if i < len(aptas) else 0
        lote = aptas[i:i + cupo]
        i += len(lote)
        for cid, pendientes in lote:
            dias = rnd.randint(lo, hi)
            for numero in pendientes[:3]:
                subprocess.run(
                    ["docker", "run", "--rm", "--network", RED, "curlimages/curl:latest",
                     "-s", "-o", "/dev/null", "-X", "POST",
                     f"http://credit-portfolio-service:8080/internal/test-support/accounts/{cid}"
                     # El parámetro se llama `daysFromToday` y es **relativo a hoy**: para que una
                     # cuota quede vencida hay que mandarlo NEGATIVO. Con `?days=` —el nombre que
                     # tenía— Spring contestaba 400 y `subprocess.run` lo ignoraba, así que este paso
                     # nunca atrasó nada: la mora que aparecía venía del envejecido de
                     # seed-portfolio, y el tramo de 181+ días no se alcanzaba jamás. Sin él no hay
                     # quebrantos que sembrar, que era la mitad de lo que este script existe para
                     # producir.
                     f"/installments/{numero}/shift-due-date?daysFromToday=-{dias}"],
                    capture_output=True, text=True)
        repartidas[nombre] = len(lote)
        print(f"    {nombre:>9}: {len(lote)} cuentas")

    interno("credit-portfolio-service", "/internal/test-support/run-delinquency-job")
    return repartidas


def verificar_mora(token):
    """
    Comprueba que el envejecido produjo mora de verdad.

    Existe porque el fallo anterior fue silencioso: se movieron fechas, el job corrió, todo devolvió
    200 y la cartera siguió con cero días de atraso.
    """
    filas = cuentas_activas(token, size=1000)
    dias = [c.get("daysDelinquent") or 0 for c in filas]
    con_mora = [d for d in dias if d > 0]
    print(f"    {len(con_mora)} de {len(filas)} cuentas activas quedaron con atraso")

    cubos = {"1-30": 0, "31-60": 0, "61-90": 0, "91-180": 0, "181+": 0}
    for d in con_mora:
        clave = ("1-30" if d <= 30 else "31-60" if d <= 60 else
                 "61-90" if d <= 90 else "91-180" if d <= 180 else "181+")
        cubos[clave] += 1
    print("    " + " · ".join(f"{k}={v}" for k, v in cubos.items()))

    if cubos["181+"] == 0:
        print("    ! ningún caso por encima de 181 días: NO habrá quebrantos", file=sys.stderr)
    if not con_mora:
        print("    ! el envejecido no produjo mora: cobranza no tendrá casos que abrir",
              file=sys.stderr)
    return len(con_mora)


# ── 4 · Riesgo: la estimación preventiva ───────────────────────────────────

def recalcular_riesgo():
    """
    Recalcula el riesgo de toda la cartera y publica `risk.assessment-updated`.

    Es **lo único** que hace que contabilidad constituya estimación preventiva. Mientras este paso no
    existió, las cuentas 1290 y 5101 salían siempre en cero y un quebranto golpeaba resultados
    entero: no había reserva que consumir porque nadie la había constituido.
    """
    salida = interno("risk-service", "/internal/test-support/run-risk-assessment", esperar_cuerpo=True)
    try:
        r = json.loads(salida)
        if r.get("skippedNoPolicy"):
            # Fallo silencioso clásico: contesta 200 y no publica nada porque falta la política.
            print(f"    ! {r['skippedNoPolicy']} cuentas sin política de provisión vigente",
                  file=sys.stderr)
        return r.get("assessed", 0)
    except Exception:
        print(f"    ! riesgo no recalculó: {salida[:120]}", file=sys.stderr)
        return 0


# ── 5 · Cobranza ───────────────────────────────────────────────────────────

def casos(token, size=200):
    r = http("GET", f"{BACKOFFICE}/collections/cases?size={size}", token=token) or {}
    return r.get("content", r) if isinstance(r, dict) else r


def prometer(token, lista, rnd):
    """Una promesa de pago: el compromiso que frena la cobranza automática mientras vive."""
    hechas, rechazos = 0, []
    for caso in lista:
        deuda = float(caso.get("totalDebt") or 0) or 1500.0
        # La fecha tiene que ser futura: el backend no lo valida y una promesa para ayer nace rota,
        # porque el job de la mañana siguiente la marca incumplida.
        fecha = (datetime.date.today() + datetime.timedelta(days=rnd.randint(3, 20))).isoformat()
        try:
            http("POST", f"{BACKOFFICE}/collections/cases/{caso['caseId']}/payment-promises",
                 {"amount": round(deuda * rnd.uniform(0.2, 0.6), 2), "promisedDate": fecha},
                 token=token)
            hechas += 1
        except Exception as e:
            rechazos.append(str(e)[:90])
    if rechazos:
        print(f"    {len(rechazos)} rechazadas por el dominio; la primera: {rechazos[0]}", file=sys.stderr)
    return hechas


# Extensión de plazo que la política de cobranza admite (`max-term-extension-months`, 12 por
# defecto). El campo es la **extensión**, no el plazo nuevo total.
#
# La siembra pedía 18, 24 o 36 meses y el dominio rechazaba las tres con 422. El resultado era «0
# convenios autorizados» en todas las corridas, que se lee como que la reestructura está rota
# cuando lo que estaba mal era pedir más de lo que la política permite.
EXTENSIONES = [6, 9, 12]


def reestructurar(token, lista, rnd):
    """Reestructura: proponer → aceptar → autorizar. Los tres pasos, para ejercitar el segundo par de ojos.

    Uno de cada tres se queda **aceptado y sin autorizar**, a propósito.

    Antes se autorizaban todos, y la consecuencia era que `GET /collections/agreements/
    awaiting-authorization` devolvía siempre una lista vacía: la pantalla «Convenios por autorizar»
    —que existe justo para el segundo par de ojos— no tenía nunca nada que enseñar, y no había forma
    de distinguir «no hay trabajo pendiente» de «esta pantalla no funciona».

    Es el sobrecorrectivo del arreglo anterior: se venía de «0 convenios autorizados» por pedir
    extensiones que la política rechazaba, se arregló autorizando, y con eso se perdió el estado
    intermedio. Los dos estados tienen que existir en la base sembrada porque los dos existen en la
    operación.
    """
    completadas, pendientes, rechazos = 0, 0, []
    for i, caso in enumerate(lista):
        try:
            conv = http("POST", f"{BACKOFFICE}/collections/cases/{caso['caseId']}/agreements",
                        {"type": "RESTRUCTURE",
                         "newTerms": {"newNominalRate": round(rnd.uniform(0.18, 0.28), 4),
                                      "newTermMonths": rnd.choice(EXTENSIONES)}},
                        token=token)
            aid = conv["agreementId"]
            http("PUT", f"{BACKOFFICE}/collections/agreements/{aid}/accept", token=token)

            # Determinista por posición y no al azar: la siembra tiene que producir el mismo
            # reparto en cada corrida, o «hay 3 por autorizar» deja de ser una afirmación
            # comprobable sobre el entorno.
            if i % 3 == 2:
                pendientes += 1
                continue

            http("PUT", f"{BACKOFFICE}/collections/agreements/{aid}/authorize",
                 {"authorizedBy": "comite@kredius.mx",
                  "authorizationRef": f"ACTA-{rnd.randint(1000, 9999)}"}, token=token)
            completadas += 1
        except Exception as e:
            rechazos.append(str(e)[:90])
    if rechazos:
        print(f"    {len(rechazos)} rechazados; el primero: {rechazos[0]}", file=sys.stderr)
    if pendientes:
        print(f"    {pendientes} quedan aceptados y esperando autorización, a propósito")
    return completadas


def quebrantar(token, lista, rnd):
    """Quebranto sobre los casos más profundos: lo único que consume la estimación preventiva."""
    hechos, rechazos = 0, []
    for caso in lista:
        cid = caso["caseId"]
        try:
            http("POST", f"{BACKOFFICE}/collections/cases/{cid}/request-write-off",
                 {"reason": "UNRECOVERABLE"}, token=token)
            http("POST", f"{BACKOFFICE}/collections/cases/{cid}/write-offs",
                 {"reason": "UNRECOVERABLE", "authorizedBy": "comite@kredius.mx",
                  "authorizationRef": f"QUEBRANTO-{rnd.randint(1000, 9999)}"}, token=token)
            hechos += 1
        except Exception as e:
            rechazos.append(str(e)[:90])
    if rechazos:
        print(f"    {len(rechazos)} rechazados; el primero: {rechazos[0]}", file=sys.stderr)
    return hechos


# ── 6 · Facturación y cierre ───────────────────────────────────────────────

def facturar(token, periodo):
    """
    Corre la facturación del período: consolida lo devengado en **un CFDI por cliente**.

    Este paso no existía en ninguna siembra, y por eso no había ni una factura en la base: los
    `invoiceable_items` se acumulaban en PENDING y el `BillingRunJob` sólo corre el día 1 a las 03:00
    sobre el mes anterior. La consola enseñaba una pestaña de facturas vacía sobre una contabilidad
    llena, que es la forma más cara de parecer roto sin estarlo.
    """
    try:
        r = http("POST", f"{BACKOFFICE}/accounting/billing-runs?period={periodo}", token=token) or {}
        return int(r.get("invoicesRequested") or 0)
    except Exception as e:
        print(f"    ! la corrida de {periodo} falló: {str(e)[:120]}", file=sys.stderr)
        return 0


def cerrar(token, periodo):
    """
    Cierra el período: «estos números ya se publicaron».

    No levanta un muro —un hecho que llegue después se asienta en el primer período abierto y queda
    marcado como extemporáneo— y por eso se puede cerrar sin miedo a perder movimientos.
    """
    try:
        http("POST", f"{BACKOFFICE}/accounting/periods/{periodo}/close", token=token)
        return True
    except Exception as e:
        print(f"    ! no se pudo cerrar {periodo}: {str(e)[:120]}", file=sys.stderr)
        return False


# ── Orquestación ───────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--meses", type=int, default=3,
                    help="meses de historia contable a construir, contando el actual")
    ap.add_argument("--sin-historia", action="store_true",
                    help="no retrocede el reloj: sólo devenga hoy (comportamiento viejo)")
    ap.add_argument("--semilla", type=int, default=7)
    args = ap.parse_args()
    rnd = random.Random(args.semilla)

    token = login()
    hoy = datetime.date.today()

    activas = cuentas_activas(token)
    print(f"  {len(activas)} cuentas activas")
    if not activas:
        print("  ! sin cartera activa: nada que hacer avanzar", file=sys.stderr)
        return 1

    # El primer día del mes que arranca la historia. Con --meses 3 y hoy en agosto: 1 de junio.
    primer_mes = hoy.replace(day=1)
    for _ in range(args.meses - 1):
        primer_mes = (primer_mes - datetime.timedelta(days=1)).replace(day=1)

    if args.sin_historia:
        paso("Devengando sólo el día de hoy (--sin-historia)")
        devengar_dia(hoy)
        meses = [(hoy.replace(day=1), hoy)]
    else:
        paso(f"Retrocediendo el reloj del devengo a {primer_mes}")
        print(f"  {retroceder_reloj(primer_mes - datetime.timedelta(days=1))} calendarios retrocedidos")

        # Los tramos de cada mes: del día 1 al último día, y el mes corriente hasta hoy.
        meses = []
        cursor = primer_mes
        while cursor <= hoy:
            siguiente = (cursor + datetime.timedelta(days=32)).replace(day=1)
            fin = min(siguiente - datetime.timedelta(days=1), hoy)
            meses.append((cursor, fin))
            cursor = siguiente

    rnd.shuffle(activas)
    n = len(activas)
    # Los cortes reparten la cartera en desenlaces distintos. Que sumen menos del total es a
    # propósito: el resto se queda al corriente, que es lo que le pasa a la mayoría.
    liquidan   = activas[: max(1, int(n * 0.15))]
    abonan     = activas[max(1, int(n * 0.15)): int(n * 0.40)]
    se_atrasan = activas[int(n * 0.40): int(n * 0.75)]

    # ── El recorrido mes a mes ─────────────────────────────────────────────
    for i, (inicio, fin) in enumerate(meses):
        periodo = periodo_de(inicio)
        ultimo = i == len(meses) - 1
        paso(f"Período {periodo} · devengando del {inicio} al {fin}")
        print(f"  {correr_mes(inicio, fin)} días devengados")

        # Que contabilidad termine de consumir antes de leer saldos para cobrar.
        time.sleep(6)

        # Los abonos parciales ocurren todos los meses; es lo que hace que el auxiliar de intereses
        # suba y baje en vez de crecer siempre.
        frescas = cuentas_activas(token, size=1000)
        por_id = {c.get("creditAccountId") or c.get("id"): c for c in frescas}
        vigentes = [por_id[c.get("creditAccountId") or c.get("id")]
                    for c in abonan if (c.get("creditAccountId") or c.get("id")) in por_id]
        print(f"  {abonar_parcial(vigentes, rnd.uniform(0.25, 0.5), f'ABN{periodo}')} abonos parciales")

        # La estimación preventiva se recalcula cada cierre, como en la vida real.
        paso(f"Período {periodo} · recalculando riesgo (estimación preventiva)")
        print(f"  {recalcular_riesgo()} cuentas evaluadas")
        time.sleep(5)

        paso(f"Período {periodo} · corrida de facturación")
        print(f"  {facturar(token, periodo)} CFDI solicitados")
        time.sleep(4)

        # El mes corriente se queda ABIERTO: es el que se está trabajando y el que la consola abre
        # por defecto. Cerrar todo dejaría la pantalla en un mes que ya nadie toca.
        if not ultimo:
            paso(f"Período {periodo} · cierre contable")
            print(f"  {'cerrado' if cerrar(token, periodo) else 'no se pudo cerrar'}")

    # ── Desenlaces, ya con toda la historia devengada ──────────────────────
    frescas = cuentas_activas(token, size=1000)
    por_id = {c.get("creditAccountId") or c.get("id"): c for c in frescas}

    paso("Liquidando cuentas por el canal de pagos")
    vivas = [por_id[k] for k in
             [(c.get("creditAccountId") or c.get("id")) for c in liquidan] if k in por_id]
    print(f"  {liquidar(vivas)} cuentas liquidadas de {len(vivas)}")

    paso("Atrasando cartera para que exista mora real")
    atrasar(se_atrasan, rnd)
    print("  job de morosidad disparado")
    time.sleep(8)
    verificar_mora(token)

    # Con la mora ya calculada, el riesgo vuelve a correr: es lo que constituye la reserva que el
    # quebranto de más abajo va a consumir. Sin esta segunda pasada, los créditos que acaban de caer
    # en mora se castigan sin reserva y todo el quebranto golpea resultados.
    paso("Recalculando riesgo con la mora ya reconocida")
    print(f"  {recalcular_riesgo()} cuentas evaluadas")
    time.sleep(6)

    print("  esperando a que cobranza abra los casos…")
    time.sleep(20)

    lista = casos(token)
    print(f"  {len(lista)} casos de cobranza abiertos")
    if not lista:
        print("  ! cobranza no abrió casos: revisar el consumidor de morosidad", file=sys.stderr)
    else:
        # Los candidatos se eligen por **estado del caso**, no sólo por días de atraso.
        #
        # El dominio sólo negocia sobre casos vivos: un convenio exige MANAGED o LEGAL, y un caso
        # CLOSED —porque la cuenta se liquidó— o recién abierto en OPEN lo rechaza con 422. Elegir
        # sólo por mora metía casos cerrados en el lote y la corrida terminaba con «0 convenios
        # autorizados» y seis rechazos, que se lee como que la reestructura está rota cuando lo que
        # estaba mal era a quién se le proponía.
        vivos = [c for c in lista if str(c.get("status", "")).upper() not in ("CLOSED", "WRITTEN_OFF")]
        negociables = [c for c in vivos if str(c.get("status", "")).upper() in ("MANAGED", "LEGAL")]
        cerrados = len(lista) - len(vivos)
        if cerrados:
            print(f"  {cerrados} casos ya cerrados o castigados: no admiten gestión")

        por_mora = sorted(vivos, key=lambda c: c.get("daysDelinquent") or 0, reverse=True)
        profundos = [c for c in por_mora if (c.get("daysDelinquent") or 0) >= 181][:4]
        medios    = [c for c in sorted(negociables, key=lambda c: c.get("daysDelinquent") or 0, reverse=True)
                     if 60 <= (c.get("daysDelinquent") or 0) < 181][:6]
        tempranos = [c for c in por_mora if (c.get("daysDelinquent") or 0) < 60][:8]
        print(f"  tramos: {len(tempranos)} tempranos · {len(medios)} negociables · {len(profundos)} de 181+ días")

        paso("Promesas de pago")
        print(f"  {prometer(token, tempranos, rnd)} promesas registradas")

        paso("Reestructuras (proponer → aceptar → autorizar)")
        print(f"  {reestructurar(token, medios, rnd)} convenios autorizados")

        paso("Quebrantos")
        print(f"  {quebrantar(token, profundos, rnd)} quebrantos aplicados")

    # La facturación del mes corriente se vuelve a correr al final: los pagos, quebrantos y
    # devengos de este último tramo generaron conceptos facturables que la corrida de arriba,
    # hecha antes de todo esto, no podía haber visto.
    paso("Corrida de facturación final del período abierto")
    print(f"  {facturar(token, periodo_de(hoy))} CFDI solicitados")

    paso("Reconciliando la sucursal de origen en contabilidad")
    r = http("POST", f"{BACKOFFICE}/accounting/backfill-origin-units", token=token)
    print(f"  {r.get('sellados', 0)} créditos sellados · {r.get('polizasAtribuidas', 0)} pólizas atribuidas"
          f" · {r.get('sinEjecutivoAsignado', 0)} sin ejecutivo")

    print("\n\033[1m✓ ciclo de vida sembrado\033[0m")
    return 0


if __name__ == "__main__":
    sys.exit(main())
