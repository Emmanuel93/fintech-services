"""The engine: one simulated day at a time, against the real stack.

Every day D:
  1. the business clock of every service is set to D;
  2. the day's operations run through the real channels (app, payments, wallet, backoffice);
  3. the clock moves to D+1 and the nightly chain runs for D: charges asks the portfolio for the
     snapshot, accrues on it, the portfolio closes the day, closing cuts, statement-service renders
     the PDFs; then risk, collections and, at month end, invoicing and the accounting period.

Nothing is written to a database. The engine only calls endpoints, the same ones a person or a cron
would call, and the test-support endpoints that exist for this (clock, job triggers).
"""
from __future__ import annotations

import concurrent.futures
import datetime
import json
import pathlib
import random
import re
import subprocess
import time
import urllib.error
import urllib.request

import altas
import escenarios
import puente

RAIZ = pathlib.Path(__file__).resolve().parents[2]

BACKOFFICE = "http://backoffice.localhost:8090"
MOBILE = "http://mobile.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")

# The staff of the run, one session per role (created by the configuration, scripts/seed-demo-staff.py). Who
# proposes is not who authorizes, and every decision is signed by the session that took it: the console derives
# the author from the session, so a name typed in a request body proves nothing.
PERSONAL = {
    "comite": ("e2e.comite@kredius.mx", ADMIN[1]),              # decides applications; authorizes agreements and write-offs
    "cobranza": ("e2e.cobranza@kredius.mx", ADMIN[1]),          # promises, proposes agreements, requests write-offs
    "cumplimiento": ("e2e.cumplimiento@kredius.mx", ADMIN[1]),  # identity and watchlists
    "riesgo": ("e2e.riesgo@kredius.mx", ADMIN[1]),              # proposes relief programs
}
# The demo's staff (scripts/seed-demo-staff.py): the demo planner drives the same engine on the demo's book.
PERSONAL_DEMO = {
    "comite": ("comite@kredius.mx", ADMIN[1]),
    "cobranza": ("cobranza@kredius.mx", ADMIN[1]),
    "cumplimiento": ("cumplimiento@kredius.mx", ADMIN[1]),
    "riesgo": ("analista@kredius.mx", ADMIN[1]),
}
PG = ["docker", "exec", "-i", "fintech-services-postgres-1", "psql", "-U", "fintech", "-d", "fintech", "-At", "-F", ","]

# Services whose business clock moves with the simulation: everything that stamps a business fact or decides by the
# hour. The STP connector dates each order with its Banxico day of operation, which is the date treasury's cash book
# carries the disbursement on; the dispatcher decides by business time when an order is due, and treasury judges a
# trace stopped by its business today. Left on the real clock, both depended on the hour and the day the run was
# launched (D66).
CON_RELOJ = ["credit-portfolio-service", "charges-service", "closing-service", "statement-service", "receipt-service",
             "accounting-service", "payments-service", "origination-service", "collections-service",
             "risk-service", "commission-service", "invoicing-service", "scoring-service",
             "beneficiary-service", "wallet-service", "stp-service", "banking-service", "disbursement-service"]

# Where the lent money comes from: capital paid in and a funding line drawn on the first day, the line's interest
# accrued and paid at each month's end. Sized to what each run lends (the E2E reaches 1.95 M in its first month; the
# demo, with its history, disburses 7.05 M on its first day): the cash book never closes a day below zero, which
# treasury's self-check judges day by day and the month-end close at each month's end. With 2.5 M drawn the demo's
# cash was 761,280 below zero for most of April (4 Nov): money lent that it did not have.
FONDEO_E2E = {"capital": 1_500_000, "aporta": 1_500_000, "linea": 2_000_000, "dispone": 1_000_000, "tasa": 0.13,
              "comision": 10_000}
FONDEO_DEMO = {"capital": 4_000_000, "aporta": 4_000_000, "linea": 5_000_000, "dispone": 4_000_000, "tasa": 0.13,
               "comision": 25_000}
# The institution's own account at STP (banking seed 005): where the funding is received.
CUENTA_PROPIA = "9b1d0000-0000-4000-8000-000000000001"

# Collections only writes off a case past this many days delinquent (`write-off-threshold-days`).
UMBRAL_QUEBRANTO = 181

CABECERAS = {"X-User-Id": "ciclo-credito", "X-Roles": "ADMIN,OPS,SERVICE,FINANCE", "X-Channel": "SERVICE",
             "Content-Type": "application/json"}




class HttpError(RuntimeError):
    def __init__(self, code, body, url):
        super().__init__(f"HTTP {code} {url}: {body[:300]}")
        self.code, self.body = code, body


def http(method, url, body=None, headers=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else (b"" if method in ("POST", "PUT") else None)
    req = urllib.request.Request(url, method=method, data=data, headers={**CABECERAS, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            if not raw:
                return None
            if "json" in (r.headers.get("Content-Type") or ""):
                return json.loads(raw)
            return raw
    except urllib.error.HTTPError as e:
        raise HttpError(e.code, e.read().decode(errors="replace"), url) from None


def svc(servicio, method, ruta, body=None, headers=None, timeout=60):
    return http(method, puente.URL[servicio] + ruta, body, headers, timeout)


def bo(token, method, ruta, body=None):
    return http(method, BACKOFFICE + ruta, body, {"Authorization": f"Bearer {token}"})


class SesionBackoffice:
    """A staff session that logs in again when its 15-minute token expires."""

    def __init__(self, credenciales=ADMIN):
        self.credenciales = credenciales
        self.token, self.at = None, 0.0

    def vigente(self) -> str:
        if not self.token or time.time() - self.at > 600:
            self.token, self.at = login(self.credenciales), time.time()
        return self.token

    def __call__(self, method, ruta, body=None):
        try:
            return bo(self.vigente(), method, ruta, body)
        except HttpError as e:
            if e.code != 401:
                raise
            self.token, self.at = login(self.credenciales), time.time()
            return bo(self.token, method, ruta, body)


class OficialDeCumplimiento:
    """
    Identity and watchlists resolved by a compliance officer through the console, the way the desk does it: find
    the person in the queue, open the file, then decide. The seed scripts resolve them calling compliance-service
    on the Docker network and sign as `siembra-demo`; the end-to-end run goes through the backoffice instead, and
    the decision carries the officer's session.
    """
    NOTA_IDENTIDAD = ("Cotejo en la mesa de cumplimiento: INE frente y reverso, prueba de vida y comprobante de "
                      "domicilio coinciden con lo capturado. Datos sintéticos del entorno de pruebas.")
    NOTA_LISTAS = "Homonimia descartada: CURP y fecha de nacimiento no coinciden con la persona listada."

    def __init__(self, sesion: SesionBackoffice):
        self.bo = sesion

    def _en_la_cola(self, prospect_id, intentos=40, pausa=0.5):
        # The verification is created by a listener: for a moment after the sign-up it is not in the queue yet.
        for _ in range(intentos):
            cola = self.bo("GET", "/compliance/verifications?size=500") or {}
            v = next((x for x in cola.get("content", []) if str(x.get("prospectId")) == str(prospect_id)), None)
            if v:
                return v
            time.sleep(pausa)
        return None

    def despeja(self, prospect_id, datos=None) -> bool:
        v = self._en_la_cola(prospect_id)
        if not v:
            return False
        vid = v["verificationId"]
        self.bo("GET", f"/compliance/verifications/{vid}")   # the officer reads the file before deciding
        if v.get("screeningStatus") == "CONFIRMED_MATCH":
            return False                                     # what the control exists to stop
        if v.get("screeningStatus") in ("POTENTIAL_MATCH", "PENDING"):
            v = self.bo("POST", f"/compliance/verifications/{vid}/adjudicate",
                        {"decision": "CLEARED", "notes": self.NOTA_LISTAS}) or v
        if v.get("identityStatus") != "VERIFIED":
            v = self.bo("POST", f"/compliance/verifications/{vid}/identity",
                        {"decision": "VERIFIED", "notes": self.NOTA_IDENTIDAD}) or v
        return v.get("identityStatus") == "VERIFIED" and v.get("screeningStatus") == "CLEARED"


def psql(sql: str) -> list[list[str]]:
    r = subprocess.run(PG + ["-c", sql], capture_output=True, text=True, check=True)
    return [l.split(",") for l in r.stdout.splitlines() if l]


def login(credenciales):
    return http("POST", f"{BACKOFFICE}/auth/staff/login", {"email": credenciales[0], "password": credenciales[1]})["accessToken"]


def login_admin():
    return login(ADMIN)


# ── The clock ──────────────────────────────────────────────────────────────────────────────────

# The simulated time of day. Every service is set to the same moment, so the run does not depend on the hour it is
# launched: the operations of a day happen at 10:00 (before the 18:00 SPEI cutoff), the night at 00:05 of the next
# day, as the real chain does. Shifting only the date kept the real time of day: launched at 20:00, every SPEI
# movement of the run was after the cutoff.
HORA_DEL_DIA = datetime.time(10, 0)
HORA_DE_LA_NOCHE = datetime.time(0, 5)


def reloj(fecha: datetime.date, hora: datetime.time = HORA_DEL_DIA, intentos: int = 5):
    """Now is `fecha` at `hora` (Mexico City) in every service that stamps business facts.

    Setting the clock is idempotent, so a connection that drops is retried: right after the environment starts (or
    the bridge comes up) a service may still close the first call without answering, and a demo of half an hour used
    to die there (3 Nov). Other calls are not retried: a payment that dropped may have been applied.
    """
    for s in CON_RELOJ:
        for intento in range(1, intentos + 1):
            try:
                svc(s, "POST", f"/internal/test-support/clock?at={fecha.isoformat()}T{hora.strftime('%H:%M:%S')}")
                break
            except (ConnectionError, urllib.error.URLError, OSError) as e:
                if intento == intentos:
                    raise
                print(f"  ! {s} no contestó al mover el reloj ({e.__class__.__name__}); reintento {intento}")
                time.sleep(3 * intento)


def reloj_real():
    for s in CON_RELOJ:
        try:
            svc(s, "DELETE", "/internal/test-support/clock")
        except Exception as e:  # noqa: BLE001 — a service that is down must not hide the others
            print(f"  ! no pude regresar el reloj de {s}: {e}")


# ── The run ────────────────────────────────────────────────────────────────────────────────────

class Corrida:
    """State of one run: scenarios, their accounts, and the log of everything that happened."""

    def __init__(self, inicio: datetime.date, fin: datetime.date, semilla: int, evidencia: pathlib.Path,
                 escenarios: dict, verbose=True, personal=None, fondeo=None):
        self.inicio, self.fin, self.semilla = inicio, fin, semilla
        self.fondeo = fondeo or FONDEO_E2E
        self.evidencia = evidencia
        self.escenarios = escenarios
        self.rnd = random.Random(semilla)
        self.verbose = verbose
        self.estado: dict[str, dict] = {}       # clave → {cuenta, prospect, token, ...}
        self.bitacora: list[dict] = []
        self.hoy: datetime.date = inicio
        self.bo = SesionBackoffice()
        self.personal = {rol: SesionBackoffice(cred) for rol, cred in (personal or PERSONAL).items()}
        # The run's own sign-ups (altas.py): compliance by the officer, decisions by the committee, all through the
        # console; nothing of the demo seed scripts is imported.
        self.cumplimiento = OficialDeCumplimiento(self.personal["cumplimiento"])
        self.altas = altas.Altas(self.cumplimiento, self.personal["comite"], self.bo)
        self._contador = 0
        evidencia.mkdir(parents=True, exist_ok=True)

    # ── logging ──
    def anota(self, clave, accion, detalle="", **datos):
        fila = {"fecha": self.hoy.isoformat(), "dia": (self.hoy - self.inicio).days, "escenario": clave,
                "accion": accion, "detalle": detalle, **datos}
        self.bitacora.append(fila)
        if self.verbose or accion in ("noche", "error", "alta", "facturación", "cierre contable", "comisiones", "quebranto",
                                      "reestructura", "fondeo"):
            extra = " ".join(f"{k}={v}" for k, v in datos.items() if k not in ("fecha",))
            print(f"  {self.hoy} {clave:>4} {accion:<18} {detalle} {extra}".rstrip())
        with open(self.evidencia / "bitacora.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(fila, ensure_ascii=False, default=str) + "\n")

    def guarda_estado(self):
        with open(self.evidencia / "estado.json", "w", encoding="utf-8") as f:
            json.dump({"inicio": self.inicio.isoformat(), "fin": self.fin.isoformat(), "semilla": self.semilla,
                       "hoy": self.hoy.isoformat(), "escenarios": self.estado}, f, ensure_ascii=False, indent=1, default=str)

    # ── onboarding ──
    def _persona(self, etiqueta: str, e: dict | None = None) -> altas.Persona:
        self._contador += 1
        p = altas.Persona(self.rnd, etiqueta, self._contador)
        if e and e.get("buro") == "limite":
            # The simulated bureau's borderline profile: the scoring policy sends the application to human review by
            # itself. The scenario picks the bureau's answer, never the system's decision.
            p.con_buro_en_el_limite()
        return p

    def _alta_con_ejecutivo(self, clave: str, p: altas.Persona) -> bool:
        """Signs a person up and gives it the executive of its branch (the run's own structure)."""
        try:
            if not self.altas.alta(p):
                self.anota(clave, "error", "el alta no terminó (OTP, KYC, registro o cumplimiento)", persona=p.nombre_completo)
                return False
        except urllib.error.HTTPError as ex:
            self.anota(clave, "error", f"alta: HTTP {ex.code} {ex.read().decode(errors='replace')[:200]}", persona=p.nombre_completo)
            return False
        ejecutivo = self.altas.ejecutivo_de(p, self._contador)
        if not self.altas.asigna_ejecutivo(p, ejecutivo):
            self.anota(clave, "error", "no se le pudo asignar el ejecutivo de su sucursal", persona=p.nombre_completo)
        return True

    def alta_persona(self, clave: str, e: dict):
        p = self._persona("cliente", e)
        if not self._alta_con_ejecutivo(clave, p):
            return
        a_la_mesa = e.get("desenlace") in ("rechazada", "en_mesa")
        desenlace = self.altas.solicita(p, e["producto"], e.get("monto"), e.get("plazo", 12), bnpl_dias=e.get("bnpl"),
                                        parar_en_mesa=a_la_mesa)
        st = {"tipo": "persona", "persona": p.nombre_completo, "prospect": p.prospect_id, "obligor": p.prospect_id,
              "party": p.party_id, "sucursal": p.sucursal, "phone": p.phone, "token": p.token, "token_at": time.time(),
              "application": p.application_id, "producto": e["producto"], "monto": e.get("monto"), "plazo": e.get("plazo", 12),
              "desenlace": desenlace, "pagos": [], "compras": []}
        self.estado[clave] = st
        if a_la_mesa and desenlace != "en_mesa":
            self.anota(clave, "error", f"la política no la mandó a la mesa: la solicitud terminó en «{desenlace}»",
                       persona=p.nombre_completo, solicitud=p.application_id)
            return
        if e.get("desenlace") == "rechazada":
            try:
                self.personal["comite"]("POST", f"/origination/applications/{p.application_id}/decision",
                                        {"approved": False, "rejectionReason": "Capacidad de pago insuficiente"})
                st["desenlace"] = "rechazada por la mesa"
            except HttpError as ex:
                st["desenlace"] = f"no se pudo rechazar: {ex}"
            self.anota(clave, "solicitud", st["desenlace"], persona=p.nombre_completo, solicitud=p.application_id)
            return
        if e.get("desenlace") == "en_mesa":
            self.anota(clave, "solicitud", "la política la mandó a la mesa de análisis; sigue sin decidir",
                       persona=p.nombre_completo, solicitud=p.application_id)
            return
        if desenlace != "desembolsado":
            self.anota(clave, "error", f"la solicitud terminó en «{desenlace}»", persona=p.nombre_completo)
            return
        c = self.altas.cuenta(p, e["producto"]) or {}
        st["cuenta"] = c.get("creditAccountId") or c.get("id")
        detalle = self.cuenta(st["cuenta"]) if st["cuenta"] else {}
        st["contrato"] = detalle.get("contractNumber")
        self.anota(clave, "alta", f"{e['producto']} {e.get('monto') or detalle.get('creditLimit') or 0:,} × {e.get('plazo', 12)} activado",
                   persona=p.nombre_completo, sucursal=p.sucursal, cuenta=st["cuenta"], contrato=st.get("contrato"),
                   limite=detalle.get("creditLimit"), tasa=detalle.get("nominalRate"))

    def cobro_de_comisiones(self, clave: str, d, e: dict | None = None):
        """Who the distributor's commissions are paid to: her fiscal profile (party publishes it to commission; the RFC
        came with KYC) and the CLABE her lots are paid to by SPEI, the account she signs the line with. The scenario's
        `regimen` (626 is RESICO) or else the regime of business and professional activities (612): a natural person
        has ISR and two thirds of the IVA withheld from each lot. Without either, her lots wait and her commissions
        stay accrued."""
        regimen = (e or {}).get("regimen") or ("626" if clave == "E39" else "612")
        perfil = altas.interno("party-service", f"/api/v1/parties/{d.party_id}/fiscal-profile", method="PUT",
                               body={"taxName": altas.sin_acentos(d.nombre_completo).upper(), "taxRegime": regimen,
                                     "taxZipCode": d.codigo_postal, "cfdiUse": "G03"})
        if perfil is None:
            self.anota(clave, "error", "party no registró el perfil fiscal de la distribuidora", persona=d.nombre_completo)
        try:
            cuenta = svc("commission-service", "PUT",
                         f"/api/v1/commissions/beneficiaries/{d.prospect_id}/payout-account",
                         {"clabe": d.clabe, "holderName": d.nombre_completo})
        except HttpError as e:
            self.anota(clave, "error", f"commission no registró la CLABE de pago de comisiones: {e}",
                       persona=d.nombre_completo)
            return
        if perfil is not None and cuenta:
            self.anota(clave, "comisiones", f"perfil fiscal (régimen {regimen}) y CLABE de pago de comisiones",
                       persona=d.nombre_completo, clabe=d.clabe)

    def alta_distribuidora(self, clave: str, e: dict):
        d = self._persona("distribuidora")
        if not self._alta_con_ejecutivo(clave, d):
            return
        ejecutivo = self.altas.ejecutivo_de(d, self._contador)
        if not self.altas.distribuidora(d, f"D_{clave}", ejecutivo):
            self.anota(clave, "error", "la distribuidora no pudo darse de alta (rol, nodo o ejecutivo)", persona=d.nombre_completo)
            return
        self.cobro_de_comisiones(clave, d, e)
        # Her own placement policy, stricter than her branch's (only the backoffice declares it, on her node).
        if e.get("politica_propia"):
            r = self.altas.admin("PUT", f"/sales-org/units/by-code/D_{clave}/placement-policy", e["politica_propia"]) or {}
            self.anota(clave, "política propia", f"más estricta que su sucursal: viene de {r.get('inheritedFrom')}",
                       politica=json.dumps(r.get("effective"), ensure_ascii=False))
        # The line's credit decision is the committee's, with its own session; the scenario may ask for its size.
        desenlace = self.altas.solicita(d, "DISTRIBUTOR_LINE", monto=e.get("linea"), promotor=d.prospect_id)
        linea = self.altas.cuenta(d, "DISTRIBUTOR_LINE") if desenlace == "desembolsado" else None
        if not linea:
            self.anota(clave, "error", f"la línea no se activó ({desenlace})", persona=d.nombre_completo)
            return
        beneficiarios = []
        for i in range(e.get("beneficiarios", 0)):
            b = self._persona("beneficiario")
            ok = False
            try:
                ok = self.altas.alta(b) and self.altas.beneficiario(d, b)
            except urllib.error.HTTPError:
                ok = False
            if ok:
                beneficiarios.append({"persona": b.nombre_completo, "prospect": b.prospect_id, "party": b.party_id})
                self.anota(clave, "beneficiario", f"{b.nombre_completo} con expediente y vínculo", indice=i)
            else:
                self.anota(clave, "error", f"el cliente {b.nombre_completo} no pudo registrarse", indice=i)
        cuenta = linea.get("creditAccountId") or linea.get("id")
        self.estado[clave] = {"tipo": "distribuidora", "persona": d.nombre_completo, "prospect": d.prospect_id,
                              "obligor": d.prospect_id, "party": d.party_id, "sucursal": d.sucursal, "phone": d.phone,
                              "token": d.token, "token_at": time.time(), "cuenta": cuenta,
                              "linea": float(linea.get("creditLimit") or 0), "producto": e["producto"],
                              "beneficiarios": beneficiarios, "pagos": [], "compras": []}
        detalle = self.cuenta(cuenta)
        self.estado[clave]["contrato"] = detalle.get("contractNumber")
        self.anota(clave, "alta", f"línea de distribuidora {float(linea.get('creditLimit') or 0):,.0f} activada",
                   persona=d.nombre_completo, sucursal=d.sucursal, cuenta=cuenta, contrato=detalle.get("contractNumber"),
                   tasa=detalle.get("nominalRate"))

    # ── sessions ──
    def token_de(self, st: dict) -> str | None:
        """The holder's app session; renewed with phone and password when the 15-minute token is old."""
        if st.get("token") and time.time() - float(st.get("token_at") or 0) < 600:
            return st["token"]
        if not st.get("phone") and st.get("prospect"):
            filas = psql(f"SELECT phone FROM origination.prospects WHERE prospect_id = '{st['prospect']}'")
            # Origination keeps it in E.164 (+52 and ten digits); the app's login asks for the ten digits. Read raw,
            # every login of a demo account answered 400 and its purchases and placements went out with no session.
            crudo = "".join(ch for ch in (filas[0][0] if filas and filas[0] else "") if ch.isdigit())
            st["phone"] = crudo[-10:] if len(crudo) >= 10 else None
        if not st.get("phone"):
            return st.get("token")
        try:
            s = altas.http("POST", f"{MOBILE}/auth/login", {"phone": st["phone"], "password": altas.PASSWORD}) or {}
            st["token"], st["token_at"] = s.get("token") or s.get("accessToken"), time.time()
        except urllib.error.HTTPError as e:
            print(f"  ! no se pudo abrir la sesión de la app de {st.get('persona') or st.get('prospect')}: HTTP {e.code}")
        return st.get("token")

    # ── reads ──
    def cuenta(self, cuenta_id):
        return svc("credit-portfolio-service", "GET", f"/api/v1/portfolio/accounts/{cuenta_id}") or {}

    def plan(self, cuenta_id):
        return svc("credit-portfolio-service", "GET", f"/api/v1/portfolio/accounts/{cuenta_id}/amortization-schedule") or []

    def estados_de_cuenta(self, cuenta_id):
        return svc("statement-service", "GET", f"/api/v1/statements?creditAccountId={cuenta_id}") or []

    def caso(self, cuenta_id):
        try:
            return self.bo("GET", f"/collections/accounts/{cuenta_id}/case")
        except HttpError as e:
            if e.code == 404:
                return None
            raise

    # ── money ──
    def pagar(self, clave, monto: float, motivo: str, recuperacion=False):
        """
        The client pays by SPEI from their bank to their collection CLABE, naming the loan with its payment reference:
        the simulated provider gives the transfer its tracking key and SPEI operation date and notifies the connector
        through the same port as STP's webhook. The money then follows the production path: inbox → decision →
        stp.credit-received → payments (prelation, balance in favor) → portfolio. A recovery names its written-off loan.
        """
        st = self.estado[clave]
        deuda = float(self.cuenta(st["cuenta"]).get("totalDebt") or 0)
        if float(monto) > deuda and not recuperacion:
            # A written-off account owes nothing on the books: what it pays is a recovery, not capped by the debt.
            motivo += f" (pedía {float(monto):,.2f}; se paga el adeudo)"
            monto = deuda
        monto = round(float(monto), 2)
        if monto <= 0:
            return None
        clabe, referencia = self.cobro_de(st)
        if not clabe:
            self.anota(clave, "pago rechazado", f"{motivo}: la persona no tiene CLABE de cobro", monto=monto)
            return None
        try:
            r = svc("stp-service", "POST", "/internal/test-support/spei/abonos",
                    {"clabe": clabe, "amount": monto, "numericReference": referencia, "paymentType": 1,
                     "payerName": "CLIENTE " + clave, "payerAccount": st.get("clabe") or "012180001234567891",
                     "payerInstitution": 40012, "concept": motivo[:39]}) or {}
        except HttpError as e:
            self.anota(clave, "pago rechazado", f"{motivo}: {e}", monto=monto)
            return None
        if r.get("decision") != "CONFIRMED":
            self.anota(clave, "pago rechazado", f"{motivo}: STP lo devolvió con causa {r.get('returnCause')}", monto=monto)
            return None
        orden = self.orden_del_abono(r["inboundCreditId"], st["cuenta"])
        st["pagos"].append({"fecha": self.hoy.isoformat(), "monto": monto, "motivo": motivo, "orden": orden,
                            "abono": r["inboundCreditId"], "rastreo": r["trackingKey"], "operacion": r["operationDate"]})
        self.anota(clave, "pago", motivo, monto=monto, orden=orden, rastreo=r["trackingKey"],
                   operacion=r["operationDate"])
        return orden

    def cobro_de(self, st, intentos=60, pausa=0.5):
        """The person's collection CLABE and the loan's payment reference. The CLABE is assigned asynchronously when
        the first loan is activated (payments asks the connector), so it is waited for."""
        referencia = None
        for _ in range(intentos):
            fila = psql(f"""SELECT (SELECT clabe FROM stp.collection_accounts WHERE party_id = '{st["obligor"]}'),
                                   (SELECT payment_reference FROM credit_portfolio.credit_accounts
                                     WHERE credit_account_id = '{st["cuenta"]}')""")[0]
            clabe, referencia = fila[0] or None, (int(fila[1]) if fila[1] else None)
            if clabe:
                return clabe, referencia
            time.sleep(pausa)
        return None, referencia

    def orden_del_abono(self, abono: str, cuenta: str, intentos=80, pausa=0.25):
        """The payment order with which payments applied this credit to this loan (the outbox publishes the credit
        within seconds); None if the credit went elsewhere (another loan by seniority, or in favor)."""
        for _ in range(intentos):
            filas = psql(f"""SELECT (SELECT count(*) FROM payments.received_credits WHERE inbound_credit_id = '{abono}'),
                                    (SELECT payment_order_id FROM payments.payment_orders
                                      WHERE inbound_credit_id = '{abono}' AND credit_account_id = '{cuenta}'
                                      ORDER BY allocation_seq LIMIT 1)""")
            aplicado, orden = int(filas[0][0]), (filas[0][1] or None)
            if aplicado:
                return orden
            time.sleep(pausa)
        self.anota("", "asentamiento", f"payments no aplicó el abono {abono} en {intentos * pausa:.0f} s")
        return None

    def devolver_pago(self, clave, orden: str, motivo: str):
        svc("payments-service", "POST", f"/api/v1/payments/{orden}/reverse", {"reason": motivo})
        self.anota(clave, "pago devuelto", motivo, orden=orden)

    def comprar(self, clave, monto: float, plazo=None):
        st = self.estado[clave]
        cuerpo = {"creditAccountId": st["cuenta"], "amount": monto}
        if plazo:
            cuerpo["termPeriods"] = plazo
        antes = self._disposiciones(st["cuenta"])
        altas.http("POST", f"{MOBILE}/credit/dispose", cuerpo, token=self.token_de(st))
        if not self._confirma_disposicion(clave, st, monto, antes):
            return
        st["compras"].append({"fecha": self.hoy.isoformat(), "monto": monto, "plazo": plazo})
        self.anota(clave, "compra", f"disposición de la línea{f' a {plazo} meses' if plazo else ''}", monto=monto)

    def _disposiciones(self, cuenta: str) -> int:
        return int(psql(f"SELECT count(*) FROM credit_portfolio.dispositions WHERE credit_account_id = '{cuenta}'")[0][0])

    def _confirma_disposicion(self, clave, st, monto: float, antes: int, que: str = "", intentos=60, pausa=0.3) -> bool:
        """
        The portfolio decides a disposition after the channel accepted it (the product's limits, the arrears gate, AML):
        a refusal arrives as an event, never as the channel's answer. Counted as bought before the portfolio created it,
        a refused placement left the run waiting 18 s a day for a disposition that never came, and the verdict never
        heard of the refusal (dist5: two placements with cents, refused by an amount step the portfolio enforced).
        """
        for _ in range(intentos):
            if self._disposiciones(st["cuenta"]) > antes:
                return True
            time.sleep(pausa)
        st["colocaciones_rechazadas"] = st.get("colocaciones_rechazadas", 0) + 1
        self.anota(clave, "colocación rechazada", f"{que + ': ' if que else ''}cartera no la autorizó: "
                   f"{self._motivo_de_rechazo(st['cuenta'], monto)}", monto=monto)
        return False

    def _motivo_de_rechazo(self, cuenta: str, monto: float) -> str:
        """The portfolio's own word on a disposition it refused, from its log (it publishes it only as an event)."""
        r = subprocess.run(["docker", "logs", "--since", "10m", "fintech-services-credit-portfolio-service-1"],
                           capture_output=True, text=True)
        for linea in reversed((r.stdout + r.stderr).splitlines()):
            if "Disposition rejected" in linea and cuenta in linea:
                m = re.search(r"amount=([0-9.]+) reason=(.*?)(\",|$)", linea)
                if m and abs(float(m.group(1)) - float(monto)) < 0.005:
                    return m.group(2)
        return "sin motivo en el log de cartera"

    def diferir(self, clave, monto: float, meses: int):
        st = self.estado[clave]
        for _ in range(20):
            token = self.token_de(st)
            compras = altas.http("GET", f"{MOBILE}/credit/dispositions", token=token) or []
            compras = compras if isinstance(compras, list) else compras.get("content", [])
            d = next((x for x in compras if float(x.get("amount") or 0) == float(monto) and x.get("dispositionId")), None)
            if d:
                try:
                    altas.http("POST", f"{MOBILE}/credit/dispositions/{d['dispositionId']}/defer", {"termPeriods": meses}, token=token)
                    self.anota(clave, "compra diferida", f"a {meses} meses", monto=monto, disposicion=d["dispositionId"])
                except urllib.error.HTTPError as ex:
                    self.anota(clave, "compra diferida", f"rechazada: HTTP {ex.code} {ex.read().decode(errors='replace')[:200]}",
                               monto=monto, disposicion=d["dispositionId"], compra=json.dumps(d, default=str)[:300])
                return
            time.sleep(1)
        self.anota(clave, "compra diferida", "no encontré la compra para diferirla", monto=monto)

    def colocar(self, clave, indice: int, monto: float, plazo: int):
        st = self.estado[clave]
        b = st["beneficiarios"][indice]
        antes = self._disposiciones(st["cuenta"])
        try:
            # Placing is disposing the line in the client's name: the app's endpoint, with the distributor's session.
            altas.http("POST", f"{MOBILE}/credit/dispose", {"amount": monto, "dispositionType": "THIRD_PARTY_CREDIT",
                         "beneficiaryPartyId": b["prospect"], "termPeriods": plazo}, token=self.token_de(st))
        except urllib.error.HTTPError as e:
            # Refused at the door (the line blocked by arrears, an amount or a term outside the product): nothing was
            # placed, so nothing waits to be settled. The verdict counts these against what the scenario expects.
            detalle = e.read().decode(errors="replace")[:200]
            st["colocaciones_rechazadas"] = st.get("colocaciones_rechazadas", 0) + 1
            self.anota(clave, "colocación rechazada", f"a {b['persona']} a {plazo} quincenas: HTTP {e.code} {detalle}",
                       monto=monto)
            return
        # Accepted at the door, decided by the portfolio: placed only once the disposition exists.
        if not self._confirma_disposicion(clave, st, monto, antes, f"a {b['persona']} a {plazo} quincenas"):
            return
        st["compras"].append({"fecha": self.hoy.isoformat(), "monto": monto, "plazo": plazo, "beneficiario": b["persona"]})
        self.anota(clave, "colocación", f"a {b['persona']} a {plazo} quincenas", monto=monto)

    # ── her clients: the red list, their own payments ──
    def _clientes(self, clave, e, st, dia):
        """Her reports to the red list and their clearing, a client paying his installments with his own reference, and
        a deposit to her CLABE with a reference that is nobody's."""
        for indice, motivo, comentario in e.get("reportes", {}).get(dia, []):
            self.reporta(clave, st, indice, motivo, comentario)
        for indice in e.get("levanta", {}).get(dia, []):
            self.levanta(clave, st, indice)
        for indice, monto in e.get("pagos_cliente", {}).get(dia, []):
            self.paga_cliente(clave, st, indice, monto)
        monto = e.get("referencia_ajena", {}).get(dia)
        if monto:
            self.pago_con_referencia_ajena(clave, st, monto)

    def reporta(self, clave, st, indice, motivo, comentario):
        b = st["beneficiarios"][indice]
        try:
            r = altas.http("POST", f"{MOBILE}/beneficiaries/{b['prospect']}/reports", {"reason": motivo, "comment": comentario},
                           token=self.token_de(st)) or {}
        except urllib.error.HTTPError as ex:
            self.anota(clave, "error", f"reporte de {b['persona']}: HTTP {ex.code} {ex.read().decode(errors='replace')[:200]}")
            return
        st.setdefault("reportes", {})[str(indice)] = r.get("flagId")
        self.anota(clave, "reporte a lista roja", f"{b['persona']}: {motivo} «{comentario}»", marca=r.get("flagId"))

    def levanta(self, clave, st, indice):
        b = st["beneficiarios"][indice]
        marca = (st.get("reportes") or {}).get(str(indice))
        if not marca:
            self.anota(clave, "error", f"no hay reporte de {b['persona']} que levantar")
            return
        try:
            altas.http("POST", f"{MOBILE}/beneficiaries/{b['prospect']}/reports/{marca}/clear",
                       {"comment": "Ya lo localicé y se puso de acuerdo conmigo"}, token=self.token_de(st))
        except urllib.error.HTTPError as ex:
            self.anota(clave, "error", f"levantar el reporte de {b['persona']}: HTTP {ex.code} {ex.read().decode(errors='replace')[:200]}")
            return
        self.anota(clave, "reporte levantado", b["persona"], marca=marca)

    def referencia_del_cliente(self, st, indice) -> int | None:
        b = st["beneficiarios"][indice]
        fila = psql(f"""SELECT payment_reference FROM credit_portfolio.client_payment_references
                         WHERE credit_account_id = '{st["cuenta"]}' AND beneficiary_party_id = '{b["prospect"]}'""")
        return int(fila[0][0]) if fila and fila[0] and fila[0][0] else None

    def paga_cliente(self, clave, st, indice, monto):
        """Her client deposits to HER collection CLABE with HIS reference: his receipt's total ("recibo") or an amount.
        The deposit pays his installments first; she then delivers her statement less what he already paid."""
        b = st["beneficiarios"][indice]
        referencia = self.referencia_del_cliente(st, indice)
        recibos = svc("receipt-service", "GET", f"/api/v1/receipts?creditAccountId={st['cuenta']}&partyId={b['prospect']}&limit=1") or []
        recibo = recibos[0] if recibos else None
        # He pays where his receipt tells him to: a receipt that printed another CLABE sends his money elsewhere
        # (STP returns it and the run says so). Without a receipt yet, her collection CLABE.
        clabe = (recibo or {}).get("clabe") or self.cobro_de(st)[0]
        if monto == "recibo":
            monto = float(recibo["totalDue"]) if recibo and recibo.get("totalDue") is not None else 0.0
        monto = round(float(monto), 2)
        if not referencia or not clabe or monto <= 0:
            self.anota(clave, "error", f"pago directo de {b['persona']}: referencia {referencia}, CLABE {clabe}, monto {monto}")
            return
        r = svc("stp-service", "POST", "/internal/test-support/spei/abonos",
                {"clabe": clabe, "amount": monto, "numericReference": referencia, "paymentType": 1,
                 "payerName": b["persona"].upper()[:39], "payerAccount": "012180009876543210", "payerInstitution": 40012,
                 "concept": "PAGO DE MI VALE"}) or {}
        if r.get("decision") != "CONFIRMED":
            self.anota(clave, "error", f"pago directo de {b['persona']}: STP lo devolvió con causa {r.get('returnCause')}")
            return
        orden = self.orden_del_abono(r["inboundCreditId"], st["cuenta"])
        ciclo = int(recibo["cycleNumber"]) if recibo else None
        # What he paid of her statement: she delivers that statement less it (the motor counts it like her own payment).
        if ciclo is not None:
            st.setdefault("pagos_por_corte", []).append({"ciclo": ciclo, "fecha": self.hoy.isoformat(), "monto": monto, "cliente": True})
        st.setdefault("pagos_cliente", []).append({"fecha": self.hoy.isoformat(), "cliente": b["prospect"], "monto": monto,
                                                   "referencia": referencia, "orden": orden, "ciclo": ciclo})
        self.anota(clave, "pago directo del cliente", f"{b['persona']} con su referencia {referencia}"
                   + (f" (su recibo del corte {ciclo})" if ciclo else ""), monto=monto, orden=orden)

    def pago_con_referencia_ajena(self, clave, st, monto):
        """A deposit to her CLABE with a valid reference that names no loan and no client: it is hers, by the rule."""
        clabe, _ = self.cobro_de(st)
        r = svc("stp-service", "POST", "/internal/test-support/spei/abonos",
                {"clabe": clabe, "amount": round(float(monto), 2), "numericReference": 9999996, "paymentType": 1,
                 "payerName": "PERSONA SIN REFERENCIA", "payerAccount": "012180001111111111", "payerInstitution": 40012,
                 "concept": "PAGO"}) or {}
        if r.get("decision") != "CONFIRMED":
            self.anota(clave, "error", f"abono con referencia ajena: STP lo devolvió con causa {r.get('returnCause')}")
            return
        orden = self.orden_del_abono(r["inboundCreditId"], st["cuenta"])
        st.setdefault("referencia_ajena", []).append({"fecha": self.hoy.isoformat(), "monto": float(monto), "abono": r["inboundCreditId"]})
        # It paid part of the statement she is about to pay (the rule applies it to what is due first): she sees it
        # applied and delivers that much less, as with a client's direct payment. Paying it again would leave it ahead.
        # Once she paid it, the deposit stays ahead and her next statement already subtracts it.
        pendiente = max((int(s.get("cycleNumber") or 0) for s in self.estados_de_cuenta(st["cuenta"])
                         if s.get("cutoffDate") and str(s["cutoffDate"]) < self.hoy.isoformat()), default=None)
        ya_lo_pago = any(p.get("ciclo") == pendiente and not p.get("cliente") and not p.get("ajena")
                         for p in st.get("pagos_por_corte", []))
        if pendiente and not ya_lo_pago:
            st.setdefault("pagos_por_corte", []).append({"ciclo": pendiente, "fecha": self.hoy.isoformat(),
                                                         "monto": float(monto), "ajena": True})
        self.anota(clave, "abono con referencia ajena", "la referencia 9999996 no es de nadie: se aplica como pago de ella",
                   monto=float(monto), orden=orden)

    def saltar_cuota(self, clave, numero: int):
        st = self.estado[clave]
        token = self.token_de(st)
        plan = altas.http("GET", f"{MOBILE}/credit/schedule", token=token) or []
        cuota = next((i for i in plan if int(i.get("installmentNumber", 0)) == numero), None)
        if not cuota:
            self.anota(clave, "salto de pago", f"no existe la cuota {numero}")
            return
        try:
            altas.http("POST", f"{MOBILE}/credit/installments/{cuota.get('installmentId') or cuota.get('id')}/skip", None, token=token)
            self.anota(clave, "salto de pago", f"cuota {numero} saltada", vence=cuota.get("dueDate"))
        except urllib.error.HTTPError as e:
            self.anota(clave, "salto de pago", f"rechazado: HTTP {e.code} {e.read().decode(errors='replace')[:160]}")

    # ── collections, through the backoffice ──
    def contacto(self, clave, canal: str, resultado: str):
        """A collections agent registers a call or a visit on the case, through the backoffice (08:00–20:00, three a day)."""
        st = self.estado[clave]
        caso = self.caso(st["cuenta"])
        if not caso:
            return False
        try:
            self.personal["cobranza"]("POST", f"/collections/cases/{caso['caseId']}/contact-attempts",
                                      {"channel": canal, "result": resultado})
            self.anota(clave, "contacto de cobranza", f"{canal} · {resultado}", caso=caso["caseId"], dpd=caso.get("daysDelinquent"))
            return True
        except HttpError as e:
            self.anota(clave, "contacto de cobranza", f"rechazado: {e}", caso=caso["caseId"], estado=caso.get("status"))
            return False

    def promesa(self, clave, parte: float = 0.5):
        st = self.estado[clave]
        caso = self.caso(st["cuenta"])
        if not caso:
            return False
        deuda = float(caso.get("totalDebt") or self.cuenta(st["cuenta"]).get("totalDebt") or 0)
        cuerpo = {"amount": round(deuda * parte, 2), "promisedDate": (self.hoy + datetime.timedelta(days=7)).isoformat()}
        try:
            self.personal["cobranza"]("POST", f"/collections/cases/{caso['caseId']}/payment-promises", cuerpo)
            self.anota(clave, "promesa de pago", f"para el {cuerpo['promisedDate']}", monto=cuerpo["amount"], caso=caso["caseId"],
                       dpd=caso.get("daysDelinquent"))
            return True
        except HttpError as e:
            self.anota(clave, "promesa de pago", f"rechazada: {e}", caso=caso["caseId"], estado=caso.get("status"))
            return False

    def convenio(self, clave, tipo="RESTRUCTURE", quita_pct=None, tasa=0.24, meses=6, respuesta="acepta"):
        """
        A collections agreement, maker-checker: the agent proposes it and records that the debtor accepted; the
        committee authorizes it. RESTRUCTURE: 24 % and 6 more months. QUITA_PARCIAL: forgives `quita_pct` of the debt.
        `respuesta="ignora"`: the offer is only proposed; the debtor never answers and collections lets it expire.
        """
        st = self.estado[clave]
        caso = self.caso(st["cuenta"])
        if not caso or str(caso.get("status")).upper() not in ("MANAGED", "LEGAL"):
            return False
        accion = "reestructura" if tipo == "RESTRUCTURE" else "quita"
        if tipo == "RESTRUCTURE":
            cuerpo, detalle = ({"type": tipo, "newTerms": {"newNominalRate": tasa, "newTermMonths": meses}},
                               f"{tasa:.0%} y {meses} meses más")
        else:
            deuda = float(caso.get("totalDebt") or self.cuenta(st["cuenta"]).get("totalDebt") or 0)
            perdon = round(deuda * quita_pct, 2)
            cuerpo, detalle = {"type": tipo, "forgivenAmount": perdon}, f"se condonan {perdon:,.2f} ({quita_pct:.0%} del adeudo)"
        try:
            conv = self.personal["cobranza"]("POST", f"/collections/cases/{caso['caseId']}/agreements", cuerpo)
            aid = conv["agreementId"]
            if respuesta == "ignora":
                self.anota(clave, accion, f"propuesta por cobranza y sin respuesta de la deudora: {detalle}",
                           caso=caso["caseId"], convenio=aid, dpd=caso.get("daysDelinquent"))
                return True
            self.personal["cobranza"]("PUT", f"/collections/agreements/{aid}/accept")
            self.personal["comite"]("PUT", f"/collections/agreements/{aid}/authorize", {"authorizationRef": f"ACTA-{clave}"})
            self.anota(clave, accion, f"propuesta por cobranza, aceptada por el deudor y autorizada por el comité: {detalle}",
                       caso=caso["caseId"], convenio=aid, dpd=caso.get("daysDelinquent"))
            return True
        except HttpError as e:
            self.anota(clave, accion, f"rechazada: {e}", caso=caso["caseId"], estado=caso.get("status"))
            return False

    def quebranto(self, clave):
        st = self.estado[clave]
        caso = self.caso(st["cuenta"])
        if not caso:
            return False
        # The committee writes off only what collections allows: below the threshold the request would be
        # rejected, so the case waits, quietly, until the account's own delinquency gets there.
        if int(caso.get("daysDelinquent") or 0) < UMBRAL_QUEBRANTO:
            return False
        try:
            self.personal["cobranza"]("POST", f"/collections/cases/{caso['caseId']}/request-write-off", {"reason": "UNRECOVERABLE"})
            self.personal["comite"]("POST", f"/collections/cases/{caso['caseId']}/write-offs",
                                    {"reason": "UNRECOVERABLE", "authorizationRef": f"QUEBRANTO-{clave}"})
            self.anota(clave, "quebranto", "solicitado por cobranza y autorizado por el comité", caso=caso["caseId"],
                       dpd=caso.get("daysDelinquent"), deuda=caso.get("totalDebt"))
            st["quebranto_dia"] = (self.hoy - self.inicio).days
            return True
        except HttpError as e:
            self.anota(clave, "quebranto", f"rechazado: {e}", caso=caso["caseId"], estado=caso.get("status"),
                       dpd=caso.get("daysDelinquent"))
            return False

    # ── the day's behaviour, from the plan ──
    def operaciones_del_dia(self):
        dia = (self.hoy - self.inicio).days
        pendiente = getattr(self, "pago_fondeo", None)
        if pendiente and pendiente[0] == self.hoy:
            self.pago_fondeo = None
            self.paga_intereses_del_fondeo(self.hoy, pendiente[1])
        # Collections' daily jobs at 10:00 of business time, as the cron runs them: the automatic cycle (reminder, how
        # to pay, late, history, help) only works from 08:00 to 20:00, and run at night it never sent a message nor
        # registered a contact (every distributor run up to 5 Nov had zero contacts).
        svc("collections-service", "POST", "/internal/test-support/run-daily-jobs", timeout=300)
        for clave, e in self.escenarios.items():
            if e.get("alta") == dia and clave not in self.estado:
                try:
                    (self.alta_distribuidora if e["producto"] == "DISTRIBUTOR_LINE" else self.alta_persona)(clave, e)
                except Exception as ex:  # noqa: BLE001 — one failed onboarding must not stop the other scenarios
                    self.anota(clave, "error", f"alta: {type(ex).__name__}: {str(ex)[:300]}")
        for clave, e in self.escenarios.items():
            st = self.estado.get(clave)
            if not st or not st.get("cuenta"):
                continue
            try:
                self._comportamiento(clave, e, st, dia)
            except HttpError as ex:
                self.anota(clave, "error", str(ex))
            except urllib.error.HTTPError as ex:
                # The app's path (seed helpers) raises urllib errors: a domain rejection is a finding, not a crash.
                self.anota(clave, "error", f"HTTP {ex.code} {ex.geturl()}: {ex.read().decode(errors='replace')[:300]}")

    def programa_de_apoyo(self, clave, e):
        """
        A relief program for the scenario's product, maker-checker: risk proposes it and reads the roster; the
        administration authorizes and grants it. Eligibility is "current the day before", as the domain requires.
        """
        # The program reaches accounts by product type (what `ReliefProgram.reaches` compares), not by product code.
        producto = e["producto"]
        periodos = e.get("apoyo_periodos", 2)
        try:
            prog = self.personal["riesgo"]("POST", "/relief-programs", {
                "name": f"Apoyo por contingencia · {producto}", "reason": "NATURAL_DISASTER", "deferredPeriods": periodos,
                "validFrom": self.hoy.isoformat(), "validTo": (self.hoy + datetime.timedelta(days=60)).isoformat(),
                "productCode": producto, "maxDaysDelinquent": 30,
                "eligibilityCutoffDate": (self.hoy - datetime.timedelta(days=1)).isoformat(), "accrualDuringRelief": "ACCRUES"})
            pid = prog["reliefProgramId"]
            padron = self.personal["riesgo"]("GET", f"/relief-programs/{pid}/roster") or {}
            self.bo("POST", f"/relief-programs/{pid}/approve")
            otorgado = self.bo("POST", f"/relief-programs/{pid}/grant") or {}
            self.anota(clave, "programa de apoyo", f"propuesto por riesgo y autorizado por administración: {periodos} períodos de {producto}",
                       programa=pid, padron=padron.get("eligibleAccounts"), inscritas=otorgado.get("cuentasInscritas"))
            return True
        except HttpError as ex:
            self.anota(clave, "programa de apoyo", f"rechazado: {ex}")
            return False

    def _ponerse_al_dia_revolvente(self, clave, e, st, dia):
        """
        Pays the latest statement in full: its total carries everything the earlier ones asked and was not paid, with
        their moratorium, so the line is current again. Adding up every unpaid statement's total paid the oldest ones
        twice, since each total already carries the ones before it. The payment counts for that statement: its due
        date asks nothing more.
        """
        emitidos = [s for s in self.estados_de_cuenta(st["cuenta"]) if s.get("cutoffDate") and s["cutoffDate"] < self.hoy.isoformat()]
        if not emitidos:
            return
        ultimo = max(emitidos, key=lambda s: int(s.get("cycleNumber") or 0))
        monto = float(ultimo.get("amountDue") or 0)
        sin_pagar = len([s for s in emitidos if s.get("paymentDueDate")
                         and (self.inicio + datetime.timedelta(days=e.get("deja_de_pagar_desde_dia", 0))).isoformat()
                         <= s["paymentDueDate"] <= self.hoy.isoformat()])
        if monto <= 0:
            return
        # Within the latest statement's ladder she still keeps that statement's commission; the earlier ones earn nothing.
        comision, tasa = self.comision_al_pagar(st, ultimo, self.hoy)
        menos = f" (menos su comisión del {tasa:.0%} del corte {ultimo.get('cycleNumber')}: {comision:,.2f})" if comision > 0.005 else ""
        self.pagar(clave, round(monto - comision, 2), f"se pone al día: paga el total del corte {ultimo.get('cycleNumber')} del "
                                 f"{ultimo.get('cutoffDate')}, que trae {sin_pagar} estados de cuenta sin pagar y sus moratorios{menos}")
        st.setdefault("pagos_por_corte", []).append(
            {"ciclo": int(ultimo.get("cycleNumber") or 0), "fecha": self.hoy.isoformat(), "monto": monto})

    def _comportamiento(self, clave, e, st, dia):
        if st.get("quebranto_hecho"):
            # Written off: what it pays from now on is a recovery, through the payments channel.
            for despues, monto in (e.get("recupera") or {}).items():
                hechos = st.setdefault("recuperado", {})
                if dia == int(st.get("quebranto_dia", -10_000)) + int(despues) and not hechos.get(str(despues)):
                    self.pagar(clave, monto, f"pago tras el quebranto ({despues} días después)", recuperacion=True)
                    hechos[str(despues)] = True
            return
        # purchases and placements first: they move the balance the same day
        compra = e.get("compras", {}).get(dia)
        if compra:
            self.comprar(clave, *compra)
        # Her clients' side first: a report lands before a placement of the same day.
        self._clientes(clave, e, st, dia)
        for indice, monto, plazo in e.get("colocaciones", {}).get(dia, []):
            self.colocar(clave, indice, monto, plazo)
        if dia in e.get("difiere", {}):
            monto, meses = e["difiere"][dia]
            self.diferir(clave, monto, meses)
        if e.get("salta_cuota"):
            self._salto(clave, e, st)
        if e.get("apoyo_en") == dia and not st.get("apoyo_hecho"):
            st["apoyo_hecho"] = self.programa_de_apoyo(clave, e)
        if e.get("liquida_en") == dia:
            total = float(self.cuenta(st["cuenta"]).get("totalDebt") or 0)
            # Paid off between a cutoff and its due date, that statement asks nothing more on its due date; a
            # distributor keeps her commission on it, so she delivers the total less that commission.
            emitidos = [s for s in (self.estados_de_cuenta(st["cuenta"]) if st.get("cuenta") else [])
                        if s.get("cutoffDate") and s["cutoffDate"] < self.hoy.isoformat()]
            comision, tasa = (0.0, None)
            # Hers to pay until she pays it: what a client paid directly or a deposit by the rule paid only part of it.
            pendiente = next((s for s in sorted(emitidos, key=lambda s: -int(s.get("cycleNumber") or 0))
                              if not any(p["ciclo"] == int(s.get("cycleNumber") or 0) and p["fecha"] > str(s.get("cutoffDate"))
                                         and not p.get("cliente") and not p.get("ajena")
                                         for p in st.get("pagos_por_corte", []))), None)
            if pendiente is not None and st.get("tipo") == "distribuidora":
                comision, tasa = self.comision_al_pagar(st, pendiente, self.hoy)
            menos = f" (menos su comisión del {tasa:.0%} del corte {pendiente.get('cycleNumber')}: {comision:,.2f})" if comision > 0.005 else ""
            self.pagar(clave, round(total - comision, 2), "liquidación anticipada del adeudo total" + menos)
            if emitidos:
                st.setdefault("pagos_por_corte", []).append(
                    {"ciclo": max(int(s.get("cycleNumber") or 0) for s in emitidos), "fecha": self.hoy.isoformat(),
                     "monto": total})
            return
        if dia in e.get("al_dia_en", []):
            self._ponerse_al_dia(clave, st)
            return
        for c in e.get("contactos", []):
            # The collections agent's calls and visits, by days late: once each, through the backoffice.
            hechos = st.setdefault("contactos_hechos", [])
            llave = f"{c['dpd']}:{c['canal']}"
            if llave in hechos or dia < int(e.get("deja_de_pagar_desde_dia", 10 ** 6)):
                continue
            caso = self.caso(st["cuenta"])
            if caso and int(caso.get("daysDelinquent") or 0) >= int(c["dpd"]):
                if self.contacto(clave, c["canal"], c["resultado"]):
                    hechos.append(llave)
        if e.get("promesa_en") == dia or (e.get("promesa_en") and dia > e["promesa_en"] and not st.get("promesa_hecha") and dia < e["promesa_en"] + 15):
            st["promesa_hecha"] = self.promesa(clave, e.get("promesa_parte", 0.5))
        if e.get("convenio_en") and dia >= e["convenio_en"] and not st.get("convenio_hecho") and dia < e["convenio_en"] + 30:
            st["convenio_hecho"] = self.convenio(clave, tasa=e.get("convenio_tasa", 0.24), meses=e.get("convenio_meses", 6),
                                                 respuesta=e.get("convenio_respuesta", "acepta"))
        if e.get("quita_en") and dia >= e["quita_en"] and not st.get("quita_hecha") and dia < e["quita_en"] + 30:
            st["quita_hecha"] = self.convenio(clave, "QUITA_PARCIAL", e.get("quita_pct", 0.25))
        if e.get("quebranto_en") is not None and dia >= e["quebranto_en"] and not st.get("quebranto_hecho"):
            st["quebranto_hecho"] = self.quebranto(clave)
            if st["quebranto_hecho"]:
                return
        if st.get("tipo") == "distribuidora" or e["producto"] in ("CREDIT_CARD", "REVOLVING_LINE", "BUSINESS_REVOLVING_LINE"):
            self._pago_revolvente(clave, e, st, dia)
        else:
            self._pago_cuotas(clave, e, st, dia)
        self._devolucion(clave, e, st)

    def _cuotas_pendientes(self, st):
        return [i for i in self.plan(st["cuenta"])
                if str(i.get("status", "")).upper() not in ("PAID", "WAIVED", "SKIPPED", "CANCELLED")]

    def _pago_cuotas(self, clave, e, st, dia):
        if not e.get("puntual"):
            return
        pendientes = sorted(self._cuotas_pendientes(st), key=lambda i: (i["dueDate"], i["installmentNumber"]))
        for cuota in pendientes:
            n = int(cuota["installmentNumber"])
            vence = datetime.date.fromisoformat(cuota["dueDate"])
            if e.get("desde_cuota_no_paga") and n >= e["desde_cuota_no_paga"] and not st.get("convenio_hecho"):
                return
            retraso = e.get("retrasos", {}).get(n, 0)
            if self.hoy == vence + datetime.timedelta(days=retraso):
                monto = float(cuota.get("totalAmount") or 0)
                if retraso:
                    monto += float(self.cuenta(st["cuenta"]).get("penaltyBalance") or 0)
                if len(pendientes) == 1:
                    monto = float(self.cuenta(st["cuenta"]).get("totalDebt") or monto)
                orden = self.pagar(clave, monto, f"cuota {n}" + (f" con {retraso} días de atraso (más moratorios)" if retraso else ""))
                if orden and e.get("devuelve_pago_de_cuota") == n:
                    st["devolver"] = {"orden": orden, "cuota": n, "dia": dia + 1, "monto": monto}
            if vence > self.hoy:
                break

    # ── the distributor's commission, netted from what she delivers ──
    def fecha_de_operacion(self, hoy: datetime.date) -> datetime.date:
        """The SPEI operation date of a payment made this morning: today on a banking day, the next one otherwise."""
        if not hasattr(self, "_inhabiles"):
            self._inhabiles = {f[0] for f in psql("SELECT day FROM closing.calendar_days WHERE calendar_code = 'MX' AND NOT is_business")}
        d = hoy
        while d.weekday() >= 5 or d.isoformat() in self._inhabiles:
            d += datetime.timedelta(days=1)
        return d

    def escalera(self, st, s: dict) -> list:
        """The distributor's ladder on a statement, from the portfolio's view: [{daysLate, rate, commission}] or []."""
        if st.get("tipo") != "distribuidora" or not s.get("periodStart") or not s.get("cutoffDate"):
            return []
        v = svc("credit-portfolio-service", "GET",
                f"/api/v1/portfolio/accounts/{st['cuenta']}/statement-view?from={s['periodStart']}&to={s['cutoffDate']}"
                f"&dueUntil={s['paymentDueDate']}") or {}
        if str(v.get("commissionSettlement") or "").upper() != "NETTED":
            return []
        return v.get("commissionLadder") or []

    def comision_al_pagar(self, st, s: dict, operacion: datetime.date) -> tuple:
        """What she deducts from a statement paid with money landing on `operacion` (the day it enters the portfolio, a Sunday
        included): (commission, rate), (0, None) if none."""
        pasos = self.escalera(st, s)
        if not pasos:
            return 0.0, None
        dias = max(0, (operacion - datetime.date.fromisoformat(s["paymentDueDate"])).days)
        paso = max((p for p in pasos if int(p["daysLate"]) <= dias), key=lambda p: int(p["daysLate"]), default=None)
        if paso is None:
            return 0.0, None
        return round(float(paso.get("commission") or 0), 2), float(paso.get("rate") or 0)

    def _pago_revolvente(self, clave, e, st, dia):
        if not e.get("puntual"):
            return
        deja, reanuda = e.get("deja_de_pagar_desde_dia"), e.get("reanuda_en")
        if reanuda is not None and dia == reanuda:
            self._ponerse_al_dia_revolvente(clave, e, st, dia)
            return
        if deja is not None and dia >= deja and (reanuda is None or dia < reanuda):
            return
        # `paga_tras_limite`: the days from the payment due date to the day it pays (negative pays before: -3 is two days
        # after a distributor's cutoff; within the grace days a late payment bears no moratorium).
        tras = datetime.timedelta(days=int(e.get("paga_tras_limite", 0)))
        estados = [s for s in self.estados_de_cuenta(st["cuenta"])
                   if s.get("paymentDueDate") and datetime.date.fromisoformat(s["paymentDueDate"]) + tras == self.hoy]
        for s in estados:
            reglas = e.get("paga", ["exigible"])
            regla = reglas[min(st.get("pagos_de_corte", 0), len(reglas) - 1)]
            monto = {"minimo": s.get("minimumPayment"), "exigible": s.get("amountDue"), "total": self.cuenta(st["cuenta"]).get("totalDebt")}[regla]
            # A statement issued before she paid the previous one carries it as pending: she pays its total minus what
            # she already paid, after its cutoff, for earlier statements (paying within the grace after a due date
            # moved to a Monday lands past the next cutoff, and a blind total would pay that statement twice).
            ya = sum(p["monto"] for p in st.get("pagos_por_corte", [])
                     if p["ciclo"] <= int(s.get("cycleNumber") or 0) and p["fecha"] > str(s.get("cutoffDate")))
            aparte = f" (menos {ya:,.2f} que ya pagó después de este corte)" if ya > 0.005 and regla == "exigible" else ""
            if regla == "exigible":
                monto = round(max(0.0, float(monto or 0) - ya), 2)
            if float(monto or 0) <= 0:
                self.anota(clave, "pago", f"nada que pagar en el corte {s.get('cycleNumber')} ({regla} = 0{aparte})")
                continue
            # A distributor delivers the statement less her commission, at the tier of the day the money lands: the
            # portfolio nets the commission against the statement, so the statement gets fully covered.
            comision, tasa = (0.0, None)
            if regla == "exigible":
                # The tier is the day the money lands at Kredius (SPEI credits on Sundays too), which is the value date
                # the portfolio nets on; the banking operation date (next business day) is treasury's, not hers.
                comision, tasa = self.comision_al_pagar(st, s, self.hoy)
                comision = min(comision, float(monto))
            neto = round(float(monto) - comision, 2)
            menos = f" (menos su comisión del {tasa:.0%}: {comision:,.2f})" if comision > 0.005 else ""
            self.pagar(clave, neto, f"pago {regla} del corte {s.get('cycleNumber')} del {s.get('cutoffDate')}{aparte}{menos}")
            st.setdefault("pagos_por_corte", []).append(
                {"ciclo": int(s.get("cycleNumber") or 0), "fecha": self.hoy.isoformat(), "monto": float(monto)})
            st["pagos_de_corte"] = st.get("pagos_de_corte", 0) + 1

    def _ponerse_al_dia(self, clave, st):
        vencidas = [i for i in self._cuotas_pendientes(st) if datetime.date.fromisoformat(i["dueDate"]) <= self.hoy]
        cuenta = self.cuenta(st["cuenta"])
        monto = sum(float(i.get("totalAmount") or 0) for i in vencidas) + float(cuenta.get("penaltyBalance") or 0)
        self.pagar(clave, monto, f"se pone al día: {len(vencidas)} cuotas vencidas más moratorios")

    def _devolucion(self, clave, e, st):
        d = st.get("devolver")
        if not d:
            return
        dia = (self.hoy - self.inicio).days
        if dia == d["dia"] and not d.get("devuelto"):
            self.devolver_pago(clave, d["orden"], "SPEI devuelto por el banco del cliente")
            d["devuelto"] = True
        elif dia == d["dia"] + 5 and d.get("devuelto") and not d.get("repuesto"):
            self.pagar(clave, d["monto"], f"repone el pago devuelto de la cuota {d['cuota']}")
            d["repuesto"] = True

    def _salto(self, clave, e, st):
        if st.get("salto_hecho"):
            return
        n = e["salta_cuota"]
        cuota = next((i for i in self.plan(st["cuenta"]) if int(i.get("installmentNumber", 0)) == n), None)
        if cuota and datetime.date.fromisoformat(cuota["dueDate"]) - datetime.timedelta(days=3) == self.hoy:
            self.saltar_cuota(clave, n)
            st["salto_hecho"] = True

    # ── the night ──
    def noche(self, d: datetime.date):
        t0 = time.time()
        resultado = None
        for intento in range(30):
            r = svc("charges-service", "POST", f"/internal/test-support/run-day-close?date={d.isoformat()}", timeout=900)
            resultado = r.get("outcome")
            if resultado in ("CLOSED", "ALREADY_CLOSED"):
                break
            time.sleep(2)
        if resultado not in ("CLOSED", "ALREADY_CLOSED"):
            raise RuntimeError(f"cargos no cerró el {d}: {r}")
        corte = self._espera_corte(d)
        estados = self._espera_estados(d)
        cargos = svc("charges-service", "GET", f"/api/v1/charges/day-close/{d.isoformat()}") or {}
        a, c = cargos.get("accounts", {}), cargos.get("controlTotals", {})
        svc("risk-service", "POST", "/internal/test-support/run-risk-assessment", timeout=300)
        siguiente = d + datetime.timedelta(days=1)
        if siguiente.weekday() == 0:
            svc("collections-service", "POST", "/internal/test-support/run-write-off-candidates", timeout=300)
        self.anota("", "noche", f"{a.get('done', 0)} cuentas devengadas, {c.get('ordinaryCharges', 0)} intereses por {c.get('ordinaryAmount', 0)}, "
                   f"{c.get('moratoriumCharges', 0)} moratorios, cuarentena {a.get('quarantined', 0)}, retenidas {a.get('held', 0)}; "
                   f"cortes {corte.get('counts', {}).get('done', 0)}, estados {estados.get('generated', 0)}/{estados.get('expected', 0)}",
                   segundos=round(time.time() - t0, 1))
        if (siguiente.month != d.month):
            self.fin_de_mes(d)
        if siguiente.day == 5:
            # The month before the 5th, as CommissionLiquidationJob computes it (YearMonth.now().minusMonths(1));
            # the night of the 4th is still that month's 4th, not the month to liquidate.
            anterior = (siguiente.replace(day=1) - datetime.timedelta(days=1))
            periodo = f"{anterior.year}{anterior.month:02d}"
            r = svc("commission-service", "POST", f"/api/v1/commissions/liquidation-runs?period={periodo}") or {}
            self.anota("", "comisiones", f"liquidación del período {periodo}", lotes=r.get("batchesCreated"),
                       arrastre=r.get("carriedOver"), esperan=r.get("waitingForBeneficiary"),
                       reintentos=r.get("retried"))
            # The lots' payouts leave tonight, with tonight's Banxico date: dispatched, registered and settled before
            # the clock moves. Nothing waited for them, and a lot liquidated on the night of the 4th went out on Friday
            # the 5th in one run and on Monday the 8th in the other, by when the dispatcher's tick came (D65).
            self.reposo()
            self.liquidaciones(self.ahora_stp())

    def liquidaciones(self, momento: str, intentos=60, pausa=1.0):
        """
        Every STP order that may go out has gone out, settled, and reached treasury's cash book. SPEI settles in
        seconds, so in the simulated day the money of a disposition is out the same day: the poller is asked on demand
        (the local stack runs the stub with no settlement delay and the poller with no grace) instead of waiting for
        its three-minute cycle. Orders held around the cutoff are left alone: they go when the hold ends, with the
        next business day, which is already their date. Not finishing is a failure of the run ("asentamiento").
        """
        pendientes = None
        for _ in range(intentos):
            pendientes = psql(f"""SELECT
                (SELECT count(*) FROM stp.payment_orders o
                  WHERE o.status IN ('PENDING', 'SENT', 'ACCEPTED')
                    AND NOT EXISTS (SELECT 1 FROM stp.outbox_messages m
                                     WHERE m.aggregate_id = o.stp_payment_order_id AND m.status = 'PENDING'
                                       AND m.next_attempt_at > '{momento}'::timestamptz)),
                (SELECT count(*) FROM stp.payment_orders s
                  WHERE s.status = 'SETTLED'
                    AND NOT EXISTS (SELECT 1 FROM accounting.treasury_cash_book b
                                     WHERE b.movement_type = 'STP_ORDER' AND b.reference = s.payment_request_id::text))""")[0]
            en_vuelo, sin_libro = (int(x) for x in pendientes)
            if en_vuelo == 0 and sin_libro == 0:
                return
            if en_vuelo:
                svc("stp-service", "POST", "/internal/test-support/run-settlement-poll", timeout=120)
            time.sleep(pausa)
        self.anota("", "asentamiento", f"al {momento} quedaron órdenes STP en vuelo o fuera del libro de caja: "
                   f"en vuelo {pendientes[0]}, liquidadas sin libro {pendientes[1]}")

    def arma_escenarios_stp(self):
        """
        Arms in the STP simulator the amounts the scenarios chose for their cents, and only those: the simulator of the
        local stack runs with scenario-amounts ARMED, and every other order settles. A loan with an opening fee is
        disbursed net of it, so its net amount is armed too. Without this, one computed payout in nine (a commission lot,
        a funding payment) landed on a failure's cents: the distributor suite of 2026-11-04 lost three lots that way.
        """
        comisiones = {}
        try:
            catalogo = svc("credit-product-service", "GET", "/api/v1/credit-products") or []
            catalogo = catalogo if isinstance(catalogo, list) else catalogo.get("content", [])
            comisiones = {p.get("productType"): float(p.get("openingFeeRate") or 0) for p in catalogo
                          if p.get("status") == "ACTIVE"}
        except Exception as ex:   # without the catalog the gross amounts are still armed
            self.anota("", "stp", f"sin catálogo para las comisiones de apertura: {ex}")
        montos = set()
        for e in self.escenarios.values():
            for monto in escenarios.montos_de_escenario_stp({"_": e}):
                montos.add(monto)
                tasa = comisiones.get(e.get("producto"), 0)
                if tasa:
                    montos.add(round(monto - round(monto * tasa, 2), 2))
        r = svc("stp-service", "PUT", "/internal/test-support/stub/scenario-amounts",
                {"amounts": [f"{m:.2f}" for m in sorted(montos)]}) or {}
        if r.get("selection") != "ARMED":
            raise RuntimeError("el simulador de STP no está en modo ARMED (STP_STUB_SCENARIO_AMOUNTS): los pagos que "
                               "calcula la plataforma caerían en sus escenarios de falla")
        self.anota("", "stp", "escenarios del simulador armados: "
                   + (", ".join(f"{a['amount']} {a['scenario']}" for a in r.get("armed", [])) or "ninguno"))

    def ahora_stp(self) -> str:
        """The connector's simulated now (ISO instant): what decides whether an order is still held."""
        return svc("stp-service", "GET", "/internal/test-support/clock")["now"]

    def _espera_corte(self, d):
        for _ in range(240):
            try:
                r = svc("closing-service", "GET", f"/api/v1/closing/cutoff-runs/{d.isoformat()}")
            except HttpError as e:
                if e.code != 404:
                    raise
                r = None
            if r and r.get("status") == "SEALED":
                return r
            if r and r.get("status") == "FAILED":
                raise RuntimeError(f"el corte del {d} quedó FAILED: {json.dumps(r.get('failures'))[:500]}")
            time.sleep(1)
        raise RuntimeError(f"el corte del {d} no selló en 4 minutos")

    def _espera_estados(self, d):
        for _ in range(60):
            r = svc("statement-service", "GET", f"/api/v1/statements/batches?businessDate={d.isoformat()}") or {}
            if not r or r.get("expected", 0) == 0:
                return r or {}
            if r.get("generated", 0) + r.get("failed", 0) >= r.get("expected", 0):
                return r
            time.sleep(1)
        # Loud: the clock moves on with statements of the day still pending, and their generation would depend on timing.
        self.anota("", "asentamiento", f"estados de cuenta del {d} sin terminar en 60 s: {r.get('generated', 0)} "
                   f"generados y {r.get('failed', 0)} fallidos de {r.get('expected', 0)}")
        return r

    def fondea_inicio(self):
        """
        The institution's funding, before the first loan: a capital contribution agreed by the shareholders' meeting
        and paid in, and a funding line from a development bank, drawn, with its opening fee paid. A SOFOM ENR does not
        take deposits from the public: it lends its capital and what it is lent. A resumed run already has it.
        """
        if svc("banking-service", "GET", "/api/v1/funding/sources"):
            return
        f, inicio = self.fondeo, self.inicio
        acuerdo = (inicio - datetime.timedelta(days=1)).isoformat()
        capital = svc("banking-service", "POST", "/api/v1/funding/sources/capital", {
            "shareholderName": "Accionistas fundadores (simulado)", "rfc": "AFS200101AB1",
            "minutesReference": f"ACTA-AGE-{acuerdo}", "bankAccountId": CUENTA_PROPIA,
            "subscribedAmount": f["capital"], "agreedOn": acuerdo})
        svc("banking-service", "POST", f"/api/v1/funding/sources/{capital['fundingSourceId']}/movements", {
            "type": "CONTRIBUTION", "amount": f["aporta"], "valueDate": inicio.isoformat(),
            "reference": "SPEI-CAPITAL-INICIAL"})
        linea = svc("banking-service", "POST", "/api/v1/funding/sources/credit-lines", {
            "lenderType": "DEVELOPMENT_BANK", "lenderName": "Banco de fomento (simulado)", "rfc": "BFS850101AB1",
            "agreementReference": f"CONTRATO-LF-{acuerdo}", "bankAccountId": CUENTA_PROPIA,
            "authorizedAmount": f["linea"], "annualRate": f["tasa"], "openingFee": f["comision"],
            "startDate": acuerdo, "maturityDate": inicio.replace(year=inicio.year + 3).isoformat()})
        ruta = f"/api/v1/funding/sources/{linea['fundingSourceId']}/movements"
        svc("banking-service", "POST", ruta, {"type": "DRAWDOWN", "amount": f["dispone"],
                                               "valueDate": inicio.isoformat(), "reference": "SPEI-LF-DISPOSICION-1"})
        if f["comision"]:
            svc("banking-service", "POST", ruta, {"type": "OPENING_FEE", "valueDate": inicio.isoformat(),
                                                   "reference": "SPEI-LF-COMISION"})
        self.anota("", "fondeo", f"capital aportado {f['aporta']:,} · línea de {f['linea']:,} dispuesta {f['dispone']:,} "
                   f"al {f['tasa'] * 100:.1f} % · comisión {f['comision']:,}")

    def intereses_del_fondeo(self, d: datetime.date, periodo: str):
        """
        The lines' interest accrued through the month's last day (what the close checks). It is paid that day if banks
        operate, else on the next business day (the line's interest then runs to the payment day): a month that ends on
        a Saturday pays on Monday, and its close shows the interest still owed in 2202.
        """
        try:
            svc("banking-service", "POST", f"/api/v1/funding/accruals?through={d.isoformat()}")
            dia = svc("closing-service", "GET", f"/api/v1/calendars/MX/business-day?day={d.isoformat()}") or {}
            pago = datetime.date.fromisoformat(dia.get("onOrAfter") or d.isoformat())
            if pago == d:
                self.paga_intereses_del_fondeo(d, periodo)
            else:
                self.pago_fondeo = (pago, periodo)
                self.anota("", "fondeo", f"intereses de {periodo} devengados al {d.isoformat()}; el {d.isoformat()} es inhábil: "
                           f"se pagan el {pago.isoformat()}")
        except HttpError as e:
            self.anota("", "fondeo", f"no se pudieron devengar los intereses de {periodo}: {e}")

    def paga_intereses_del_fondeo(self, d: datetime.date, periodo: str):
        pagado = 0.0
        try:
            for f in svc("banking-service", "GET", "/api/v1/funding/sources") or []:
                if f.get("kind") == "CREDIT_LINE" and float(f.get("interestPayable") or 0) > 0:
                    hechos = svc("banking-service", "POST", f"/api/v1/funding/sources/{f['fundingSourceId']}/movements", {
                        "type": "INTEREST_PAYMENT", "amount": f["interestPayable"], "valueDate": d.isoformat(),
                        "reference": f"SPEI-INT-{periodo}"}) or []
                    # Paying accrues through the payment day first: what is paid is what is owed that day.
                    pagado += sum(float(m["amount"]) for m in hechos if m.get("type") == "INTEREST_PAYMENT")
            self.anota("", "fondeo", f"intereses de {periodo} pagados el {d.isoformat()}", importe=round(pagado, 2))
        except HttpError as e:
            self.anota("", "fondeo", f"no se pudieron pagar los intereses de {periodo}: {e}")

    def fin_de_mes(self, d: datetime.date):
        periodo = f"{d.year}{d.month:02d}"
        self.intereses_del_fondeo(d, periodo)
        self.liquidaciones(self.ahora_stp())
        try:
            r = svc("accounting-service", "POST", f"/api/v1/accounting/billing-runs?period={periodo}", timeout=300)
            self.anota("", "facturación", f"corrida del período {periodo}", cfdi=(r or {}).get("invoicesRequested"))
        except HttpError as e:
            self.anota("", "facturación", f"falló la corrida de {periodo}: {e}")
        # The close reconciles before it publishes the month: the portfolio closed the last day, risk measured the
        # reserve at that date and the ledger equals the subledgers account by account. 409 means it does not
        # reconcile and the month stays open. Right after the night the last facts may still be on their way
        # (the day close, the reserves, the funding accrual, treasury's cash book, the bank's side of the SPEI money
        # the link of each order to its loan or lot, and the commissions accrued and paid): those are waited for; any
        # other difference is reported as it is.
        cierre, espera = None, {"CARTERA_CERRADA", "RESERVA_MEDIDA", "FONDEO_DEVENGADO", "FONDEO_MAYOR", "BANCOS_1101",
                                "TRANSITO_COBRANZA_1103", "TRANSITO_DISPERSION_2103", "SALDOS_A_FAVOR_2102",
                                "COMISIONES_2120"}
        for _ in range(45):
            try:
                cierre = svc("accounting-service", "POST", f"/api/v1/accounting/periods/{periodo}/close", timeout=180)
                break
            except HttpError as e:
                if e.code != 409:
                    self.anota("", "cierre contable", f"no se pudo cerrar {periodo}: {e}")
                    return
                try:
                    cierre = json.loads(e.body)
                except ValueError:
                    cierre = {"message": e.body, "checks": []}
                fuera = {c.get("code") for c in cierre.get("checks", []) if c.get("blocking") and not c.get("ok")}
                if not fuera or not fuera <= espera:
                    break
                time.sleep(2)
        cierre = cierre or {}
        carpeta = self.evidencia / "cierres"
        carpeta.mkdir(exist_ok=True)
        (carpeta / f"{periodo}.json").write_text(json.dumps(cierre, ensure_ascii=False, indent=2, default=str),
                                                 encoding="utf-8")
        if cierre.get("status") == "CLOSED":
            self.anota("", "cierre contable", f"período {periodo} concilió y quedó cerrado",
                       comprobaciones=len(cierre.get("checks", [])))
        else:
            fuera = [c.get("code") for c in cierre.get("checks", []) if c.get("blocking") and not c.get("ok")]
            self.anota("", "cierre contable", f"{periodo} no concilia: {cierre.get('message')}", fuera=fuera)

    # ── resuming ──
    def reanudar(self):
        """Picks up a run from its saved state: the scenarios keep their accounts, the loop restarts at the saved day."""
        guardado = json.loads((self.evidencia / "estado.json").read_text())
        self.estado = guardado["escenarios"]
        self.hoy = datetime.date.fromisoformat(guardado["hoy"])
        for st in self.estado.values():
            st.pop("_d", None)
            st.setdefault("obligor", st.get("prospect"))
        print(f"  reanudando desde {self.hoy} con {len(self.estado)} escenarios ya en marcha")

    # ── the start of the chain ──
    def verifica_arranque_limpio(self):
        """
        The run starts on a virgin environment: no close of any service on or after the start date. Nothing is
        deleted: charges no longer closes days before any account exists (it used to close "real yesterday" minutes
        after a fresh start, and the engine deleted those rows). If closes are there, the environment is not clean
        and the run stops with what it found.
        """
        desde = self.inicio.isoformat()
        hallados = psql(f"""
            SELECT 'cargos ' || business_date FROM charges.accrual_days WHERE business_date >= '{desde}'
            UNION ALL SELECT 'foto ' || business_date FROM credit_portfolio.day_snapshots WHERE business_date >= '{desde}'
            UNION ALL SELECT 'cartera ' || business_date FROM credit_portfolio.day_closes WHERE business_date >= '{desde}'
            UNION ALL SELECT 'corte ' || business_date FROM closing.close_runs WHERE business_date >= '{desde}'
            ORDER BY 1""")
        if hallados:
            raise RuntimeError("el entorno no está limpio: ya hay cierres en o después del inicio "
                               f"({', '.join(f[0] for f in hallados[:8])}). Reinícialo con scripts/reinicia-entorno.sh")

    def verifica_crones_en_pausa(self):
        """
        Every business cron is paused: the run drives each job itself, at the simulated moment it belongs to. A cron
        left on fires on real time in the middle of the run, and nothing used to check it (the pause is a start-up
        flag, BUSINESS_CRONS_PAUSED). Each service says it of itself; one whose locked crons are not paused stops it.
        """
        encendidos = []
        for servicio in CON_RELOJ:
            try:
                r = svc(servicio, "GET", "/internal/test-support/scheduling") or {}
            except HttpError as e:
                encendidos.append(f"{servicio} (no contesta: {e})")
                continue
            if r.get("lockedCrons") and not r.get("paused"):
                encendidos.append(servicio)
        if encendidos:
            raise RuntimeError("crones de negocio encendidos en " + ", ".join(encendidos)
                               + ": la corrida los dispara a su hora simulada; arranca con BUSINESS_CRONS_PAUSED=true")

    @staticmethod
    def _quietud(servicio):
        """What a service says of its rest, or None where it has no Kafka listeners (nothing to wait for)."""
        try:
            return svc(servicio, "GET", "/internal/test-support/quiescence") or None
        except HttpError:
            return None

    def reposo(self, intentos=150, pausa=0.2):
        """
        The system is at rest: every service consumed everything published to the topics it listens to, and holds no
        due work in a table of its own (an order to dispatch, a message to relay), as each says of itself. The clock
        moves only at rest: this is waited on before each move, so what the day or the night published, and what a
        poller still had to send, is done at the simulated moment it belongs to. The lag alone said a commission lot's
        request was consumed while its order still waited for the dispatcher's tick (D65).
        """
        pendientes = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(CON_RELOJ)) as hilos:
            for _ in range(intentos):
                pendientes = {}
                for servicio, r in zip(CON_RELOJ, hilos.map(self._quietud, CON_RELOJ)):
                    if r and not r.get("quiet", True):
                        colas = {w.get("name"): w.get("pending") for w in r.get("work") or [] if w.get("pending")}
                        pendientes[servicio] = {"pendiente": r.get("pending"), **({"colas": colas} if colas else {})}
                if not pendientes:
                    return True
                time.sleep(pausa)
        self.anota("", "asentamiento", f"el sistema no quedó en reposo en {intentos * pausa:.0f} s: {pendientes}")
        return False

    # ── settling ──
    def asentar(self, intentos=60, pausa=0.3):
        """
        Waits until the portfolio consumed what the day's operations published (payments, purchases, write-offs)
        before the clock moves to D+1. Those facts reach the portfolio asynchronously and take the value date of
        the moment they are applied: consumed after the shift, a write-off of D was valued D+1. In production the
        same happens to a fact consumed after midnight; here it would make the run depend on timing.

        The money path too: every disposition of D has its STP order registered before the clock moves, because the
        order takes the Banxico day it is registered on. Registered after the shift, a disbursement the ledger
        booked on the 30th went out on the 1st, and the month did not reconcile with treasury's cash book (3 Nov).
        """
        compras = sum(len(st.get("compras", [])) for st in self.estado.values())
        quebrantos = sum(1 for st in self.estado.values() if st.get("quebranto_hecho"))
        previo = None
        for _ in range(intentos):
            fila = psql("""SELECT (SELECT count(*) FROM payments.payment_orders WHERE status = 'PENDING'),
                                  (SELECT count(*) FROM credit_portfolio.dispositions),
                                  (SELECT count(*) FROM credit_portfolio.balance_events),
                                  (SELECT count(*) FROM credit_portfolio.credit_accounts WHERE status = 'WRITTEN_OFF'),
                                  (SELECT count(*) FROM credit_portfolio.dispositions p
                                    WHERE p.status NOT IN ('FAILED', 'REVERSED')
                                      AND NOT EXISTS (SELECT 1 FROM disbursement.disbursement_orders o
                                                        JOIN stp.payment_orders s ON s.payment_request_id = o.disbursement_id
                                                       WHERE o.source_reference = p.disposition_id::text)),
                                  (SELECT count(*) FROM stp.inbound_credits c WHERE c.decision = 'CONFIRMED'
                                      AND NOT EXISTS (SELECT 1 FROM payments.received_credits r
                                                       WHERE r.inbound_credit_id = c.inbound_credit_id))""")[0]
            pendientes, disposiciones, _, castigadas, sin_orden, sin_aplicar = (int(x) for x in fila)
            if (pendientes == 0 and disposiciones >= compras and castigadas >= quebrantos and sin_orden == 0
                    and sin_aplicar == 0 and fila == previo):
                self.liquidaciones(self.ahora_stp())
                self.reposo()
                return
            previo = fila
            time.sleep(pausa)
        self.anota("", "asentamiento", f"cartera no terminó de consumir en {intentos * pausa:.0f} s: órdenes pendientes {pendientes}, "
                   f"disposiciones {disposiciones} de {compras} compradas, castigadas {castigadas} de {quebrantos}, "
                   f"sin orden STP {sin_orden}, abonos sin aplicar {sin_aplicar}, estable {fila == previo}")

    # ── the loop ──
    def correr(self):
        print(f"▸ Simulación del {self.inicio} al {self.fin} ({(self.fin - self.inicio).days + 1} días), semilla {self.semilla}")
        print(f"  evidencia en {self.evidencia}")
        self.verifica_crones_en_pausa()
        self.arma_escenarios_stp()
        if self.hoy <= self.inicio:
            self.verifica_arranque_limpio()   # a resumed run already has its own closes
        self.fondea_inicio()
        d = self.hoy if self.hoy > self.inicio else self.inicio
        try:
            while d <= self.fin:
                self.hoy = d
                self.reposo()   # the clock moves only at rest (D65)
                reloj(d, HORA_DEL_DIA)
                antes = len(self.bitacora)
                self.operaciones_del_dia()
                if len(self.bitacora) > antes:
                    self.asentar()
                self.reposo()
                reloj(d + datetime.timedelta(days=1), HORA_DE_LA_NOCHE)
                self.noche(d)
                self.guarda_estado()
                d += datetime.timedelta(days=1)
        finally:
            self.guarda_estado()
        print("✓ simulación terminada")


class _Obj:
    def __init__(self, **kw):
        self.__dict__.update(kw)
