"""The verdict of a run: did every scenario end where its plan says, and does the whole chain reconcile to zero?

A run that only "finishes" proves nothing: a scenario can stall half way, a payment can be rejected, a night can
quarantine an account, and the run still ends with a summary. The verdict reads the outcome back from the
services and fails the run when anything is off:

  · per scenario, what `espera` declares in `escenarios.py` (status, delinquency, ledger events, vouchers, rate,
    stage, installments, collections case, application status);
  · the reconciliation (`concilia.sql`): every row must show zero in `fuera`;
  · accounting against the source of each fact (`scripts/verifica-contabilidad.py`): exit code 0;
  · the trial balance by product: per period, the products add up to the whole account by account and each one
    balances; every closed month's checks by product reconciled;
  · the system's self-checks: every check that commissions, treasury, the portfolio and the statements must hold
    of themselves holds (what only informs is printed);
  · the run's own log: no errors, no rejected payment the plan did not expect, nothing left unsettled.

Nothing here writes: it reads the services' API and, for what has no API, the database read-only.
"""
from __future__ import annotations

import calendar
import datetime
import json
import pathlib
import subprocess
import sys

import motor

RAIZ = motor.RAIZ
PSQL_FILAS = ["docker", "exec", "-i", "fintech-services-postgres-1", "psql", "-U", "fintech", "-d", "fintech",
              "-At", "-F", "|", "-v", "ON_ERROR_STOP=1"]


def _filas(sql: str) -> list[list[str]]:
    r = subprocess.run(PSQL_FILAS, input=sql, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"la consulta falló: {r.stderr.strip()[:400]}")
    return [l.split("|") for l in r.stdout.splitlines() if l.strip()]


def _uno(sql: str) -> str | None:
    f = _filas(sql)
    return f[0][0] if f and f[0] else None


class Juicio:
    def __init__(self):
        self.filas: list[dict] = []

    def compara(self, clave, comprobacion, esperado, real, ok=None):
        ok = (esperado == real) if ok is None else ok
        self.filas.append({"escenario": clave, "comprobacion": comprobacion, "esperado": esperado, "real": real, "ok": bool(ok)})
        return ok

    @property
    def fallas(self):
        return [f for f in self.filas if not f["ok"]]


# ── per scenario ───────────────────────────────────────────────────────────────────────────────

def _cuenta(cuenta_id):
    return motor.svc("credit-portfolio-service", "GET", f"/api/v1/portfolio/accounts/{cuenta_id}") or {}


def _al_cierre(cuenta_id, cuenta: dict, fin: str | None) -> tuple[int, int]:
    """Days delinquent and overdue installments as the last night of the run measured them (the day snapshot of FIN).

    The verdict runs once the clock is back on the real date: an installment due the day after FIN shows as overdue
    in the live account, though the run never reached that day (2 Nov E2E: six scenarios «not current» on 3 Nov)."""
    if fin:
        f = _filas(f"""SELECT days_delinquent, overdue_installments FROM credit_portfolio.account_day_snapshots
                        WHERE credit_account_id = '{cuenta_id}' AND business_date <= '{fin}'
                        ORDER BY business_date DESC LIMIT 1""")
        if f:
            return int(f[0][0] or 0), int(f[0][1] or 0)
    return int(cuenta.get("daysDelinquent") or 0), int(cuenta.get("overdueInstallments") or 0)


def _eventos(cuenta_id) -> set[str]:
    return {f[0] for f in _filas(f"SELECT DISTINCT event_type FROM credit_portfolio.balance_events WHERE credit_account_id = '{cuenta_id}'")}


def _polizas(cuenta_id) -> set[str]:
    return {f[0] for f in _filas(f"SELECT DISTINCT trigger_event FROM accounting.vouchers WHERE credit_account_id = '{cuenta_id}' AND status = 'POSTED'")}


def _cuotas(cuenta_id) -> dict[str, int]:
    filas = _filas(f"""
        SELECT i.status, count(*) FROM credit_portfolio.installments i
         WHERE i.schedule_id = '{cuenta_id}'
            OR i.schedule_id IN (SELECT disposition_id FROM credit_portfolio.dispositions WHERE credit_account_id = '{cuenta_id}')
         GROUP BY 1""")
    return {f[0]: int(f[1]) for f in filas}


def _riesgo(cuenta_id) -> dict:
    f = _filas(f"SELECT ifrs9_stage, days_delinquent, round(provision_amount, 2), status, is_forborne FROM risk.risk_profiles WHERE credit_account_id = '{cuenta_id}'")
    if not f:
        return {}
    etapa, dpd, reserva, estatus, forborne = f[0]
    return {"etapa": etapa, "dpd": int(dpd), "reserva": float(reserva), "estatus": estatus, "forborne": forborne == "t"}


def _mas_meses(d: datetime.date, meses: int) -> datetime.date:
    """The same day `meses` months later (the month's last day when that day does not exist)."""
    m = d.month - 1 + meses
    anio, mes = d.year + m // 12, m % 12 + 1
    return datetime.date(anio, mes, min(d.day, calendar.monthrange(anio, mes)[1]))


def _caso(cuenta_id) -> dict:
    f = _filas(f"""SELECT c.status,
                          (SELECT count(*) FROM collections.payment_promises p WHERE p.case_id = c.case_id),
                          (SELECT count(*) FROM collections.collection_agreements a WHERE a.case_id = c.case_id AND a.status = 'EXECUTED'),
                          (SELECT count(*) FROM collections.write_off_records w WHERE w.case_id = c.case_id),
                          (SELECT count(*) FROM collections.contact_attempts t WHERE t.case_id = c.case_id),
                          (SELECT count(*) FROM collections.contact_attempts t WHERE t.case_id = c.case_id AND t.origin = 'AUTOMATIC')
                     FROM collections.collection_cases c WHERE c.credit_account_id = '{cuenta_id}'
                    ORDER BY c.opened_at DESC LIMIT 1""")
    if not f:
        return {}
    estatus, promesas, convenios, quebrantos, contactos, automaticos = f[0]
    return {"estatus": estatus, "promesas": int(promesas), "convenios": int(convenios), "quebrantos": int(quebrantos),
            "contactos": int(contactos), "automaticos": int(automaticos)}


def juzga_escenario(j: Juicio, clave: str, e: dict, st: dict, bitacora: list[dict], config: dict | None = None,
                    fin: str | None = None):
    espera = e.get("espera") or {}
    filas = [b for b in bitacora if b.get("escenario") == clave]

    # The run's own log: errors and rejected payments are findings unless the plan expects them.
    errores = [b for b in filas if b.get("accion") == "error"]
    j.compara(clave, "errores en la bitácora", 0, len(errores), ok=not errores)
    rechazados = [b for b in filas if b.get("accion") == "pago rechazado"]
    tolera = espera.get("pagos_rechazados", 0)
    j.compara(clave, "pagos rechazados", f"≤ {tolera}", len(rechazados), ok=len(rechazados) <= tolera)
    fallidas = [b for b in filas if "rechazad" in str(b.get("detalle", ""))
                and b.get("accion") not in ("pago rechazado", "solicitud", "colocación rechazada")]
    j.compara(clave, "operaciones de backoffice rechazadas", 0, len(fallidas), ok=not fallidas)
    # A placement the platform refuses at the door (the line blocked by arrears, an amount or a term outside the
    # product) is a finding unless the plan expects exactly that many.
    rechazadas = [b for b in filas if b.get("accion") == "colocación rechazada"]
    j.compara(clave, "colocaciones rechazadas en la puerta (línea bloqueada, fuera del producto)",
              espera.get("colocaciones_rechazadas", 0), len(rechazadas))
    if rechazadas and espera.get("colocaciones_rechazadas", 0):
        bloqueo = [b for b in rechazadas if "bloqueada" in str(b.get("detalle", "")) or "cerrada" in str(b.get("detalle", ""))]
        j.compara(clave, "las colocaciones rechazadas dicen por qué: la línea estaba bloqueada por atraso o cerrada por la quita",
                  len(rechazadas), len(bloqueo))

    if "solicitud" in espera:
        real = _uno(f"SELECT status FROM origination.credit_applications WHERE application_id = '{st.get('application')}'") if st.get("application") else None
        j.compara(clave, "estatus de la solicitud", espera["solicitud"], real)
    if espera.get("sin_cuenta"):
        j.compara(clave, "sin cuenta de crédito", None, st.get("cuenta"))
        return
    if not st.get("cuenta"):
        j.compara(clave, "cuenta de crédito", "existe", None, ok=False)
        return

    cuenta = _cuenta(st["cuenta"])
    # Every account accrues its interest as its product declares: daily on the balance, or per period as each plan
    # sets it (a distributor's placements).
    modo = (config or {}).get(e.get("producto"))
    if modo:
        real = _uno(f"SELECT interest_accrual FROM charges.accrual_schedules WHERE credit_account_id = '{st['cuenta']}'")
        j.compara(clave, "modo de interés según el producto", modo, real)
    if "estatus" in espera:
        j.compara(clave, "estatus de la cuenta", espera["estatus"], cuenta.get("status"))
    dpd, vencidas = _al_cierre(st["cuenta"], cuenta, fin)
    if espera.get("al_corriente"):
        j.compara(clave, "al corriente al cierre de FIN (días de atraso, cuotas vencidas)", "0, 0", f"{dpd}, {vencidas}",
                  ok=dpd == 0 and vencidas == 0)
    if "mora_minima" in espera:
        j.compara(clave, "días de atraso", f"≥ {espera['mora_minima']}", dpd, ok=dpd >= espera["mora_minima"])
    if "deuda" in espera:
        deuda = round(float(cuenta.get("totalDebt") or 0), 2)
        j.compara(clave, "deuda total", espera["deuda"], deuda, ok=abs(deuda - espera["deuda"]) < 0.01)
    if "intentos_de_desembolso" in espera:
        # D63: each attempt of the payment is its own order; the disposition keeps the one in flight or the last.
        real = _uno(f"SELECT max(attempt) FROM credit_portfolio.dispositions WHERE credit_account_id = '{st['cuenta']}'")
        j.compara(clave, "intentos de su desembolso", espera["intentos_de_desembolso"], int(real or 0))
    if "tasa" in espera:
        tasa = round(float(cuenta.get("nominalRate") or 0), 6)
        j.compara(clave, "tasa nominal", espera["tasa"], tasa, ok=abs(tasa - espera["tasa"]) < 1e-6)
    if "eventos" in espera:
        hay = _eventos(st["cuenta"])
        for ev in espera["eventos"]:
            j.compara(clave, f"libro de cartera tiene {ev}", True, ev in hay)
    if "sin_eventos" in espera:
        hay = _eventos(st["cuenta"])
        for ev in espera["sin_eventos"]:
            j.compara(clave, f"libro de cartera sin {ev}", True, ev not in hay)
    if "corridas_a_habil" in espera:
        corridas = int(_filas(f"""
            SELECT count(*) FROM credit_portfolio.installments
             WHERE schedule_id = '{st["cuenta"]}' AND status <> 'REPLACED'
               AND original_due_date IS NOT NULL AND original_due_date <> due_date""")[0][0])
        j.compara(clave, "cuotas corridas al día hábil siguiente", f"≥ {espera['corridas_a_habil']}", corridas,
                  ok=corridas >= espera["corridas_a_habil"])
    if espera.get("ningun_vencimiento_inhabil"):
        inhabiles = int(_filas(f"""
            SELECT count(*) FROM credit_portfolio.installments i
             WHERE i.schedule_id = '{st["cuenta"]}' AND i.status <> 'REPLACED'
               AND (extract(isodow FROM i.due_date) IN (6, 7)
                    OR EXISTS (SELECT 1 FROM closing.calendar_days c
                                WHERE c.calendar_code = 'MX' AND c.day = i.due_date AND NOT c.is_business))""")[0][0])
        j.compara(clave, "cuotas que vencen en día inhábil", 0, inhabiles)
    if "polizas" in espera:
        hay = _polizas(st["cuenta"])
        for p in espera["polizas"]:
            j.compara(clave, f"póliza {p}", True, p in hay)
    if "cuotas" in espera:
        hay = _cuotas(st["cuenta"])
        for estado, n in espera["cuotas"].items():
            real = hay.get(estado, 0)
            if isinstance(n, str) and n.startswith(">"):
                j.compara(clave, f"cuotas {estado}", n, real, ok=real > int(n[1:]))
            else:
                j.compara(clave, f"cuotas {estado}", n, real)
    if "saltadas" in espera:
        n = int(_uno(f"SELECT count(*) FROM credit_portfolio.installments WHERE schedule_id = '{st['cuenta']}' "
                     "AND skipped_mode IS NOT NULL") or 0)
        j.compara(clave, "cuotas saltadas", espera["saltadas"], n)
    if "etapa" in espera or "forborne" in espera or espera.get("estatus") in ("SETTLED", "WRITTEN_OFF", "CLOSED"):
        r = _riesgo(st["cuenta"])
        if "etapa" in espera:
            j.compara(clave, "etapa IFRS 9", espera["etapa"], r.get("etapa"))
        if "forborne" in espera:
            # Risk marks the account forborne when the restructure is executed and clears the mark once the cure window
            # (six months) has run: a scenario that expects the mark checks the restructure happened and the mark's
            # state at FIN, in force or cured (D12's restructure of 10 March was cured by 10 September, dist5).
            ejecutada = _uno(f"""SELECT max(executed_at)::date FROM collections.collection_agreements
                                  WHERE credit_account_id = '{st['cuenta']}' AND type = 'RESTRUCTURE' AND status = 'EXECUTED'""")
            if espera["forborne"]:
                j.compara(clave, "reestructura ejecutada (convenio)", True, bool(ejecutada), ok=bool(ejecutada))
                corte_de_cura = _mas_meses(datetime.date.fromisoformat(str(ejecutada)), 6) if ejecutada else None
                vigente = bool(corte_de_cura) and (fin is None or datetime.date.fromisoformat(str(fin)) < corte_de_cura)
                j.compara(clave, "marca de reestructura (forborne) " + ("vigente" if vigente else f"curada desde el {corte_de_cura}"),
                          vigente, r.get("forborne"))
            else:
                j.compara(clave, "marca de reestructura (forborne)", False, r.get("forborne"))
        if espera.get("estatus") in ("SETTLED", "WRITTEN_OFF", "CLOSED"):
            j.compara(clave, "reserva al cerrar", 0.0, r.get("reserva", 0.0), ok=abs(r.get("reserva", 0.0)) < 0.01)
    if "caso" in espera:
        c = _caso(st["cuenta"])
        for campo, valor in espera["caso"].items():
            real = c.get(campo)
            if isinstance(valor, str) and valor.startswith("≥"):
                j.compara(clave, f"cobranza: {campo}", valor, real, ok=(real or 0) >= int(valor[1:]))
            else:
                j.compara(clave, f"cobranza: {campo}", valor, real)
    for accion in espera.get("acciones", []):
        hechas = [b for b in filas if b.get("accion") == accion and "rechazad" not in str(b.get("detalle", ""))]
        j.compara(clave, f"la corrida hizo «{accion}»", "sí", "sí" if hechas else "no", ok=bool(hechas))
    if espera.get("estados_de_cuenta"):
        n = int(_uno(f"SELECT count(*) FROM statement.statements WHERE credit_account_id = '{st['cuenta']}' AND status = 'GENERATED'") or 0)
        j.compara(clave, "estados de cuenta generados", f"≥ {espera['estados_de_cuenta']}", n, ok=n >= espera["estados_de_cuenta"])
    if espera.get("quincenal"):
        juzga_quincenal(j, clave, st["cuenta"], cuenta, fin, espera.get("escalones"))


# ── the distributor line: its fortnights ────────────────────────────────────────────────────────

# The line's two cutoff days and the day each one's statement is paid by (configura checks the catalog says the same).
DIAS_DE_CORTE = (6, 21)
DIAS_DE_PAGO = {6: 18, 21: 3}
# A placement at 95 % paid by fortnights costs (1 + 0.95/24)^24 − 1 = 153.88 % (Circular 21/2009); with its first
# fortnight counted by the days the money was held, every placement lands between 153.8 % and 154.5 %.
CAT_DE_LA_LINEA = 153.88
CAT_DE_COLOCACION = (1.538, 1.545)


def _corte_siguiente(d: datetime.date) -> datetime.date:
    """The first cutoff (the 6th or the 21st) strictly after d."""
    if d.day < DIAS_DE_CORTE[0]:
        return d.replace(day=DIAS_DE_CORTE[0])
    if d.day < DIAS_DE_CORTE[1]:
        return d.replace(day=DIAS_DE_CORTE[1])
    return (d.replace(day=28) + datetime.timedelta(days=4)).replace(day=DIAS_DE_CORTE[0])


def _cortes_entre(desde: datetime.date, hasta: datetime.date) -> int:
    """How many cutoffs fall after `desde` and up to `hasta`: the ones a line activated on `desde` has had."""
    n, d = 0, _corte_siguiente(desde)
    while d <= hasta:
        n, d = n + 1, _corte_siguiente(d)
    return n


def _pago_crudo(corte: datetime.date) -> datetime.date:
    """The day a cutoff's statement is paid by, before the business-day shift: the 18th or the 3rd after it."""
    dia = DIAS_DE_PAGO[corte.day]
    mismo = corte.replace(day=dia)
    return mismo if mismo > corte else (corte.replace(day=28) + datetime.timedelta(days=4)).replace(day=dia)


def juzga_quincenal(j: Juicio, clave: str, cuenta_id: str, cuenta: dict, fin: str | None, espera_escalones: dict | None = None):
    """
    The distributor line lives by its fortnights, and every piece has to say so: the line cuts on the 6th and on the
    21st from its activation to the end, every cutoff and none missing; each statement is paid by the 18th or the 3rd
    after its cutoff (the next business day when that one is not, at the latest the day of the next cutoff); the
    ordinary interest is charged only at the cutoffs, and its IVA is the plans' own, installment by installment; each
    placement pays on consecutive cutoffs from the first one after it was placed, so twelve payments are twelve
    fortnights; one statement per cutoff, issued the day after it, covering from the day after the previous cutoff, and
    its total adds up to what the clients pay; the line at 95 % and a CAT of 153.9 %, its placements from $1,000 to
    $10,000 each with the rate's CAT, and the distributor's commission the market's way: a flat 18 % of what her clients
    pay each statement, deducted from what she delivers by the day she delivers (14 / 11 / 7 % late, nothing past the
    third day), paid out of the placement's interest and never out of its capital, the same on her statements as in
    the commission ledger.
    """
    hasta = fin or datetime.date.today().isoformat()
    alta = _uno(f"SELECT activated_on FROM closing.account_close_profiles WHERE credit_account_id = '{cuenta_id}'")
    cortes = _filas(f"""
        SELECT cutoff_date, payment_due_date, status,
               lead(cutoff_date) OVER (ORDER BY cycle_number) AS siguiente
          FROM closing.cutoff_schedules WHERE credit_account_id = '{cuenta_id}' ORDER BY cycle_number""")
    hechos = [c for c in cortes if c[0] <= hasta]
    fuera = [c[0] for c in hechos if datetime.date.fromisoformat(c[0]).day not in DIAS_DE_CORTE]
    j.compara(clave, "cortes el 6 y el 21 de cada mes", 0, len(fuera), ok=not fuera)
    esperados = _cortes_entre(datetime.date.fromisoformat(alta), datetime.date.fromisoformat(hasta)) if alta else -1
    j.compara(clave, "un corte por quincena desde el alta (ninguno de más ni de menos)", esperados, len(hechos))
    # A line that ended (closed after a quita, settled) is not cut any more: closing skips its cutoffs from the day it
    # ended; a cutoff skipped while the line was alive, or left in any other state, is a finding.
    terminal = _uno(f"SELECT terminal_since::date FROM closing.account_close_profiles WHERE credit_account_id = '{cuenta_id}'")
    sin_sellar = [c[0] for c in hechos if c[2] != "SEALED" and not (c[2] == "SKIPPED" and terminal and c[0] >= str(terminal))]
    j.compara(clave, "cortes sellados (saltados sólo desde que la línea terminó)", 0, len(sin_sellar), ok=not sin_sellar)
    sellados = [c for c in hechos if c[2] == "SEALED"]
    inhabiles = {r[0] for r in _filas("SELECT day FROM closing.calendar_days WHERE calendar_code = 'MX' AND NOT is_business")}

    def habil(d: datetime.date) -> bool:
        return d.weekday() < 5 and d.isoformat() not in inhabiles

    mal_limite = []
    for corte, limite, _, siguiente in hechos:
        c, l = datetime.date.fromisoformat(corte), datetime.date.fromisoformat(limite)
        crudo = _pago_crudo(c) if c.day in DIAS_DE_CORTE else c
        # Its day, or the first business day after it; never after the next cutoff.
        primero = crudo
        while not habil(primero):
            primero += datetime.timedelta(days=1)
        if l != primero or (siguiente and limite > siguiente):
            mal_limite.append(f"{corte}→{limite} (tocaba {primero})")
    j.compara(clave, "fecha límite el 18 o el 3 (o el hábil siguiente), a más tardar el corte siguiente", 0,
              len(mal_limite), ok=not mal_limite)
    for m in mal_limite[:3]:
        j.compara(clave, "  fecha límite fuera de regla", "—", m, ok=False)

    interes = _filas(f"""
        SELECT count(*) FILTER (WHERE extract(day FROM accrual_date) NOT IN ({DIAS_DE_CORTE[0]}, {DIAS_DE_CORTE[1]})), count(*)
          FROM charges.charge_records
         WHERE credit_account_id = '{cuenta_id}' AND charge_type = 'ORDINARY_INTEREST' AND status <> 'REVERSED'""")[0]
    j.compara(clave, "interés ordinario sólo en los cortes", f"0 de {interes[1]}", f"{interes[0]} de {interes[1]}",
              ok=int(interes[0]) == 0 and int(interes[1]) > 0)
    # The IVA of each cutoff is the plans' own, each installment's rounded as its client pays it: on a cutoff without
    # moratorium, the IVA charged equals the tax of the installments falling due that day. A placement paid off before
    # the cutoff owes nothing for it: its installments still on the plan were settled without their interest.
    iva = _filas(f"""
        WITH cortes AS (
            SELECT accrual_date AS d, sum(amount) FILTER (WHERE charge_type = 'IVA') AS iva,
                   count(*) FILTER (WHERE charge_type = 'MORATORIUM_INTEREST') AS moratorios
              FROM charges.charge_records
             WHERE credit_account_id = '{cuenta_id}' AND status <> 'REVERSED'
               AND extract(day FROM accrual_date) IN ({DIAS_DE_CORTE[0]}, {DIAS_DE_CORTE[1]})
             GROUP BY accrual_date),
        planes AS (
            SELECT coalesce(i.original_due_date, i.due_date) AS d, sum(i.tax_amount) AS iva
              FROM credit_portfolio.installments i
              JOIN credit_portfolio.dispositions x ON x.disposition_id = i.schedule_id
             WHERE x.credit_account_id = '{cuenta_id}' AND i.status NOT IN ('CANCELLED', 'REPLACED') AND i.interest_amount > 0
               AND NOT EXISTS (SELECT 1 FROM credit_portfolio.installments y
                                WHERE y.schedule_id = i.schedule_id AND y.status NOT IN ('CANCELLED', 'REPLACED')
                               HAVING bool_and(y.status = 'PAID')
                                  AND max(y.paid_on) < coalesce(i.original_due_date, i.due_date))
             GROUP BY 1)
        SELECT count(*) FILTER (WHERE abs(coalesce(c.iva, 0) - coalesce(p.iva, 0)) > 0.005), count(*)
          FROM cortes c LEFT JOIN planes p ON p.d = c.d
         WHERE c.moratorios = 0 AND c.d <= '{hasta}'""")[0]
    j.compara(clave, "IVA de cada corte = el de las cuotas de los clientes, cuota por cuota",
              f"0 de {iva[1]} cortes", f"{iva[0]} de {iva[1]}", ok=int(iva[0]) == 0)

    # Each placement's plan: its installments' own dates (before a statement bills them) are consecutive cutoffs, the
    # first one the first after the day it was placed.
    planes = _filas(f"""
        SELECT d.disposition_id, (d.created_at AT TIME ZONE 'America/Mexico_City')::date,
               array_agg(coalesce(i.original_due_date, i.due_date) ORDER BY i.installment_number)
          FROM credit_portfolio.dispositions d
          JOIN credit_portfolio.installments i ON i.schedule_id = d.disposition_id
         WHERE d.credit_account_id = '{cuenta_id}' AND i.status NOT IN ('CANCELLED', 'REPLACED')
         GROUP BY d.disposition_id, d.created_at""")
    descuadradas = 0
    for _, colocada, fechas in planes:
        dias = [x.strip() for x in fechas.strip("{}").split(",") if x.strip()]
        d = datetime.date.fromisoformat(colocada)
        for f in dias:
            d = _corte_siguiente(d)
            if f != d.isoformat():
                descuadradas += 1
                break
    j.compara(clave, "cada colocación paga en quincenas seguidas desde el primer corte", f"0 de {len(planes)}",
              f"{descuadradas} de {len(planes)}", ok=descuadradas == 0 and len(planes) > 0)

    # Issued: the business day the statement was assembled (snapshot.issuedAt); generated_at is when the PDF was
    # rendered, by the wall clock.
    estados = _filas(f"""
        SELECT s.cutoff_date,
               (coalesce((s.snapshot->>'issuedAt')::timestamptz, s.generated_at) AT TIME ZONE 'America/Mexico_City')::date,
               s.period_start,
               lag(s.cutoff_date) OVER (ORDER BY s.cycle_number)
          FROM statement.statements s
         WHERE s.credit_account_id = '{cuenta_id}' AND s.status = 'GENERATED' ORDER BY s.cycle_number""")
    j.compara(clave, "un estado de cuenta por corte sellado", len(sellados), len([e for e in estados if e[0] <= hasta]))
    tarde = [e[0] for e in estados if (datetime.date.fromisoformat(e[1]) - datetime.date.fromisoformat(e[0])).days != 1]
    j.compara(clave, "cada estado de cuenta se emite el día siguiente del corte", 0, len(tarde), ok=not tarde)
    periodo = [e[0] for e in estados if e[3] and e[2] != (datetime.date.fromisoformat(e[3]) + datetime.timedelta(days=1)).isoformat()]
    j.compara(clave, "el periodo del estado empieza el día siguiente al corte anterior", 0, len(periodo), ok=not periodo)

    # Each statement adds up: the total to pay is what the clients pay this fortnight (the rows' «A pagar») when the
    # line is current, and never less than it; the rest is moratorium and what earlier cutoffs left unpaid.
    cuadre = _filas(f"""
        WITH e AS (
            SELECT s.cycle_number, s.amount_due,
                   coalesce((s.snapshot->'view'->>'arrears')::numeric, 0) AS atraso,
                   coalesce((s.snapshot->'view'->'totals'->>'moratoriumInterest')::numeric, 0) AS moratorio,
                   (SELECT coalesce(sum((i->>'total')::numeric), 0)
                      FROM jsonb_array_elements(coalesce(s.snapshot->'view'->'placements', '[]'::jsonb)) p,
                           jsonb_array_elements(coalesce(p->'dueInPeriod', '[]'::jsonb)) i
                     WHERE (p->>'outstandingPrincipal')::numeric > 0) AS filas,
                   -- A quita in the period forgave part of what the rows ask: the total is legitimately less.
                   EXISTS (SELECT 1 FROM credit_portfolio.balance_events q
                            WHERE q.credit_account_id = s.credit_account_id AND q.event_type = 'AGREEMENT_QUITA_PARCIAL'
                              AND q.value_date BETWEEN s.period_start AND s.cutoff_date) AS quita
              FROM statement.statements s
             WHERE s.credit_account_id = '{cuenta_id}' AND s.status = 'GENERATED')
        SELECT count(*) FILTER (WHERE atraso = 0 AND moratorio = 0 AND abs(amount_due - filas) > 0.005),
               count(*) FILTER (WHERE atraso = 0 AND moratorio = 0),
               count(*) FILTER (WHERE amount_due - filas < -0.005 AND NOT quita), count(*),
               coalesce(string_agg(cycle_number || ': ' || amount_due || ' contra ' || filas, '; ')
                        FILTER (WHERE atraso = 0 AND moratorio = 0 AND abs(amount_due - filas) > 0.005), '')
          FROM e""")[0]
    j.compara(clave, "al corriente, el total a pagar = la suma de los pagos de sus clientes, al centavo",
              f"0 de {cuadre[1]}", f"{cuadre[0]} de {cuadre[1]}" + (f" ({cuadre[4][:200]})" if cuadre[4] else ""),
              ok=int(cuadre[0]) == 0)
    j.compara(clave, "ningún total a pagar menor que los pagos de sus clientes", f"0 de {cuadre[3]}",
              f"{cuadre[2]} de {cuadre[3]}", ok=int(cuadre[2]) == 0)

    tasa = round(float(cuenta.get("nominalRate") or 0), 6)
    j.compara(clave, "tasa de la línea", 0.95, tasa, ok=abs(tasa - 0.95) < 1e-6)
    cat = float(_uno(f"SELECT coalesce(cat, 0) FROM credit_portfolio.credit_accounts WHERE credit_account_id = '{cuenta_id}'") or 0)
    j.compara(clave, "CAT de la línea (Circular 21/2009, 24 quincenas)", f"{CAT_DE_LA_LINEA} %", f"{cat:.2f} %",
              ok=abs(cat - CAT_DE_LA_LINEA) < 0.006)
    cat_col = _filas(f"""
        SELECT count(*) FILTER (WHERE (p->>'cat') IS NULL),
               count(*) FILTER (WHERE (p->>'cat')::numeric NOT BETWEEN {CAT_DE_COLOCACION[0]} AND {CAT_DE_COLOCACION[1]}),
               count(*), coalesce(min((p->>'cat')::numeric), 0), coalesce(max((p->>'cat')::numeric), 0)
          FROM statement.statements s,
               jsonb_array_elements(coalesce(s.snapshot->'view'->'placements', '[]'::jsonb)) p
         WHERE s.credit_account_id = '{cuenta_id}' AND s.status = 'GENERATED'
           AND (p->>'outstandingPrincipal')::numeric > 0""")[0]
    j.compara(clave, "CAT de cada colocación en sus estados: el de la tasa (153.8 %–154.5 %)",
              f"0 fuera de {cat_col[2]}",
              f"{int(cat_col[0]) + int(cat_col[1])} fuera de {cat_col[2]} (de {float(cat_col[3]) * 100:.1f} % a "
              f"{float(cat_col[4]) * 100:.1f} %)", ok=int(cat_col[0]) + int(cat_col[1]) == 0 and int(cat_col[2]) > 0)
    montos = _filas(f"""
        SELECT count(*) FILTER (WHERE amount < 1000 OR amount > 10000), count(*)
          FROM credit_portfolio.dispositions WHERE credit_account_id = '{cuenta_id}'""")[0]
    j.compara(clave, "colocaciones de $1,000 a $10,000", f"0 de {montos[1]}", f"{montos[0]} de {montos[1]}", ok=int(montos[0]) == 0)
    # Her commission, the market's way: a flat 18 % of what her clients pay each statement, deducted from what she
    # delivers if she delivers by the due date (14 % a day later, 11 % two, 7 % three, nothing after), paid out of
    # the placement's interest and never out of its capital. The plans carry it installment by installment.
    planes = _filas(f"""
        WITH q AS (
            SELECT i.schedule_id, i.installment_number, i.total_amount, i.interest_amount, i.scheduled_commission,
                   sum(i.scheduled_commission) OVER (PARTITION BY i.schedule_id ORDER BY i.installment_number) AS com_acum,
                   sum(i.interest_amount) OVER (PARTITION BY i.schedule_id ORDER BY i.installment_number) AS int_acum
              FROM credit_portfolio.installments i
              JOIN credit_portfolio.dispositions d ON d.disposition_id = i.schedule_id
             WHERE d.credit_account_id = '{cuenta_id}' AND i.status NOT IN ('REPLACED', 'CANCELLED') AND d.status <> 'FAILED')
        SELECT count(DISTINCT schedule_id),
               count(DISTINCT schedule_id) FILTER (WHERE scheduled_commission IS NULL),
               count(DISTINCT schedule_id) FILTER (WHERE com_acum > int_acum + 0.005),
               (SELECT count(*) FROM (SELECT schedule_id FROM q GROUP BY schedule_id
                                      HAVING abs(sum(scheduled_commission) - sum(round(total_amount * 0.18, 2))) > 0.01) x),
               (SELECT count(*) FROM (SELECT schedule_id FROM q WHERE installment_number > 2
                                       GROUP BY schedule_id
                                      HAVING count(*) FILTER (WHERE abs(scheduled_commission - round(total_amount * 0.18, 2)) > 0.01
                                                                AND installment_number < (SELECT max(installment_number) FROM q q2 WHERE q2.schedule_id = q.schedule_id)) > 0) y)
          FROM q""")[0]
    n_planes, sin_comision, por_delante, suma_mal, no_plana = (int(x) for x in planes)
    j.compara(clave, "cada colocación lleva en su plan el 18 % de cada pago del cliente, plano, y la suma exacta",
              f"0 de {n_planes} planes", f"{sin_comision + suma_mal + no_plana} de {n_planes}",
              ok=n_planes > 0 and sin_comision + suma_mal + no_plana == 0)
    j.compara(clave, "la comisión acumulada nunca va por delante del interés acumulado de la colocación: sale del interés, no del capital",
              f"0 de {n_planes} planes", f"{por_delante} de {n_planes}", ok=por_delante == 0)
    # Each netted commission is the statement's plan commission at the tier of the day the money landed, measured
    # against the statement's own due date (closing's), not against what the record says.
    neteos = _filas(f"""
        WITH r AS (
            SELECT r.cycle_number, r.value_date, r.rate, r.amount, r.basis, c.payment_due_date,
                   greatest(0, r.value_date - c.payment_due_date) AS dias,
                   (SELECT coalesce(sum(i.scheduled_commission), 0) FROM credit_portfolio.installments i
                      JOIN credit_portfolio.dispositions d ON d.disposition_id = i.schedule_id
                     WHERE d.credit_account_id = r.credit_account_id AND i.billed_cycle = r.cycle_number
                       AND i.status NOT IN ('REPLACED', 'CANCELLED')) AS plano
              FROM commission.commission_records r
              JOIN closing.cutoff_schedules c ON c.credit_account_id = r.credit_account_id AND c.cycle_number = r.cycle_number
             WHERE r.credit_account_id = '{cuenta_id}' AND r.status = 'COMPENSATED' AND r.reverses_commission_id IS NULL)
        SELECT count(*),
               count(*) FILTER (WHERE rate <> CASE dias WHEN 0 THEN 0.18 WHEN 1 THEN 0.14 WHEN 2 THEN 0.11 WHEN 3 THEN 0.07 ELSE 0 END),
               count(*) FILTER (WHERE abs(amount - round(plano * CASE dias WHEN 0 THEN 0.18 WHEN 1 THEN 0.14 WHEN 2 THEN 0.11 WHEN 3 THEN 0.07 ELSE 0 END / 0.18, 2)) > 0.01),
               count(*) FILTER (WHERE dias >= 4),
               coalesce(sum(amount), 0)
          FROM r""")[0]
    n_net, otro_escalon, otro_importe, tardios, neteado = int(neteos[0]), int(neteos[1]), int(neteos[2]), int(neteos[3]), float(neteos[4])
    j.compara(clave, "cada comisión neteada es la del plan al escalón del día en que entró el dinero (18/14/11/7, nada después)",
              f"0 de {n_net} fuera", f"{otro_escalon + otro_importe} de {n_net} fuera ({neteado:,.2f} neteados; {tardios} fuera de la escalera)",
              ok=otro_escalon + otro_importe + tardios == 0)
    # Every statement she paid within the ladder netted her commission: nothing of hers was lost on the way.
    perdidas = _filas(f"""
        SELECT count(*) FILTER (WHERE i.status = 'PAID' AND p.plano > 0 AND p.pagado_en IS NOT NULL
                                  AND p.pagado_en - i.due_date <= 3 AND NOT EXISTS (
                                      SELECT 1 FROM commission.commission_records r
                                       WHERE r.credit_account_id = i.schedule_id AND r.cycle_number = i.installment_number
                                         AND r.status = 'COMPENSATED' AND r.reverses_commission_id IS NULL)),
               count(*) FILTER (WHERE i.status = 'PAID' AND p.plano > 0)
          FROM credit_portfolio.installments i
          JOIN LATERAL (SELECT coalesce(sum(x.scheduled_commission), 0) AS plano,
                               -- Her money (or the netting next to it): a quita covers a statement but earns nothing.
                               (SELECT min(e.value_date) FROM credit_portfolio.balance_events e
                                 WHERE e.credit_account_id = i.schedule_id
                                   AND e.event_type IN ('PAYMENT_APPLIED', 'COMMISSION_NETTED')
                                   AND e.value_date > i.cutoff_date) AS pagado_en
                          FROM credit_portfolio.installments x JOIN credit_portfolio.dispositions d ON d.disposition_id = x.schedule_id
                         WHERE d.credit_account_id = i.schedule_id AND x.billed_cycle = i.installment_number
                           AND x.status NOT IN ('REPLACED', 'CANCELLED')) p ON true
         WHERE i.schedule_id = '{cuenta_id}' AND i.status NOT IN ('REPLACED', 'CANCELLED') AND i.due_date <= '{hasta}'""")[0]
    j.compara(clave, "ningún estado pagado dentro de la escalera se quedó sin su comisión neteada",
              f"0 de {perdidas[1]}", f"{perdidas[0]} de {perdidas[1]}", ok=int(perdidas[0]) == 0)
    # Her statements show the ladder the plans set (the on-time step is the plans' commission) and what she deducted
    # in the period, the same the commission ledger holds.
    en_estados = _filas(f"""
        WITH e AS (
            SELECT s.cycle_number, s.period_start, s.cutoff_date,
                   (s.snapshot->'view'->>'commissionSettlement') AS neteo,
                   (s.snapshot->'view'->'commissionLadder'->0->>'commission')::numeric AS a_tiempo,
                   (SELECT coalesce(sum(i.scheduled_commission), 0) FROM credit_portfolio.installments i
                      JOIN credit_portfolio.dispositions d ON d.disposition_id = i.schedule_id
                     WHERE d.credit_account_id = s.credit_account_id AND i.billed_cycle = s.cycle_number
                       AND i.status NOT IN ('REPLACED', 'CANCELLED')) AS plano,
                   (SELECT coalesce(sum((c->>'amount')::numeric), 0)
                      FROM jsonb_array_elements(coalesce(s.snapshot->'commission'->'earned', '[]'::jsonb)) c
                     WHERE (c->>'valueDate')::date BETWEEN s.period_start AND s.cutoff_date AND c->>'settlement' = 'NETTED') AS en_el_estado,
                   (SELECT coalesce(sum(r.amount), 0) FROM commission.commission_records r
                     WHERE r.credit_account_id = s.credit_account_id AND r.status = 'COMPENSATED'
                       AND r.value_date BETWEEN s.period_start AND s.cutoff_date) AS en_el_libro
              FROM statement.statements s
             WHERE s.credit_account_id = '{cuenta_id}' AND s.status = 'GENERATED')
        SELECT count(*) FILTER (WHERE neteo IS DISTINCT FROM 'NETTED'),
               count(*) FILTER (WHERE abs(coalesce(a_tiempo, 0) - plano) > 0.005),
               count(*) FILTER (WHERE abs(en_el_estado - en_el_libro) > 0.005), count(*),
               coalesce(sum(en_el_estado), 0), coalesce(sum(en_el_libro), 0)
          FROM e""")[0]
    j.compara(clave, "sus estados de cuenta traen la escalera del plan y lo descontado en el periodo, igual que el libro de comisiones",
              f"0 de {en_estados[3]} ({float(en_estados[5]):,.2f})",
              f"{int(en_estados[0]) + int(en_estados[1]) + int(en_estados[2])} de {en_estados[3]} ({float(en_estados[4]):,.2f})",
              ok=int(en_estados[0]) + int(en_estados[1]) + int(en_estados[2]) == 0 and int(en_estados[3]) > 0)
    # The tiers the plan expects: how many statements she paid on time, a day late, two, three, past the ladder.
    if espera_escalones:
        por_dia = {str(f[0]): int(f[1]) for f in _filas(f"""
            SELECT least(greatest(r.value_date - c.payment_due_date, 0), 4), count(*)
              FROM commission.commission_records r
              JOIN closing.cutoff_schedules c ON c.credit_account_id = r.credit_account_id AND c.cycle_number = r.cycle_number
             WHERE r.credit_account_id = '{cuenta_id}' AND r.status = 'COMPENSATED' AND r.reverses_commission_id IS NULL
             GROUP BY 1""")}
        tardias = int(_uno(f"""
            SELECT count(*) FROM credit_portfolio.installments i
             WHERE i.schedule_id = '{cuenta_id}' AND i.status = 'PAID' AND i.due_date <= '{hasta}'
               AND NOT EXISTS (SELECT 1 FROM commission.commission_records r
                                WHERE r.credit_account_id = i.schedule_id AND r.cycle_number = i.installment_number AND r.status = 'COMPENSATED')
               AND EXISTS (SELECT 1 FROM credit_portfolio.installments x JOIN credit_portfolio.dispositions d ON d.disposition_id = x.schedule_id
                            WHERE d.credit_account_id = i.schedule_id AND x.billed_cycle = i.installment_number AND x.scheduled_commission > 0)""") or 0)
        por_dia["4"] = por_dia.get("4", 0) + tardias   # paid past the ladder: no netting at all
        for dia, valor in espera_escalones.items():
            real = por_dia.get(str(dia), 0)
            nombre = {"0": "a tiempo", "1": "un día tarde", "2": "dos días tarde", "3": "tres días tarde", "4": "fuera de la escalera (sin comisión)"}[str(dia)]
            if isinstance(valor, str) and valor.startswith("≥"):
                j.compara(clave, f"estados pagados {nombre}", valor, real, ok=real >= int(valor[1:]))
            else:
                j.compara(clave, f"estados pagados {nombre}", valor, real)


# ── the whole chain ─────────────────────────────────────────────────────────────────────────────

def juzga_cadena(j: Juicio, bitacora: list[dict]) -> str:
    r = subprocess.run(PSQL_FILAS, input=(pathlib.Path(__file__).parent / "concilia.sql").read_text(), capture_output=True, text=True)
    if r.returncode != 0:
        j.compara("", "conciliación ejecutable", "sin error", r.stderr.strip()[:300], ok=False)
    for linea in r.stdout.splitlines():
        partes = linea.split("|")
        if len(partes) < 3:
            continue
        nombre, fuera, detalle = partes[0], partes[1], "|".join(partes[2:])
        try:
            valor = float(fuera or 0)
        except ValueError:
            valor = float("nan")
        j.compara("", f"conciliación · {nombre}", 0, f"{fuera} ({detalle})", ok=valor == 0)

    v = subprocess.run([sys.executable, str(RAIZ / "scripts/verifica-contabilidad.py")], capture_output=True, text=True, cwd=RAIZ)
    j.compara("", "contabilidad contra la fuente (verifica-contabilidad)", "código 0", f"código {v.returncode}", ok=v.returncode == 0)

    sin_escenario = [b for b in bitacora if not b.get("escenario") and b.get("accion") in ("error", "asentamiento")]
    j.compara("", "bitácora de la corrida: errores y asentamientos incompletos", 0, len(sin_escenario), ok=not sin_escenario)
    noches = [b for b in bitacora if b.get("accion") == "noche"]
    con_cuarentena = [b for b in noches if "cuarentena 0," not in b.get("detalle", "") or "retenidas 0;" not in b.get("detalle", "")]
    j.compara("", "noches con cuentas en cuarentena o retenidas", 0, len(con_cuarentena), ok=not con_cuarentena)
    return v.stdout + v.stderr


INSTITUCION = "INSTITUCION"
POR_PRODUCTO = ("CAPITAL_1201_POR_PRODUCTO", "POR_COBRAR_1203_POR_PRODUCTO", "RESERVA_1290_POR_PRODUCTO")


def productos_de(periodos: list[dict]) -> list[str]:
    """Every product with movement in any period, and the institution's own facts: a product can hold balances (its
    year's revenue) in a month it did not move."""
    vistos = {INSTITUCION}
    for p in periodos:
        for m in motor.svc("accounting-service", "GET", f"/api/v1/accounting/products?period={p['period']}") or []:
            vistos.add(m.get("productType") or INSTITUCION)
    return sorted(vistos)


def juzga_productos(j: Juicio) -> None:
    """The trial balance by product, read through the API the back office uses: nothing lost or counted twice."""
    periodos = motor.svc("accounting-service", "GET", "/api/v1/accounting/periods") or []
    productos = productos_de(periodos)
    for p in periodos:
        periodo = p["period"]
        todo = {r["accountCode"]: float(r.get("closingBalance") or 0)
                for r in motor.svc("accounting-service", "GET", f"/api/v1/accounting/trial-balance?period={periodo}") or []}
        suma, descuadrados = {}, []
        for producto in productos:
            filas = motor.svc("accounting-service", "GET",
                              f"/api/v1/accounting/trial-balance?period={periodo}&productTypes={producto}") or []
            saldo = sum(float(r.get("closingBalance") or 0) for r in filas)
            if abs(saldo) > 0.005:
                descuadrados.append(f"{producto} {saldo:,.2f}")
            for r in filas:
                suma[r["accountCode"]] = suma.get(r["accountCode"], 0.0) + float(r.get("closingBalance") or 0)
        fuera = [f"{c} {todo.get(c, 0):,.2f}/{suma.get(c, 0):,.2f}" for c in sorted(set(todo) | set(suma))
                 if abs(todo.get(c, 0) - suma.get(c, 0)) > 0.005]
        j.compara("", f"balanza por producto {periodo}: los {len(productos)} productos suman la balanza", 0,
                  len(fuera) if not fuera else f"{len(fuera)} ({'; '.join(fuera[:4])})", ok=not fuera)
        j.compara("", f"balanza por producto {periodo}: cada producto cuadra", 0,
                  len(descuadrados) if not descuadrados else f"{len(descuadrados)} ({'; '.join(descuadrados[:4])})",
                  ok=not descuadrados)
        if p.get("status") != "CLOSED":
            continue
        cierre = motor.svc("accounting-service", "GET", f"/api/v1/accounting/periods/{periodo}/reconciliation") or {}
        checks = {c.get("code"): c for c in cierre.get("checks") or []}
        for codigo in POR_PRODUCTO:
            c = checks.get(codigo)
            j.compara("", f"cierre {periodo} · {codigo}", "en orden",
                      "no se corrió" if c is None else ("en orden" if c.get("ok") else c.get("detail")),
                      ok=bool(c and c.get("ok")))


AUTOVERIFICACION = (("commission-service", "/api/v1/commissions/self-checks"),
                    ("banking-service", "/api/v1/treasury/self-checks"),
                    ("credit-portfolio-service", "/api/v1/portfolio/self-checks"),
                    ("statement-service", "/api/v1/statements/self-checks"))


def autoverificacion() -> dict:
    """Each service's report on itself: the checks it ran on its own data, the ones it must hold and the ones that inform."""
    reportes = {}
    for servicio, ruta in AUTOVERIFICACION:
        try:
            reportes[servicio] = motor.svc(servicio, "GET", ruta) or {}
        except motor.HttpError as e:
            reportes[servicio] = {"error": str(e)}
    return reportes


def juzga_autoverificacion(j: Juicio) -> None:
    """The system verifies itself: every check a service must hold, holds. What only informs is printed, not judged."""
    for servicio, r in autoverificacion().items():
        checks = r.get("checks") or []
        if not checks:
            j.compara("", f"autoverificación · {servicio}", "en orden", r.get("error") or "sin comprobaciones", ok=False)
        for c in checks:
            if c.get("blocking"):
                j.compara("", f"autoverificación · {servicio} · {c.get('code')}", "en orden",
                          "en orden" if c.get("ok") else c.get("detail"), ok=bool(c.get("ok")))
            elif not c.get("ok"):
                print(f"  ⓘ {servicio} · {c.get('code')}: {c.get('detail')}")


def juzgar(evidencia: pathlib.Path, escenarios: dict) -> bool:
    estado = json.loads((evidencia / "estado.json").read_text())
    bitacora = [json.loads(l) for l in (evidencia / "bitacora.jsonl").read_text().splitlines() if l.strip()]
    j = Juicio()
    config = {}
    if (evidencia / "configuracion.json").exists():
        config = {f["tipo"]: f.get("interes") or "DAILY" for f in json.loads((evidencia / "configuracion.json").read_text())}
    for clave, e in escenarios.items():
        juzga_escenario(j, clave, e, estado["escenarios"].get(clave) or {}, bitacora, config, estado.get("fin"))
    contabilidad = juzga_cadena(j, bitacora)
    juzga_productos(j)
    juzga_autoverificacion(j)

    ok = not j.fallas
    md = [f"# Veredicto · {'✅ todo cuadra' if ok else f'❌ {len(j.fallas)} comprobaciones fallaron'}", "",
          f"Corrida {estado['inicio']} → {estado['fin']}, semilla {estado['semilla']}. "
          f"{len(j.filas)} comprobaciones: lo que cada escenario debía ser al terminar, leído de los servicios, "
          "la conciliación de la cadena en cero y la contabilidad contra la fuente de cada hecho.", ""]
    if j.fallas:
        md += ["## Lo que no cuadra", "", "| Escenario | Comprobación | Esperado | Real |", "|---|---|---|---|"]
        md += [f"| {f['escenario'] or 'cadena'} | {f['comprobacion']} | {f['esperado']} | {f['real']} |" for f in j.fallas]
        md.append("")
    md += ["## Todas las comprobaciones", "", "| | Escenario | Comprobación | Esperado | Real |", "|---|---|---|---|---|"]
    md += [f"| {'✅' if f['ok'] else '❌'} | {f['escenario'] or 'cadena'} | {f['comprobacion']} | {f['esperado']} | {f['real']} |" for f in j.filas]
    md += ["", "## Contabilidad contra la fuente", "", "```", contabilidad.strip(), "```", ""]
    (evidencia / "veredicto.md").write_text("\n".join(md), encoding="utf-8")
    (evidencia / "veredicto.json").write_text(json.dumps({"ok": ok, "comprobaciones": j.filas}, ensure_ascii=False, indent=1, default=str),
                                             encoding="utf-8")
    print(f"{'✓' if ok else '✗'} veredicto: {len(j.filas) - len(j.fallas)}/{len(j.filas)} comprobaciones en orden"
          + ("" if ok else f"; fallan {len(j.fallas)} (ver {evidencia / 'veredicto.md'})"))
    for f in j.fallas[:25]:
        print(f"    ✗ {f['escenario'] or 'cadena'} · {f['comprobacion']}: esperado {f['esperado']}, real {f['real']}")
    return ok


if __name__ == "__main__":
    import corre
    import escenarios as esc
    sys.exit(0 if juzgar(pathlib.Path(sys.argv[1]).expanduser(), esc.SUITES[corre.suite_de(pathlib.Path(sys.argv[1]).expanduser())]["escenarios"]) else 1)
