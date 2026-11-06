#!/usr/bin/env python3
"""Hace avanzar el ciclo de vida de la cartera sembrada **día por día**, con la cadena nocturna real.

`seed-portfolio.py`, `seed-distribuidoras.py` y `seed-revolventes.py` dejan créditos vivos, originados
en la fecha en la que estaba el reloj de negocio (la demo los origina meses atrás). Esto los hace
vivir: cada día de negocio, con el reloj puesto en ese día, una parte de la cartera paga su cuota,
otra liquida, otra se atrasa y se recupera, otra nunca paga; y cada noche corre la misma cadena que
el cron de las 00:05: foto → devengo → cierre de cartera → corte → estados de cuenta → riesgo →
cobranza; fin de mes: facturación y cierre contable; día 5: liquidación de comisiones.

**No inserta filas ni retrocede calendarios.** La mora nace de no pagar; el interés, de la foto de cada
día; la reserva, del recálculo de riesgo de cada noche; las pólizas, de los hechos con su fecha. Es el
mismo motor de las pruebas E2E (`tools/ciclo-credito/motor.py`), con un plan aleatorio de semilla fija
en vez del plan escrito.

    python3 scripts/seed-ciclo-credito.py --desde 2026-04-01            # hasta ayer (fecha de negocio)
    python3 scripts/seed-ciclo-credito.py --desde 2026-04-01 --hasta 2026-10-31 --semilla 7

`--desde` es la fecha en la que se puso el reloj antes de originar (`corre.py reloj`): el ciclo
empieza ahí. Al terminar, el reloj vuelve a ser el real.
"""
from __future__ import annotations

import argparse
import datetime
import pathlib
import random
import sys
import time
from zoneinfo import ZoneInfo

RAIZ = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RAIZ / "tools" / "ciclo-credito"))

import motor   # noqa: E402
import puente  # noqa: E402

REVOLVENTES = {"CREDIT_CARD", "REVOLVING_LINE", "BUSINESS_REVOLVING_LINE"}
DISTRIBUIDORA = "DISTRIBUTOR_LINE"


def cuentas_activas(token):
    r = motor.bo(token, "GET", "/portfolio?status=ACTIVE&size=1000") or {}
    filas = r.get("content", r) if isinstance(r, dict) else r
    return [c for c in filas if c.get("creditAccountId") or c.get("id")]


def beneficiarios_de(obligor):
    """A distributor's clients (their prospect ids, which is what a placement names), from its party relationships."""
    filas = motor.psql(f"""
        SELECT b.prospect_id, coalesce(b.first_name, 'cliente')
          FROM party.parties d
          JOIN party.party_relationships r ON r.party_id = d.party_id AND r.relationship_type = 'BENEFICIARY'
          JOIN party.parties b ON b.party_id = r.related_party_id
         WHERE d.prospect_id = '{obligor}' ORDER BY b.prospect_id""")
    return [{"prospect": f[0], "persona": f[1]} for f in filas if f and f[0]]


def _redondea(x, paso=100):
    return max(paso, int(round(x / paso)) * paso)


def actividad_revolvente(producto, limite, rnd: random.Random, dias: int, beneficiarios, hasta_dia=None):
    """
    What a card, a line or a distributor does with its credit over the history: purchases every few weeks (a card
    defers one of them to months), placements to the distributor's clients. Without this the lines sat at zero from
    the first cutoff and no statement of the demo had a purchase, a deferred plan or a placement on it.
    """
    fin = min(dias - 5, hasta_dia if hasta_dia is not None else dias)
    limite = float(limite or 20_000)
    e = {}
    if producto == DISTRIBUIDORA:
        if beneficiarios:
            colocaciones, d = {}, rnd.randint(1, 10)
            while d < fin:
                colocaciones.setdefault(d, []).append(
                    (rnd.randrange(len(beneficiarios)), _redondea(limite * rnd.uniform(0.04, 0.10), 500), rnd.choice([6, 8, 12])))
                d += rnd.randint(25, 50)
            e["colocaciones"] = colocaciones
        return e
    compras, d = {}, rnd.randint(1, 8)
    while d < fin:
        compras[d] = (_redondea(limite * rnd.uniform(0.03, 0.10)), None)
        d += rnd.randint(14, 35)
    e["compras"] = compras
    if producto == "CREDIT_CARD" and compras and rnd.random() < 0.6:
        dia = rnd.choice(sorted(compras)[:max(1, len(compras) // 2)])
        e["difiere"] = {dia: (compras[dia][0], rnd.choice([3, 6, 12]))}
    return e


def plan_para(cuentas, rnd: random.Random, dias: int):
    """
    Reparte la cartera en desenlaces, por cupos y no al azar puro, para que cada caso exista aunque
    la cartera sea chica: liquidan, pagan con algún atraso, se atrasan y se recuperan, se atrasan y
    negocian, nunca pagan. El resto queda al corriente, que es lo que le pasa a la mayoría.
    """
    cuentas = list(cuentas)
    rnd.shuffle(cuentas)
    cortes = [("liquida", 0.12), ("atrasa_y_paga", 0.15), ("recupera", 0.12), ("negocia", 0.10),
              ("cae_en_mora", 0.10), ("nunca_paga", 0.08)]

    # The quotas are drawn per family (loans, cards and lines, distributors), not over the whole book: drawn
    # together, the ten distributors of a demo could all land on outcomes that end current, and the demo had no
    # distributor in arrears to show.
    def familia(c):
        producto = c.get("productType")
        return "distribuidora" if producto == DISTRIBUIDORA else "revolvente" if producto in REVOLVENTES else "prestamo"

    asignadas = []
    for fam in ("prestamo", "revolvente", "distribuidora"):
        grupo = [c for c in cuentas if familia(c) == fam]
        n, i = len(grupo), 0
        for nombre, prop in cortes + [("puntual", 1.0)]:
            cupo = n - i if nombre == "puntual" else (max(1, round(n * prop)) if n >= 8 else round(n * prop))
            cupo = max(0, min(cupo, n - i))
            asignadas += [(c, nombre) for c in grupo[i:i + cupo]]
            i += cupo

    plan, estado = {}, {}
    for c, nombre in asignadas:
        cid = c.get("creditAccountId") or c.get("id")
        producto = c.get("productType")
        clave = f"{nombre[:3].upper()}-{cid[:8]}"
        e = {"titulo": nombre, "producto": producto, "puntual": True}
        revolvente = producto in REVOLVENTES or producto == DISTRIBUIDORA
        beneficiarios = beneficiarios_de(c.get("obligorPartyId")) if producto == DISTRIBUIDORA else []
        if revolvente:
            # A distributor line has no minimum: «pays the minimum» would be «never pays».
            e["paga"] = [rnd.choice(["exigible", "total"] if producto == DISTRIBUIDORA else ["minimo", "exigible", "total"])]
            # Who never pays stops using the line too (its purchases would be rejected against the limit).
            e.update(actividad_revolvente(producto, c.get("creditLimit"), rnd, dias, beneficiarios,
                                          hasta_dia=30 if nombre == "nunca_paga" else None))
        if nombre == "liquida":
            e["liquida_en"] = rnd.randint(min(40, dias - 1), max(min(40, dias - 1), dias - 10))
        elif nombre == "atrasa_y_paga":
            if revolvente:
                # A statement or two paid late: stops for 20-40 days and pays everything it skipped.
                e["deja_de_pagar_desde_dia"] = rnd.randint(30, max(31, dias - 60))
                e["reanuda_en"] = e["deja_de_pagar_desde_dia"] + rnd.randint(20, 40)
            else:
                e["retrasos"] = {k: rnd.randint(4, 25) for k in rnd.sample(range(1, 7), 2)}
        elif nombre == "cae_en_mora":
            # Pays until 50-100 days before the end and then stops: it ends the history in arrears (Stage 2 or 3,
            # with a collections case), short of the write-off.
            desde = max(10, dias - rnd.randint(50, 100))
            if revolvente:
                e["deja_de_pagar_desde_dia"] = desde
            else:
                dias_por_cuota = {"MICRO_LOAN": 7, "PAYROLL_LOAN": 15}.get(producto, 30)
                e["desde_cuota_no_paga"] = max(1, desde // dias_por_cuota)
        elif nombre == "recupera":
            if revolvente:
                # Stops, falls 30 to 90 days behind, and pays every statement it skipped: the line is current again.
                e["deja_de_pagar_desde_dia"] = rnd.randint(20, 60)
                e["reanuda_en"] = min(dias - 5, e["deja_de_pagar_desde_dia"] + rnd.randint(40, 90))
            else:
                e["desde_cuota_no_paga"] = rnd.choice([1, 2])
                e["al_dia_en"] = [rnd.randint(70, max(71, dias - 15))]
        elif nombre == "negocia":
            if revolvente:
                # Stops paying, promises, restructures (rate) and goes on paying from the agreement.
                e["deja_de_pagar_desde_dia"] = rnd.randint(15, 30)
                e["promesa_en"] = e["deja_de_pagar_desde_dia"] + rnd.randint(35, 50)
                e["convenio_en"] = e["deja_de_pagar_desde_dia"] + rnd.randint(65, 85)
                e["reanuda_en"] = e["convenio_en"] + 3
            else:
                e["desde_cuota_no_paga"] = 1
                e["promesa_en"] = rnd.randint(45, 70)
                e["convenio_en"] = rnd.randint(95, 125)
                e["al_dia_en"] = [e["convenio_en"] + 3]
        elif nombre == "nunca_paga":
            e["puntual"] = False
            if revolvente:
                e["deja_de_pagar_desde_dia"] = 0
            # Written off as soon as its own delinquency passes the collections threshold (181 days):
            # with seven or more months of history, the oldest of these get there.
            e["quebranto_en"] = 0
        plan[clave] = e
        estado[clave] = {"tipo": "distribuidora" if producto == DISTRIBUIDORA else "persona", "cuenta": cid,
                         "obligor": c.get("obligorPartyId"), "prospect": c.get("obligorPartyId"), "producto": producto,
                         "persona": c.get("holderName") or c.get("obligorName"), "contrato": c.get("contractNumber"),
                         "beneficiarios": beneficiarios, "pagos": [], "compras": []}
    return plan, estado


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--desde", required=True, help="la fecha en la que estaba el reloj al originar (AAAA-MM-DD)")
    ap.add_argument("--hasta", help="último día a cerrar; por omisión, ayer en la zona de negocio")
    ap.add_argument("--semilla", type=int, default=7)
    ap.add_argument("--evidencia", help="carpeta de bitácora; por omisión ~/Documents/material-kredius/siembra/<fecha>")
    args = ap.parse_args()

    desde = datetime.date.fromisoformat(args.desde)
    hoy = datetime.datetime.now(ZoneInfo("America/Mexico_City")).date()
    hasta = datetime.date.fromisoformat(args.hasta) if args.hasta else hoy - datetime.timedelta(days=1)
    dias = (hasta - desde).days + 1
    if dias < 1:
        sys.exit(f"nada que correr: {desde} → {hasta}")
    evidencia = pathlib.Path(args.evidencia).expanduser() if args.evidencia else \
        pathlib.Path.home() / "Documents" / "material-kredius" / "siembra" / f"ciclo-{time.strftime('%Y%m%d-%H%M%S')}"

    puente.up()
    token = motor.login_admin()
    cuentas = cuentas_activas(token)
    print(f"  {len(cuentas)} cuentas activas · {dias} días de historia ({desde} → {hasta}) · semilla {args.semilla}")
    if not cuentas:
        sys.exit("  ! sin cartera activa: nada que hacer avanzar")

    rnd = random.Random(args.semilla)
    plan, estado = plan_para(cuentas, rnd, dias)
    por_desenlace = {}
    for e in plan.values():
        por_desenlace[e["titulo"]] = por_desenlace.get(e["titulo"], 0) + 1
    print("  desenlaces: " + " · ".join(f"{k}={v}" for k, v in sorted(por_desenlace.items())))

    # The demo's book with the demo's staff: the engine is the E2E's, the people and decisions here are the demo's.
    corrida = motor.Corrida(desde, hasta, args.semilla, evidencia, plan, verbose=False, personal=motor.PERSONAL_DEMO,
                            fondeo=motor.FONDEO_DEMO)
    corrida.estado = estado
    try:
        corrida.correr()
    finally:
        motor.reloj_real()
        print("  reloj real restablecido")

    # The open month is billed too: the payments and charges of the last stretch created invoiceable
    # items that the month-end run of the previous period could not have seen.
    periodo = f"{hasta.year}{hasta.month:02d}"
    try:
        r = motor.svc("accounting-service", "POST", f"/api/v1/accounting/billing-runs?period={periodo}", timeout=300) or {}
        print(f"  facturación del período abierto {periodo}: {r.get('invoicesRequested', 0)} CFDI")
    except motor.HttpError as e:
        print(f"  ! la facturación de {periodo} falló: {e}")
    # A fresh login: the token taken before the eight months of nights expired long ago (15 minutes).
    r = motor.bo(motor.login_admin(), "POST", "/accounting/backfill-origin-units") or {}
    print(f"  sucursal de origen: {r.get('sellados', 0)} créditos sellados · {r.get('polizasAtribuidas', 0)} pólizas atribuidas")
    print(f"\n\033[1m✓ ciclo de vida sembrado\033[0m · bitácora en {evidencia}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
