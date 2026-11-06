"""The deterministic plan: every scenario a credit can go through, as data, and how each one must end.

Days are offsets from the start date. What each scenario *does* is declared here; the engine
(`motor.py`) turns it into real operations against the stack: onboarding through the app's path,
compliance by the officer and decisions by the committee through the backoffice, payments through the
payments channel, purchases through the wallet, collections through the backoffice (the agent proposes,
the committee authorizes), and the nightly chain every simulated night. Nothing is inserted in a database.

Payment behaviour, evaluated every simulated day:
  puntual                 pays each installment on its due date (loans) or each statement on its
                          payment due date (revolving, with `paga` = minimo | exigible | total)
  retrasos {n: d}         installment n is paid d days after its due date (moratorium after grace)
  desde_cuota_no_paga n   from installment n on, nothing is paid until `al_dia_en` (or an agreement)
  al_dia_en [d, ...]      on those days, pays everything overdue plus penalties
  liquida_en d            pays the whole debt that day (early settlement)
  devuelve_pago_de_cuota n the payment of installment n is returned the next day and paid again 5 days later
  salta_cuota n           asks to skip installment n three days before it is due
  compras {d: (monto, plazo)}  purchases on the line (plazo = None → revolving)
  difiere {d: (monto, meses)}  defers the purchase of that amount to monthly installments (only the day it is bought)
  colocaciones {d: [(beneficiario, monto, plazo)]}  distributor places credit to its clients
  deja_de_pagar_desde_dia d    a revolving account stops paying from that day…
  reanuda_en d                 …and on that day pays every statement it skipped plus the moratorium, then goes on
  promesa_en / convenio_en d   collections actions (need an open case); convenio = restructure, at `convenio_tasa`
                               (24 % by default) and `convenio_meses` (6) more
  quita_en d, quita_pct p      a partial forgiveness agreement (QUITA_PARCIAL) of p of the debt
  quebranto_en d          from that day on, written off as soon as the case passes 181 days delinquent
  recupera {k: monto}     k days after the write-off, pays that amount (a recovery)
  apoyo_en d              a relief program for the scenario's product (risk proposes, administration grants)
  buro = "limite"         the simulated bureau answers a borderline profile: the policy sends it to the desk

How it must end (`espera`), read back from the services by `veredicto.py` at the end of the run:
  estatus                 the account's status (ACTIVE | SETTLED | WRITTEN_OFF | CANCELLED)
  intentos_de_desembolso n how many attempts its disbursement had (D63: a technical failure is asked again)
  al_corriente            0 days past due and no overdue installment
  mora_minima n           at least n days past due
  tasa r                  the nominal rate the account carries
  eventos [...]           event types its portfolio ledger must have
  polizas [...]           voucher triggers its accounting must have
  cuotas {status: n}      installments by status (">0" = at least one)
  saltadas n              installments skipped (a skip shifts the plan and marks the installment, it is no status)
  etapa                   its IFRS 9 stage
  caso {campo: valor}     its collections case (estatus, promesas, convenios, quebrantos; "≥1" = at least one)
  acciones [...]          actions that must appear in the run's log for it
  solicitud / sin_cuenta  the application's status, and that no account was opened
  pagos_rechazados n      how many rejected payments the plan tolerates (default 0)
  colocaciones_rechazadas n  placements refused at the door (the line blocked by arrears) the plan expects (default 0)
  escalones {día: n|"≥n"} a distributor's statements paid at each step of her ladder: "0" on time, "1".."3" days late,
                          "4" past the ladder (no commission at all)
  caso.contactos / caso.automaticos  the case's contact attempts, all and the automatic ones
Scenario keys for a distributor's arrears: contactos=[{dpd, canal, resultado}] (the agent's calls and visits through the
backoffice once the case is that many days late), promesa_parte (share of the debt promised, 0.5), convenio_respuesta
("acepta" or "ignora": proposed and left to expire).
"""
import datetime

INICIO = datetime.date(2026, 3, 2)    # a Monday
FIN = datetime.date(2026, 11, 1)      # 245 days: a card's first due date (≈ day 51) reaches 181+ days past due
SEMILLA = 20260302

PERSONAL = "PERSONAL_LOAN"
NOMINA = "PAYROLL_LOAN"
MICRO = "MICRO_LOAN"
PYME = "SME_LOAN"
GRUPAL = "GROUP_LOAN"
TARJETA = "CREDIT_CARD"
LINEA = "REVOLVING_LINE"
LINEA_EMPRESA = "BUSINESS_REVOLVING_LINE"
DISTRIBUIDORA = "DISTRIBUTOR_LINE"

# What every current credit must show at the end.
AL_CORRIENTE = dict(estatus="ACTIVE", al_corriente=True)
# What every write-off must leave behind: the ledger entry, the voucher, the case closed by it, no reserve.
CASTIGADO = dict(estatus="WRITTEN_OFF", eventos=["WRITE_OFF"], polizas=["WRITE_OFF"],
                 caso={"estatus": "WRITTEN_OFF", "quebrantos": 1}, acciones=["quebranto"])

ESCENARIOS = {
    # ── Personal loan: every way it can go ───────────────────────────────────────────────────────
    "E01": dict(titulo="Préstamo personal puntual, liquidado al plazo", producto=PERSONAL, monto=20_000, plazo=6,
                alta=0, puntual=True,
                espera=dict(estatus="SETTLED", cuotas={"PAID": 6}, polizas=["ACCOUNT_ACTIVATED", "PAYMENT_APPLIED"], estados_de_cuenta=5)),
    "E02": dict(titulo="Préstamo personal con un pago tardío y recuperación", producto=PERSONAL, monto=30_000, plazo=12,
                alta=0, puntual=True, retrasos={3: 20},
                espera=dict(**AL_CORRIENTE, eventos=["CHARGE_MORATORIUM_INTEREST"])),
    "E03": dict(titulo="Préstamo personal que nunca paga: mora, promesa rota, quebranto y recuperación", producto=PERSONAL,
                monto=15_000, plazo=12, alta=0, puntual=False, promesa_en=75, quebranto_en=205, recupera={3: 1_500, 10: 2_000},
                espera=dict(**{**CASTIGADO, "eventos": ["WRITE_OFF", "RECOVERY_PAYMENT"], "polizas": ["WRITE_OFF", "RECOVERY_PAYMENT"],
                               "caso": {"estatus": "WRITTEN_OFF", "quebrantos": 1, "promesas": "≥1"}})),
    "E04": dict(titulo="Préstamo personal en mora con reestructura", producto=PERSONAL, monto=25_000, plazo=12,
                alta=0, puntual=True, desde_cuota_no_paga=2, convenio_en=100, al_dia_en=[102],
                espera=dict(**AL_CORRIENTE, tasa=0.24, eventos=["AGREEMENT_RESTRUCTURE"], cuotas={"REPLACED": ">0"},
                            caso={"convenios": 1}, acciones=["reestructura"])),
    "E05": dict(titulo="Préstamo personal liquidado anticipadamente", producto=PERSONAL, monto=12_000, plazo=12,
                alta=0, puntual=True, liquida_en=80,
                espera=dict(estatus="SETTLED", deuda=0.0)),
    "E06": dict(titulo="Préstamo personal con un pago devuelto", producto=PERSONAL, monto=18_000, plazo=6,
                alta=0, puntual=True, devuelve_pago_de_cuota=1,
                espera=dict(estatus="SETTLED", eventos=["PAYMENT_RETURNED"], polizas=["PAYMENT_RETURNED"], acciones=["pago devuelto"])),
    "E16": dict(titulo="Préstamo personal con BNPL a 30 días", producto=PERSONAL, monto=20_000, plazo=12,
                alta=0, bnpl=30, puntual=True,
                espera=dict(**AL_CORRIENTE)),
    "E17": dict(titulo="Préstamo personal con un salto de pago", producto=PERSONAL, monto=15_000, plazo=12,
                alta=0, puntual=True, salta_cuota=2,
                espera=dict(**AL_CORRIENTE, saltadas=1, acciones=["salto de pago"])),
    "E18": dict(titulo="Solicitud que la política manda a la mesa y el comité rechaza", producto=PERSONAL, monto=22_000, plazo=12,
                alta=0, buro="limite", desenlace="rechazada",
                espera=dict(solicitud="REJECTED", sin_cuenta=True)),
    "E19": dict(titulo="Solicitud que la política manda a la mesa y se queda sin decidir", producto=PERSONAL, monto=9_000, plazo=6,
                alta=0, buro="limite", desenlace="en_mesa",
                espera=dict(solicitud="UNDER_MANUAL_REVIEW", sin_cuenta=True)),
    "E20": dict(titulo="Préstamo personal originado dos meses después, puntual", producto=PERSONAL, monto=35_000, plazo=12,
                alta=61, puntual=True,
                espera=dict(**AL_CORRIENTE)),
    "E27": dict(titulo="Préstamo personal en mora con quita parcial y liquidación", producto=PERSONAL, monto=20_000, plazo=12,
                alta=0, puntual=True, desde_cuota_no_paga=2, quita_en=110, quita_pct=0.25, liquida_en=116,
                espera=dict(estatus="SETTLED", eventos=["AGREEMENT_QUITA_PARCIAL"], polizas=["AGREEMENT_QUITA_PARCIAL"],
                            caso={"convenios": 1}, acciones=["quita"])),

    # ── Payroll, micro, SME and group loans ──────────────────────────────────────────────────────
    "E07": dict(titulo="Crédito de nómina quincenal puntual", producto=NOMINA, monto=40_000, plazo=12,
                alta=0, puntual=True,
                espera=dict(estatus="SETTLED", cuotas={"PAID": 12})),
    "E29": dict(titulo="Crédito de nómina liquidado anticipadamente", producto=NOMINA, monto=30_000, plazo=12,
                alta=0, puntual=True, liquida_en=70,
                espera=dict(estatus="SETTLED", deuda=0.0)),
    "E30": dict(titulo="Crédito de nómina con una quincena pagada tarde", producto=NOMINA, monto=20_000, plazo=12,
                alta=0, puntual=True, retrasos={3: 12},
                espera=dict(estatus="SETTLED", eventos=["CHARGE_MORATORIUM_INTEREST"])),
    "E21": dict(titulo="Crédito de nómina que deja de pagar y se castiga", producto=NOMINA, monto=25_000, plazo=12,
                alta=0, puntual=True, desde_cuota_no_paga=2, promesa_en=60, quebranto_en=200,
                espera=dict(**CASTIGADO)),
    "E08": dict(titulo="Microcrédito semanal con una semana de atraso", producto=MICRO, monto=5_000, plazo=12,
                alta=0, puntual=True, retrasos={4: 5},
                espera=dict(estatus="SETTLED", cuotas={"PAID": 12})),
    "E22": dict(titulo="Microcrédito semanal que deja de pagar y se castiga", producto=MICRO, monto=6_000, plazo=16,
                alta=0, puntual=True, desde_cuota_no_paga=3, quebranto_en=200,
                espera=dict(**CASTIGADO)),
    "E31": dict(titulo="Microcrédito semanal liquidado anticipadamente", producto=MICRO, monto=8_000, plazo=16,
                alta=0, puntual=True, liquida_en=40,
                espera=dict(estatus="SETTLED", deuda=0.0)),
    "E09": dict(titulo="Crédito PyME aprobado por comité, puntual", producto=PYME, monto=500_000, plazo=12,
                alta=0, puntual=True,
                espera=dict(**AL_CORRIENTE)),
    "E32": dict(titulo="Crédito PyME liquidado anticipadamente", producto=PYME, monto=200_000, plazo=12,
                alta=0, puntual=True, liquida_en=150,
                espera=dict(estatus="SETTLED", deuda=0.0)),
    "E23": dict(titulo="Crédito PyME en mora con reestructura", producto=PYME, monto=300_000, plazo=12,
                alta=0, puntual=True, desde_cuota_no_paga=3, convenio_en=125, al_dia_en=[127],
                espera=dict(**AL_CORRIENTE, tasa=0.24, eventos=["AGREEMENT_RESTRUCTURE"], cuotas={"REPLACED": ">0"},
                            caso={"convenios": 1}, acciones=["reestructura"])),
    "E28": dict(titulo="Crédito grupal semanal con programa de apoyo", producto=GRUPAL, monto=30_000, plazo=16,
                alta=0, puntual=True, apoyo_en=30, apoyo_periodos=2,
                espera=dict(estatus="SETTLED", acciones=["programa de apoyo"])),
    "E33": dict(titulo="Crédito grupal semanal con una semana pagada tarde", producto=GRUPAL, monto=20_000, plazo=16,
                alta=0, puntual=True, retrasos={5: 10},
                espera=dict(estatus="SETTLED", eventos=["CHARGE_MORATORIUM_INTEREST"])),

    # ── Card and revolving lines ─────────────────────────────────────────────────────────────────
    "E10": dict(titulo="Tarjeta: compras, pago mínimo y después pago total", producto=TARJETA, monto=30_000, plazo=12,
                alta=0, puntual=True, paga=["minimo", "total"], compras={2: (8_000, None), 20: (3_500, None), 65: (1_200, None)},
                espera=dict(**AL_CORRIENTE, estados_de_cuenta=6)),
    "E11": dict(titulo="Tarjeta con una compra a meses sin intereses", producto=TARJETA, monto=30_000, plazo=12,
                alta=0, puntual=True, paga=["exigible"], compras={3: (3_000, None), 5: (6_000, None)}, difiere={5: (6_000, 6)},
                espera=dict(**AL_CORRIENTE, acciones=["compra diferida"])),
    "E24": dict(titulo="Tarjeta que nunca paga: mora, promesa y quebranto", producto=TARJETA, monto=30_000, plazo=12,
                alta=0, puntual=False, compras={1: (12_000, None)}, promesa_en=70, quebranto_en=225,
                espera=dict(**CASTIGADO)),
    "E34": dict(titulo="Tarjeta que deja de pagar dos cortes, cae en mora y se pone al día", producto=TARJETA, monto=30_000, plazo=12,
                alta=0, puntual=True, paga=["exigible"], compras={2: (10_000, None), 35: (4_000, None), 70: (3_000, None)},
                deja_de_pagar_desde_dia=60, reanuda_en=110,
                espera=dict(**AL_CORRIENTE, eventos=["CHARGE_MORATORIUM_INTEREST"])),
    "E12": dict(titulo="Línea revolvente llevada a saldo cero y usada de nuevo", producto=LINEA, monto=25_000, plazo=12,
                alta=0, puntual=True, paga=["minimo"], compras={2: (4_000, None), 40: (15_000, None)}, liquida_en=10,
                espera=dict(**AL_CORRIENTE)),
    "E25": dict(titulo="Línea revolvente que deja de pagar, cae en mora y se pone al día", producto=LINEA, monto=40_000, plazo=12,
                alta=0, puntual=True, paga=["exigible"], compras={2: (15_000, None), 150: (2_000, None)},
                deja_de_pagar_desde_dia=45, reanuda_en=130,
                espera=dict(**AL_CORRIENTE, eventos=["CHARGE_MORATORIUM_INTEREST"])),
    "E36": dict(titulo="Línea revolvente con compras mes a mes que paga el total de cada corte", producto=LINEA,
                monto=40_000, plazo=12, alta=0, puntual=True, paga=["total"],
                compras={3: (6_000, None), 33: (5_000, None), 63: (7_000, None), 93: (4_000, None), 123: (6_000, None),
                         153: (3_000, None), 183: (5_000, None), 213: (4_000, None)},
                espera=dict(**AL_CORRIENTE, estados_de_cuenta=7)),
    "E13": dict(titulo="Línea empresarial que paga el exigible de cada corte", producto=LINEA_EMPRESA, monto=300_000, plazo=12,
                alta=0, puntual=True, paga=["exigible"], compras={1: (200_000, None)},
                espera=dict(**AL_CORRIENTE)),
    "E37": dict(titulo="Línea empresarial en mora con reestructura de tasa", producto=LINEA_EMPRESA, monto=300_000, plazo=12,
                alta=0, puntual=True, paga=["exigible"], compras={2: (150_000, None)},
                deja_de_pagar_desde_dia=40, convenio_en=95, convenio_tasa=0.15, convenio_meses=6, reanuda_en=125,
                espera=dict(**AL_CORRIENTE, tasa=0.15, eventos=["AGREEMENT_RESTRUCTURE"], caso={"convenios": 1},
                            acciones=["reestructura"])),

    # ── Banxico business days: a due date on a weekend or a bank holiday moves to the next business day ──────────
    "E41": dict(titulo="Préstamo personal con cuotas que caen en Jueves Santo, sábado y domingo: paga el día hábil siguiente",
                producto=PERSONAL, monto=18_000, plazo=8, alta=0, puntual=True,
                espera=dict(**AL_CORRIENTE, sin_eventos=["CHARGE_MORATORIUM_INTEREST"], corridas_a_habil=2,
                            ningun_vencimiento_inhabil=True)),
    "E42": dict(titulo="Préstamo personal: la cuota del Jueves Santo, corrida al lunes, se paga el martes (1 día tarde, en gracia)",
                producto=PERSONAL, monto=16_000, plazo=9, alta=0, puntual=True, retrasos={1: 1},
                espera=dict(**AL_CORRIENTE, sin_eventos=["CHARGE_MORATORIUM_INTEREST"], ningun_vencimiento_inhabil=True)),
    "E43": dict(titulo="Préstamo personal liquidado en domingo, víspera de un día inhábil (16 de marzo)",
                producto=PERSONAL, monto=12_000, plazo=12, alta=0, puntual=True, liquida_en=13,
                espera=dict(estatus="SETTLED", eventos=["PAYMENT_APPLIED"], ningun_vencimiento_inhabil=True)),
    # ── A disbursement that does not happen (D63): asked again while another attempt may work, compensated if not ──
    # The cents of the amount choose the STP simulator's outcome (stp-service README).
    "E44": dict(titulo="Préstamo personal cuyo desembolso STP rechaza por PLD: se cancela sin costo",
                producto=PERSONAL, monto=20_000.02, plazo=6, alta=0, puntual=False,
                espera=dict(estatus="CANCELLED", deuda=0.0, cuotas={"CANCELLED": 6}, solicitud="DISBURSEMENT_FAILED",
                            intentos_de_desembolso=1, eventos=["DISPOSITION_FAILED", "CHARGE_REVERSED"],
                            polizas=["ACCOUNT_CANCELLED", "DISPOSITION_FAILED"])),
    "E45": dict(titulo="Préstamo personal cuyo desembolso falla por el enlace en sus tres intentos: se cancela sin costo",
                producto=PERSONAL, monto=18_000.03, plazo=6, alta=0, puntual=False,
                espera=dict(estatus="CANCELLED", deuda=0.0, cuotas={"CANCELLED": 6}, solicitud="DISBURSEMENT_FAILED",
                            intentos_de_desembolso=3, eventos=["DISPOSITION_FAILED", "CHARGE_REVERSED"])),
    "E46": dict(titulo="Préstamo personal cuyo primer desembolso falla por el enlace y el segundo intento llega",
                producto=PERSONAL, monto=16_000.11, plazo=6, alta=0, puntual=True,
                espera=dict(estatus="SETTLED", cuotas={"PAID": 6}, intentos_de_desembolso=2)),
    "E47": dict(titulo="Préstamo personal cuyo desembolso devuelve el banco receptor: se cancela sin costo",
                producto=PERSONAL, monto=22_000.04, plazo=6, alta=0, puntual=False,
                espera=dict(estatus="CANCELLED", deuda=0.0, cuotas={"CANCELLED": 6}, solicitud="DISBURSEMENT_FAILED",
                            eventos=["DISPOSITION_RETURNED", "CHARGE_REVERSED"])),
}


# ════════════════════════════════════════════════════════════════════════════════════════════════
# The distributor line's own suite: calendar fortnights, from 1 January 2025
# ════════════════════════════════════════════════════════════════════════════════════════════════
#
# A distributor line is a micro-loan model: the distributor («la experta») places credit of $1,000 to $10,000 with her
# clients, at 95 % a year, and keeps 18 % of the interest their payments collect. The line cuts by fortnights on the
# 6th and the 21st (two per month, as the reference system does); each fortnight's interest accrues at its cutoff; the
# statement is issued the day after and is paid by the 18th (the cutoff of the 6th) and by the 3rd of the next month
# (the one of the 21st), on the next business day when that one is not. A placement pays on the line's cutoffs: twelve
# payments are twelve fortnights, the first at the first cutoff after it is placed.
#
# Ten distributors, nine current and one in arrears, with different kinds of clients: one placement and done, clients
# who renew when they finish, clients who grow from $2,000 to $10,000, the top and the bottom of the range, two
# placements at once, a placement STP rejects (PLD) and one the receiving bank returns; and different ways of paying:
# on the due date, two days after the cutoff, two days late within the grace, two statements skipped and caught up,
# the whole line paid off and used again. Days are offsets from INICIO_DISTRIBUIDORAS; the dates below are calendar
# dates, turned into offsets by `_dia`.

INICIO_DISTRIBUIDORAS = datetime.date(2025, 1, 1)
FIN_DISTRIBUIDORAS = datetime.date(2026, 11, 3)
SEMILLA_DISTRIBUIDORAS = 20250101


def _dia(fecha: str) -> int:
    return (datetime.date.fromisoformat(fecha) - INICIO_DISTRIBUIDORAS).days


# The line's two cutoff days and the day each statement is paid by (the product's billing cycle; configura checks it).
DIAS_DE_CORTE = (6, 21)
DIAS_DE_PAGO = {6: 18, 21: 3}


def _corte(d: datetime.date, n: int = 1) -> datetime.date:
    """The n-th cutoff (the 6th or the 21st) strictly after d: the same rule the line cuts by."""
    for _ in range(n):
        if d.day < DIAS_DE_CORTE[0]:
            d = d.replace(day=DIAS_DE_CORTE[0])
        elif d.day < DIAS_DE_CORTE[1]:
            d = d.replace(day=DIAS_DE_CORTE[1])
        else:
            d = (d.replace(day=28) + datetime.timedelta(days=4)).replace(day=DIAS_DE_CORTE[0])
    return d


def _renueva(indice: int, desde: str, montos: list, plazo: int, espera_dias: int = 3) -> list:
    """A client who places again when the last one is paid: each placement three days after the previous plan's last
    cutoff. Placements past the suite's end are left out."""
    fila, d = [], datetime.date.fromisoformat(desde)
    for monto in montos:
        if d > FIN_DISTRIBUIDORAS:
            break
        fila.append((d.isoformat(), indice, monto, plazo))
        d = _corte(d, plazo) + datetime.timedelta(days=espera_dias)
    return fila


def _una(indice: int, fecha: str, monto, plazo: int) -> list:
    return [(fecha, indice, monto, plazo)]


def _colocaciones(*clientes: list) -> dict:
    """The placements of a distributor by day offset: {día: [(cliente, monto, quincenas)]}."""
    plan: dict[int, list] = {}
    for fila in clientes:
        for fecha, indice, monto, plazo in fila:
            plan.setdefault(_dia(fecha), []).append((indice, monto, plazo))
    return plan


# What every current distributor shows at the end, besides being current: its interest charged per period, at the
# line's cutoffs; its cutoffs on the 6th and the 21st; its placements paying on them; each statement adding up to its
# clients' payments, with her commission and the CAT.
QUINCENAL = dict(quincenal=True)

DISTRIBUIDORAS = {
    # Every distributor pays her statement less her commission (18 % of what her clients pay, on time), places from
    # 12 to 24 fortnights and is blocked from the first day a statement is late until she pays it.
    "D01": dict(titulo="Distribuidora con diez clientes que renuevan, suben de monto y llegan al tope", producto=DISTRIBUIDORA,
                alta=_dia("2025-01-02"), linea=200_000, beneficiarios=10, puntual=True, paga=["exigible"],
                colocaciones=_colocaciones(
                    _renueva(0, "2025-01-08", [5_000, 6_000, 7_000, 8_000], 12),
                    _renueva(1, "2025-01-20", [2_000, 4_000, 6_000, 8_000, 10_000], 12),
                    _renueva(2, "2025-02-03", [10_000, 10_000], 24),          # a bank holiday: SPEI goes out on the 4th
                    _renueva(3, "2025-03-10", [1_000, 1_500, 2_000, 2_500, 3_000, 3_500], 12),
                    _una(4, "2025-04-14", 3_000, 14), _una(4, "2025-06-16", 4_500, 14),   # two placements at once
                    _una(5, "2026-02-27", 7_500, 16),
                    _renueva(6, "2025-02-10", [4_000, 5_000, 6_000], 12),
                    _renueva(7, "2025-05-12", [9_000, 9_500, 10_000], 16),
                    _una(8, "2025-09-08", 2_000, 12), _una(8, "2026-03-23", 2_500, 12),
                    _renueva(9, "2025-11-10", [6_000, 7_000], 12)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL, escalones={"0": "≥40"})),
    "D02": dict(titulo="Distribuidora que paga cada estado de cuenta tres días antes de la fecha límite", producto=DISTRIBUIDORA,
                alta=_dia("2025-01-06"), linea=120_000, beneficiarios=7, puntual=True, paga=["exigible"], paga_tras_limite=-3,
                regimen="626",
                colocaciones=_colocaciones(
                    _renueva(0, "2025-01-13", [3_000, 3_000, 3_500, 4_000], 12),
                    _una(1, "2025-02-21", 9_500, 20),
                    _renueva(2, "2025-03-05", [1_500, 3_000, 4_500, 6_000], 12),
                    _una(3, "2025-07-31", 5_000, 12), _una(3, "2025-09-15", 2_500, 12),
                    _renueva(4, "2025-04-07", [2_000, 2_500, 3_000], 12),
                    _una(5, "2025-10-13", 8_000, 18),
                    _renueva(6, "2026-01-12", [1_000, 1_500], 12)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL, sin_eventos=["CHARGE_MORATORIUM_INTEREST"], escalones={"0": "≥40", "1": 0, "2": 0, "3": 0, "4": 0})),
    "D03": dict(titulo="Distribuidora que paga dos días después de la fecha límite, dentro de la gracia", producto=DISTRIBUIDORA,
                alta=_dia("2025-02-10"), linea=140_000, beneficiarios=7, puntual=True, paga=["exigible"], paga_tras_limite=2,
                colocaciones=_colocaciones(
                    _renueva(0, "2025-02-14", [4_000, 5_000, 6_000], 12),
                    _renueva(1, "2025-02-28", [2_000, 2_000, 2_000, 2_000], 12),
                    _una(2, "2025-05-30", 8_000, 18),
                    _renueva(3, "2025-10-01", [6_500, 7_000], 14),
                    _renueva(4, "2025-03-17", [3_000, 3_500, 4_000], 12),
                    _una(5, "2025-08-11", 5_500, 16),
                    _renueva(6, "2026-02-09", [2_500, 3_000], 12)),
                # Two days late she keeps 11 %; when the second day is a weekend the money lands later and she keeps less.
                espera=dict(**AL_CORRIENTE, **QUINCENAL, sin_eventos=["CHARGE_MORATORIUM_INTEREST"], escalones={"0": 0, "2": "≥20"})),
    "D04": dict(titulo="Distribuidora con una colocación que STP rechaza por PLD: la línea recupera su cupo", producto=DISTRIBUIDORA,
                alta=_dia("2025-03-03"), linea=150_000, beneficiarios=6, puntual=True, paga=["exigible"],
                colocaciones=_colocaciones(
                    _renueva(0, "2025-03-06", [3_500, 4_000, 4_500], 12),
                    _una(1, "2025-03-06", 4_000.02, 12),                     # the cents choose the STP simulator: PLD
                    _renueva(2, "2025-04-22", [9_000, 9_000], 16),
                    _renueva(3, "2025-08-04", [1_000, 2_000, 3_000, 4_000], 12),
                    _renueva(4, "2025-05-19", [5_000, 6_000], 14),
                    _una(5, "2026-01-26", 7_000, 12)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL, eventos=["DISPOSITION_FAILED"])),
    "D05": dict(titulo="Distribuidora que deja de pagar dos cortes, cae en mora, la contacta cobranza y se pone al día", producto=DISTRIBUIDORA,
                alta=_dia("2025-04-01"), linea=120_000, beneficiarios=5, puntual=True, paga=["exigible"],
                deja_de_pagar_desde_dia=_dia("2025-08-16"), reanuda_en=_dia("2025-09-10"),
                contactos=[dict(dpd=3, canal="PHONE", resultado="NO_ANSWER"), dict(dpd=10, canal="PHONE", resultado="ANSWERED")],
                colocaciones=_colocaciones(
                    _renueva(0, "2025-04-07", [6_000, 7_500, 9_000], 12),
                    _renueva(1, "2025-05-19", [2_500, 2_500, 3_000, 3_000], 12),
                    _una(2, "2026-01-26", 10_000, 24),
                    _renueva(3, "2025-06-16", [4_000, 4_500], 14),
                    _una(4, "2025-11-10", 3_500, 12)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL, eventos=["CHARGE_MORATORIUM_INTEREST"],
                            caso={"estatus": "CLOSED", "contactos": "≥2"}, escalones={"4": "≥1"})),
    "D06": dict(titulo="Distribuidora que liquida su línea completa antes del plazo y vuelve a colocar", producto=DISTRIBUIDORA,
                alta=_dia("2025-05-05"), linea=200_000, beneficiarios=5, puntual=True, paga=["exigible"],
                liquida_en=_dia("2025-12-10"),
                colocaciones=_colocaciones(
                    _una(0, "2025-05-12", 10_000, 24), _una(1, "2025-06-02", 6_000, 18), _una(2, "2025-07-15", 3_000, 12),
                    _renueva(0, "2026-01-12", [8_000, 9_500], 12), _una(1, "2026-03-31", 4_000, 12),
                    _renueva(3, "2025-08-04", [5_000, 5_500], 12),
                    _una(4, "2026-02-16", 6_500, 16)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL)),
    "D07": dict(titulo="Distribuidora nueva en 2026 con seis clientes", producto=DISTRIBUIDORA,
                alta=_dia("2026-01-05"), linea=120_000, beneficiarios=6, puntual=True, paga=["exigible"],
                colocaciones=_colocaciones(
                    _renueva(0, "2026-01-09", [2_500, 3_500, 5_000], 12),
                    _una(1, "2026-02-16", 7_000, 14),
                    _una(2, "2026-06-15", 1_000, 12), _una(2, "2026-08-31", 1_500, 12),
                    _renueva(3, "2026-01-26", [4_000, 4_500], 12),
                    _una(4, "2026-03-09", 9_000, 16),
                    _una(5, "2026-05-11", 2_000, 12)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL)),
    "D08": dict(titulo="Distribuidora con una colocación que devuelve el banco receptor: se cancela sin costo", producto=DISTRIBUIDORA,
                alta=_dia("2025-07-01"), linea=160_000, beneficiarios=5, puntual=True, paga=["exigible"],
                colocaciones=_colocaciones(
                    _renueva(0, "2025-07-03", [5_500, 6_500, 7_500], 12),
                    _una(1, "2025-07-03", 3_000.04, 12),                     # the cents choose the STP simulator: returned
                    _renueva(2, "2025-09-29", [4_000, 4_000, 4_000], 12),
                    _renueva(3, "2025-08-11", [2_000, 2_500], 12),
                    _una(4, "2026-01-19", 8_500, 18)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL, eventos=["DISPOSITION_RETURNED"])),
    "D09": dict(titulo="Distribuidora grande: dieciséis clientes de $1,000 a $10,000", producto=DISTRIBUIDORA,
                alta=_dia("2025-01-13"), linea=300_000, beneficiarios=16, puntual=True, paga=["exigible"],
                colocaciones=_colocaciones(
                    _renueva(0, "2025-01-16", [10_000, 10_000, 10_000, 10_000], 12),
                    _renueva(1, "2025-01-16", [1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000], 12),
                    _renueva(2, "2025-01-31", [5_000, 5_500, 6_000], 16),
                    _una(3, "2025-02-14", 8_500, 24), _una(3, "2026-02-13", 9_000, 18),
                    _renueva(4, "2025-03-21", [3_000, 4_000, 5_000, 6_000], 12),
                    _renueva(5, "2025-05-15", [2_000, 3_000, 4_000, 5_000, 6_000], 12),
                    _una(6, "2025-08-29", 7_000, 20),
                    _renueva(7, "2025-11-03", [1_500, 2_500, 3_500], 12),
                    _renueva(8, "2025-01-20", [4_500, 5_000, 5_500], 12),
                    _renueva(9, "2025-02-17", [2_000, 2_500, 3_000, 3_500], 12),
                    _una(10, "2025-03-10", 10_000, 24),
                    _renueva(11, "2025-04-14", [6_500, 7_000], 14),
                    _renueva(12, "2025-06-09", [1_500, 2_000, 2_500], 12),
                    _una(13, "2025-09-22", 9_500, 16),
                    _renueva(14, "2025-12-08", [3_000, 4_000], 12),
                    _una(15, "2026-04-20", 5_000, 12)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL)),
    # Collections for a distributor: she recovers on her own within 30 days; after that she is offered a way out, a
    # restructure if she shows she will pay, a partial forgiveness to settle in the later stages; no recovery after a
    # write-off is contemplated. D10 never pays again: a promise she breaks, a restructure she ignores, a forgiveness
    # she takes to settle; meanwhile her line, blocked, refuses three placements.
    "D10": dict(titulo="Distribuidora que deja de pagar en marzo de 2026: promesa rota, bloqueada, reestructura que ignora y quita con la que liquida y se va (la línea se cierra)", producto=DISTRIBUIDORA,
                alta=_dia("2025-02-04"), linea=130_000, beneficiarios=6, puntual=True, paga=["exigible"],
                deja_de_pagar_desde_dia=_dia("2026-03-16"), promesa_en=_dia("2026-04-20"), promesa_parte=0.5,
                contactos=[dict(dpd=3, canal="PHONE", resultado="NO_ANSWER"), dict(dpd=10, canal="PHONE", resultado="ANSWERED"),
                           dict(dpd=25, canal="VISIT", resultado="NO_ANSWER"), dict(dpd=40, canal="PHONE", resultado="ANSWERED")],
                convenio_en=_dia("2026-05-10"), convenio_tasa=0.95, convenio_meses=6, convenio_respuesta="ignora",
                quita_en=_dia("2026-06-15"), quita_pct=0.25, liquida_en=_dia("2026-06-22"),
                # The quita is her exit: the line places no more from that day and closes when she settles (22 June).
                # Her clients' renewals scheduled after it (9 August, 9 September) are refused at the door: 3 + 2.
                colocaciones=_colocaciones(
                    _renueva(0, "2025-02-07", [4_000, 5_000, 6_000, 7_000], 12),
                    _renueva(1, "2025-03-18", [2_000, 2_500, 3_000, 3_500, 4_000], 12),
                    _una(2, "2025-09-10", 9_000, 24),
                    _una(3, "2026-01-12", 6_000, 12),
                    _renueva(4, "2025-06-16", [3_000, 3_500], 12),
                    _una(5, "2025-11-10", 8_000, 16),
                    # Blocked by arrears: the three are refused at the door.
                    _una(3, "2026-04-09", 5_000, 12), _una(4, "2026-05-04", 4_000, 12), _una(5, "2026-06-09", 6_000, 12)),
                espera=dict(estatus="CLOSED", al_corriente=True, eventos=["CHARGE_MORATORIUM_INTEREST", "AGREEMENT_QUITA_PARCIAL"],
                            caso={"estatus": "CLOSED", "promesas": "≥1", "convenios": "≥1", "contactos": "≥4"},
                            colocaciones_rechazadas=5, escalones={"4": "≥1"}, **QUINCENAL)),
    "D11": dict(titulo="Distribuidora que cae en mora, queda bloqueada, promete, cumple dentro de los 30 días y vuelve a colocar", producto=DISTRIBUIDORA,
                alta=_dia("2025-03-10"), linea=110_000, beneficiarios=5, puntual=True, paga=["exigible"],
                deja_de_pagar_desde_dia=_dia("2025-10-16"), reanuda_en=_dia("2025-11-19"),
                contactos=[dict(dpd=5, canal="PHONE", resultado="NO_ANSWER"), dict(dpd=9, canal="PHONE", resultado="PROMISE_MADE")],
                # She promises a tenth of what she owes in all (the two statements behind) and pays them a week later.
                promesa_en=_dia("2025-11-12"), promesa_parte=0.1,
                colocaciones=_colocaciones(
                    _renueva(0, "2025-03-14", [5_000, 6_000], 12),
                    _renueva(1, "2025-04-07", [2_000, 3_000, 4_000], 12),
                    _una(2, "2025-06-16", 4_000, 12),
                    _una(2, "2025-11-10", 3_000, 12),                        # blocked: refused at the door
                    _una(2, "2025-11-24", 3_000, 12),                        # current again: placed
                    _una(3, "2025-08-11", 7_000, 16),
                    _renueva(4, "2026-01-26", [2_500, 3_500], 12)),
                espera=dict(**AL_CORRIENTE, **QUINCENAL, eventos=["CHARGE_MORATORIUM_INTEREST"],
                            caso={"estatus": "CLOSED", "promesas": "≥1", "contactos": "≥2"}, colocaciones_rechazadas=1,
                            escalones={"4": "≥1"}, acciones=["promesa de pago", "contacto de cobranza"])),
    "D12": dict(titulo="Distribuidora en mora con voluntad de pago: promesa rota, reestructura aceptada y se regulariza", producto=DISTRIBUIDORA,
                alta=_dia("2025-06-02"), linea=120_000, beneficiarios=4, puntual=True, paga=["exigible"],
                deja_de_pagar_desde_dia=_dia("2026-01-16"), reanuda_en=_dia("2026-03-16"),
                contactos=[dict(dpd=3, canal="PHONE", resultado="NO_ANSWER"), dict(dpd=20, canal="PHONE", resultado="ANSWERED"),
                           dict(dpd=33, canal="VISIT", resultado="PROMISE_MADE")],
                promesa_en=_dia("2026-02-25"), promesa_parte=0.5,
                convenio_en=_dia("2026-03-10"), convenio_tasa=0.95, convenio_meses=6,
                colocaciones=_colocaciones(
                    _renueva(0, "2025-06-06", [6_000, 7_000], 12),
                    _renueva(1, "2025-07-07", [3_000, 3_500, 4_000], 12),
                    _una(2, "2025-09-08", 9_000, 18),
                    _renueva(3, "2025-10-13", [2_000, 2_500], 12),
                    _una(0, "2026-02-09", 5_000, 12),                        # blocked: refused at the door
                    _una(0, "2026-04-13", 5_000, 12)),                       # regularized: placed
                espera=dict(**AL_CORRIENTE, **QUINCENAL, eventos=["CHARGE_MORATORIUM_INTEREST", "AGREEMENT_RESTRUCTURE"],
                            caso={"estatus": "CLOSED", "promesas": "≥1", "convenios": "≥1", "contactos": "≥3"},
                            forborne=True, colocaciones_rechazadas=1, acciones=["reestructura"])),
    # Her own placement policy, stricter than her branch's: a NEW client up to $3,000, one live loan, 12 fortnights; a
    # REVOLVING one (he fully paid a loan) up to $6,000, two live loans, 16 fortnights. Four placements refused at the
    # door, each saying why in her words; she reports a client (red list) and clears him; another client pays his own
    # installment with his reference (his receipt), so she delivers that statement less what he paid; and a deposit
    # with a reference that is nobody's is hers by the rule.
    "D13": dict(titulo="Distribuidora con política propia más estricta: límites por tipo de cliente, lista roja y un cliente que paga directo", producto=DISTRIBUIDORA,
                alta=_dia("2025-04-07"), linea=80_000, beneficiarios=3, puntual=True, paga=["exigible"],
                politica_propia={"nuevo": {"maxLoanAmount": 3_000, "maxOutstanding": 3_000, "maxActiveLoans": 1, "maxTerm": 12},
                                 "revolvente": {"maxLoanAmount": 6_000, "maxOutstanding": 8_000, "maxActiveLoans": 2, "maxTerm": 16}},
                colocaciones=_colocaciones(
                    _una(0, "2025-04-11", 5_000, 12),                       # refused: a new client up to $3,000
                    _una(0, "2025-04-11", 3_000, 12),
                    _una(1, "2025-04-14", 3_000, 18),                       # refused: up to 12 fortnights
                    _una(1, "2025-04-14", 2_500, 12),
                    _una(2, "2025-04-21", 2_000, 12),
                    _una(0, "2025-05-12", 1_000, 12),                       # refused: one live loan
                    _una(2, "2025-06-09", 1_000, 12),                       # refused: she reported him (and one live)
                    _una(0, "2025-10-27", 6_000, 16),                       # REVOLVENTE: his first loan is paid
                    _una(2, "2025-11-10", 1_500, 12)),                      # REVOLVENTE and cleared
                reportes={_dia("2025-06-02"): [(2, "NOT_LOCATABLE", "No contesta desde mayo")]},
                levanta={_dia("2025-07-01"): [2]},
                pagos_cliente={_dia("2025-06-10"): [(1, "recibo")]},
                referencia_ajena={_dia("2025-08-11"): 150.00},
                espera=dict(**AL_CORRIENTE, **QUINCENAL, colocaciones_rechazadas=4,
                            rechazos_con=["le puedes prestar hasta $3,000.00 por préstamo", "hasta 1 préstamo vivo",
                                          "a lo más a 12 quincenas", "lista roja"],
                            politica_propia={"nuevo": 3_000}, pago_directo=1, lista_roja_levantada=2, referencia_ajena=True,
                            tipos={0: ("NUEVO", "REVOLVENTE"), 2: ("NUEVO", "REVOLVENTE")},
                            acciones=["reporte a lista roja", "reporte levantado", "pago directo del cliente"])),
}

# ── The STP simulator's scenarios ────────────────────────────────────────────────────────────────
#
# The local stack's simulator plays a failure only for the amounts a run arms (scenario-amounts ARMED). These are the
# ones the scenarios chose for their cents (.02 PLD, .03 a sustained -30, .04 returned by the bank, .11 a failure the
# next attempt overcomes); everything the platform computes — a commission lot, a funding payment — settles.

CENTAVOS_DE_ESCENARIO = range(1, 12)


def montos_de_escenario_stp(escenarios: dict) -> set:
    """The amounts of a suite's scenarios whose cents choose a simulator scenario: loan amounts and placements."""
    montos = set()

    def mira(valor):
        if isinstance(valor, float):
            if round(valor * 100) % 100 in CENTAVOS_DE_ESCENARIO:
                montos.add(round(valor, 2))
        elif isinstance(valor, dict):
            for v in valor.values():
                mira(v)
        elif isinstance(valor, (list, tuple)):
            for v in valor:
                mira(v)

    for e in escenarios.values():
        mira(e.get("monto"))
        mira(e.get("colocaciones"))
    return montos


# ── Suites: each product's tests run and report on their own ─────────────────────────────────────
#
# A suite is a set of scenarios with its own dates, seed and evidence. The products other than the distributor line
# share the dates of the plan above (2026-03-02 → 2026-11-01) and can run one product at a time or all together
# («productos»); the distributor line runs its own history from 1 January 2025.

def _del_producto(producto: str) -> dict:
    return {k: v for k, v in ESCENARIOS.items() if v["producto"] == producto}


SUITES = {
    "productos": dict(titulo="Productos de crédito, menos la línea de distribuidora", inicio=INICIO, fin=FIN,
                      semilla=SEMILLA, escenarios=ESCENARIOS),
    "distribuidoras": dict(titulo="Línea de distribuidora: quincenas de calendario desde el 1 de enero de 2025",
                           inicio=INICIO_DISTRIBUIDORAS, fin=FIN_DISTRIBUIDORAS, semilla=SEMILLA_DISTRIBUIDORAS,
                           escenarios=DISTRIBUIDORAS),
    **{producto: dict(titulo=f"Sólo {producto}", inicio=INICIO, fin=FIN, semilla=SEMILLA, escenarios=_del_producto(producto))
       for producto in (PERSONAL, NOMINA, MICRO, PYME, GRUPAL, TARJETA, LINEA, LINEA_EMPRESA)},
}
