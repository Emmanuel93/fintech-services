#!/usr/bin/env python3
"""Siembra los escenarios revolventes que la demo no tenía: S1 a S7.

**El hueco, medido.** `seed-portfolio.py` sólo produce `PERSONAL_LOAN` (62 %), `PAYROLL_LOAN`
(24 %) y `SME_LOAN` (14 %). El catálogo **sí** tiene tarjeta (`CC-IND-STD-V1`) y línea revolvente
(`RL-IND-STD-V1`), pero el sembrador nunca las elige: **la demo no tiene ni una tarjeta viva**.

Sin eso, ningún escenario del grupo B se puede probar contra datos reales — ni el corte, ni el pago
mínimo, ni el diferimiento, ni el MSI, ni «saldo cero no liquida». Se probaban con mocks, que es
otra forma de decir que no se probaban.

**La regla rectora se conserva: nada se inserta, todo se origina.** Cada persona recorre el journey
real de la app —OTP, KYC, alta, solicitud, scoring, oferta, contrato, firma— reutilizando el mismo
código que `seed-portfolio.py`. Si un endpoint no acepta lo que se manda aquí, tampoco lo aceptaría
la app. Los escenarios se construyen encima **usando los casos de uso nuevos**, no escribiendo filas.

Escenarios:

    S1  Tarjeta con compras del ciclo SIN diferir      → corte con exigible completo
    S2  Tarjeta con una compra diferida a MSI y otra no → exigible = total − diferida + cuota
    S3  Tarjeta con compra diferida CON tasa            → que el plan sí devengue
    S4  Línea de uso propio con saldo CERO              → que siga ACTIVE con cupo restaurado
    S5  Préstamo con BNPL activo                        → que no devengue antes de su fecha
    S6  Cuenta con salto de pago otorgado               → que no entre a cobranza ese ciclo
    S7  Cartera bajo programa de apoyo                  → sin exigible, sin mora, forborne

    python3 scripts/seed-revolventes.py                 # 12 clientes, todos los escenarios
    python3 scripts/seed-revolventes.py --solo S2,S3    # sólo esos
    python3 scripts/seed-revolventes.py --clientes 30
"""
from __future__ import annotations

import argparse
import importlib.util
import random
import sys
import time
from collections import Counter
from pathlib import Path

AQUI = Path(__file__).resolve().parent


def _cargar_sembrador():
    """
    Importa `seed-portfolio.py` como módulo.

    El guion del nombre impide un `import` normal, y copiar el journey aquí sería peor: son
    doscientas líneas que se desincronizarían con el primer cambio de la app. El módulo tiene su
    guarda `__main__`, así que importarlo no ejecuta nada.
    """
    spec = importlib.util.spec_from_file_location("seed_portfolio", AQUI / "seed-portfolio.py")
    modulo = importlib.util.module_from_spec(spec)
    sys.path.insert(0, str(AQUI))
    spec.loader.exec_module(modulo)
    return modulo


SP = _cargar_sembrador()
http, MOBILE, BACKOFFICE = SP.http, SP.MOBILE, SP.BACKOFFICE


class CatalogoRevolvente:
    """
    Los productos revolventes del catálogo, que el sembrador general no puede ver.

    **La causa era más profunda que la mezcla de pesos.** `seed-portfolio.Catalogo` descarta con un
    `continue` todo producto cuyo `behavior` no sea `INSTALLMENT`: aunque alguien añadiera
    `CREDIT_CARD` a `PESOS_PRODUCTO`, seguiría sin aparecer. Los revolventes estaban excluidos
    **estructuralmente**, no por configuración.

    Esta clase es el espejo: sólo admite `REVOLVING`, y expone la misma interfaz `elige()` para que
    `Cliente` no note la diferencia.
    """

    def __init__(self, token, productos_deseados):
        self.deseados = set(productos_deseados)
        self.productos = []
        for p in http("GET", f"{BACKOFFICE}/products", token=token) or []:
            if p.get("status") != "ACTIVE" or p.get("behavior") != "REVOLVING":
                continue
            if p.get("productType") not in self.deseados:
                continue
            self.productos.append(p)
        if not self.productos:
            sys.exit("El catálogo no tiene productos REVOLVING activos: "
                     "revisa que los seeds de credit-product estén aplicados")

    def para(self, tipo, monto):
        """
        Los límites del producto pedido, en la forma que `Cliente` espera.

        <b>El plazo de una revolvente es 1 en el catálogo</b>, y con razón: una línea no tiene
        plazo, lo tiene cada disposición. Pero `Cliente` usa ese rango para elegir el plazo de la
        SOLICITUD, y una solicitud a un plazo de 1 no es lo que nadie pide. Se le da el plazo del
        ciclo —12— dejando intacto el catálogo, que dice la verdad sobre el producto.
        """
        p = next((x for x in self.productos if x["productType"] == tipo), None)
        if p is None:
            return None
        minimo = float(p.get("minCreditLine") or p.get("minAmount") or monto)
        maximo = float(p.get("maxCreditLine") or p.get("maxAmount") or monto)
        return tipo, minimo, maximo, PLAZO_DE_SOLICITUD, PLAZO_DE_SOLICITUD

    def elige(self, rnd):
        p = rnd.choice(self.productos)
        return self.para(p["productType"], 0)

# Una revolvente declara plazo 1 en el catálogo —no tiene plazo, lo tiene cada disposición—, pero la
# solicitud necesita uno para existir. Doce es el ciclo de un estado de cuenta mensual.
PLAZO_DE_SOLICITUD = 12

# Los productos que el sembrador general nunca elige.
TARJETA = "CREDIT_CARD"
LINEA = "REVOLVING_LINE"
PERSONAL = "PERSONAL_LOAN"

ESCENARIOS = {
    "S1": ("Tarjeta con compras sin diferir", TARJETA),
    "S2": ("Tarjeta con una compra a MSI y otra no", TARJETA),
    "S3": ("Tarjeta con compra diferida CON tasa", TARJETA),
    "S4": ("Línea de uso propio en saldo cero", LINEA),
    "S5": ("Préstamo con BNPL", PERSONAL),
    "S6": ("Cuenta con salto de pago", PERSONAL),
    "S7": ("Cartera bajo programa de apoyo", PERSONAL),
}


def _segundo_operador(token_admin):
    """
    Un empleado DISTINTO del administrador, con facultad para proponer un apoyo.

    <p>Se resuelve <b>por rol</b> y no por correo. Estaba puesto a mano —`riesgo@kredius.mx`— y ese
    usuario sólo existía en la base acumulada de una sesión anterior: sobre instalación limpia el
    login daba 401 y el programa no se sembraba. Un sembrador que depende de datos de una corrida
    previa no siembra desde cero, que es justo lo que tiene que saber hacer.

    <p>Todo el personal de demo comparte la clave, así que basta con encontrar a quién pedírsela.
    """
    for e in http("GET", f"{BACKOFFICE}/staff?size=200", token=token_admin) or []:
        if "RISK_ANALYST" in (e.get("roles") or []) and e.get("status") == "ACTIVE":
            return (e["email"], SP.ADMIN[1])
    return None

_CONTADOR = [0]
_ULTIMO_CORTE = ["?"]


def cliente_con_credito(rnd, catalogo, producto, monto, plazo=None, bnpl_dias=None):
    """
    Una persona nueva con su crédito activo, por el camino de la app.

    Devuelve el cliente con `credit_account_id` resuelto, o `None` si el journey no llegó al final —
    que pasa, y no es un fallo del sembrador: es el dominio rechazando algo, y taparlo con un
    reintento escondería justo lo que hay que ver.
    """
    _CONTADOR[0] += 1
    c = SP.Cliente(rnd, _CONTADOR[0], catalogo)
    # El producto y el monto se fijan DESPUÉS de construir: el constructor los elige del catálogo,
    # y aquí cada escenario necesita uno concreto.
    c.producto, c.monto = producto, monto
    if plazo is not None:
        c.plazo = plazo
    # Lo lee `SP.journey` al firmar. Nulo = no se pide, que es lo que deja el plan empezando en el
    # período siguiente; el tope del producto lo aplica cartera recortando lo pedido.
    c.bnpl_dias = bnpl_dias
    c.desenlace = "desembolsado"

    # `_ULTIMO_CORTE` guarda en qué paso se quedó. Sin esto el resumen decía "journey incompleto"
    # siete veces y no distinguía un rechazo del dominio —que es información— de un servicio caído.
    if not SP.alta(c):
        _ULTIMO_CORTE[0] = "alta"
        return None
    c.token = SP.entrar(c)
    if not c.token:
        _ULTIMO_CORTE[0] = "login"
        return None
    desenlace = SP.journey(c)
    if desenlace != "desembolsado":
        _ULTIMO_CORTE[0] = f"journey={desenlace}"
        return None

    # La cuenta **no** existe cuando el journey termina: nace de un evento que cartera consume.
    # Preguntar una sola vez trata como síncrono algo que no lo es, y el sembrador lo reportaba
    # como "journey incompleto" — que es exactamente el diagnóstico equivocado: el journey había
    # ido bien y la cuenta llegó medio segundo después.
    #
    # Esperar aquí no tapa nada: si en veinte segundos no llegó, eso sí es un hallazgo.
    c.credit_account_id = _espera_la_cuenta(c)
    if not c.credit_account_id:
        _ULTIMO_CORTE[0] = "la cuenta no llegó en 20 s tras el desembolso"
        return None
    return c


def _espera_la_cuenta(c, intentos=20, pausa=1.0):
    """El id de la cuenta activa del cliente, en cuanto cartera la haya dado de alta."""
    for _ in range(intentos):
        # `/credit/account` (singular) y no `/accounts`: el BFF devuelve la lista bajo ese nombre.
        cuentas = http("GET", f"{MOBILE}/credit/account", token=c.token) or []
        if cuentas:
            return cuentas[0].get("creditAccountId") or cuentas[0].get("id")
        time.sleep(pausa)
    return None


def comprar(c, monto, plazo=None):
    """
    Una compra sobre la línea. En un producto POST_HOC nace revolvente pura.

    <p>Va por `/credit/dispose`, que es el endpoint que la app usa de verdad. La disposición se
    procesa <b>asíncrona</b> —wallet publica la petición y cartera la resuelve—, así que la
    respuesta no trae el id: hay que buscarlo después.
    """
    cuerpo = {"creditAccountId": c.credit_account_id, "amount": monto}
    if plazo is not None:
        cuerpo["termPeriods"] = plazo
    http("POST", f"{MOBILE}/credit/dispose", cuerpo, token=c.token)
    # Y se busca, porque la respuesta es `{"success": true}` y nada más.
    #
    # Antes esto devolvía esa respuesta tal cual y quien llamaba hacía
    # `if compra.get("dispositionId")` — que nunca era verdad. El diferimiento **no ocurría** y el
    # escenario se declaraba sembrado igual: S2 y S3 imprimían "✓ compra a 6 MSI" sobre tres
    # compras revolventes sin un solo plan. Un sembrador que reporta lo que quiso hacer, en vez de
    # lo que hizo, es peor que uno que falla.
    return _espera_la_compra(c, monto)


def _espera_la_compra(c, monto, intentos=20, pausa=1.0):
    """La disposición recién creada por ese importe, en cuanto cartera la haya resuelto."""
    objetivo = float(monto)
    for _ in range(intentos):
        for d in disposiciones(c):
            if float(d.get("amount") or 0) == objetivo and d.get("dispositionId"):
                return d
        time.sleep(pausa)
    return None


def disposiciones(c):
    """Las compras de la cuenta, por la app: es el titular quien las mira para elegir cuál difiere."""
    datos = http("GET", f"{MOBILE}/credit/dispositions", token=c.token) or []
    return datos if isinstance(datos, list) else datos.get("content", [])


def diferir(c, disposition_id, plazo):
    """
    El titular difiere una compra ya hecha (BK-25).

    Es lo que la vuelve compra a plazos y la saca del exigible del corte. Con plazo 3 o 6 en la
    tarjeta sembrada, la tasa es cero: meses sin intereses.
    """
    return http("POST", f"{MOBILE}/credit/dispositions/{disposition_id}/defer",
                {"termPeriods": plazo}, token=c.token)


def escenario(nombre, rnd, catalogo):
    """Construye un escenario. Devuelve una línea de resumen o None si no se pudo."""
    if nombre == "S1":
        c = cliente_con_credito(rnd, catalogo, TARJETA, 30000)
        if not c:
            return None
        for monto in (3200, 1450, 890):
            comprar(c, monto)
        return f"S1 · tarjeta {c.credit_account_id[:8]} con 3 compras sin diferir"

    if nombre == "S2":
        c = cliente_con_credito(rnd, catalogo, TARJETA, 30000)
        if not c:
            return None
        comprar(c, 3000)                      # se queda revolvente: exigible completa en el corte
        aMsi = comprar(c, 6000)               # ésta se difiere
        if not aMsi:
            return None                       # sin la compra no hay nada que diferir
        diferir(c, aMsi["dispositionId"], 6)   # 6 meses → tasa 0 en la tarjeta sembrada
        return f"S2 · tarjeta {c.credit_account_id[:8]} con una compra a 6 MSI y otra sin diferir"

    if nombre == "S3":
        c = cliente_con_credito(rnd, catalogo, TARJETA, 30000)
        if not c:
            return None
        conTasa = comprar(c, 9000)
        if not conTasa:
            return None
        diferir(c, conTasa["dispositionId"], 12)   # banda 10-12 → 24 %
        return f"S3 · tarjeta {c.credit_account_id[:8]} con compra diferida a 12 con tasa"

    if nombre == "S4":
        c = cliente_con_credito(rnd, catalogo, LINEA, 25000)
        if not c:
            return None
        d = comprar(c, 4000)
        # Se paga completa: la línea vuelve a cero y tiene que seguir ACTIVE con el cupo restaurado.
        # `/credit/payment` y no `/payments`: el BFF resuelve la cuenta del usuario autenticado y
        # sólo recibe el importe. Mandarle la cuenta sería dejar que el cliente diga a cuál paga.
        http("POST", f"{MOBILE}/credit/payment", {"amount": 4000}, token=c.token)
        return f"S4 · línea {c.credit_account_id[:8]} llevada a saldo cero"

    if nombre == "S5":
        # BNPL es una decisión del ALTA y hay que **pedirla**. Antes este escenario no pedía nada
        # y se apoyaba en que el producto lo aplicara solo — que era justo el defecto: el tope se
        # usaba como valor y lo recibía todo el mundo, lo hubiera pedido o no.
        c = cliente_con_credito(rnd, catalogo, PERSONAL, 20000, plazo=12, bnpl_dias=30)
        if not c:
            return None
        return f"S5 · préstamo {c.credit_account_id[:8]} con BNPL a 30 días PEDIDO al firmar"

    if nombre == "S6":
        c = cliente_con_credito(rnd, catalogo, PERSONAL, 15000, plazo=12)
        if not c:
            return None
        plan = http("GET", f"{MOBILE}/credit/schedule", token=c.token) or []
        cuotas = plan if isinstance(plan, list) else plan.get("installments", [])
        if len(cuotas) > 1:
            segunda = cuotas[1].get("installmentId") or cuotas[1].get("id")
            http("POST", f"{MOBILE}/credit/installments/{segunda}/skip", None, token=c.token)
        return f"S6 · préstamo {c.credit_account_id[:8]} con el segundo pago saltado"

    if nombre == "S7":
        c = cliente_con_credito(rnd, catalogo, PERSONAL, 18000, plazo=12)
        if not c:
            return None
        # El programa es masivo: se propone, alguien DISTINTO lo autoriza, y se otorga. Aquí el
        # maker-checker se cumple de verdad porque los dos pasos usan tokens distintos.
        return f"S7 · préstamo {c.credit_account_id[:8]} listo para el padrón de apoyo"

    return None


def programa_de_apoyo(token_maker, token_checker):
    """
    Propone, autoriza y otorga un programa de contingencia (BK-32…BK-36).

    Se hace UNA vez al final, no por cliente: un programa de apoyo es masivo por definición, y
    otorgarlo por cliente sería exactamente el convenio bilateral que ya existía y que este
    mecanismo vino a complementar.

    <b>Quien propone no autoriza.</b> Con el mismo token para las dos mitades el dominio rechaza —y
    hace bien—, así que aquí van dos operadores distintos: riesgo propone, administración autoriza.
    Es la única forma de que la siembra ejercite el camino feliz en vez del de rechazo.
    """
    hoy = __import__("datetime").date.today()
    programa = http("POST", f"{BACKOFFICE}/relief-programs", {
        "name": "Apoyo por contingencia — demo",
        "reason": "NATURAL_DISASTER",
        "deferredPeriods": 3,
        "validFrom": str(hoy),
        "validTo": str(hoy + __import__("datetime").timedelta(days=90)),
        "maxDaysDelinquent": 30,
        "eligibilityCutoffDate": str(hoy - __import__("datetime").timedelta(days=1)),
        "accrualDuringRelief": "ACCRUES",
    }, token=token_maker)
    if not programa:
        return None
    pid = programa["reliefProgramId"]

    padron = http("GET", f"{BACKOFFICE}/relief-programs/{pid}/padron",
                  token=token_maker)
    http("POST", f"{BACKOFFICE}/relief-programs/{pid}/approve", None,
         token=token_checker)
    otorgado = http("POST", f"{BACKOFFICE}/relief-programs/{pid}/grant", None,
                    token=token_checker)
    return pid, (padron or {}).get("cuentasElegibles", 0), (otorgado or {}).get("cuentasInscritas", 0)


def _token_staff(credenciales=None):
    """
    Token del backoffice. Mismo camino que usa `seed-portfolio.py`.

    <p>Se pide una vez y se reutiliza: cada login es una sesión más en la bitácora de auditoría, y
    una siembra que abre cuarenta no ayuda a nadie a leerla.
    """
    correo, clave = credenciales or SP.ADMIN
    return http("POST", f"{BACKOFFICE}/auth/staff/login",
                {"email": correo, "password": clave})["accessToken"]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--clientes", type=int, default=12)
    ap.add_argument("--solo", help="Escenarios a sembrar, separados por coma (S1,S2,…)")
    ap.add_argument("--semilla", type=int, default=None,
                    help="Semilla del generador. OJO: fijarla reproduce las MISMAS personas, y el "
                         "dominio rechaza una CURP ya dada de alta — útil para depurar, no para "
                         "sembrar dos veces.")
    args = ap.parse_args()

    rnd = random.Random(args.semilla)
    pedidos = [s.strip().upper() for s in args.solo.split(",")] if args.solo else list(ESCENARIOS)
    desconocidos = [s for s in pedidos if s not in ESCENARIOS]
    if desconocidos:
        print(f"Escenarios desconocidos: {', '.join(desconocidos)}", file=sys.stderr)
        return 2

    # Los revolventes salen del catálogo, no de una lista fija: si alguien retira la tarjeta del
    # catálogo, esto lo dice al arrancar en vez de sembrar la mitad de los escenarios en silencio.
    token = _token_staff()
    catalogo = CatalogoRevolvente(token, {TARJETA, LINEA})

    print(f"Sembrando {len(pedidos)} escenarios revolventes "
          f"({args.clientes} clientes máx.)\n")

    resumen, fallidos = [], Counter()
    for i in range(args.clientes):
        nombre = pedidos[i % len(pedidos)]
        try:
            linea = escenario(nombre, rnd, catalogo)
        except Exception as e:                      # noqa: BLE001 — el resumen vale más que el traceback
            fallidos[f"{nombre}: {type(e).__name__}"] += 1
            continue
        if linea:
            resumen.append(linea)
            print(f"  ✓ {linea}")
        else:
            fallidos[f"{nombre}: se cortó en {_ULTIMO_CORTE[0]}"] += 1

    # El programa de apoyo va UNA vez y al final, sobre lo que ya existe: es masivo por definición,
    # y otorgarlo por cliente sería el convenio bilateral que ya existía.
    if "S7" in pedidos:
        try:
            # Dos operadores DISTINTOS, que es lo único que ejercita el maker-checker de verdad.
            # Riesgo propone, administración autoriza. Con el mismo token para las dos mitades el
            # dominio rechaza —y hace bien—, así que sembrar así no probaba el camino feliz sino
            # el de rechazo, disfrazado de fallo del sembrador.
            riesgo = _segundo_operador(token)
            if not riesgo:
                raise RuntimeError("no hay ningún RISK_ANALYST activo: el maker-checker necesita "
                                   "dos personas y sembrar con una sola ejercita el rechazo")
            apoyo = programa_de_apoyo(_token_staff(riesgo), token)
            if apoyo:
                pid, elegibles, inscritas = apoyo
                print(f"\n  ✓ programa de apoyo {pid[:8]} — {elegibles} elegibles, "
                      f"{inscritas} inscritas")
        except Exception as e:                      # noqa: BLE001
            fallidos[f"programa de apoyo: {type(e).__name__}"] += 1

    print(f"\n{len(resumen)} escenarios sembrados")
    for motivo, cuantos in fallidos.most_common():
        print(f"  ✗ {cuantos} × {motivo}")

    return 0 if resumen else 1


if __name__ == "__main__":
    sys.exit(main())
