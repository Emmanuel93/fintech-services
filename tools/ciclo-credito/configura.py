"""The end-to-end run's own configuration seed: fixed, deterministic, and configuration only.

The E2E does not start from the demo seed. It starts from a virgin platform (`scripts/reinicia-entorno.sh
--solo-plataforma`: the migrations bring the product catalog, the scoring and reserve policies and the
calendars) and seeds here exactly what its scenarios need and nothing else:

  · the products, checked: every product type active, with the configuration the run will exercise
    (rate, frequency, amortization, billing cycle and interest mode: daily or per period);
  · a minimal sales structure under the national root: one region, one zone, two branches, two executives
    per branch (each one a staff user with the EXECUTIVE role and its own leaf node), where the run's
    clients and distributors hang;
  · the staff that takes the run's decisions, one person per role, so maker-checker is real: compliance,
    committee, collections and risk.

No person, application, account or movement is created here: those are the run's, through the real channels.
Run twice, it changes nothing the second time.

    python3 tools/ciclo-credito/configura.py          # after `reinicia-entorno.sh --solo-plataforma`
"""
from __future__ import annotations

import hashlib
import json
import sys
import time
import unicodedata
import urllib.error
import urllib.request

import puente

BACKOFFICE = "http://backoffice.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")
STAFF_PASSWORD = "Backoffice#2026"

PRODUCTOS = ["PERSONAL_LOAN", "PAYROLL_LOAN", "MICRO_LOAN", "SME_LOAN", "GROUP_LOAN",
             "CREDIT_CARD", "REVOLVING_LINE", "BUSINESS_REVOLVING_LINE", "DISTRIBUTOR_LINE"]

# The distributor line as the business defined it: what `productos` must read from the catalog.
DISTRIBUIDORA_DEFINIDA = {"tasa": 0.95, "frecuencia": "SEMI_MONTHLY", "corte": "SEMI_MONTHLY", "dias_de_corte": [6, 21],
                          "dias_de_pago": [18, 3], "gracia": 2, "interes": "PER_PERIOD",
                          # Placements from 12 to 24 fortnights; her commission 18 % of what her clients pay, by the day
                          # she delivers (14/11/7 % late, nothing past the third day), netted from what she delivers.
                          "plazo_min": 12, "plazo_max": 24, "comision": 0.18, "escalones": [0.18, 0.14, 0.11, 0.07],
                          "neteo": "NETTED"}

# The run's sales structure. Branch codes are the run's own (S_E2E_*), so nothing in the demo can be mistaken for it.
REGION = ("R_E2E", "Región Pruebas E2E")
ZONA = ("Z_E2E", "Zona Pruebas E2E")
SUCURSALES = [("S_E2E_NTE", "Sucursal E2E Norte"), ("S_E2E_CTR", "Sucursal E2E Centro")]
EJECUTIVOS = {
    "S_E2E_NTE": [("e2e.ejecutivo.nte1@kredius.mx", "Ejecutivo Norte Uno"), ("e2e.ejecutivo.nte2@kredius.mx", "Ejecutivo Norte Dos")],
    "S_E2E_CTR": [("e2e.ejecutivo.ctr1@kredius.mx", "Ejecutivo Centro Uno"), ("e2e.ejecutivo.ctr2@kredius.mx", "Ejecutivo Centro Dos")],
}

# The branches' placement policy (distributor line only): what a distributor may place to each kind of client. Wide
# enough for every placement the scenarios already make (measured: a client still NUEVO reaches 3 live loans and
# $12,865 owed, a REVOLVENTE 3 loans and $12,276, because a renewal comes three days after the last cutoff, before that
# statement is paid); the refusals are exercised by a distributor with her own, stricter policy (D13).
POLITICA_DE_COLOCACION = {
    "nuevo": {"maxLoanAmount": 10_000, "maxOutstanding": 25_000, "maxActiveLoans": 4, "maxTerm": 24},
    "revolvente": {"maxLoanAmount": 10_000, "maxOutstanding": 30_000, "maxActiveLoans": 5, "maxTerm": 24},
}

# The staff that takes the run's decisions, by function.
PERSONAL = [
    ("e2e.cumplimiento@kredius.mx", "Oficialía de Cumplimiento E2E", ["OPS_SUPERVISOR"]),
    ("e2e.comite@kredius.mx", "Comité de Crédito E2E", ["UNDERWRITER", "COMMITTEE"]),
    ("e2e.cobranza@kredius.mx", "Gestión de Cobranza E2E", ["COLLECTIONS_AGENT"]),
    ("e2e.riesgo@kredius.mx", "Análisis de Riesgo E2E", ["RISK_ANALYST"]),
]


def http(method, path, body=None, token=None, timeout=30):
    req = urllib.request.Request(
        BACKOFFICE + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def login(credenciales=ADMIN):
    return http("POST", "/auth/staff/login", {"email": credenciales[0], "password": credenciales[1]})["accessToken"]


def curp_de(email: str, nombre: str) -> str:
    """A deterministic, well-formed CURP for a staff member: the audit trail identifies who acts by CURP."""
    h = hashlib.sha256(email.encode()).hexdigest()
    # ASCII letters only: «Análisis» gave ANÁL and identity rejected the CURP, as it should.
    sin_acentos = unicodedata.normalize("NFD", nombre.upper())
    letras = "".join(c for c in sin_acentos if "A" <= c <= "Z")[:4].ljust(4, "X")
    digitos = str(int(h[:10], 16))[-6:].rjust(6, "0")
    mes = (int(digitos[2:4]) % 12) + 1
    dia = (int(digitos[4:6]) % 28) + 1
    consonantes = "".join("BCDFGHJKLMNPQRSTVWXZ"[int(h[i], 16) % 20] for i in range(10, 13))
    return f"{letras}{digitos[:2]}{mes:02d}{dia:02d}HDF{consonantes}A{int(h[13], 16) % 10}"


def asegura_personal(token, existentes, email, nombre, roles):
    staff_id = existentes.get(email)
    if staff_id:
        http("PUT", f"/staff/{staff_id}/roles", {"roles": roles}, token)
        return staff_id, False
    r = http("POST", "/staff", {"email": email, "fullName": nombre, "curp": curp_de(email, nombre),
                                 "employeeType": "INTERNO", "distributorPartyId": None, "roles": roles,
                                 "password": STAFF_PASSWORD}, token)
    existentes[email] = r["staffUserId"]
    return r["staffUserId"], True


def productos(token) -> list[dict]:
    """Every product type active, with the configuration the run exercises, read from the catalog's owner (the
    console's view leaves out the billing cycle and the payment frequency)."""
    req = urllib.request.Request(puente.URL["credit-product-service"] + "/api/v1/credit-products",
                                 headers={"X-User-Id": "e2e-configura", "X-Roles": "ADMIN,SERVICE"})
    with urllib.request.urlopen(req, timeout=30) as r:
        catalogo = json.loads(r.read() or b"[]")
    catalogo = catalogo if isinstance(catalogo, list) else catalogo.get("content", [])
    filas = []
    for tipo in PRODUCTOS:
        p = next((x for x in catalogo if x.get("productType") == tipo and x.get("status") == "ACTIVE"), None)
        if not p:
            sys.exit(f"✗ no hay un producto {tipo} activo en el catálogo: el E2E no tiene con qué originarlo")
        ciclo = p.get("billingCycle") or {}
        filas.append({"tipo": tipo, "codigo": p.get("productCode"), "tasa": p.get("nominalRateAnnual"),
                      "frecuencia": p.get("defaultPaymentFrequency"), "amortizacion": p.get("amortizationType"),
                      "corte": ciclo.get("cutoffRule"), "dias_para_pagar": ciclo.get("paymentDueOffsetDays"),
                      "gracia": ciclo.get("graceDays"), "minimo": ciclo.get("minimumPaymentRate"),
                      "interes": ciclo.get("interestAccrual") or "DAILY",
                      "dias_de_corte": ciclo.get("cutoffDays"), "dias_de_pago": ciclo.get("paymentDueDays"),
                      "comision_apertura": p.get("openingFeeRate"),
                      "plazo_min": p.get("minTerm"), "plazo_max": p.get("maxTerm"),
                      "comision": (p.get("distributorCommission") or {}).get("rate"),
                      "escalones": [t.get("rate") for t in (p.get("distributorCommission") or {}).get("tiers") or []],
                      "neteo": (p.get("distributorCommission") or {}).get("settlement")})
    return filas


def estructura(token) -> dict:
    niveles = {l["code"]: l["levelId"] for l in http("GET", "/sales-org/levels", token=token)}
    for requerido in ("REGION", "ZONE", "BRANCH", "EXECUTIVE", "DISTRIBUTOR"):
        if requerido not in niveles:
            sys.exit(f"✗ sales-org no tiene el nivel {requerido}")
    unidades = {u["code"]: u for u in http("GET", "/sales-org/units", token=token)}
    raiz = next((u["unitId"] for u in unidades.values() if u.get("parentUnitId") is None), None)
    if not raiz:
        sys.exit("✗ sales-org no tiene la unidad nacional")

    def unidad(nivel, codigo, nombre, padre, party_ref=None):
        if codigo in unidades:
            return unidades[codigo]["unitId"]
        r = http("POST", "/sales-org/units", {"levelId": niveles[nivel], "parentUnitId": padre, "code": codigo,
                                              "name": nombre, "partyRef": party_ref}, token)
        unidades[codigo] = r
        return r["unitId"]

    existentes = {s["email"]: s["staffUserId"] for s in (http("GET", "/staff?size=500", token=token) or [])}
    rid = unidad("REGION", *REGION, raiz)
    zid = unidad("ZONE", *ZONA, rid)
    ejecutivos = 0
    for codigo, nombre in SUCURSALES:
        sid = unidad("BRANCH", codigo, nombre, zid)
        for i, (email, persona) in enumerate(EJECUTIVOS[codigo], start=1):
            staff_id, _ = asegura_personal(token, existentes, email, persona, ["EXECUTIVE"])
            ecodigo = f"E_{codigo[2:]}_{i}"
            nuevo = ecodigo not in unidades
            eid = unidad("EXECUTIVE", ecodigo, persona, sid, party_ref=staff_id)
            if nuevo:
                http("POST", f"/sales-org/units/{eid}/assignments",
                     {"assigneeType": "STAFF", "assigneeId": staff_id, "assignmentRole": "OWNER"}, token)
            ejecutivos += 1
    return {"sucursales": [c for c, _ in SUCURSALES], "ejecutivos": ejecutivos}


def politica_de_colocacion(token) -> list[str]:
    """Each branch declares the same placement policy; its distributors inherit it the day they are created."""
    lineas = []
    for codigo, _ in SUCURSALES:
        r = http("PUT", f"/sales-org/units/by-code/{codigo}/placement-policy", POLITICA_DE_COLOCACION, token)
        e = r.get("effective") or {}
        if not r.get("configured") or e.get("nuevo", {}).get("maxLoanAmount") != POLITICA_DE_COLOCACION["nuevo"]["maxLoanAmount"]:
            sys.exit(f"✗ la política de colocación de {codigo} no quedó como se declaró: {r}")
        n, v = e["nuevo"], e["revolvente"]
        lineas.append(f"{codigo}: nuevo hasta ${n['maxLoanAmount']:,} por préstamo, ${n['maxOutstanding']:,} de saldo, "
                      f"{n['maxActiveLoans']} vivos, {n['maxTerm']} quincenas · revolvente ${v['maxLoanAmount']:,}, "
                      f"${v['maxOutstanding']:,}, {v['maxActiveLoans']} vivos, {v['maxTerm']} quincenas")
    return lineas


def personal(token) -> list[str]:
    existentes = {s["email"]: s["staffUserId"] for s in (http("GET", "/staff?size=500", token=token) or [])}
    hechos = []
    for email, nombre, roles in PERSONAL:
        _, nuevo = asegura_personal(token, existentes, email, nombre, roles)
        hechos.append(f"{email} ({', '.join(roles)}){' · alta' if nuevo else ''}")
    return hechos


def espera_matriz_de_permisos(token, intentos=40):
    """The console authorizes with the matrix it read from identity; right after a fresh start it may still be on
    its fallback. The run waits until a role seeded here shows its grants, instead of finding a 403 on day one."""
    for _ in range(intentos):
        try:
            matriz = http("GET", "/permissions/matrix", token=token) or {}
            m = matriz.get("matrix", matriz) if isinstance(matriz, dict) else {}
            if "collections.authorize" in (m.get("COMMITTEE") or []) and "portfolio.relief-grant" in (m.get("RISK_ANALYST") or []):
                return True
        except urllib.error.HTTPError:
            pass
        time.sleep(3)
    return False


def main():
    puente.up()
    token = login()
    print("▸ Configuración del E2E (fija, sólo configuración)")
    filas = productos(token)
    print(f"  productos: {len(filas)} activos")
    for f in filas:
        print(f"    {f['tipo']:<24} {f['codigo']:<16} tasa {f['tasa']}  {f['frecuencia'] or '—':<9} "
              f"{f['amortizacion'] or '—':<7} corte {f['corte']}  interés {f['interes']}")
    # The distributor line the suite exercises is the product the business defined (credit-product 023, commission
    # 012): a run against an older catalog would test another product and pass.
    d = next(f for f in filas if f["tipo"] == "DISTRIBUTOR_LINE")
    distinto = {k: (d[k], v) for k, v in DISTRIBUIDORA_DEFINIDA.items() if str(d[k]) != str(v)}
    if distinto:
        sys.exit(f"✗ la línea de distribuidora del catálogo no es la definida: {distinto}")
    print("  línea de distribuidora: corta el 6 y el 21, se paga el 18 y el 3, gracia de 2 días, 95 %, interés por periodo, "
          "colocaciones de 12 a 24 quincenas, comisión 18/14/11/7 % de lo cobrado neteada de la entrega")
    e = estructura(token)
    print(f"  estructura comercial: {', '.join(e['sucursales'])} · {e['ejecutivos']} ejecutivos")
    for linea in politica_de_colocacion(token):
        print(f"  política de colocación: {linea}")
    for linea in personal(token):
        print(f"  personal: {linea}")
    if not espera_matriz_de_permisos(token):
        sys.exit("✗ la consola no cargó la matriz de permisos de identity")
    print("  matriz de permisos de la consola: cargada desde identity")
    print("✓ configuración del E2E lista")


if __name__ == "__main__":
    main()
