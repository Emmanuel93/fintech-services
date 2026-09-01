#!/usr/bin/env python3
"""Siembra el crédito de distribuidora (B2B2C): distribuidoras, sus clientes y sus colocaciones.

La consola tenía la pestaña «Distribuidores», el árbol comercial tenía el nivel, el catálogo tenía
el producto y commission tenía la atribución — y no había una sola distribuidora en la base. Todo
eso salía vacío, y un cero no se distingue de un error: quien abría la pantalla no podía saber si
el módulo estaba mal o si simplemente no había nadie.

**Recorre el journey real, no inserta filas.** Cada distribuidora pasa por OTP, KYC, alta, solicitud
de su línea, comité y firma, igual que lo haría desde el teléfono; cada beneficiario lo da de alta
su distribuidora, y cada colocación es una disposición de la línea. Si un endpoint no acepta lo que
se le manda aquí, tampoco lo aceptaría la app.

**Una distribuidora = una cuenta de crédito.** Sus «créditos» son colocaciones: disposiciones de su
única línea revolvente a nombre de un beneficiario. Sembrar una cuenta por beneficiario produciría
una cartera que no suma y contradiría el modelo — quien responde por el saldo íntegro es la
distribuidora, no el cliente final.

**Del beneficiario, sólo el expediente y su evaluación.** No tiene cuenta de crédito, ni mora, ni
ECL, ni reporte a buró. Se le evalúa contra el producto —para saber a quién se le está colocando—
pero esa evaluación informa; no bloquea, porque quien decide a quién venderle es la distribuidora.

    python3 scripts/seed-distribuidoras.py
    python3 scripts/seed-distribuidoras.py --distribuidoras 10 --clientes 5
"""
from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from collections import Counter

BACKOFFICE = "http://backoffice.localhost:8090"
MOBILE = "http://mobile.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")
PASSWORD = "Kredius#Cliente2026"
OTP_DEV_CODE = "123456"
RED_DOCKER = "fintech-services_fintech-network"

# Quién dice ser la siembra ante los servicios de dominio. Es un identificador fijo y reconocible a
# propósito: si algo de lo que deja esta siembra aparece en la bitácora, se ve de dónde vino.
SEED_USER_ID = "00000000-0000-0000-0000-000000005EED"

PRODUCTO_LINEA = "DISTRIBUTOR_LINE"

NOMBRES_M = ["Alejandra", "Beatriz", "Cecilia", "Dolores", "Estela", "Fabiola", "Guadalupe",
             "Hortensia", "Isabel", "Josefina", "Leticia", "Margarita", "Norma", "Ofelia",
             "Perla", "Rosario", "Silvia", "Teresa", "Ursula", "Violeta"]
NOMBRES_H = ["Arturo", "Benjamín", "César", "Damián", "Emilio", "Federico", "Gustavo",
             "Hugo", "Ismael", "Javier", "Lorenzo", "Marcelo", "Nicolás", "Octavio",
             "Porfirio", "Rodrigo", "Salvador", "Teodoro", "Urbano", "Vicente"]
APELLIDOS = ["Alcántara", "Bustamante", "Carranza", "Delgado", "Espinoza", "Figueroa", "Guzmán",
             "Huerta", "Islas", "Juárez", "Lugo", "Montalvo", "Nieto", "Olvera",
             "Peralta", "Rentería", "Solís", "Treviño", "Valdés", "Zúñiga"]

ESTADOS = [
    ("Jalisco", "JC", "Guadalajara"), ("Ciudad de México", "DF", "Ciudad de México"),
    ("Nuevo León", "NL", "Monterrey"), ("Puebla", "PL", "Puebla"),
    ("Yucatán", "YN", "Mérida"), ("Sinaloa", "SL", "Culiacán"),
    ("Veracruz", "VZ", "Veracruz"), ("Sonora", "SR", "Hermosillo"),
]

# ─────────────────────────────────────────────────────────────────────────────
# Los diez estados de la línea.
#
# La variedad no es decorativa: es lo que permite distinguir «esta pantalla está mal» de «esta
# pantalla está bien y no hay nada que enseñar». Una cartera donde las diez distribuidoras están al
# corriente no ejercita ni la estimación preventiva, ni la bandeja de cobranza, ni el quebranto — y
# son justo las tres cosas que alguien va a querer mirar.
#
#   dispone   cuántos de sus clientes reciben colocación
#   liquida   cuántas de esas colocaciones se pagan (quedan cerradas)
#   atraso    cuántos días se envejece el calendario de una colocación — de ahí sale el DPD
# ─────────────────────────────────────────────────────────────────────────────
PERFILES = [
    dict(clave="al_corriente",   dispone=5, liquida=2, atraso=0,   nota="Al corriente, con disponible"),
    dict(clave="al_corriente",   dispone=3, liquida=1, atraso=0,   nota="Al corriente, poco dispuesto"),
    dict(clave="linea_agotada",  dispone=5, liquida=0, atraso=0,   nota="Línea prácticamente agotada"),
    dict(clave="dpd_30",         dispone=4, liquida=1, atraso=60,  nota="Una colocación en atraso — DPD 30"),
    dict(clave="dpd_60",         dispone=5, liquida=1, atraso=90,  nota="DPD 60"),
    dict(clave="dpd_90",         dispone=5, liquida=0, atraso=120, nota="DPD 90 — STAGE_3"),
    dict(clave="en_cobranza",    dispone=4, liquida=0, atraso=75,  nota="En cobranza, con promesa de pago"),
    dict(clave="reestructurada", dispone=3, liquida=1, atraso=70,  nota="Con convenio de reestructura"),
    dict(clave="quebrantada",    dispone=5, liquida=0, atraso=230, nota="Quebrantada"),
    dict(clave="sin_disponer",   dispone=0, liquida=0, atraso=0,   nota="Línea aprobada, nunca dispuesta"),
]

# A cuántos meses se coloca. La variedad importa: una cartera donde todo está a 12 meses no ejercita
# el cálculo de la cuota ni deja ver plazos distintos conviviendo en la misma línea.
PLAZOS = [6, 9, 12, 18, 24]


# ── Transporte ───────────────────────────────────────────────────────────────

def clabe(rnd=None):
    """
    Una CLABE con su dígito verificador **calculado**, no al azar.

    Antes esto era `f"0021801{random.randint(...)}"[:18]`: dieciocho dígitos de los que el último
    caía donde tocara. Nueve de cada diez salían inválidas, y no lo notaba nadie porque el
    validador de originación sólo comprobaba que fueran dieciocho dígitos. El crédito se otorgaba
    y se activaba contra una cuenta que **no puede existir**, y el desembolso moría al final del
    todo, con el cliente ya debiendo.

    Es el algoritmo de Banxico, el mismo de `shared.banking.ClabeCheckDigit`.
    """
    fuente = rnd or random
    cuerpo = f"0021801{fuente.randint(10**9, 10**10 - 1)}"[:17].ljust(17, "0")
    pesos = (3, 7, 1)
    suma = sum((int(d) * pesos[i % 3]) % 10 for i, d in enumerate(cuerpo))
    return cuerpo + str((10 - suma % 10) % 10)


def http(method, url, body=None, token=None, timeout=60, reintentos=6):
    """Petición con reintento ante 429 — el gateway limita alta, OTP y KYC a ráfaga de uno."""
    req = urllib.request.Request(
        url, method=method,
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
            time.sleep(0.4 * (intento + 1) + random.random() * 0.3)
    return None


def interno(servicio, ruta, method="POST", body=None):
    """
    Llama a un servicio por la red de Docker, saltándose el gateway.

    Hace falta para lo que no es API pública: los roles de party y el soporte de pruebas de cartera.
    `/internal/*` no se enruta desde fuera —y está bien que no—, así que llamarlo por el gateway
    devuelve 404 y el script lo daría por hecho sin que nada fallara: exactamente la clase de
    silencio que produce una siembra a medias que parece completa.
    """
    # `X-User-Id` siempre. credit-portfolio autentica con esa cabecera y sin ella devuelve 401; como
    # este helper se traga los errores para no tumbar la siembra, la consulta habría fallado en
    # silencio y las distribuidoras habrían salido todas al corriente. party no lo exige y lo
    # ignora, así que mandarlo siempre sale gratis y evita tener que acordarse de cuál es cuál.
    cmd = ["docker", "run", "--rm", "--network", RED_DOCKER, "curlimages/curl:latest",
           "-s", "-w", "\n%{http_code}", "-X", method,
           "-H", f"X-User-Id: {SEED_USER_ID}",
           f"http://{servicio}:8080{ruta}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    r = subprocess.run(cmd, capture_output=True)
    salida = r.stdout.decode().strip().rsplit("\n", 1)
    codigo = salida[-1] if salida else ""
    cuerpo = salida[0] if len(salida) > 1 else ""
    if codigo not in ("200", "201", "204"):
        return None
    try:
        return json.loads(cuerpo) if cuerpo else {}
    except json.JSONDecodeError:
        return {}


# ── Personas ─────────────────────────────────────────────────────────────────

def sin_acentos(texto: str) -> str:
    return unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode()


class Persona:
    """Alguien con datos que pasan las validaciones del dominio (CURP y RFC bien formados)."""

    def __init__(self, rnd: random.Random, etiqueta: str, i: int):
        self.mujer = rnd.random() < 0.5
        self.nombre = rnd.choice(NOMBRES_M if self.mujer else NOMBRES_H)
        self.ap1, self.ap2 = rnd.sample(APELLIDOS, 2)
        self.estado, self.clave_estado, self.ciudad = rnd.choice(ESTADOS)
        self.phone = f"55{rnd.randint(10_000_000, 99_999_999)}"
        anio = rnd.randint(70, 97)
        mes, dia = rnd.randint(1, 12), rnd.randint(1, 28)
        sexo = "M" if self.mujer else "H"
        cons = "".join(rnd.choices("BCDFGLMNPRSTVXZ", k=5))
        ap1, ap2, nom = (sin_acentos(x).upper() for x in (self.ap1, self.ap2, self.nombre))
        self.curp = (f"{ap1[0]}{'AEIOU'[rnd.randrange(5)]}{ap2[0]}{nom[0]}"
                     f"{anio:02d}{mes:02d}{dia:02d}{sexo}{self.clave_estado}{cons[:3]}"
                     f"{rnd.choice('AB')}{rnd.randint(0, 9)}").upper()
        homoclave = "".join(rnd.choices("ABCDEFGHJKLMNPQRSTUVWXYZ0123456789", k=3))
        self.rfc = f"{ap1[:2]}{ap2[0]}{nom[0]}{anio:02d}{mes:02d}{dia:02d}{homoclave}"
        self.fecha_nacimiento = f"19{anio:02d}-{mes:02d}-{dia:02d}"
        self.email = f"{etiqueta}.{self.nombre}.{self.ap1}{i}@example.mx".lower()
        # Dos identidades para la misma persona, y hay que llevar las dos.
        #
        # `prospect_id` es el sujeto del JWT móvil y —comprobado contra la base— lo que
        # credit-portfolio guarda como `obligor_party_id`: la cartera, las disposiciones y la
        # atribución por promotor hablan en este identificador.
        # `party_id` es la clave del expediente en party-service, y es la que exigen los roles y
        # las relaciones.
        #
        # Usar uno donde va el otro no truena: devuelve 404 o, peor, una comprobación que nunca
        # encuentra nada y pasa en silencio.
        self.prospect_id = None
        self.party_id = None
        self.token = None

    @property
    def nombre_completo(self):
        return f"{self.nombre} {self.ap1} {self.ap2}"


def alta(p: Persona) -> bool:
    """OTP → KYC → alta. El camino de la app, sin atajos."""
    try:
        http("POST", f"{MOBILE}/otp/send", {"phone": p.phone})
        verify = http("POST", f"{MOBILE}/otp/verify", {"phone": p.phone, "code": OTP_DEV_CODE})
        pre = verify.get("preAuthToken")
        folio = http("POST", f"{MOBILE}/kyc/submit", {
            "phone": p.phone, "nombres": p.nombre, "apellidoPaterno": p.ap1, "apellidoMaterno": p.ap2,
            "curp": p.curp, "rfc": p.rfc, "fechaNacimiento": p.fecha_nacimiento,
            "genero": "M" if p.mujer else "H", "estadoNacimiento": p.estado, "email": p.email,
            "calle": "Av. Juárez", "numeroExterior": str(random.randint(10, 900)),
            "colonia": "Centro", "municipio": p.ciudad, "ciudad": p.ciudad, "estado": p.estado,
            "codigoPostal": f"{random.randint(10000, 99999)}",
            "aceptaAvisoPrivacidad": True, "aceptaCirculo": True,
        }, token=pre)["folioKyc"]
        creado = http("POST", f"{MOBILE}/auth/register",
                      {"folioKyc": folio, "password": PASSWORD, "documents": []}, token=pre)
        p.prospect_id = creado.get("userId")
        p.token = entrar(p)
        p.party_id = espera_party_id(p.prospect_id)
        if not p.party_id:
            print(f"      ✗ party-service nunca creó el expediente de {p.nombre_completo}")
        return bool(p.prospect_id and p.token and p.party_id)
    except Exception as e:
        print(f"      ✗ no se pudo dar de alta a {p.nombre_completo}: {e}")
        return False


def espera_party_id(prospect_id: str, intentos=30, pausa=0.5):
    """
    Del prospecto al expediente.

    party-service crea el party al consumir `origination.prospect-created`, así que durante un
    instante el prospecto existe y su expediente todavía no. Reintentar es esperar a que el sistema
    alcance a su propia respuesta; dormir un tiempo fijo sería adivinarlo.
    """
    for _ in range(intentos):
        resp = interno("party-service", f"/api/v1/parties/by-prospect/{prospect_id}", method="GET")
        if resp and resp.get("partyId"):
            return resp["partyId"]
        time.sleep(pausa)
    return None


def entrar(p: Persona, intentos=20, pausa=0.5):
    """Espera a que identity aprovisione la credencial. Reintentar es esperar, no adivinar."""
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


# ── El árbol comercial ───────────────────────────────────────────────────────

def ejecutivos_disponibles(token):
    """
    Los nodos de ejecutivo de los que puede colgar una distribuidora.

    Se reparten entre sucursales distintas a propósito: si las diez colgaran del mismo ejecutivo,
    el alcance por subárbol —que es lo que sostiene el tablero— no se podría probar, porque todas
    las ramas darían el mismo número o cero.
    """
    unidades = http("GET", f"{BACKOFFICE}/sales-org/units", token=token) or []
    niveles = http("GET", f"{BACKOFFICE}/sales-org/levels", token=token) or []
    por_codigo = {n["code"]: n["levelId"] for n in niveles}

    if "DISTRIBUTOR" not in por_codigo or "EXECUTIVE" not in por_codigo:
        sys.exit("El árbol no tiene los niveles EXECUTIVE/DISTRIBUTOR — corre primero seed-sales-org.py")

    ejecutivos = [u for u in unidades if u.get("levelId") == por_codigo["EXECUTIVE"]]
    if not ejecutivos:
        sys.exit("No hay nodos de ejecutivo en el árbol — corre primero seed-sales-org.py")

    # Uno por sucursal, para esparcir; si sobran distribuidoras, se da la vuelta.
    por_sucursal = {}
    for e in ejecutivos:
        por_sucursal.setdefault(e.get("parentUnitId"), []).append(e)
    esparcidos = []
    ronda = 0
    while len(esparcidos) < len(ejecutivos):
        agregado = False
        for lista in por_sucursal.values():
            if ronda < len(lista):
                esparcidos.append(lista[ronda])
                agregado = True
        if not agregado:
            break
        ronda += 1
    return esparcidos, por_codigo["DISTRIBUTOR"]


# ── La distribuidora ─────────────────────────────────────────────────────────

class Distribuidora:
    def __init__(self, persona: Persona, perfil: dict, codigo: str):
        self.p = persona
        self.perfil = perfil
        self.codigo = codigo
        self.unit_id = None
        self.application_id = None
        self.credit_account_id = None
        self.linea = 0
        self.clientes = []
        self.colocaciones = 0
        self.estado = "pendiente"


def da_de_alta_distribuidora(d: Distribuidora, token, ejecutivo, nivel_distribuidor) -> bool:
    """Persona → rol DISTRIBUTOR → nodo del árbol. Los tres pasos que la vuelven distribuidora."""
    if not alta(d.p):
        return False

    # El rol, no un tipo nuevo (I-03). Una persona física que además distribuye sigue siendo
    # INDIVIDUAL: conserva su CURP, su expediente y su tratamiento regulatorio.
    if interno("party-service", f"/api/v1/parties/{d.p.party_id}/roles",
               body={"roleType": "DISTRIBUTOR"}) is None:
        print(f"      ✗ no se pudo otorgar el rol DISTRIBUTOR a {d.p.nombre_completo}")
        return False

    # El nodo del árbol, colgando de su ejecutivo. `partyRef` es lo que enlaza el nodo con la
    # persona, y sin él la distribuidora existe pero no tiene alcance ni aparece en el tablero.
    try:
        unidad = http("POST", f"{BACKOFFICE}/sales-org/units", {
            "levelId": nivel_distribuidor,
            "parentUnitId": ejecutivo["unitId"],
            "code": d.codigo,
            "name": d.p.nombre_completo,
            # `partyRef` lleva el prospect_id a propósito: es el identificador con el que hablan
            # cartera, commission y el tablero comercial. Poner aquí el partyId del expediente
            # dejaría el nodo sin poder cruzarse con ninguna cuenta.
            "partyRef": d.p.prospect_id,
        }, token=token)
        d.unit_id = unidad.get("unitId")
    except Exception as e:
        print(f"      ✗ no se pudo crear el nodo de {d.codigo}: {e}")
        return False

    # Y su ejecutivo, en el expediente.
    #
    # El nodo ya dice de qué ejecutivo cuelga, pero la reconciliación contable no mira el árbol:
    # resuelve la sucursal desde el `assignedExecutiveId` del party, igual que para cualquier
    # cliente. Sin esto, la línea de la distribuidora nace y se queda sin sucursal, y su capital
    # —casi dos millones entre las diez— no aparece en el árbol comercial aunque sí en el tablero.
    # Es la misma grieta que deja a un cliente sin ejecutivo, por la otra puerta.
    try:
        http("POST", f"{BACKOFFICE}/clients/{d.p.party_id}/assign-executive",
             {"executiveId": str(ejecutivo.get("partyRef"))}, token=token)
    except Exception as e:
        print(f"      ⚠ {d.codigo} sin ejecutivo asignado ({e}); su cartera no colgará de la rama")

    return bool(d.unit_id)


def solicita_linea(d: Distribuidora, token_admin) -> bool:
    """
    Solicitud → comité → oferta → contrato → firma.

    La línea de distribuidora tiene banda de comité a propósito (la política de scoring deja
    AUTO_APPROVED inalcanzable), así que hay que aprobarla a mano. No se atajа: es el mismo camino
    que recorrería una solicitud real, y de paso deja la mesa con casos de este producto.
    """
    # La línea se atribuye a la propia distribuidora.
    #
    # No es un truco: bajo este modelo la línea **es** la cartera de la distribuidora con la
    # institución. Los beneficiarios no tienen cuenta —sus colocaciones son disposiciones—, así que
    # la única cuenta de crédito atribuible a una distribuidora es la suya. Sin este `promoterCode`,
    # `commission` no crea la asignación, y como el tablero comercial arma `distributorCartera`
    # cruzando el subárbol contra los créditos por promotor, la pantalla saldría vacía teniendo diez
    # distribuidoras vivas — el mismo cero engañoso de siempre, por otra causa.
    try:
        app = http("POST", f"{MOBILE}/credit/applications", {
            "prospectId": d.p.prospect_id, "productType": PRODUCTO_LINEA,
            "promoterCode": d.p.prospect_id,
        }, token=d.p.token)
        d.application_id = app.get("applicationId") or app.get("id")
    except Exception as e:
        print(f"      ✗ {d.codigo}: la solicitud no entró: {e}")
        return False
    if not d.application_id:
        return False

    estado = espera_estado(d, distinto_de="PENDING_SCORING")
    if estado in ("UNDER_MANUAL_REVIEW", "COMMITTEE_REVIEW"):
        try:
            http("POST", f"{BACKOFFICE}/origination/applications/{d.application_id}/decision",
                 {"approved": True, "decidedBy": "comite@kredius.mx"}, token=token_admin)
        except Exception as e:
            print(f"      ✗ {d.codigo}: el comité no pudo aprobar: {e}")
            return False
        estado = espera_estado(d, distinto_de=estado)

    if estado in ("REJECTED", "DECLINED"):
        d.estado = "rechazada"
        return False

    try:
        http("POST", f"{MOBILE}/credit/applications/{d.application_id}/offer", None, token=d.p.token)
        http("POST", f"{MOBILE}/credit/applications/{d.application_id}/offer/accept", None, token=d.p.token)
        http("POST", f"{MOBILE}/credit/applications/{d.application_id}/contract", None, token=d.p.token)
        http("POST", f"{MOBILE}/credit/applications/{d.application_id}/contract/sign", {
            "clabeAccount": clabe(),
            "signatureProof": f"OTP-{random.randint(100000, 999999)}",
            "documentRef": f"linea-{d.application_id[:8]}.pdf"}, token=d.p.token)
    except Exception as e:
        print(f"      ✗ {d.codigo}: no se pudo firmar la línea: {e}")
        return False

    # La cuenta la crea cartera al consumir el evento del contrato firmado: se espera a que
    # aparezca en vez de dormir un tiempo fijo, que es la forma de que esto funcione en la máquina
    # de quien lo escribió y falle en cualquier otra.
    for _ in range(30):
        try:
            cuentas = http("GET", f"{MOBILE}/credit/account", token=d.p.token) or []
            linea = next((c for c in cuentas if c.get("productType") == PRODUCTO_LINEA), None)
            if linea:
                d.credit_account_id = linea.get("creditAccountId")
                d.linea = float(linea.get("creditLimit") or 0)
                return True
        except Exception:
            pass
        time.sleep(1)
    print(f"      ✗ {d.codigo}: la línea nunca se activó")
    return False


# Estados por los que la solicitud *pasa*, no en los que se queda. Esperar a «cualquier cosa
# distinta de PENDING_SCORING» devolvía SCORING —el motor trabajando— y el script seguía adelante
# como si ya hubiera decisión: se saltaba la aprobación del comité y presentaba oferta sobre una
# solicitud sin decidir, que es un 422 merecido. Es la clase de carrera que funciona en una máquina
# rápida y falla en otra.
TRANSITORIOS = {"PENDING_SCORING", "SCORING", ""}


def espera_estado(d: Distribuidora, distinto_de: str, intentos=40, pausa=0.5) -> str:
    for _ in range(intentos):
        try:
            det = http("GET", f"{MOBILE}/credit/applications/{d.application_id}", token=d.p.token)
            estado = det.get("status") or det.get("applicationStatus") or ""
            if estado not in TRANSITORIOS and estado != distinto_de:
                return estado
        except Exception:
            pass
        time.sleep(pausa)
    return ""


# ── Los beneficiarios ────────────────────────────────────────────────────────

def da_de_alta_beneficiario(d: Distribuidora, b: Persona) -> bool:
    """
    La distribuidora captura el expediente de su cliente final, y ahí termina.

    El beneficiario queda con expediente, rol BENEFICIARY y el vínculo con su distribuidora. **No**
    tiene cuenta de crédito, ni mora, ni ECL, ni reporte a buró: quien debe es la distribuidora. El
    vínculo es lo que deja rastro de quién capturó el expediente — sin él no hay a quién preguntarle
    por un documento mal tomado.
    """
    if not alta(b):
        return False

    interno("party-service", f"/api/v1/parties/{b.party_id}/roles",
            body={"roleType": "BENEFICIARY"})

    # Dirección: la distribuidora TIENE COMO beneficiario a esta persona.
    interno("party-service", f"/api/v1/parties/{d.p.party_id}/relationships",
            body={"relatedPartyId": b.party_id, "relationshipType": "BENEFICIARY"})

    return True


def coloca(d: Distribuidora, b: Persona, monto: float, plazo: int) -> bool:
    """
    Colocar es **disponer** de la línea a nombre del beneficiario — no abrir una cuenta nueva.

    Es donde el modelo se hace verdad, y por partida doble:

    - **No se crea una cuenta de crédito por beneficiario.** Si se creara habría cien deudores en
      vez de diez, y la cartera dejaría de sumar. Quien debe es la distribuidora.
    - **Sí se crea una tabla de amortización por colocación.** La línea no se amortiza; cada
      colocación sí, a su plazo, como una compra a meses en una tarjeta. Es lo que le da a la línea
      algo que vencer, y por tanto lo único que puede ponerla en mora.
    """
    try:
        http("POST", f"{MOBILE}/credit/dispose", {
            "amount": monto,
            "dispositionType": "THIRD_PARTY_CREDIT",
            "beneficiaryPartyId": b.prospect_id,
            "termPeriods": plazo,
        }, token=d.p.token)
        return True
    except Exception as e:
        print(f"      ✗ {d.codigo}: no se pudo colocar a {b.nombre_completo}: {e}")
        return False


# ── Los estados de la línea ──────────────────────────────────────────────────

def envejece_una_colocacion(d: Distribuidora, token) -> bool:
    """
    Le corre el calendario de una colocación hacia atrás, para que sus cuotas venzan.

    Es lo que produce la mora de verdad: no se escribe un número de días en la cuenta, se envejece
    el calendario y se deja que el envejecido **cuente** los días desde la cuota vencida más
    antigua. Un DPD escrito a mano se vería igual en la pantalla sin haber pasado por la regla que
    lo produce, y entonces no probaría nada.

    Basta con envejecer UNA colocación: la mora de la línea es la de lo más viejo que dejó de
    pagarse, venga de la colocación que venga.
    """
    disposiciones = interno("credit-portfolio-service",
                            f"/api/v1/portfolio/accounts/{d.credit_account_id}/dispositions",
                            method="GET") or []
    if not disposiciones:
        return False
    disp_id = disposiciones[0].get("dispositionId")
    r = interno("credit-portfolio-service",
                f"/internal/test-support/dispositions/{disp_id}/age-schedule"
                f"?daysAgo={d.perfil['atraso']}")
    return r is not None


def paga(d: Distribuidora, monto: float):
    try:
        http("POST", f"{MOBILE}/credit/payment", {"amount": monto}, token=d.p.token)
        return True
    except Exception:
        return False


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--distribuidoras", type=int, default=10)
    ap.add_argument("--clientes", type=int, default=5,
                    help="Beneficiarios por distribuidora")
    ap.add_argument("--semilla", type=int, default=None)
    args = ap.parse_args()

    rnd = random.Random(args.semilla)

    token = http("POST", f"{BACKOFFICE}/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]}).get("accessToken")
    if not token:
        sys.exit("No se pudo entrar al backoffice")

    ejecutivos, nivel_distribuidor = ejecutivos_disponibles(token)
    print(f"  {len(ejecutivos)} ejecutivos disponibles para colgar distribuidoras")

    distribuidoras = []
    for i in range(args.distribuidoras):
        perfil = PERFILES[i % len(PERFILES)]
        persona = Persona(rnd, "dist", i)
        d = Distribuidora(persona, perfil, f"DIST{i + 1:04d}")
        ejecutivo = ejecutivos[i % len(ejecutivos)]

        print(f"\n  ▸ {d.codigo} · {persona.nombre_completo} — {perfil['nota']}")
        if not da_de_alta_distribuidora(d, token, ejecutivo, nivel_distribuidor):
            d.estado = "no_dada_de_alta"
            distribuidoras.append(d)
            continue
        print(f"      alta y nodo listos (bajo {ejecutivo.get('code')})")

        if not solicita_linea(d, token):
            distribuidoras.append(d)
            continue
        print(f"      línea activa: ${d.linea:,.0f}")

        # Sus beneficiarios: todos reciben expediente y evaluación; sólo algunos, colocación.
        for j in range(args.clientes):
            b = Persona(rnd, f"ben{i}", j)
            if da_de_alta_beneficiario(d, b):
                d.clientes.append(b)
        print(f"      {len(d.clientes)} beneficiarios con expediente")

        # Las colocaciones. El monto se acota para que la suma quepa en la línea; la distribuidora
        # con la línea agotada es la excepción y se le acerca al límite a propósito.
        a_colocar = min(d.perfil["dispone"], len(d.clientes))
        if a_colocar:
            fraccion = 0.19 if d.perfil["clave"] == "linea_agotada" else 0.10
            monto = round(d.linea * fraccion / 100) * 100
            for b in d.clientes[:a_colocar]:
                plazo = rnd.choice(PLAZOS)
                if coloca(d, b, monto, plazo):
                    d.colocaciones += 1
                    time.sleep(0.4)   # la disposición es async; no atropellarla
            print(f"      {d.colocaciones} colocaciones de ${monto:,.0f}, cada una con su calendario")

        # Y la mora: se envejece el calendario de una colocación y el envejecido cuenta los días.
        if d.perfil["atraso"]:
            time.sleep(2)   # que la disposición haya aterrizado y su calendario exista
            if envejece_una_colocacion(d, token):
                print(f"      una colocación envejecida {d.perfil['atraso']} días")
            else:
                print("      ⚠ no se pudo envejecer ninguna colocación")

        for _ in range(d.perfil["liquida"]):
            paga(d, round(d.linea * 0.03))

        d.estado = d.perfil["clave"]
        distribuidoras.append(d)

    # Los procesos, una vez, al final y en este orden:
    #
    #   vencimiento → marca las cuotas que ya pasaron su fecha
    #   envejecido  → cuenta los días desde la más antigua y publica DelinquencyStatusUpdated,
    #                 que es lo que risk consume para mover de etapa IFRS-9 y cobranza para abrir caso
    #
    # Al revés, el envejecido correría antes de que las cuotas estén marcadas y todas las líneas
    # saldrían al corriente — que es justo el falso cero que este trabajo vino a quitar.
    print("\n  ▸ Cerrando cuotas vencidas")
    interno("credit-portfolio-service", "/internal/test-support/run-installment-due-job")
    time.sleep(2)

    print("  ▸ Corriendo el envejecido para que el atraso se vuelva mora")
    interno("credit-portfolio-service", "/internal/test-support/run-delinquency-job")
    time.sleep(4)

    # ── Resumen ──────────────────────────────────────────────────────────────
    print("\n  ── Resumen ──")
    estados = Counter(d.estado for d in distribuidoras)
    vivas = [d for d in distribuidoras if d.credit_account_id]
    print(f"  distribuidoras dadas de alta : {len(vivas)}/{args.distribuidoras}")
    print(f"  beneficiarios con expediente : {sum(len(d.clientes) for d in distribuidoras)}")
    print(f"  colocaciones                 : {sum(d.colocaciones for d in distribuidoras)}")
    print(f"  línea total otorgada         : ${sum(d.linea for d in vivas):,.0f}")
    print("  estados:")
    for estado, n in sorted(estados.items()):
        print(f"    {estado:20s} {n}")

    if len(vivas) < args.distribuidoras:
        print(f"\n  ⚠ {args.distribuidoras - len(vivas)} distribuidoras no llegaron a tener línea.")
        sys.exit(1)


if __name__ == "__main__":
    main()
