"""Who the end-to-end run signs up, and how: people, distributors and their clients, through the real channels.

This is the E2E's own: it does not import the demo seed scripts. Everything that varies (names, CURP, RFC, phone,
address, CLABE, signature proof) comes from the run's seeded random generator, so two runs on a virgin platform
sign up the same people with the same data.

  · a person signs up through the app (OTP → KYC with documents → registration → login); the compliance
    officer resolves identity and watchlists through the console; the executive of the person's branch is
    assigned before the application, so the account is sealed with its branch;
  · an application goes through the app (scoring → desk or committee when the policy says → offer → acceptance →
    contract → signature with a valid CLABE);
  · a distributor is a person with the DISTRIBUTOR role, a node under an executive and its line; its clients are
    people with the BENEFICIARY role linked to it.

The DISTRIBUTOR and BENEFICIARY roles and the link between them are still granted on party-service directly,
through the Docker network: there is no console screen to enroll a distributor nor an app screen to register a
client yet (finding M4). Everything else goes through the channels a person would use.
"""
from __future__ import annotations

import base64
import io
import json
import random
import subprocess
import time
import unicodedata
import urllib.error
import urllib.request

try:
    from PIL import Image, ImageDraw
except ImportError:  # the documents are optional: without Pillow the KYC goes without images
    Image = None

BACKOFFICE = "http://backoffice.localhost:8090"
MOBILE = "http://mobile.localhost:8090"
PASSWORD = "Kredius#Cliente2026"
OTP_DEV_CODE = "123456"
RED_DOCKER = "fintech-services_fintech-network"
USUARIO_E2E = "e2e-ciclo-credito"

NOMBRES_M = ["Adriana", "Brenda", "Carolina", "Daniela", "Elena", "Fernanda", "Gabriela", "Hilda", "Irene", "Jimena",
             "Karla", "Lorena", "Mariana", "Nadia", "Olivia", "Patricia", "Rocío", "Sofía", "Tania", "Verónica"]
NOMBRES_H = ["Alberto", "Bruno", "Carlos", "Diego", "Eduardo", "Fernando", "Gerardo", "Héctor", "Ignacio", "Joaquín",
             "Leonardo", "Manuel", "Néstor", "Óscar", "Pablo", "Ramiro", "Sergio", "Tomás", "Ulises", "Víctor"]
APELLIDOS = ["Aguilar", "Beltrán", "Cárdenas", "Domínguez", "Escobar", "Fuentes", "Gallardo", "Herrera", "Ibarra",
             "Jiménez", "Lozano", "Mendoza", "Navarro", "Ochoa", "Padilla", "Quiroz", "Ramírez", "Salazar", "Tapia",
             "Uribe", "Vargas", "Zamora"]
# Where the run's people live, and the run's branch that serves them (configura.py creates both branches).
PLAZAS = [("Sinaloa", "SL", "Culiacán", "S_E2E_NTE"), ("Nuevo León", "NL", "Monterrey", "S_E2E_NTE"),
          ("Ciudad de México", "DF", "Ciudad de México", "S_E2E_CTR"), ("Jalisco", "JC", "Guadalajara", "S_E2E_CTR")]
TRANSITORIOS = {"PENDING_SCORING", "SCORING", ""}


def sin_acentos(texto: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", texto) if unicodedata.category(c) != "Mn")


def http(method, url, body=None, token=None, timeout=60, reintentos=6):
    """The channels' HTTP, with the session's bearer; a 429 waits and retries, anything else is raised."""
    req = urllib.request.Request(url, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": f"Bearer {token}"} if token else {})})
    for intento in range(reintentos):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            if e.code != 429 or intento == reintentos - 1:
                raise
            time.sleep(0.5 * (intento + 1))
    return None


def interno(servicio, ruta, method="POST", body=None):
    """A call through the Docker network, for what has no channel yet (party roles and links: finding M4)."""
    cmd = ["docker", "run", "--rm", "--network", RED_DOCKER, "curlimages/curl:8.10.1", "-s", "-w", "\n%{http_code}",
           "-X", method, "-H", f"X-User-Id: {USUARIO_E2E}", f"http://{servicio}:8080{ruta}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    r = subprocess.run(cmd, capture_output=True)
    salida = r.stdout.decode().strip().rsplit("\n", 1)
    if (salida[-1] if salida else "") not in ("200", "201", "204"):
        return None
    try:
        return json.loads(salida[0]) if len(salida) > 1 and salida[0] else {}
    except json.JSONDecodeError:
        return {}


def clabe(rnd: random.Random) -> str:
    """A CLABE with its check digit computed (Banxico's algorithm, as shared.banking.ClabeCheckDigit)."""
    cuerpo = f"0021801{rnd.randint(10**9, 10**10 - 1)}"[:17]
    pesos = (3, 7, 1)
    suma = sum((int(d) * pesos[i % 3]) % 10 for i, d in enumerate(cuerpo))
    return cuerpo + str((10 - suma % 10) % 10)


class Persona:
    """A made-up person whose data passes the domain's validations, all of it from the run's generator."""

    def __init__(self, rnd: random.Random, etiqueta: str, i: int, plaza=None):
        self.mujer = rnd.random() < 0.5
        self.nombre = rnd.choice(NOMBRES_M if self.mujer else NOMBRES_H)
        self.ap1, self.ap2 = rnd.sample(APELLIDOS, 2)
        self.estado, self.clave_estado, self.ciudad, self.sucursal = plaza or rnd.choice(PLAZAS)
        self.phone = f"55{rnd.randint(10_000_000, 99_999_999)}"
        anio, mes, dia = rnd.randint(70, 99), rnd.randint(1, 12), rnd.randint(1, 28)
        sexo = "M" if self.mujer else "H"
        cons = "".join(rnd.choices("BCDFGLMNPRSTVXZ", k=3))
        ap1, ap2, nom = (sin_acentos(x).upper() for x in (self.ap1, self.ap2, self.nombre))
        self.curp = (f"{ap1[0]}{'AEIOU'[rnd.randrange(5)]}{ap2[0]}{nom[0]}{anio:02d}{mes:02d}{dia:02d}{sexo}"
                     f"{self.clave_estado}{cons}{rnd.choice('AB')}{rnd.randint(0, 9)}").upper()
        homoclave = "".join(rnd.choices("ABCDEFGHJKLMNPQRSTUVWXYZ0123456789", k=3))
        self.rfc = f"{ap1[:2]}{ap2[0]}{nom[0]}{anio:02d}{mes:02d}{dia:02d}{homoclave}"
        self.fecha_nacimiento = f"19{anio:02d}-{mes:02d}-{dia:02d}"
        self.email = f"e2e.{etiqueta}.{sin_acentos(self.nombre).lower()}.{sin_acentos(self.ap1).lower()}{i}@example.mx"
        self.numero_exterior = str(rnd.randint(10, 900))
        self.codigo_postal = f"{rnd.randint(10000, 99999)}"
        self.firma = f"OTP-{rnd.randint(100000, 999999)}"
        self.clabe = clabe(rnd)
        self.prospect_id = self.party_id = self.token = self.application_id = None

    @property
    def nombre_completo(self):
        return f"{self.nombre} {self.ap1} {self.ap2}"

    def con_buro_en_el_limite(self):
        """The simulated bureau answers a borderline profile (FICO 650-699) for this CURP: the policy sends it to the desk."""
        self.curp = "ZZMR" + self.curp[4:]
        return self


def documento_png(titulo: str, p: Persona):
    if Image is None:
        return None
    img = Image.new("RGB", (700, 440), (245, 245, 242))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 700, 60], fill=(30, 58, 95))
    d.text((20, 24), titulo, fill=(255, 255, 255))
    d.text((30, 120), f"NOMBRE  {p.nombre_completo}", fill=(25, 25, 25))
    d.text((30, 160), f"CURP    {p.curp}", fill=(25, 25, 25))
    d.text((30, 380), "DOCUMENTO DE PRUEBA · E2E", fill=(190, 70, 70))
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return base64.b64encode(buf.getvalue()).decode()


class Altas:
    """
    The run's sign-ups. `cumplimiento` resolves identity and watchlists (the officer, through the console);
    `comite` and `admin` are staff sessions (callables `(method, path, body)` that renew their token).
    """

    def __init__(self, cumplimiento, comite, admin):
        self.cumplimiento = cumplimiento
        self.comite = comite
        self.admin = admin
        self._ejecutivos = None
        self._nivel_distribuidor = None

    # ── sessions ──
    def entrar(self, p: Persona, intentos=20, pausa=0.5):
        """Logs in as soon as identity provisioned the credential (it does so consuming an event)."""
        for _ in range(intentos):
            try:
                s = http("POST", f"{MOBILE}/auth/login", {"phone": p.phone, "password": PASSWORD})
                token = s.get("token") or s.get("accessToken")
                if token:
                    return token
            except urllib.error.HTTPError as e:
                if e.code not in (401, 404):
                    raise
            time.sleep(pausa)
        return None

    # ── the sales structure the run seeded ──
    def ejecutivos(self):
        """The run's executives (configura.py), by branch: a person is served by an executive of its branch."""
        if self._ejecutivos is None:
            unidades = self.admin("GET", "/sales-org/units") or []
            niveles = {n["code"]: n["levelId"] for n in (self.admin("GET", "/sales-org/levels") or [])}
            por_id = {u["unitId"]: u for u in unidades}
            self._ejecutivos = {}
            for u in unidades:
                if u.get("levelId") == niveles.get("EXECUTIVE") and str(u.get("code", "")).startswith("E_E2E_"):
                    sucursal = por_id.get(u.get("parentUnitId"), {}).get("code")
                    self._ejecutivos.setdefault(sucursal, []).append(u)
            for lista in self._ejecutivos.values():
                lista.sort(key=lambda u: u["code"])
            self._nivel_distribuidor = niveles.get("DISTRIBUTOR")
            if not self._ejecutivos:
                raise RuntimeError("no hay ejecutivos E2E: corre primero tools/ciclo-credito/configura.py")
        return self._ejecutivos

    def ejecutivo_de(self, p: Persona, i: int):
        lista = self.ejecutivos().get(p.sucursal) or next(iter(self.ejecutivos().values()))
        return lista[i % len(lista)]

    # ── a person ──
    def alta(self, p: Persona) -> bool:
        """OTP → KYC with documents → registration → login → party → compliance by the officer."""
        http("POST", f"{MOBILE}/otp/send", {"phone": p.phone})
        pre = http("POST", f"{MOBILE}/otp/verify", {"phone": p.phone, "code": OTP_DEV_CODE}).get("preAuthToken")
        folio = http("POST", f"{MOBILE}/kyc/submit", {
            "phone": p.phone, "nombres": p.nombre, "apellidoPaterno": p.ap1, "apellidoMaterno": p.ap2,
            "curp": p.curp, "rfc": p.rfc, "fechaNacimiento": p.fecha_nacimiento,
            "genero": "M" if p.mujer else "H", "estadoNacimiento": p.estado, "email": p.email,
            "calle": "Av. Reforma", "numeroExterior": p.numero_exterior, "colonia": "Centro",
            "municipio": p.ciudad, "ciudad": p.ciudad, "estado": p.estado, "codigoPostal": p.codigo_postal,
            "aceptaAvisoPrivacidad": True, "aceptaCirculo": True}, token=pre)["folioKyc"]
        documentos = []
        for tipo, titulo in [("INE_FRONT", "Credencial para votar — FRENTE"), ("INE_BACK", "Credencial para votar — REVERSO"),
                             ("SELFIE", "Prueba de vida"), ("ADDRESS_PROOF", "Comprobante de domicilio")]:
            contenido = documento_png(titulo, p)
            if contenido:
                documentos.append({"documentType": tipo, "fileName": f"{tipo.lower()}.png",
                                   "contentType": "image/png", "contentBase64": contenido})
        creado = http("POST", f"{MOBILE}/auth/register", {"folioKyc": folio, "password": PASSWORD, "documents": documentos},
                      token=pre)
        p.prospect_id = creado.get("userId")
        p.token = self.entrar(p)
        if not (p.prospect_id and p.token):
            return False
        p.party_id = self.expediente(p)
        if not p.party_id:
            return False
        return bool(self.cumplimiento.despeja(p.prospect_id))

    def expediente(self, p: Persona, intentos=40, pausa=0.5):
        """The person's party, found by CURP in the console (party creates it consuming the sign-up)."""
        for _ in range(intentos):
            r = self.admin("GET", f"/clients?q={p.curp}&size=5") or {}
            fila = next((c for c in r.get("content", []) if c.get("curp") == p.curp), None)
            if fila and fila.get("partyId"):
                return fila["partyId"]
            time.sleep(pausa)
        return None

    def asigna_ejecutivo(self, p: Persona, ejecutivo) -> bool:
        """The branch's executive takes the client before the application: the account is sealed with its branch."""
        try:
            self.admin("POST", f"/clients/{p.party_id}/assign-executive", {"executiveId": str(ejecutivo.get("partyRef"))})
            return True
        except Exception:  # noqa: BLE001 — the account would only lose its branch attribution
            return False

    # ── an application ──
    def estado_solicitud(self, p: Persona, distinto_de: str, intentos=40, pausa=0.5) -> str:
        for _ in range(intentos):
            try:
                d = http("GET", f"{MOBILE}/credit/applications/{p.application_id}", token=p.token)
                estado = d.get("status") or d.get("applicationStatus") or ""
                if estado not in TRANSITORIOS and estado != distinto_de:
                    return estado
            except Exception:  # noqa: BLE001
                pass
            time.sleep(pausa)
        return ""

    def solicita(self, p: Persona, producto: str, monto=None, plazo=None, bnpl_dias=None, parar_en_mesa=False,
                 promotor=None) -> str:
        """
        The application through the app, as far as it goes: "desembolsado", "en_mesa" (when asked to stop where the
        policy sent it), or the status where the domain left it. Desk and committee decisions are the committee's.
        """
        cuerpo = {"prospectId": p.prospect_id, "productType": producto}
        if monto is not None:
            cuerpo["requestedAmount"] = monto
        if plazo is not None:
            cuerpo["requestedTerm"] = plazo
        if promotor:
            cuerpo["promoterCode"] = promotor
        app = http("POST", f"{MOBILE}/credit/applications", cuerpo, token=p.token)
        p.application_id = app.get("applicationId") or app.get("id")
        if not p.application_id:
            return "sin_solicitud"
        estado = self.estado_solicitud(p, distinto_de="PENDING_SCORING")
        if estado in ("UNDER_MANUAL_REVIEW", "COMMITTEE_REVIEW"):
            if parar_en_mesa:
                return "en_mesa"
            self.comite("POST", f"/origination/applications/{p.application_id}/decision", {"approved": True})
            estado = self.estado_solicitud(p, distinto_de=estado)
        elif parar_en_mesa:
            return f"no_llegó_a_mesa ({estado})"
        if estado in ("REJECTED", "DECLINED"):
            return "rechazado"
        if estado not in ("APPROVED", "PRE_APPROVED", "OFFER_PRESENTED"):
            return estado.lower() if estado else "sin_resolver"
        http("POST", f"{MOBILE}/credit/applications/{p.application_id}/offer", None, token=p.token)
        http("POST", f"{MOBILE}/credit/applications/{p.application_id}/offer/accept", None, token=p.token)
        http("POST", f"{MOBILE}/credit/applications/{p.application_id}/contract", None, token=p.token)
        firma = {"clabeAccount": p.clabe, "signatureProof": p.firma, "documentRef": f"contrato-{p.application_id[:8]}.pdf"}
        if bnpl_dias:
            firma["bnplDeferralDays"] = bnpl_dias
        http("POST", f"{MOBILE}/credit/applications/{p.application_id}/contract/sign", firma, token=p.token)
        return "desembolsado"

    def cuenta(self, p: Persona, producto=None, intentos=40):
        """The account the app shows the holder once it is active."""
        for _ in range(intentos):
            try:
                cuentas = http("GET", f"{MOBILE}/credit/account", token=p.token) or []
                c = next((x for x in cuentas if producto is None or x.get("productType") == producto), None)
                if c:
                    return c
            except Exception:  # noqa: BLE001
                pass
            time.sleep(1)
        return None

    # ── a distributor and its clients ──
    def distribuidora(self, p: Persona, codigo: str, ejecutivo) -> bool:
        """The DISTRIBUTOR role (party, M4), its node under the executive, and the executive in its file."""
        if interno("party-service", f"/api/v1/parties/{p.party_id}/roles", body={"roleType": "DISTRIBUTOR"}) is None:
            return False
        self.ejecutivos()
        self.admin("POST", "/sales-org/units", {"levelId": self._nivel_distribuidor, "parentUnitId": ejecutivo["unitId"],
                                                "code": codigo, "name": p.nombre_completo, "partyRef": p.prospect_id})
        return self.asigna_ejecutivo(p, ejecutivo)

    def beneficiario(self, distribuidora: Persona, b: Persona) -> bool:
        """The distributor's client: the BENEFICIARY role and the link (party, M4). It has no account of its own."""
        ok = interno("party-service", f"/api/v1/parties/{b.party_id}/roles", body={"roleType": "BENEFICIARY"}) is not None
        return ok and interno("party-service", f"/api/v1/parties/{distribuidora.party_id}/relationships",
                              body={"relatedPartyId": b.party_id, "relationshipType": "BENEFICIARY"}) is not None
