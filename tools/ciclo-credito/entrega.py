"""What the run hands over: per scenario, the statements (PDF), the vouchers, the ledger, the daily
snapshot and a day-by-day view that ties them together; globally, the trial balance per period and
the reconciliation of the whole chain. Everything is read back from the services and the database
after the simulation, never from the engine's memory."""
from __future__ import annotations

import csv
import datetime
import io
import json
import pathlib
import hashlib
import re
import shutil
import subprocess
import sys
from collections import defaultdict

import huella
import motor
import veredicto
from motor import svc, bo, psql

RAIZ = motor.RAIZ
PG_CSV = ["docker", "exec", "-i", "fintech-services-postgres-1", "psql", "-U", "fintech", "-d", "fintech", "--csv"]


def csv_sql(sql: str) -> list[dict]:
    r = subprocess.run(PG_CSV + ["-c", sql], capture_output=True, text=True, check=True)
    return list(csv.DictReader(io.StringIO(r.stdout)))


def escribe_csv(ruta: pathlib.Path, filas: list[dict]):
    with open(ruta, "w", newline="", encoding="utf-8") as f:
        if not filas:
            f.write("")
            return
        w = csv.DictWriter(f, fieldnames=list(filas[0].keys()))
        w.writeheader()
        w.writerows(filas)


def escribe_json(ruta: pathlib.Path, datos):
    ruta.write_text(json.dumps(datos, ensure_ascii=False, indent=1, default=str), encoding="utf-8")


def slug(texto: str) -> str:
    t = texto.lower()
    for a, b in zip("áéíóúñ", "aeioun"):
        t = t.replace(a, b)
    return re.sub(r"[^a-z0-9]+", "-", t).strip("-")[:60]


def dinero(x) -> str:
    try:
        return f"{float(x):,.2f}"
    except (TypeError, ValueError):
        return "" if x in (None, "") else str(x)


# ── per account ──────────────────────────────────────────────────────────────────────────────────

def polizas_de(cuenta: str) -> list[dict]:
    todas, page = [], 0
    while True:
        r = svc("accounting-service", "GET", f"/api/v1/accounting/vouchers?creditAccountId={cuenta}&size=200&page={page}") or {}
        todas += r.get("content", [])
        if page + 1 >= (r.get("totalPages") or 1):
            return todas
        page += 1


def polizas_csv(polizas: list[dict]) -> list[dict]:
    filas = []
    for p in sorted(polizas, key=lambda p: (p.get("voucherDate") or "", p.get("folio") or 0)):
        for l in p.get("lines", []):
            filas.append({"fecha": (p.get("voucherDate") or "")[:10], "tipo": p.get("voucherType"), "folio": p.get("folio"),
                          "periodo": p.get("period"), "concepto": p.get("concept"), "hecho": p.get("triggerEvent"),
                          "cuenta": l.get("accountCode"), "nombre": l.get("accountName"),
                          "cargo": l.get("debit"), "abono": l.get("credit"), "extemporanea": p.get("latePosting"),
                          "origen": p.get("sourceEventId")})
    return filas


def estados_pdf(cuenta: str, destino: pathlib.Path) -> list[dict]:
    estados = svc("statement-service", "GET", f"/api/v1/statements?creditAccountId={cuenta}") or []
    destino.mkdir(parents=True, exist_ok=True)
    filas = []
    for s in sorted(estados, key=lambda s: s.get("cycleNumber", 0)):
        fila = {k: s.get(k) for k in ("cycleNumber", "periodStart", "cutoffDate", "paymentDueDate", "balanceAtCutoff",
                                      "amountDue", "minimumPayment", "status", "layout", "contractNumber", "statementId")}
        if s.get("status") == "GENERATED":
            # The document statement-service rendered and stored; nothing here generates or alters it.
            pdf = svc("statement-service", "GET", f"/api/v1/statements/{s['statementId']}/pdf")
            nombre = f"{s.get('cutoffDate')}-corte-{s.get('cycleNumber'):02d}.pdf"
            (destino / nombre).write_bytes(pdf)
            fila["archivo"] = nombre
            fila["sha256"] = hashlib.sha256(pdf).hexdigest()
        filas.append(fila)
    return filas


def recibos_pdf(cuenta: str, destino: pathlib.Path) -> list[dict]:
    """The receipts of her clients on this line: one PDF per client and statement, named by cutoff and folio."""
    filas = csv_sql(f"""SELECT receipt_id, cycle_number, cutoff_date, payment_due_date, beneficiary_party_id, client_name,
                               client_type, live_loans, total_due, payment_reference, clabe, folio, status, template_version
                          FROM receipt.receipts WHERE credit_account_id = '{cuenta}'
                         ORDER BY cycle_number, client_name""")
    destino.mkdir(parents=True, exist_ok=True)
    for f in filas:
        if f["status"] != "GENERATED":
            continue
        pdf = svc("receipt-service", "GET", f"/api/v1/receipts/{f['receipt_id']}/pdf")
        nombre = f"{f['cutoff_date']}-corte-{int(f['cycle_number']):02d}-{slug(f['client_name'] or f['beneficiary_party_id'][:8])}.pdf"
        (destino / nombre).write_bytes(pdf)
        f["archivo"] = nombre
        f["sha256"] = hashlib.sha256(pdf).hexdigest()
    return filas


def paso_a_paso(clave, st, foto, libro, cargos, polizas, cortes, estados, bitacora) -> str:
    """One row per day the account moved or was photographed: the chain, readable."""
    por_dia = defaultdict(lambda: {"cargos": [], "libro": [], "polizas": [], "ops": [], "corte": None, "estado": None, "foto": None})
    for f in foto:
        por_dia[f["business_date"]]["foto"] = f
    for e in libro:
        por_dia[e["value_date"]]["libro"].append(e)
    for c in cargos:
        por_dia[c["accrual_date"]]["cargos"].append(c)
    for p in polizas:
        por_dia[(p.get("voucherDate") or "")[:10]]["polizas"].append(p)
    for c in cortes:
        por_dia[c["cutoff_date"]]["corte"] = c
    for s in estados:
        por_dia[s.get("cutoffDate")]["estado"] = s
    for b in bitacora:
        if b.get("escenario") == clave and b.get("accion") not in ("noche",):
            por_dia[b["fecha"]]["ops"].append(b)

    out = [f"# {clave} · {st.get('titulo', '')}", "",
           f"Cuenta `{st.get('cuenta')}` · contrato {st.get('contrato')} · {st.get('producto')} · titular: {st.get('persona')}", "",
           "Cada fila es un día de negocio. **Foto** = saldos al cierre del día que tomó cartera (base del devengo y del corte). "
           "**Cargos** = lo que cargos devengó sobre esa foto (interés, IVA, moratorio). **Libro** = asientos de cartera con fecha valor ese día. "
           "**Pólizas** = lo que contabilidad asentó con fecha de ese hecho. **Corte/Estado** = el corte sellado y su estado de cuenta.", "",
           "| Día | Capital | Interés | IVA | Mora | Deuda | DPD | Cargos del día | Movimientos del libro | Pólizas | Operaciones | Corte / estado de cuenta |",
           "|---|---:|---:|---:|---:|---:|---:|---|---|---|---|---|"]
    for dia in sorted(k for k in por_dia if k):
        x = por_dia[dia]
        f = x["foto"] or {}
        cargos_txt = "; ".join(f"{c['charge_type'].replace('_INTEREST', '').replace('ORDINARY', 'interés').replace('MORATORIUM', 'moratorio').replace('IVA', 'IVA').lower()} {dinero(c['amount'])}"
                              + (f" (base {dinero(c['basis'])})" if c.get("basis") not in (None, "") and c["charge_type"] != "IVA" else "")
                              for c in x["cargos"])
        libro_txt = "; ".join(f"{e['event_type']} {dinero(e['delta_amount'])}" for e in x["libro"])
        pol_txt = "; ".join(f"{p.get('voucherType')}-{p.get('folio')} {p.get('triggerEvent')} {dinero(p.get('totalDebit'))}" for p in x["polizas"])
        ops_txt = "; ".join(f"{b['accion']} {b.get('detalle', '')}".strip() + (f" ${dinero(b['monto'])}" if b.get("monto") else "") for b in x["ops"])
        corte_txt = ""
        if x["corte"]:
            c = x["corte"]
            corte_txt = f"corte {c.get('cycle_number')} {c.get('status')}: capital {dinero(c.get('principal_at_cutoff'))}"
        if x["estado"]:
            s = x["estado"]
            corte_txt += f"; estado {s.get('status')} saldo {dinero(s.get('balanceAtCutoff'))}, exigible {dinero(s.get('amountDue'))}, mínimo {dinero(s.get('minimumPayment'))}, vence {s.get('paymentDueDate')}"
            if s.get("archivo"):
                corte_txt += f" ([pdf](estados-de-cuenta/{s['archivo']}))"
        out.append(f"| {dia} | {dinero(f.get('principal'))} | {dinero(f.get('accrued_interest'))} | {dinero(f.get('iva'))} | {dinero(f.get('penalty'))} | "
                   f"{dinero(f.get('total_debt'))} | {f.get('days_delinquent', '')} | {cargos_txt} | {libro_txt} | {pol_txt} | {ops_txt} | {corte_txt} |")
    return "\n".join(out) + "\n"


def mora_md(clave: str, st: dict, cuenta: str, foto: list[dict], cargos: list[dict], bitacora: list[dict]) -> str:
    """
    A distributor's arrears, day by day: how late she is, the principal overdue the moratorium runs on, the moratorium
    of the day and accumulated, whether her line is blocked for placing, and what collections did that day (the
    automatic messages, the agent's calls and visits, promises, agreements) next to what she did (payments, placements
    refused). Read from the day snapshot, the charges, collections' own tables and the run's log.
    """
    contactos = csv_sql(f"""
        SELECT (t.attempted_at AT TIME ZONE 'America/Mexico_City')::date AS dia, t.channel, t.result, t.origin, t.dunning_step
          FROM collections.contact_attempts t JOIN collections.collection_cases c ON c.case_id = t.case_id
         WHERE c.credit_account_id = '{cuenta}' ORDER BY t.attempted_at""")
    promesas = csv_sql(f"""
        SELECT (p.created_at AT TIME ZONE 'America/Mexico_City')::date AS dia, p.promised_date, p.amount, p.status
          FROM collections.payment_promises p JOIN collections.collection_cases c ON c.case_id = p.case_id
         WHERE c.credit_account_id = '{cuenta}' ORDER BY p.created_at""")
    convenios = csv_sql(f"""
        SELECT (a.proposed_at AT TIME ZONE 'America/Mexico_City')::date AS dia, a.type, a.status, a.forgiven_amount
          FROM collections.collection_agreements a JOIN collections.collection_cases c ON c.case_id = a.case_id
         WHERE c.credit_account_id = '{cuenta}' ORDER BY a.proposed_at""")
    casos = csv_sql(f"""
        SELECT (opened_at AT TIME ZONE 'America/Mexico_City')::date AS abierto, (closed_at AT TIME ZONE 'America/Mexico_City')::date AS cerrado,
               status, current_bucket, strategy FROM collections.collection_cases WHERE credit_account_id = '{cuenta}' ORDER BY opened_at""")
    por_dia = defaultdict(lambda: {"foto": None, "mora": [], "cobranza": [], "ops": []})
    for f in foto:
        por_dia[f["business_date"]]["foto"] = f
    for c in cargos:
        if c["charge_type"] == "MORATORIUM_INTEREST":
            por_dia[c["accrual_date"]]["mora"].append(c)
    for t in contactos:
        quien = "sistema" if t.get("origin") == "AUTOMATIC" else "agente"
        paso = f" paso {t['dunning_step']}" if t.get("dunning_step") not in (None, "") else ""
        por_dia[t["dia"]]["cobranza"].append(f"{quien}: {t['channel']} {t['result']}{paso}")
    for p in promesas:
        por_dia[p["dia"]]["cobranza"].append(f"promesa de {dinero(p['amount'])} para el {p['promised_date']} ({p['status']})")
    for a in convenios:
        por_dia[a["dia"]]["cobranza"].append(f"convenio {a['type']} {a['status']}" + (f" condona {dinero(a['forgiven_amount'])}" if a.get("forgiven_amount") not in (None, "") else ""))
    for c in casos:
        por_dia[c["abierto"]]["cobranza"].append(f"caso abierto ({c['strategy']})")
        if c.get("cerrado"):
            por_dia[c["cerrado"]]["cobranza"].append("caso cerrado: al corriente")
    for b in bitacora:
        if b.get("escenario") == clave and b.get("accion") in ("pago", "pago rechazado", "colocación rechazada", "colocación", "promesa de pago",
                                                                "contacto de cobranza", "reestructura", "quita", "quebranto"):
            por_dia[b["fecha"]]["ops"].append(f"{b['accion']} {b.get('detalle', '')}".strip() + (f" ${dinero(b['monto'])}" if b.get("monto") else ""))
    acumulado = 0.0
    out = [f"# {clave} · {st.get('titulo', '')} · la mora día a día", "",
           f"Cuenta `{cuenta}` · contrato {st.get('contrato')} · {st.get('persona')}", "",
           "Sólo los días con atraso o con algo de cobranza. **DPD** = días de atraso al cierre del día (la foto de cartera). "
           "**Capital vencido** = la base del moratorio: el capital de los estados vencidos sin pagar, nunca su interés ni su IVA. "
           "**Moratorio** = 36 % anual / 360 sobre esa base, desde dos días después de la fecha límite. "
           "**Bloqueada** = la línea no coloca mientras un estado esté vencido sin pagar. "
           "**Cobranza** = lo que hizo collections ese día (mensajes automáticos, llamadas y visitas del agente, promesas, convenios). "
           "**Ella** = lo que hizo la distribuidora.", "",
           "| Día | DPD | Capital vencido | Moratorio del día | Moratorio acumulado | Bloqueada | Cobranza | Ella |",
           "|---|---:|---:|---:|---:|:---:|---|---|"]
    for dia in sorted(k for k in por_dia if k):
        x = por_dia[dia]
        f = x["foto"] or {}
        dpd = int(f.get("days_delinquent") or 0)
        mora = sum(float(c["amount"]) for c in x["mora"])
        acumulado += mora
        if dpd == 0 and not mora and not x["cobranza"] and not x["ops"]:
            continue
        out.append(f"| {dia} | {dpd} | {dinero(f.get('overdue_principal'))} | {dinero(mora) if mora else ''} | "
                   f"{dinero(acumulado) if acumulado else ''} | {'sí' if dpd > 0 else ''} | {'; '.join(x['cobranza'])} | {'; '.join(x['ops'])} |")
    return "\n".join(out) + "\n"


def flujo_mes_a_mes(cuenta: str) -> list[dict]:
    """
    Each statement of the account, cut by cut, with the figures it showed: how the balance moved period after period
    (what it opened with, what was placed or bought, the interest, IVA and moratorium charged, what was paid, what it
    closed with) and what it asked (amount due, minimum, payment due date), plus whether that cycle was paid.
    """
    return csv_sql(f"""
        SELECT s.cycle_number AS ciclo, s.period_start AS desde, s.cutoff_date AS corte, s.payment_due_date AS fecha_limite,
               (s.snapshot->'view'->'opening'->>'total')::numeric AS saldo_inicial,
               coalesce((s.snapshot->'view'->'totals'->>'dispositions')::numeric, 0) AS colocaciones_o_compras,
               coalesce((s.snapshot->'view'->'totals'->>'ordinaryInterest')::numeric, 0) AS interes,
               coalesce((s.snapshot->'view'->'totals'->>'iva')::numeric, 0) AS iva,
               coalesce((s.snapshot->'view'->'totals'->>'moratoriumInterest')::numeric, 0) AS moratorio,
               coalesce((s.snapshot->'view'->'totals'->>'fees')::numeric, 0) AS comisiones,
               coalesce((s.snapshot->'view'->'totals'->>'payments')::numeric, 0) AS pagos,
               (s.snapshot->'view'->'closing'->>'principal')::numeric AS capital_al_corte,
               s.balance_at_cutoff AS saldo_al_corte, s.amount_due AS exigible, s.minimum_payment AS minimo,
               coalesce((s.snapshot->'view'->>'arrears')::numeric, 0) AS vencido,
               (SELECT i.status FROM credit_portfolio.installments i
                 WHERE i.schedule_id = s.credit_account_id AND i.installment_number = s.cycle_number
                   AND i.status <> 'REPLACED' LIMIT 1) AS pagado,
               s.status AS estado_del_pdf
          FROM statement.statements s
         WHERE s.credit_account_id = '{cuenta}'
         ORDER BY s.cycle_number""")


def planes_de_colocaciones(cuenta: str) -> list[dict]:
    """A distributor's placements, installment by installment: the interest each period carries and when it ends."""
    return csv_sql(f"""
        SELECT d.disposition_id AS colocacion, (d.created_at AT TIME ZONE 'America/Mexico_City')::date AS colocada, d.amount AS monto, i.installment_number AS cuota,
               coalesce(i.original_due_date, i.due_date) AS fin_del_periodo, i.due_date AS vence, i.billed_cycle AS ciclo_que_la_cobra,
               i.principal_amount AS capital, i.interest_amount AS interes, i.tax_amount AS iva, i.total_amount AS total,
               i.status AS estatus,
               (SELECT coalesce(sum(cr.amount), 0) FROM charges.charge_records cr
                 WHERE cr.credit_account_id = d.credit_account_id AND cr.charge_type = 'ORDINARY_INTEREST'
                   AND cr.accrual_date = coalesce(i.original_due_date, i.due_date)) AS interes_cobrado_ese_dia
          FROM credit_portfolio.dispositions d
          JOIN credit_portfolio.installments i ON i.schedule_id = d.disposition_id
         WHERE d.credit_account_id = '{cuenta}' AND d.disposition_type = 'THIRD_PARTY_CREDIT'
         ORDER BY d.created_at, i.installment_number""")


def flujo_md(clave: str, st: dict, filas: list[dict]) -> str:
    md = [f"# {clave} · {st.get('titulo', '')} · flujo mes a mes", "",
          f"{st.get('persona', '')} · {st.get('producto', '')} · contrato {st.get('contrato', '')}", "",
          "Cada fila es un estado de cuenta generado por statement-service, con las cifras que mostró.", "",
          "| Ciclo | Periodo | Corte | Fecha límite | Saldo inicial | Colocaciones / compras | Interés | IVA | Moratorio | Pagos "
          "| Saldo al corte | Exigible | Mínimo | Vencido | Pagado |",
          "|---:|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    for f in filas:
        md.append(f"| {f['ciclo']} | {f['desde']} → {f['corte']} | {f['corte']} | {f['fecha_limite']} | {dinero(f['saldo_inicial'])} | "
                  f"{dinero(f['colocaciones_o_compras'])} | {dinero(f['interes'])} | {dinero(f['iva'])} | {dinero(f['moratorio'])} | "
                  f"{dinero(f['pagos'])} | {dinero(f['saldo_al_corte'])} | {dinero(f['exigible'])} | {dinero(f['minimo'])} | "
                  f"{dinero(f['vencido'])} | {f.get('pagado') or ''} |")
    return "\n".join(md) + "\n"


def entrega_cuenta(clave: str, st: dict, carpeta: pathlib.Path, bitacora: list[dict]) -> dict:
    cuenta = st["cuenta"]
    carpeta.mkdir(parents=True, exist_ok=True)
    detalle = motor.Corrida.cuenta(None, cuenta)
    escribe_json(carpeta / "cuenta.json", detalle)
    plan = svc("credit-portfolio-service", "GET", f"/api/v1/portfolio/accounts/{cuenta}/amortization-schedule") or []
    escribe_csv(carpeta / "plan-de-pagos.csv", [{k: i.get(k) for k in ("installmentNumber", "dueDate", "principalAmount", "interestAmount",
                                                                      "taxAmount", "totalAmount", "status", "originalDueDate")} for i in plan])
    libro = csv_sql(f"SELECT value_date, applied_at, event_type, delta_amount, principal_after, interest_after, penalty_after, "
                    f"day_close_date, source_event_id FROM credit_portfolio.balance_events WHERE credit_account_id = '{cuenta}' "
                    f"ORDER BY value_date, applied_at")
    escribe_csv(carpeta / "libro-de-cartera.csv", libro)
    foto = csv_sql(f"SELECT business_date, account_status, principal, accrued_interest, iva, penalty, total_debt, overdue_principal, "
                   f"overdue_installments, oldest_due_date, days_delinquent, later_movements, ledger_total, ledger_gap "
                   f"FROM credit_portfolio.account_day_snapshots WHERE credit_account_id = '{cuenta}' ORDER BY business_date")
    escribe_csv(carpeta / "foto-diaria.csv", foto)
    cargos = csv_sql(f"SELECT accrual_date, charge_type, basis, rate, amount, status, day_close_date, charge_id "
                     f"FROM charges.charge_records WHERE credit_account_id = '{cuenta}' ORDER BY accrual_date, charge_type")
    escribe_csv(carpeta / "cargos.csv", cargos)
    cortes = csv_sql(f"SELECT c.cycle_number, c.cutoff_date, c.payment_due_date, c.status, c.principal_at_cutoff, c.interest_at_cutoff, "
                     f"c.penalty_at_cutoff, c.balance_at_cutoff, c.amount_due, c.minimum_payment, c.movement_count, "
                     f"(SELECT max(u.days_delinquent_at_day) FROM closing.close_units u JOIN closing.close_runs r ON r.run_id = u.run_id "
                     f"  WHERE u.credit_account_id = c.credit_account_id AND r.business_date = c.cutoff_date) AS days_delinquent_at_cutoff "
                     f"FROM closing.cutoff_schedules c WHERE c.credit_account_id = '{cuenta}' ORDER BY c.cycle_number")
    escribe_csv(carpeta / "cortes.csv", cortes)
    polizas = polizas_de(cuenta)
    escribe_json(carpeta / "polizas.json", polizas)
    escribe_csv(carpeta / "polizas.csv", polizas_csv(polizas))
    estados = estados_pdf(cuenta, carpeta / "estados-de-cuenta")
    escribe_csv(carpeta / "estados-de-cuenta.csv", estados)
    flujo = flujo_mes_a_mes(cuenta)
    escribe_csv(carpeta / "flujo-mes-a-mes.csv", flujo)
    (carpeta / "flujo-mes-a-mes.md").write_text(flujo_md(clave, st, flujo), encoding="utf-8")
    if st.get("tipo") == "distribuidora":
        escribe_csv(carpeta / "planes-de-colocaciones.csv", planes_de_colocaciones(cuenta))
        # Her clients' payment receipts, one per client and statement, as receipt-service generated them.
        escribe_csv(carpeta / "recibos.csv", recibos_pdf(cuenta, carpeta / "recibos"))
    try:
        escribe_json(carpeta / "riesgo.json", svc("risk-service", "GET", f"/api/v1/risk/accounts/{cuenta}"))
    except motor.HttpError:
        pass
    try:
        escribe_json(carpeta / "cobranza.json", bo(motor.login_admin(), "GET", f"/collections/accounts/{cuenta}/case"))
    except motor.HttpError:
        pass
    if st.get("tipo") == "distribuidora":
        try:
            escribe_json(carpeta / "comisiones.json", svc("commission-service", "GET", f"/api/v1/commissions/accounts/{cuenta}"))
        except motor.HttpError:
            pass
    (carpeta / "paso-a-paso.md").write_text(paso_a_paso(clave, st, foto, libro, cargos, polizas, cortes, estados, bitacora), encoding="utf-8")
    if st.get("tipo") == "distribuidora" and any(int(f.get("days_delinquent") or 0) > 0 for f in foto):
        (carpeta / "mora.md").write_text(mora_md(clave, st, cuenta, foto, cargos, bitacora), encoding="utf-8")
    pagos = [b for b in bitacora if b.get("escenario") == clave and b.get("accion") == "pago"]
    return {"clave": clave, "titulo": st.get("titulo"), "persona": st.get("persona"), "contrato": detalle.get("contractNumber"),
            "producto": detalle.get("productType"), "estatus": detalle.get("status"), "capital": detalle.get("principalBalance"),
            "deuda": detalle.get("totalDebt"), "dpd": detalle.get("daysDelinquent"), "pagos": len(pagos),
            "pagado": round(sum(float(p.get("monto") or 0) for p in pagos), 2), "estados": len([e for e in estados if e.get("archivo")]),
            "polizas": len(polizas), "cargos": len(cargos), "carpeta": carpeta.name}


# ── every statement of the environment, by client ─────────────────────────────────────────────

PRODUCTOS = {
    "PERSONAL_LOAN": "prestamo-personal", "PAYROLL_LOAN": "nomina", "MICRO_LOAN": "microcredito",
    "SME_LOAN": "pyme", "GROUP_LOAN": "grupal", "CREDIT_CARD": "tarjeta", "REVOLVING_LINE": "linea-revolvente",
    "BUSINESS_REVOLVING_LINE": "linea-empresarial", "DISTRIBUTOR_LINE": "distribuidora",
}


def nombres(ids: list[str]) -> dict[str, str]:
    """Holder names from party: by party id first, then by prospect id (what the portfolio stores as obligor)."""
    encontrados: dict[str, str] = {}
    for por in ("partyId", "prospectId"):
        faltan = [i for i in ids if i not in encontrados]
        for k in range(0, len(faltan), 100):
            lote = faltan[k:k + 100]
            filas = svc("party-service", "GET", "/api/v1/parties/batch?" + "&".join(f"ids={x}" for x in lote) + f"&by={por}") or []
            for f in filas:
                clave = str(f.get("prospectId") if por == "prospectId" else f.get("partyId"))
                nombre = f.get("taxName") or " ".join(x for x in (f.get("firstName"), f.get("lastName1"), f.get("lastName2")) if x)
                if clave and nombre:
                    encontrados[clave] = nombre.strip()
    return encontrados


def exportar_estados(destino: pathlib.Path) -> list[dict]:
    """
    Downloads every generated statement of the environment into destino/<product>/<holder> (<contract>)/ with an
    index (indice.md, indice.csv): who, which contract, which cutoff, balance, amount due, minimum. It reads the
    statements back from statement-service, never from memory.
    """
    filas = csv_sql("""SELECT statement_id, credit_account_id, obligor_party_id, product_type, holder_kind, cycle_number,
                              period_start, cutoff_date, payment_due_date, balance_at_cutoff, amount_due, minimum_payment,
                              contract_number
                         FROM statement.statements WHERE status = 'GENERATED'
                        ORDER BY product_type, contract_number, cycle_number""")
    quien = nombres(sorted({f["obligor_party_id"] for f in filas if f["obligor_party_id"]}))
    indice = []
    for f in filas:
        titular = quien.get(f["obligor_party_id"], "Sin nombre en party")
        carpeta = destino / PRODUCTOS.get(f["product_type"], f["product_type"].lower()) / f"{slug(titular)}-{f['contract_number'] or f['credit_account_id'][:8]}"
        carpeta.mkdir(parents=True, exist_ok=True)
        archivo = carpeta / f"{f['cutoff_date']}-corte-{int(f['cycle_number']):02d}.pdf"
        if not archivo.exists():
            archivo.write_bytes(svc("statement-service", "GET", f"/api/v1/statements/{f['statement_id']}/pdf"))
        indice.append({"producto": f["product_type"], "titular": titular, "contrato": f["contract_number"],
                       "ciclo": f["cycle_number"], "periodo": f"{f['period_start']} a {f['cutoff_date']}",
                       "fecha_limite": f["payment_due_date"], "saldo_al_corte": f["balance_at_cutoff"],
                       "total_a_pagar": f["amount_due"], "pago_minimo": f["minimum_payment"],
                       "pdf": str(archivo.relative_to(destino))})
    escribe_csv(destino / "indice.csv", indice)
    por_producto = defaultdict(list)
    for r in indice:
        por_producto[r["producto"]].append(r)
    md = [f"# Estados de cuenta · {len(indice)} documentos de {len({r['contrato'] for r in indice})} contratos", ""]
    for producto, rs in sorted(por_producto.items()):
        md += [f"## {producto} · {len(rs)} estados de cuenta", "",
               "| Titular | Contrato | Ciclo | Periodo | Fecha límite | Saldo al corte | Total a pagar | Mínimo | PDF |",
               "|---|---|---:|---|---|---:|---:|---:|---|"]
        md += [f"| {r['titular']} | {r['contrato']} | {r['ciclo']} | {r['periodo']} | {r['fecha_limite']} | {dinero(r['saldo_al_corte'])} | "
               f"{dinero(r['total_a_pagar'])} | {dinero(r['pago_minimo'])} | [pdf]({r['pdf']}) |" for r in rs]
        md.append("")
    (destino / "indice.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    return indice


# ── global ───────────────────────────────────────────────────────────────────────────────────────

def _renglones(filas: list[dict]) -> list[dict]:
    # A trial balance: opening balance, the period's debits and credits, closing balance. "balance" alone is the
    # period's net movement, and read as a balance it showed the portfolio negative in a month of collections.
    return [{"accountCode": r.get("accountCode"), "accountName": r.get("accountName"), "accountType": r.get("accountType"),
             "saldoInicial": r.get("openingBalance"), "cargos": r.get("totalDebit"), "abonos": r.get("totalCredit"),
             "saldoFinal": r.get("closingBalance"), "movimientoNeto": r.get("balance")} for r in filas]


def esquemas(carpeta: pathlib.Path, productos: list[str]) -> None:
    """Each product's accounting scheme, as the ledger booked it in the run: every concept and the accounts it moves."""
    for producto in productos:
        e = svc("accounting-service", "GET", f"/api/v1/accounting/products/{producto}/scheme") or {}
        md = [f"# Esquema contable · {producto}", "",
              "Lo que el mayor asentó para el producto en la corrida: cada concepto con las cuentas que mueve.", "",
              "| Concepto | Hecho | Cargo | Abono | Asientos | Importe |", "|---|---|---|---|---:|---:|"]
        for c in e.get("concepts") or []:
            for par in c.get("pairs") or []:
                md.append(f"| {c.get('concept') or ''} | `{c.get('triggerEvent')}` | {par.get('debitAccount')} "
                          f"{par.get('debitName')} | {par.get('creditAccount')} {par.get('creditName')} | "
                          f"{par.get('entries')} | {dinero(par.get('amount'))} |")
        (carpeta / f"esquema-{producto}.md").write_text("\n".join(md) + "\n", encoding="utf-8")


def balanzas(carpeta: pathlib.Path) -> list[dict]:
    periodos = svc("accounting-service", "GET", "/api/v1/accounting/periods") or []
    escribe_json(carpeta / "periodos.json", periodos)
    productos = veredicto.productos_de(periodos)
    for p in periodos:
        periodo = p.get("period")
        filas = svc("accounting-service", "GET", f"/api/v1/accounting/trial-balance?period={periodo}") or []
        escribe_csv(carpeta / f"balanza-{periodo}.csv", _renglones(filas))
        # The same balance product by product (the institution's own facts apart): what the back office's selector shows.
        for producto in productos:
            de_producto = svc("accounting-service", "GET",
                              f"/api/v1/accounting/trial-balance?period={periodo}&productTypes={producto}") or []
            if de_producto:
                escribe_csv(carpeta / f"balanza-{periodo}-{producto}.csv", _renglones(de_producto))
        try:
            escribe_json(carpeta / f"resumen-contable-{periodo}.json", svc("accounting-service", "GET", f"/api/v1/accounting/summary?period={periodo}"))
        except motor.HttpError:
            pass
    esquemas(carpeta, productos)
    return periodos


def conciliacion(carpeta: pathlib.Path) -> str:
    r = subprocess.run(["docker", "exec", "-i", "fintech-services-postgres-1", "psql", "-U", "fintech", "-d", "fintech"],
                       input=(RAIZ / "tools/ciclo-credito/concilia.sql").read_text(), capture_output=True, text=True)
    texto = "## Conciliación de la cadena (tools/ciclo-credito/concilia.sql)\n\n```\n" + r.stdout + r.stderr + "\n```\n"
    v = subprocess.run([sys.executable, str(RAIZ / "scripts/verifica-contabilidad.py")], capture_output=True, text=True, cwd=RAIZ)
    texto += "\n## Contabilidad contra la fuente de cada hecho (scripts/verifica-contabilidad.py)\n\n```\n" + v.stdout + v.stderr + "\n```\n"
    (carpeta / "conciliacion.md").write_text(texto, encoding="utf-8")
    escribe_json(carpeta / "autoverificacion.json", veredicto.autoverificacion())
    return texto


def estados_por_cliente(evidencia: pathlib.Path) -> int:
    """
    The test clients' statements in one place: evidencia/estados-de-cuenta-por-cliente/<holder>-<contract>/ with an
    index. Built from the delivered evidence (resumen.json and each scenario's estados-de-cuenta.csv and PDFs),
    so it works after the environment is gone.
    """
    destino = evidencia / "estados-de-cuenta-por-cliente"
    destino.mkdir(exist_ok=True)
    resumen = json.loads((evidencia / "resumen.json").read_text())
    filas = []
    for r in resumen:
        carpeta = evidencia / "escenarios" / r["carpeta"]
        estados = carpeta / "estados-de-cuenta.csv"
        if not estados.exists():
            continue
        cliente = destino / f"{slug(r.get('persona') or r['clave'])}-{r.get('contrato') or r['clave']}"
        cliente.mkdir(exist_ok=True)
        for e in csv.DictReader(open(estados, encoding="utf-8")):
            if not e.get("archivo"):
                continue
            shutil.copy2(carpeta / "estados-de-cuenta" / e["archivo"], cliente / e["archivo"])
            filas.append({"escenario": r["clave"], "titular": r.get("persona"), "contrato": r.get("contrato"),
                          "producto": r.get("producto"), "ciclo": e.get("cycleNumber"),
                          "periodo": f"{e.get('periodStart')} a {e.get('cutoffDate')}", "fecha_limite": e.get("paymentDueDate"),
                          "saldo_al_corte": e.get("balanceAtCutoff"), "total_a_pagar": e.get("amountDue"),
                          "pago_minimo": e.get("minimumPayment"), "pdf": f"{cliente.name}/{e['archivo']}",
                          "statement_id": e.get("statementId"), "sha256": e.get("sha256")})
    escribe_csv(destino / "indice.csv", filas)
    md = [f"# Estados de cuenta de los clientes de prueba · {len(filas)} documentos", "",
          "Todos los genera statement-service (plantilla Jasper por producto) al sellarse cada corte; aquí sólo se "
          "descargan por su API. `indice.csv` trae el id de cada estado de cuenta y la huella SHA-256 del documento, "
          "comparable con `statement.statement_documents.sha256`.", "",
          "| Escenario | Titular | Contrato | Producto | Ciclo | Periodo | Fecha límite | Saldo al corte | Total a pagar | Mínimo | PDF |",
          "|---|---|---|---|---:|---|---|---:|---:|---:|---|"]
    md += [f"| {f['escenario']} | {f['titular']} | {f['contrato']} | {f['producto']} | {f['ciclo']} | {f['periodo']} | {f['fecha_limite']} | "
           f"{dinero(f['saldo_al_corte'])} | {dinero(f['total_a_pagar'])} | {dinero(f['pago_minimo'])} | [pdf]({f['pdf']}) |" for f in filas]
    (destino / "indice.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    return len(filas)


def entregar(evidencia: pathlib.Path, escenarios: dict):
    estado = json.loads((evidencia / "estado.json").read_text())
    bitacora = [json.loads(l) for l in (evidencia / "bitacora.jsonl").read_text().splitlines() if l.strip()]
    resumen = []
    for clave, e in escenarios.items():
        st = estado["escenarios"].get(clave) or {}
        st["titulo"] = e["titulo"]
        carpeta = evidencia / "escenarios" / f"{clave}-{slug(e['titulo'])}"
        if st.get("cuenta"):
            print(f"  {clave} {e['titulo']}")
            resumen.append(entrega_cuenta(clave, st, carpeta, bitacora))
        else:
            carpeta.mkdir(parents=True, exist_ok=True)
            resumen.append({"clave": clave, "titulo": e["titulo"], "persona": st.get("persona"), "estatus": st.get("desenlace", "sin cuenta"),
                            "carpeta": carpeta.name})
        (carpeta / "linea-de-tiempo.md").write_text(
            f"# {clave} · {e['titulo']}\n\n| Fecha | Día | Acción | Detalle | Datos |\n|---|---:|---|---|---|\n" +
            "\n".join(f"| {b['fecha']} | {b['dia']} | {b['accion']} | {b.get('detalle', '')} | "
                      + ", ".join(f"{k}={v}" for k, v in b.items() if k not in ('fecha', 'dia', 'escenario', 'accion', 'detalle')) + " |"
                      for b in bitacora if b.get("escenario") == clave) + "\n", encoding="utf-8")
    print("  balanzas por período")
    periodos = balanzas(evidencia)
    print("  conciliación")
    concilia = conciliacion(evidencia)
    print("  huella de la corrida")
    huella.guarda(evidencia)
    noches = [b for b in bitacora if b.get("accion") == "noche"]
    suite = json.loads((evidencia / "suite.json").read_text()) if (evidencia / "suite.json").exists() else {}
    md = [f"# Ciclo de crédito de punta a punta · {estado['inicio']} → {estado['fin']}", "",
          *([f"Suite «{suite['suite']}»: {suite.get('titulo', '')}.", ""] if suite else []),
          f"Semilla {estado['semilla']}. {len(noches)} noches cerradas (foto → devengo → cierre de cartera → corte → estados de cuenta → riesgo → cobranza), "
          f"{len(periodos)} períodos contables. Sólo se sembró configuración; todo lo demás ocurrió por las operaciones reales (app, pagos, wallet, backoffice).", "",
          "## Escenarios", "",
          "| Clave | Escenario | Titular | Contrato | Producto | Estatus | Capital | Deuda | DPD | Pagos | Pagado | Estados de cuenta | Pólizas | Carpeta |",
          "|---|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|"]
    for r in resumen:
        md.append(f"| {r['clave']} | {r['titulo']} | {r.get('persona', '')} | {r.get('contrato', '')} | {r.get('producto', '')} | {r.get('estatus', '')} | "
                  f"{dinero(r.get('capital'))} | {dinero(r.get('deuda'))} | {r.get('dpd', '')} | {r.get('pagos', '')} | {dinero(r.get('pagado'))} | "
                  f"{r.get('estados', '')} | {r.get('polizas', '')} | [{r['carpeta']}](escenarios/{r['carpeta']}/) |")
    md += ["", "Cada carpeta trae: `paso-a-paso.md` (la vista en registros, día por día), `estados-de-cuenta/*.pdf`, `polizas.csv`, "
           "`libro-de-cartera.csv`, `foto-diaria.csv`, `cargos.csv`, `cortes.csv`, `plan-de-pagos.csv`, `cuenta.json`, `riesgo.json`, "
           "`cobranza.json` y `linea-de-tiempo.md` (lo que hizo la corrida).", "",
           "## Períodos contables", "", "| Período | Estatus | Pólizas |", "|---|---|---:|"]
    md += [f"| {p.get('period')} | {p.get('status')} | {p.get('vouchers', p.get('entries', ''))} |" for p in periodos]
    md += ["", "Balanzas en `balanza-YYYYMM.csv`, por producto en `balanza-YYYYMM-PRODUCTO.csv` (INSTITUCION: lo que no "
           "es de ningún producto); esquema contable de cada producto en `esquema-PRODUCTO.md`; resumen contable en "
           "`resumen-contable-YYYYMM.json`.", "",
           "## Noches", "", "| Fecha | Resultado | s |", "|---|---|---:|"]
    md += [f"| {n['fecha']} | {n['detalle']} | {n.get('segundos', '')} |" for n in noches]
    md += ["", "El veredicto de la corrida —cada escenario contra lo que debía ser, la conciliación en cero y la contabilidad "
           "contra la fuente— está en [veredicto.md](veredicto.md).", "", concilia]
    (evidencia / "RESUMEN.md").write_text("\n".join(md) + "\n", encoding="utf-8")

    escribe_json(evidencia / "resumen.json", resumen)
    n = estados_por_cliente(evidencia)
    print(f"  {n} estados de cuenta reunidos por cliente en estados-de-cuenta-por-cliente/")
    print(f"✓ entrega en {evidencia}")
    return resumen


if __name__ == "__main__":
    import corre
    import escenarios as esc
    entregar(pathlib.Path(sys.argv[1]).expanduser(), esc.SUITES[corre.suite_de(pathlib.Path(sys.argv[1]).expanduser())]["escenarios"])
