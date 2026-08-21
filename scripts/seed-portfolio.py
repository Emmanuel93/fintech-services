#!/usr/bin/env python3
"""Genera solicitudes y cartera repartidas por toda la red de sucursales.

La estructura comercial ya tenía 22 sucursales y 52 ejecutivos, pero ninguno con
cartera: el tablero salía en ceros y no había forma de distinguir «está mal» de
«está vacío». Peor, la bandeja de solicitudes tenía un puñado de casos del mismo
prospecto, así que los filtros por estatus, producto y fecha no se podían probar
contra nada.

Este script recorre **el journey real**, no inserta filas: cada cliente pasa por
el canal móvil igual que lo haría desde el teléfono —OTP, KYC, alta, solicitud,
scoring, oferta, contrato, firma— y termina con su cuenta de crédito activa. Si
un endpoint no acepta lo que le mandamos aquí, tampoco lo aceptaría la app.

Deja a propósito solicitudes en varios estados: no todo el mundo llega al final,
y una bandeja donde todas las filas están desembolsadas no ejercita ni la mesa de
análisis ni los filtros.

    python3 scripts/seed-portfolio.py                 # 40 clientes
    python3 scripts/seed-portfolio.py --clientes 80
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures as futures
import io
import json
import random
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

try:
    from PIL import Image, ImageDraw
except ImportError:  # el expediente es opcional; la cartera no depende de él
    Image = None

BACKOFFICE = "http://backoffice.localhost:8090"
MOBILE = "http://mobile.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")
PASSWORD = "Kredius#Cliente2026"
OTP_DEV_CODE = "123456"

# El token de staff, para que el journey pueda pedir la decisión de mesa/comité. Se llena en main().
# Va a nivel de módulo porque `journey()` corre en un pool de hilos y arrastrar el token por cinco
# firmas para un solo uso ensucia más de lo que aclara.
TOKEN_STAFF = None
RED_DOCKER = "fintech-services_fintech-network"

NOMBRES_M = ["Adriana", "Brenda", "Carolina", "Daniela", "Elena", "Fernanda", "Gabriela",
             "Hilda", "Irene", "Jimena", "Karla", "Lorena", "Mariana", "Nadia", "Olivia",
             "Patricia", "Rocío", "Sofía", "Tania", "Verónica", "Ximena", "Yolanda"]
NOMBRES_H = ["Alberto", "Bruno", "Carlos", "Diego", "Eduardo", "Fernando", "Gerardo",
             "Héctor", "Ignacio", "Joaquín", "Kevin", "Leonardo", "Manuel", "Néstor",
             "Óscar", "Pablo", "Ramiro", "Sergio", "Tomás", "Ulises", "Víctor"]
APELLIDOS = ["Aguilar", "Beltrán", "Cárdenas", "Domínguez", "Escobar", "Fuentes", "Gallardo",
             "Herrera", "Ibarra", "Jiménez", "Lozano", "Mendoza", "Navarro", "Ochoa",
             "Padilla", "Quiroz", "Ramírez", "Salazar", "Tapia", "Uribe", "Vargas", "Zamora"]

# Qué tan seguido aparece cada producto. Una cartera real tiene muchos préstamos
# personales chicos y pocos créditos PyME grandes, y esa forma es la que hace que
# el tablero y la mezcla por producto se vean como algo y no como ruido.
#
# Los montos y plazos **no** se declaran aquí: se leen del catálogo. Escribirlos a
# mano producía solicitudes fuera de los límites del producto —un PAYROLL_LOAN de
# 135 000 contra un máximo de 100 000— y el rechazo llegaba tres pasos después,
# al presentar la oferta.
PESOS_PRODUCTO = {"PERSONAL_LOAN": 0.62, "PAYROLL_LOAN": 0.24, "SME_LOAN": 0.14}

# Reparto parejo, para las siembras chicas.
#
# Con los pesos reales, SME_LOAN es el 14% y sólo la mitad de los clientes llega a desembolsar: en
# una siembra de veinte clientes salen uno o dos SME, y el producto queda sin casos que revisar. Con
# pocos datos importa más que **haya de todo** que reproducir la mezcla comercial, así que esta
# variante iguala los pesos y deja la proporción real para las corridas grandes.
PESOS_PAREJOS = {k: 1.0 for k in PESOS_PRODUCTO}

# Hasta dónde llega cada cliente. La bandeja necesita casos vivos en cada peldaño:
# una consola donde todo está desembolsado no ejercita la mesa de análisis.
DESENLACES = [
    ("desembolsado", 0.55),   # firma y activa la cuenta → cartera
    ("firmando",     0.10),   # contrato generado, sin firmar
    ("ofertado",     0.12),   # oferta presentada, sin aceptar
    ("en_revision",  0.13),   # mandado a mesa de análisis
    ("solicitado",   0.10),   # recién creada, el scoring decide
]

# Dónde vive el solicitante y **a qué sucursal le toca**.
#
# La cuarta columna no es decorativa: es lo que decide la atribución contable del crédito. Antes la
# cartera se repartía en round-robin sobre los 54 ejecutivos de toda la red, así que un crédito
# pedido en Mérida podía quedar en manos de un ejecutivo de Culiacán — y el ingreso se le atribuía a
# una sucursal a 2 000 km del cliente. Peor: si la búsqueda del cliente fallaba, se saltaba en
# silencio y el crédito acababa sin ejecutivo, sin sucursal y fuera de toda la contabilidad por rama.
#
# La regla es la de una red comercial real: **la sucursal más cercana a donde se solicita**. Ningún
# crédito queda sin sucursal; si en la plaza no hay ejecutivos, se asigna igual a la sucursal y el
# hueco que queda es «sucursal sin gente», que es un problema de plantilla y se ve como tal.
ESTADOS = [
    ("Sinaloa",          "SL", "Culiacán",         "S_CUL"),
    ("Sinaloa",          "SL", "Los Mochis",       "S_LMO"),
    ("Sonora",           "SR", "Hermosillo",       "S_HMO"),
    ("Nuevo León",       "NL", "Monterrey",        "S_MTY"),
    ("Nuevo León",       "NL", "San Pedro",        "S_SAP"),
    ("Coahuila",         "CL", "Saltillo",         "S_SAL"),
    ("Guanajuato",       "GT", "León",             "S_LEO"),
    ("Querétaro",        "QT", "Querétaro",        "S_QRO"),
    ("Aguascalientes",   "AS", "Aguascalientes",   "S_AGS"),
    ("Jalisco",          "JC", "Guadalajara",      "S_GDL"),
    ("Jalisco",          "JC", "Zapopan",          "S_ZAP"),
    ("Jalisco",          "JC", "Puerto Vallarta",  "S_PVR"),
    ("Ciudad de México", "DF", "Ciudad de México", "S_CDMX"),
    ("Ciudad de México", "DF", "Polanco",          "S_POL"),
    ("México",           "MC", "Satélite",         "S_SAT"),
    ("Puebla",           "PL", "Puebla",           "S_PUE"),
    ("Hidalgo",          "HG", "Pachuca",          "S_PAC"),
    ("México",           "MC", "Toluca",           "S_TOL"),
    ("Veracruz",         "VZ", "Veracruz",         "S_VER"),
    ("Tabasco",          "TC", "Villahermosa",     "S_VIL"),
    ("Yucatán",          "YN", "Mérida",           "S_MER"),
    ("Quintana Roo",     "QR", "Cancún",           "S_CUN"),
]


def http(method, url, body=None, token=None, timeout=60, reintentos=6):
    """
    Petición con reintento ante 429.

    El gateway limita alta, OTP y KYC a ráfaga de uno: son endpoints que en
    producción recibe una persona con un teléfono, no un script abriendo cuarenta
    cuentas. El límite está bien puesto y no se toca; quien tiene que ceder es
    esto, que es el cliente anómalo. Se espera un poco más en cada intento para no
    convertir el reintento en la misma ráfaga otra vez.
    """
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


class Cliente:
    """Una persona inventada, con datos que pasan las validaciones del dominio."""

    def __init__(self, rnd: random.Random, i: int, catalogo: "Catalogo"):
        self.mujer = rnd.random() < 0.5
        self.nombre = rnd.choice(NOMBRES_M if self.mujer else NOMBRES_H)
        self.ap1, self.ap2 = rnd.sample(APELLIDOS, 2)
        self.estado, self.clave_estado, self.ciudad, self.sucursal = rnd.choice(ESTADOS)
        # Teléfono único por corrida: el dominio lo exige y un choque aborta el alta.
        self.phone = f"55{rnd.randint(10_000_000, 99_999_999)}"
        anio = rnd.randint(75, 99)
        mes, dia = rnd.randint(1, 12), rnd.randint(1, 28)
        sexo = "M" if self.mujer else "H"
        cons = "".join(rnd.choices("BCDFGLMNPRSTVXZ", k=5))
        ap1, ap2, nom = (sin_acentos(x).upper() for x in (self.ap1, self.ap2, self.nombre))
        self.curp = (f"{ap1[0]}{'AEIOU'[rnd.randrange(5)]}{ap2[0]}{nom[0]}"
                     f"{anio:02d}{mes:02d}{dia:02d}{sexo}{self.clave_estado}{cons[:3]}"
                     f"{rnd.choice('AB')}{rnd.randint(0, 9)}").upper()
        # RFC de persona física: cuatro letras, seis dígitos de fecha y tres de
        # homoclave. Se arma por posiciones y no recortando una cadena, que es como
        # salían de once caracteres y el alta los rechazaba con 400.
        letras = f"{ap1[:2]}{ap2[0]}{nom[0]}"
        homoclave = "".join(rnd.choices("ABCDEFGHJKLMNPQRSTUVWXYZ0123456789", k=3))
        self.rfc = f"{letras}{anio:02d}{mes:02d}{dia:02d}{homoclave}"
        self.fecha_nacimiento = f"19{anio:02d}-{mes:02d}-{dia:02d}"
        self.email = f"{self.nombre}.{self.ap1}{i}@example.mx".lower()
        self.producto, minimo, maximo, plazo_min, plazo_max = catalogo.elige(rnd)
        # El monto, con la forma que tiene una cartera real: **muchos chicos y pocos grandes**.
        #
        # Antes era uniforme sobre el tercio bajo del rango. Evitaba lo peor —una cartera de puros
        # créditos al máximo, que no se parece a ninguna— pero producía la deformación contraria:
        # todos los créditos parecidos y ninguno grande, así que la cartera PyME pesaba casi nada y
        # los tramos de monto del tablero no tenían nada que separar.
        #
        # Elevar al cuadrado un uniforme concentra en la parte baja y deja una cola larga: la
        # mayoría de los créditos cerca del mínimo, unos pocos arriba. Es la forma que uno espera
        # ver, y de paso hace que la mezcla por producto signifique algo — un PyME grande pesa lo
        # que pesan cincuenta personales, que es justo el caso que el tablero debe saber mostrar.
        techo = min(maximo, minimo + (maximo - minimo) * 0.85)
        sesgo = rnd.random() ** 2
        self.monto = int((minimo + (techo - minimo) * sesgo) / 5_000) * 5_000 or int(minimo)
        self.plazo = rnd.choice([p for p in (6, 12, 18, 24, 36, 48)
                                 if plazo_min <= p <= plazo_max] or [plazo_min])
        self.desenlace = ponderado(rnd, DESENLACES)[0]
        self.prospect_id = None
        self.application_id = None
        self.token = None

    @property
    def nombre_completo(self):
        return f"{self.nombre} {self.ap1} {self.ap2}"


class Catalogo:
    """Los productos activos, con sus límites reales."""

    def __init__(self, token, equilibrado=False):
        self.pesos = PESOS_PAREJOS if equilibrado else PESOS_PRODUCTO
        self.productos = []
        for p in http("GET", f"{BACKOFFICE}/products", token=token) or []:
            if p.get("status") != "ACTIVE" or p.get("behavior") != "INSTALLMENT":
                continue
            peso = self.pesos.get(p.get("productType"))
            if not peso or p.get("minAmount") is None:
                continue
            self.productos.append((p["productType"], peso, float(p["minAmount"]),
                                   float(p["maxAmount"]), int(p["minTerm"]), int(p["maxTerm"])))
        if not self.productos:
            sys.exit("No hay productos amortizables activos en el catálogo")

    def elige(self, rnd):
        elegido = ponderado(rnd, self.productos)
        return (elegido[0], elegido[2], elegido[3], elegido[4], elegido[5])


def sin_acentos(texto: str) -> str:
    """
    Quita los acentos para armar CURP y RFC.

    Los dos formatos aceptan sólo A–Z, y las iniciales salen de nombres reales que
    llevan tilde: «Óscar» producía una CURP con Ó en la cuarta posición y el alta
    la rechazaba con 400. El nombre de la persona conserva su acento; lo que se
    normaliza es la clave, que es donde el formato manda.
    """
    return unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode()


def ponderado(rnd, opciones):
    """
    Elige respetando el peso de la segunda posición de cada tupla.

    Los pesos se **normalizan** contra su suma. Antes se comparaban contra `rnd.random()` dando por
    hecho que sumaban 1.0, y esa suposición no está escrita en ninguna firma: en cuanto alguien pasa
    pesos con otra escala —tres opciones con peso 1.0, para repartir parejo— el primero acumula 1.0,
    la condición se cumple siempre y **devuelve siempre la primera**. El síntoma no es un error: son
    treinta solicitudes del mismo producto y una siembra que parece sesgada.
    """
    total = sum(op[1] for op in opciones)
    if total <= 0:
        return opciones[-1]
    r, acc = rnd.random() * total, 0.0
    for op in opciones:
        acc += op[1]
        if r <= acc:
            return op
    return opciones[-1]


def documento_png(titulo, cliente):
    if Image is None:
        return None
    img = Image.new("RGB", (700, 440), (245, 245, 242))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 700, 60], fill=(30, 58, 95))
    d.text((20, 24), titulo, fill=(255, 255, 255))
    d.text((30, 120), f"NOMBRE  {cliente.nombre_completo}", fill=(25, 25, 25))
    d.text((30, 160), f"CURP    {cliente.curp}", fill=(25, 25, 25))
    d.text((30, 380), "DOCUMENTO DE PRUEBA · ambiente local", fill=(190, 70, 70))
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return base64.b64encode(buf.getvalue()).decode()


def alta(c: Cliente) -> bool:
    """OTP → KYC → alta, con el expediente dentro. Es el camino de la app."""
    http("POST", f"{MOBILE}/otp/send", {"phone": c.phone})
    verify = http("POST", f"{MOBILE}/otp/verify", {"phone": c.phone, "code": OTP_DEV_CODE})
    pre = verify.get("preAuthToken")
    folio = http("POST", f"{MOBILE}/kyc/submit", {
        "phone": c.phone, "nombres": c.nombre, "apellidoPaterno": c.ap1, "apellidoMaterno": c.ap2,
        "curp": c.curp, "rfc": c.rfc, "fechaNacimiento": c.fecha_nacimiento,
        "genero": "M" if c.mujer else "H", "estadoNacimiento": c.estado, "email": c.email,
        "calle": "Av. Reforma", "numeroExterior": str(random.randint(10, 900)),
        "colonia": "Centro", "municipio": c.ciudad, "ciudad": c.ciudad, "estado": c.estado,
        "codigoPostal": f"{random.randint(10000, 99999)}",
        "aceptaAvisoPrivacidad": True, "aceptaCirculo": True,
    }, token=pre)["folioKyc"]

    documentos = []
    for tipo, titulo in [("INE_FRONT", "Credencial para votar — FRENTE"),
                         ("INE_BACK", "Credencial para votar — REVERSO"),
                         ("SELFIE", "Prueba de vida"),
                         ("ADDRESS_PROOF", "Comprobante de domicilio")]:
        contenido = documento_png(titulo, c)
        if contenido:
            documentos.append({"documentType": tipo, "fileName": f"{tipo.lower()}.png",
                               "contentType": "image/png", "contentBase64": contenido})

    creado = http("POST", f"{MOBILE}/auth/register",
                  {"folioKyc": folio, "password": PASSWORD, "documents": documentos}, token=pre)
    c.prospect_id = creado.get("userId")

    c.token = entrar(c)
    return bool(c.prospect_id and c.token)


def entrar(c: Cliente, intentos=20, pausa=0.5) -> str | None:
    """Inicia sesión en cuanto exista la credencial.

    El alta devuelve 201 con el prospecto creado, pero la credencial la aprovisiona
    identity al consumir el evento: durante un instante el usuario existe y todavía
    no puede entrar. Reintentar es esperar a que el sistema alcance a su propia
    respuesta; dormir un tiempo fijo sería adivinarlo.
    """
    for _ in range(intentos):
        try:
            s = http("POST", f"{MOBILE}/auth/login", {"phone": c.phone, "password": PASSWORD})
            token = s.get("token") or s.get("accessToken")
            if token:
                return token
        except urllib.error.HTTPError as e:
            if e.code not in (401, 404):
                raise
        time.sleep(pausa)
    return None


def journey(c: Cliente) -> str:
    """Lleva la solicitud hasta donde le tocó a este cliente."""
    app = http("POST", f"{MOBILE}/credit/applications", {
        "prospectId": c.prospect_id, "productType": c.producto,
        "requestedAmount": c.monto, "requestedTerm": c.plazo}, token=c.token)
    c.application_id = app.get("applicationId") or app.get("id")
    if not c.application_id:
        return "sin_solicitud"
    if c.desenlace == "solicitado":
        return c.desenlace

    # El scoring es asíncrono: se espera a que la solicitud deje PENDING_SCORING
    # en vez de dormir un tiempo fijo, que es la forma de que esto falle en una
    # máquina más lenta y "funcione" en la de quien lo escribió.
    estado = esperar_estado(c, distinto_de="PENDING_SCORING")
    if c.desenlace == "en_revision":
        # A la mesa aunque el motor lo haya resuelto: sin esto la bandeja no tiene
        # ni un caso «por decidir» y la mesa de análisis no se puede probar.
        return "en_revision" if enviar_a_mesa(c) else "no_llegó_a_mesa"
    if estado in ("REJECTED", "DECLINED"):
        return "rechazado"

    # El motor mandó a decisión humana. Para quien debía llegar a desembolso, eso no es el final del
    # camino sino un paso más: alguien decide. Se pide la decisión por el mismo endpoint que usa la
    # consola y se sigue. Antes se abandonaba aquí, y como la política de PyME manda TODAS sus
    # solicitudes a comité, ningún crédito grande llegaba nunca a la cartera.
    if estado in ("UNDER_MANUAL_REVIEW", "COMMITTEE_REVIEW"):
        if decide_la_mesa(c, aprobar=True):
            estado = esperar_estado(c, distinto_de=estado)

    if estado not in ("APPROVED", "PRE_APPROVED", "OFFER_PRESENTED"):
        # Pidió documentos, o la decisión no prosperó: el cliente se queda donde el dominio lo dejó.
        return estado.lower() if estado else "sin_resolver"

    http("POST", f"{MOBILE}/credit/applications/{c.application_id}/offer", None, token=c.token)
    if c.desenlace == "ofertado":
        return c.desenlace

    http("POST", f"{MOBILE}/credit/applications/{c.application_id}/offer/accept", None, token=c.token)
    http("POST", f"{MOBILE}/credit/applications/{c.application_id}/contract", None, token=c.token)
    if c.desenlace == "firmando":
        return c.desenlace

    http("POST", f"{MOBILE}/credit/applications/{c.application_id}/contract/sign", {
        "clabeAccount": f"0021801{random.randint(10**10, 10**11 - 1)}"[:18].ljust(18, "0"),
        "signatureProof": f"OTP-{random.randint(100000, 999999)}",
        "documentRef": f"contrato-{c.application_id[:8]}.pdf"}, token=c.token)
    return "desembolsado"


def enviar_a_mesa(c: Cliente, comite=False) -> bool:
    """
    Manda la solicitud a revisión humana.

    Va por la red de Docker y no por el gateway a propósito: `/internal/*` no se
    enruta desde fuera —es soporte de pruebas, no API—. Llamarlo por el gateway
    devolvía 404 y el script lo daba por hecho, así que la bandeja se quedaba sin
    un solo caso por decidir y nadie lo notaba hasta abrirla.
    """
    r = subprocess.run(
        ["docker", "run", "--rm", "--network", RED_DOCKER, "curlimages/curl:latest",
         "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
         f"http://origination-service:8080/internal/test-support/applications/"
         f"{c.application_id}/route-to-review?committee={'true' if comite else 'false'}"],
        capture_output=True)
    return r.stdout.decode().strip() == "200"


def decide_la_mesa(c: Cliente, aprobar=True) -> bool:
    """
    Registra la decisión humana que la solicitud está esperando.

    Sin esto, **ningún crédito PyME llegaba a existir**: su política de scoring deja la banda de
    auto-aprobación inalcanzable a propósito, así que las siete solicitudes de SME_LOAN —de 50 000 a
    5 000 000, las únicas grandes de la cartera— se quedaban en comité para siempre y la cartera
    entera valía dos millones en vez de once. El motor hacía lo correcto y la siembra se detenía
    justo antes del paso que faltaba.

    Se usa el mismo endpoint que aprieta un analista en la consola. Los casos que deben quedarse
    esperando decisión los pone `llenar_mesa` al final, a propósito y contados.
    """
    try:
        http("POST", f"{BACKOFFICE}/origination/applications/{c.application_id}/decision",
             {"approved": aprobar, "decidedBy": "comite@kredius.mx",
              **({} if aprobar else {"rejectionReason": "Capacidad de pago insuficiente"})},
             token=TOKEN_STAFF)
        return True
    except Exception:
        return False


def esperar_estado(c: Cliente, distinto_de: str, intentos=25, pausa=0.4) -> str:
    for _ in range(intentos):
        try:
            d = http("GET", f"{MOBILE}/credit/applications/{c.application_id}", token=c.token)
            estado = d.get("status") or d.get("applicationStatus") or ""
            if estado and estado != distinto_de:
                return estado
        except Exception:
            pass
        time.sleep(pausa)
    return ""


def cadena_de_mando(token):
    """
    Para cada sucursal, la **cadena de escalamiento** de su gente: sucursal → zona → región → nacional.

    Ninguna cartera puede quedar sin dueño. Si la sucursal no tiene ejecutivos, el crédito no se va a
    un ejecutivo cualquiera de la red —eso tira por la borda la decisión geográfica que se acaba de
    tomar— sino que **sube por el árbol**: al gerente de esa sucursal, y si tampoco hay, al de su
    zona, luego al de su región, y en última instancia al nacional. Siempre dentro de la misma rama.

    Devuelve `código de sucursal → [[gente del nivel 0], [gente del nivel 1], …]`, de abajo arriba.
    Cada escalón es una lista porque dentro del nivel que acabe recibiendo la cartera el reparto es
    **round-robin**: la geografía ya decidió la rama, ahí sólo queda repartir parejo.
    """
    unidades = http("GET", f"{BACKOFFICE}/sales-org/units", token=token) or []
    por_id = {u["unitId"]: u for u in unidades}

    def gente(unit_id):
        try:
            filas = http("GET", f"{BACKOFFICE}/sales-org/units/{unit_id}/assignments",
                         token=token) or []
        except Exception:
            return []
        return [a["assigneeId"] for a in filas
                if a.get("assigneeType") == "STAFF" and a.get("assigneeId")]

    # El árbol son decenas de nodos, no miles: se lee entero una vez y se indexa.
    plantilla_por_unidad = {u["unitId"]: gente(u["unitId"]) for u in unidades}

    # Los ejecutivos NO cuelgan de la sucursal: cada uno es un nodo hoja propio por debajo de ella,
    # y su asignación vive ahí. Leer sólo las asignaciones de la sucursal devuelve al responsable de
    # plaza y a nadie más — que es por lo que 21 de 22 sucursales parecían no tener ejecutivos y
    # escalaban hasta el nacional.
    hijos = {}
    for u in unidades:
        padre = u.get("parentUnitId")
        if padre:
            hijos.setdefault(padre, []).append(u)

    cadenas = {}
    for u in unidades:
        if not str(u.get("code", "")).startswith("S_"):
            continue

        # Escalón 0 = los ejecutivos de la sucursal (sus nodos hijos) más quien esté asignado
        # directamente a ella. Es el nivel que debería recibir casi toda la cartera.
        de_la_sucursal = list(plantilla_por_unidad.get(u["unitId"], []))
        for hijo in hijos.get(u["unitId"], []):
            de_la_sucursal.extend(plantilla_por_unidad.get(hijo["unitId"], []))
            # `partyRef` es el enlace modelado nodo→persona; se usa si la asignación falta.
            ref = hijo.get("partyRef")
            if ref and ref not in de_la_sucursal:
                de_la_sucursal.append(ref)

        escalones = [list(dict.fromkeys(de_la_sucursal))]   # sin duplicados, orden estable

        # Y de ahí hacia arriba: zona, región, nacional.
        nodo, guarda = por_id.get(u.get("parentUnitId")), 0
        while nodo is not None and guarda < 10:   # tope contra un árbol con ciclo
            escalones.append(plantilla_por_unidad.get(nodo["unitId"], []))
            padre = nodo.get("parentUnitId")
            nodo = por_id.get(padre) if padre else None
            guarda += 1

        cadenas[u["code"]] = escalones
    return cadenas


def repartir_cartera(token, clientes):
    """
    Le da a cada cliente un ejecutivo, **sin excepción**.

    Dos reglas, en este orden:

    1. **Geografía manda.** El crédito se queda en la rama de la sucursal más cercana a donde se
       solicitó. Antes esto era round-robin sobre los 54 ejecutivos de la red y un crédito pedido en
       Mérida podía acabar atribuido a Culiacán.
    2. **Nada queda sin dueño.** Si la sucursal no tiene ejecutivos se sube por su propia rama —zona,
       región, nacional— hasta encontrar a alguien. Caer al primer nivel con gente conserva la
       responsabilidad lo más cerca posible del cliente; saltar a la raíz la borra.

    Dentro del nivel que recibe, **round-robin**: la rama ya está decidida, ahí sólo queda repartir
    parejo para que ningún ejecutivo cargue con todo.

    La búsqueda del cliente va por apellido y se filtra por CURP. Buscar directamente por CURP
    dependía de que el índice de texto del BFF lo cubriera, y no está comprobado que lo haga — el
    resultado fue cientos de clientes sin asignar y un fallo que se manifestaba dos servicios más
    allá, como «cartera sin sucursal».
    """
    cadenas = cadena_de_mando(token)
    if not cadenas:
        print("  ! no se pudo leer la estructura comercial: no se reparte cartera", file=sys.stderr)
        return 0, len(clientes)

    turno = {}
    asignados, sin_nadie = 0, []
    escalados = Counter()

    for c in clientes:
        if not c.prospect_id:
            continue
        try:
            # Se reintenta porque el expediente se crea **async**: party-service lo abre al
            # consumir `prospect-created`, así que durante unos segundos el cliente existe como
            # prospecto y todavía no es buscable. Sin reintento, los últimos clientes de la tanda
            # se quedaban sin ejecutivo — y un cliente sin ejecutivo es cartera que no cuelga de
            # ninguna rama: el árbol comercial suma menos que el tablero y no hay forma de ver por
            # qué. El síntoma aparece en una pantalla y la causa está tres servicios atrás.
            match = None
            for intento in range(8):
                # El apellido va **escapado**. Iba crudo, y urllib codifica la URL en ASCII: cada
                # apellido con acento —Beltrán, Cárdenas, Domínguez, Jiménez, Ramírez, cinco de los
                # veintidós— reventaba con UnicodeEncodeError. El error se tragaba en el `except`
                # de más abajo y el cliente se quedaba sin ejecutivo, así que **el 23 % de la
                # cartera no colgaba de ninguna rama** y el árbol comercial nunca sumaba lo mismo
                # que el tablero. Un fallo de codificación disfrazado de problema de datos.
                party = http("GET",
                             f"{BACKOFFICE}/clients?q={urllib.parse.quote(c.ap1)}&size=100",
                             token=token)
                filas = party.get("content", party) if isinstance(party, dict) else party
                match = next((p for p in filas if p.get("curp") == c.curp), None)
                if match:
                    break
                time.sleep(0.5 * (intento + 1))
            if not match:
                sin_nadie.append((c.curp, "no se encontró el cliente tras 8 intentos"))
                continue

            escalones = cadenas.get(c.sucursal) or []
            nivel = next((i for i, gente in enumerate(escalones) if gente), None)
            if nivel is None:
                sin_nadie.append((c.curp, f"{c.sucursal} y toda su rama sin gente"))
                continue
            if nivel > 0:
                escalados[(c.sucursal, nivel)] += 1

            plantilla = escalones[nivel]
            clave = (c.sucursal, nivel)
            i = turno.get(clave, 0)
            turno[clave] = i + 1

            http("POST", f"{BACKOFFICE}/clients/{match['partyId']}/assign-executive",
                 {"executiveId": plantilla[i % len(plantilla)]}, token=token)
            asignados += 1
        except Exception as e:
            sin_nadie.append((c.curp, str(e)[:60]))

    if escalados:
        niveles = {0: "sucursal", 1: "zona", 2: "región", 3: "nacional"}
        detalle = ", ".join(f"{suc}→{niveles.get(n, f'nivel {n}')}: {v}"
                            for (suc, n), v in sorted(escalados.items()))
        print(f"  ↑ carteras escaladas por falta de ejecutivos — {detalle}")
    if sin_nadie:
        print(f"  ! {len(sin_nadie)} clientes SIN ejecutivo:", file=sys.stderr)
        for curp, motivo in sin_nadie[:5]:
            print(f"      {curp}  {motivo}", file=sys.stderr)
    return asignados, len(sin_nadie)


def envejecer_cartera(token, proporcion=0.22, rnd=None):
    """
    Atrasa una parte de la cartera para que la mora exista.

    Toda cuenta recién desembolsada está sana, así que el panel de IFRS-9 salía
    100% Stage 1 y las tres barras eran una sola: no se podía ver si el cálculo
    de etapas funciona, ni probar cobranza, ni la vista de morosidad.

    Se corren los días hacia atrás sobre las primeras mensualidades y se dispara el
    job de morosidad, que es quien decide la etapa. No se escribe la etapa a mano:
    lo que se quiere probar es justamente ese cálculo.
    """
    rnd = rnd or random.Random()
    cuentas = (http("GET", f"{BACKOFFICE}/portfolio?status=ACTIVE&size=200", token=token) or {})
    filas = cuentas.get("content", cuentas) if isinstance(cuentas, dict) else cuentas
    elegidas = rnd.sample(filas, min(len(filas), int(len(filas) * proporcion)))

    tramos = 0
    for cuenta in elegidas:
        # Tres profundidades para tocar los tres tramos: al día siguiente del corte,
        # a mes y medio (SICR) y a más de noventa días (deteriorada).
        atraso = rnd.choice([rnd.randint(5, 28), rnd.randint(35, 85), rnd.randint(95, 150)])
        cid = cuenta.get("creditAccountId") or cuenta.get("id")
        if not cid:
            continue
        for numero in (1, 2, 3):
            r = subprocess.run(
                ["docker", "run", "--rm", "--network", RED_DOCKER, "curlimages/curl:latest",
                 "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
                 f"http://credit-portfolio-service:8080/internal/test-support/accounts/{cid}"
                 f"/installments/{numero}/shift-due-date?daysFromToday={-atraso + (numero - 1) * 15}"],
                capture_output=True)
            if r.stdout.decode().strip() != "200":
                break
        tramos += 1

    subprocess.run(
        ["docker", "run", "--rm", "--network", RED_DOCKER, "curlimages/curl:latest",
         "-s", "-o", "/dev/null", "-X", "POST",
         "http://credit-portfolio-service:8080/internal/test-support/run-delinquency-job"],
        capture_output=True)
    return tramos


def llenar_mesa(token, minimo=8):
    """
    Deja al menos `minimo` solicitudes esperando decisión humana.

    El motor auto-aprueba casi todo —que es lo correcto— así que una siembra fiel
    al journey deja la mesa de análisis casi vacía, y es la pantalla que más
    importa probar: es donde una persona decide. Se mandan a revisión solicitudes
    que ya existen, en vez de fabricar otras: son casos reales del mismo lote.
    """
    pendientes = [a for a in (http("GET", f"{BACKOFFICE}/origination/applications", token=token) or [])
                  if a.get("status") in ("UNDER_MANUAL_REVIEW", "COMMITTEE_REVIEW")]
    faltan = minimo - len(pendientes)
    if faltan <= 0:
        return 0

    candidatas = [a for a in (http("GET", f"{BACKOFFICE}/origination/applications", token=token) or [])
                  if a.get("status") in ("APPROVED", "OFFER_PRESENTED", "PENDING_SCORING")]
    movidas = 0
    for i, a in enumerate(candidatas):
        if movidas >= faltan:
            break
        falso = Cliente.__new__(Cliente)
        falso.application_id = a["applicationId"]
        # Uno de cada cuatro al comité: el flujo de dos ojos y el de comité se ven
        # igual en la bandeja salvo por el estado, y hay que poder distinguirlos.
        if enviar_a_mesa(falso, comite=(i % 4 == 3)):
            movidas += 1
    return movidas


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clientes", type=int, default=40)
    ap.add_argument("--mora", type=float, default=0.22,
                    help="Proporción de la cartera a atrasar (0 la deja toda sana).")
    ap.add_argument("--mesa", type=int, default=8,
                    help="Mínimo de solicitudes esperando decisión humana al terminar.")
    ap.add_argument("--paralelo", type=int, default=4,
                    help="Journeys simultáneos. Más de 6 satura el scoring en local.")
    # Semilla nueva en cada corrida: repetirla genera las mismas CURP y teléfonos, y
    # el dominio —con razón— rechaza al segundo con 409 DUPLICATE_PROSPECT. Se puede
    # fijar para reproducir una corrida concreta.
    ap.add_argument("--equilibrado", action="store_true",
                    help="reparte los productos por igual en vez de con los pesos comerciales; "
                         "para siembras chicas donde importa que haya de todo")
    ap.add_argument("--semilla", type=int, default=None)
    args = ap.parse_args()
    semilla = args.semilla if args.semilla is not None else int(time.time())
    rnd = random.Random(semilla)

    token = http("POST", f"{BACKOFFICE}/auth/staff/login",
                 {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]
    global TOKEN_STAFF
    TOKEN_STAFF = token

    catalogo = Catalogo(token, equilibrado=args.equilibrado)
    clientes = [Cliente(rnd, i, catalogo) for i in range(args.clientes)]
    resultados = Counter()

    motivos = []

    def corre(c):
        try:
            if not alta(c):
                return "sin credencial tras el alta"
            return journey(c)
        except urllib.error.HTTPError as e:
            # El motivo importa: un 409 por CURP repetida y un 500 del scoring se
            # arreglan de formas opuestas, y un contador que sólo dice "error_500"
            # obliga a reproducirlo a mano para enterarse de cuál fue.
            detalle = ""
            try:
                cuerpo = json.loads(e.read().decode())
                detalle = str(cuerpo.get("detail", ""))[:160]
            except Exception:
                pass
            motivos.append(f"{e.code} {c.producto} {c.monto}: {detalle}")
            return f"error {e.code}"
        except Exception as ex:
            motivos.append(f"{type(ex).__name__}: {ex}"[:160])
            return "error"

    print(f"▸ {args.clientes} clientes por el journey completo ({args.paralelo} en paralelo)…")
    with futures.ThreadPoolExecutor(max_workers=args.paralelo) as pool:
        for i, r in enumerate(pool.map(corre, clientes), 1):
            resultados[r] += 1
            if i % 10 == 0:
                print(f"   {i}/{args.clientes}")

    print("\n▸ Repartiendo la cartera entre los ejecutivos…")
    asignados, sin_asignar = repartir_cartera(token, clientes)

    print("▸ Asegurando cola para la mesa de análisis…")
    a_mesa = llenar_mesa(token, minimo=args.mesa)

    en_mora = 0
    if args.mora > 0:
        print("▸ Atrasando una parte de la cartera para que exista mora…")
        en_mora = envejecer_cartera(token, proporcion=args.mora, rnd=rnd)

    print(f"\nResultado (semilla {semilla}):")
    for k, v in resultados.most_common():
        print(f"  {v:>4}  {k}")
    for m in motivos[:6]:
        print(f"        · {m}")
    print(f"  {asignados:>4}  clientes con ejecutivo de su propia sucursal")
    if sin_asignar:
        print(f"  {sin_asignar:>4}  SIN ejecutivo — su cartera queda fuera de la contabilidad por rama")
    print(f"  {a_mesa:>4}  solicitudes movidas a la mesa de análisis")
    print(f"  {en_mora:>4}  cuentas atrasadas para generar mora")

    resumen = http("GET", f"{BACKOFFICE}/dashboard/summary", token=token) or {}
    # Los nombres son los que publica el BFF hoy: `activeAccounts` y `capitalColocado`. Estaban
    # escritos como `accounts`/`totalAccounts` y `principalBalance`/`principal` —ninguno existe en
    # esa respuesta—, así que la línea de cierre de la siembra llevaba imprimiendo «? cuentas ·
    # principal ?» sin que nadie lo leyera como un fallo. Se mantiene el `or "?"` por si el resumen
    # viene vacío, que sí es un caso real, pero ya no tapa un nombre equivocado.
    cuentas = resumen.get("activeAccounts", "?")
    principal = resumen.get("capitalColocado", "?")
    print(f"\nCartera: {cuentas} cuentas · principal {principal}")
    for fila in resumen.get("byProduct", []):
        print(f"  {fila.get('productType', '?'):<16} {fila.get('accounts', 0):>4} cuentas · "
              f"{fila.get('capital', 0)}")


if __name__ == "__main__":
    main()
