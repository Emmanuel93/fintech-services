#!/usr/bin/env python3
"""Mide cuánto aguanta la plataforma y cuánto tarda una originación, contra el stack local.

    .venv/bin/python -I capacidad.py [--segundos 15] [--journeys 8] [--paralelo 6] [--lotes 24]

El video de arquitectura da cifras de capacidad y de tiempo; esto es de donde salen. Dos mediciones:

  1. **Lectura**, por el gateway como un cliente real (gateway → BFF → dominio): se sube la
     concurrencia por escalones y se anota en cada uno req/s, p50, p95, p99 y errores. «Tolerado»
     es el escalón más alto que cumple el objetivo de servicio: p95 ≤ 300 ms y menos de 1 % de
     errores. Se miden tres rutas de costo distinto: el catálogo (un salto), la cartera (un salto
     con paginado) y el tablero (un BFF que reparte la consulta entre varios servicios).
  2. **Originación**, el journey de la app (`scripts/seed-portfolio.py`) con cronómetro en cada
     salto: alta, identidad lista, cumplimiento, solicitud, scoring, oferta, contrato, firma y la
     cuenta activa. Primero en serie, para el tiempo limpio de cada paso; luego en paralelo, para
     cuántas originaciones por minuto completa el sistema.

Lo que hace una persona —dictaminar la identidad, decidir en mesa— lo hace el script, como en la
siembra, pero **su tiempo no se cuenta como del sistema**: en producción son minutos u horas de
alguien. Se anota aparte. La liquidación del SPEI tampoco se espera: el conector concilia por
consulta cada 3 minutos, y ese es el número honesto, no una medición de un simulador.

Escribe datos de verdad (prospectos, solicitudes, créditos marcados con correo `carga-…`):
respalda antes con `scripts/respalda-siembra.sh guardar`. Deja el detalle y el resumen que lee el
guion fuera del repo: `~/Documents/material-kredius/pruebas/capacidad/` (o `MATERIAL_DIR`).
"""
import argparse
import concurrent.futures as futures
import datetime
import http.client
import importlib.util
import json
import os
import random
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
from pathlib import Path

AQUI = Path(__file__).resolve().parent
SCRIPTS = (AQUI / "../../scripts").resolve()
sys.path.insert(0, str(SCRIPTS))  # seed-portfolio importa _cumplimiento por nombre
_spec = importlib.util.spec_from_file_location("siembra", SCRIPTS / "seed-portfolio.py")
siembra = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(siembra)
import _cumplimiento  # noqa: E402

GATEWAY = ("127.0.0.1", 8090)
DESTINO = Path(os.environ.get("MATERIAL_DIR", Path.home() / "Documents" / "material-kredius")) / "pruebas" / "capacidad"
SLO_P95 = 0.300
SLO_ERRORES = 0.01
ESCALONES = [1, 4, 8, 16, 32, 64]


# ── Cumplimiento por el puerto publicado, sin un contenedor por llamada ─────────────────────────
# `_cumplimiento` llama por la red de Docker con un `docker run` por petición: correcto para sembrar,
# pero añade un segundo de arranque por llamada y ensuciaría el tiempo del journey en paralelo.
def _llama_directo(method, ruta, body=None):
    conn = http.client.HTTPConnection("127.0.0.1", 8105, timeout=30)
    try:
        conn.request(method, f"{_cumplimiento.RUTA}{ruta}", json.dumps(body) if body is not None else None,
                     {"Content-Type": "application/json"})
        r = conn.getresponse()
        cuerpo = r.read()
        if r.status not in (200, 201, 204):
            return None
        return json.loads(cuerpo) if cuerpo else {}
    except (OSError, json.JSONDecodeError):
        return None
    finally:
        conn.close()


_cumplimiento._llama = _llama_directo


def percentil(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    k = (len(xs) - 1) * p
    i = int(k)
    return xs[i] if i + 1 >= len(xs) else xs[i] + (xs[i + 1] - xs[i]) * (k - i)


# ── 1. Lectura ──────────────────────────────────────────────────────────────────────────────────
def escalon(host, ruta, token, concurrencia, segundos):
    """`concurrencia` clientes con conexión persistente, pidiendo sin pausa durante `segundos`."""
    fin = time.perf_counter() + segundos
    latencias, errores = [[] for _ in range(concurrencia)], [0] * concurrencia
    cabeceras = {"Host": host, "Authorization": f"Bearer {token}"}

    def cliente(k):
        conn = http.client.HTTPConnection(*GATEWAY, timeout=30)
        while time.perf_counter() < fin:
            t0 = time.perf_counter()
            try:
                conn.request("GET", ruta, headers=cabeceras)
                r = conn.getresponse()
                r.read()
                ok = r.status < 400
            except (OSError, http.client.HTTPException):
                ok = False
                conn.close()
                conn = http.client.HTTPConnection(*GATEWAY, timeout=30)
            if ok:
                latencias[k].append(time.perf_counter() - t0)
            else:
                errores[k] += 1
        conn.close()

    antes = reinicios()
    hilos = [threading.Thread(target=cliente, args=(k,)) for k in range(concurrencia)]
    for h in hilos:
        h.start()
    # A mitad del escalón, quién está trabajando: el cuello se ve en el CPU, no en la latencia.
    time.sleep(segundos / 2)
    cpu = consumo()
    for h in hilos:
        h.join()
    xs = [x for lista in latencias for x in lista]
    total = len(xs) + sum(errores)
    despues = reinicios()
    return dict(concurrencia=concurrencia, rps=round(len(xs) / segundos, 1),
                p50=percentil(xs, .5), p95=percentil(xs, .95), p99=percentil(xs, .99),
                errores=round(sum(errores) / total, 4) if total else 0.0, cpu=cpu,
                reinicios=sorted(n for n, k in despues.items() if k > antes.get(n, 0)))


def reinicios():
    """Cuántas veces se ha reiniciado cada contenedor: un escalón que tumba un servicio debe decirlo."""
    ids = subprocess.run(["docker", "ps", "-q"], capture_output=True, text=True).stdout.split()
    r = subprocess.run(["docker", "inspect", "-f", "{{.Name}} {{.RestartCount}}"] + ids, capture_output=True, text=True)
    out = {}
    for linea in r.stdout.splitlines():
        nombre, _, n = linea.strip().lstrip("/").rpartition(" ")
        out[nombre.replace("fintech-services-", "").removesuffix("-1")] = int(n or 0)
    return out


def espera_sano(segundos=120):
    """Tras una caída, hasta que el gateway vuelva a responder la cartera: medir encima de un reinicio
    atribuiría a la ruta siguiente los errores de la anterior."""
    fin = time.time() + segundos
    while time.time() < fin:
        try:
            siembra.http("GET", f"{siembra.BACKOFFICE}/portfolio?size=1", token=espera_sano.token, reintentos=1)
            return True
        except Exception:
            time.sleep(2)
    return False


def consumo():
    """Los cinco contenedores con más CPU en este instante (docker stats)."""
    r = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}"],
                       capture_output=True, text=True)
    filas = []
    for linea in r.stdout.splitlines():
        nombre, _, cpu = linea.partition("\t")
        try:
            filas.append((nombre.replace("fintech-services-", "").removesuffix("-1"), float(cpu.rstrip("%"))))
        except ValueError:
            pass
    return sorted(filas, key=lambda f: -f[1])[:5]


def mide_lectura(rutas, segundos):
    out = {}
    for nombre, (host, ruta, token) in rutas.items():
        log(f"lectura · {nombre}: calentando")
        escalon(host, ruta, token, 4, 5)  # JIT y conexiones: el primer escalón no debe pagarlo
        escalones = []
        for c in ESCALONES:
            e = escalon(host, ruta, token, c, segundos)
            escalones.append(e)
            log(f"  c={c:>3}  {e['rps']:>7.1f} req/s  p50 {ms(e['p50'])}  p95 {ms(e['p95'])}  "
                f"p99 {ms(e['p99'])}  err {e['errores']:.1%}  · {e['cpu'][0][0]} {e['cpu'][0][1]:.0f}%")
            if e["reinicios"]:
                log(f"  ✗ se reinició {', '.join(e['reinicios'])}: esperando a que vuelva")
                espera_sano()
                time.sleep(10)
            if e["errores"] > 0.2 or (e["p95"] or 0) > 3:
                break  # ya saturó: subir más sólo carga la máquina
        dentro = [e for e in escalones if (e["p95"] or 9) <= SLO_P95 and e["errores"] < SLO_ERRORES]
        out[nombre] = dict(ruta=f"{host}{ruta}", escalones=escalones,
                           tolerado=max(dentro, key=lambda e: e["rps"]) if dentro else None,
                           maximo=max(escalones, key=lambda e: e["rps"]))
    return out


# ── 2. Originación ──────────────────────────────────────────────────────────────────────────────
class Cronometro:
    def __init__(self):
        self.pasos, self.humano = {}, {}

    def mide(self, paso, f, *args, humano=False, **kw):
        t0 = time.perf_counter()
        r = f(*args, **kw)
        (self.humano if humano else self.pasos)[paso] = time.perf_counter() - t0
        return r


def espera(f, cond, intentos=150, pausa=0.05):
    """Repite `f` hasta que `cond` se cumpla. Pausa corta: lo que se mide es el retraso del evento."""
    for _ in range(intentos):
        try:
            v = f()
            if cond(v):
                return v
        except (urllib.error.HTTPError, OSError):
            pass
        time.sleep(pausa)
    return None


def originacion(c, token_staff, producto):
    """Un journey completo con cronómetro. Devuelve los tiempos o la razón por la que no terminó."""
    MOBILE, BO, http = siembra.MOBILE, siembra.BACKOFFICE, siembra.http
    t = Cronometro()
    c.producto, c.monto, c.plazo = producto
    pre = t.mide("otp", lambda: (http("POST", f"{MOBILE}/otp/send", {"phone": c.phone}),
                                 http("POST", f"{MOBILE}/otp/verify", {"phone": c.phone,
                                                                      "code": siembra.OTP_DEV_CODE}))[1])
    pre = pre.get("preAuthToken")
    folio = t.mide("kyc", http, "POST", f"{MOBILE}/kyc/submit", {
        "phone": c.phone, "nombres": c.nombre, "apellidoPaterno": c.ap1, "apellidoMaterno": c.ap2,
        "curp": c.curp, "rfc": c.rfc, "fechaNacimiento": c.fecha_nacimiento,
        "genero": "M" if c.mujer else "H", "estadoNacimiento": c.estado, "email": c.email,
        "calle": "Av. Reforma", "numeroExterior": "100", "colonia": "Centro", "municipio": c.ciudad,
        "ciudad": c.ciudad, "estado": c.estado, "codigoPostal": "06000",
        "aceptaAvisoPrivacidad": True, "aceptaCirculo": True}, token=pre)["folioKyc"]
    creado = t.mide("registro", http, "POST", f"{MOBILE}/auth/register",
                    {"folioKyc": folio, "password": siembra.PASSWORD, "documents": []}, token=pre)
    c.prospect_id = creado.get("userId")
    # identity aprovisiona la credencial al consumir el evento del alta: el login espera al evento.
    c.token = t.mide("credencial", lambda: espera(
        lambda: (lambda s: s.get("accessToken") or s.get("token"))(
            http("POST", f"{MOBILE}/auth/login", {"phone": c.phone, "password": siembra.PASSWORD}) or {}), bool))
    if not c.token:
        return "sin credencial", t
    # compliance evalúa listas al consumir el alta (sistema); el dictamen de identidad es humano.
    if not t.mide("listas", _cumplimiento.veredicto, c.prospect_id, 150, 0.05):
        return "sin verificación", t
    if not t.mide("dictamen KYC", _cumplimiento.despeja, c.prospect_id, humano=True):
        return "bloqueada en cumplimiento", t

    cuerpo = {"prospectId": c.prospect_id, "productType": c.producto,
              "requestedAmount": c.monto, "requestedTerm": c.plazo}
    # El dictamen viaja por evento hasta la compuerta de originación: una solicitud en el mismo
    # instante todavía ve IN_REVIEW (422). Se reintenta y ese retraso se mide como propagación.
    t0 = time.perf_counter()
    for _ in range(200):
        intento = time.perf_counter()
        try:
            app = http("POST", f"{MOBILE}/credit/applications", cuerpo, token=c.token)
            break
        except urllib.error.HTTPError as e:
            if e.code != 422:
                raise
            time.sleep(0.05)
    else:
        return "dictamen sin propagar", t
    t.pasos["solicitud"] = time.perf_counter() - intento
    t.pasos["propagación del dictamen"] = intento - t0
    c.application_id = app.get("applicationId") or app.get("id")
    estado_de = lambda: (http("GET", f"{MOBILE}/credit/applications/{c.application_id}", token=c.token)
                         or {}).get("status", "")
    estado = t.mide("scoring", espera, estado_de, lambda s: s and s not in ("PENDING_SCORING", "SCORING"), 600, 0.05)
    if estado in ("UNDER_MANUAL_REVIEW", "COMMITTEE_REVIEW"):
        t.mide("mesa", http, "POST", f"{BO}/origination/applications/{c.application_id}/decision",
               {"approved": True, "decidedBy": "carga@kredius.mx"}, token=token_staff, humano=True)
        estado = t.mide("decisión aplicada", espera, estado_de, lambda s: s and s not in (estado,))
    if estado not in ("APPROVED", "PRE_APPROVED", "OFFER_PRESENTED"):
        return f"scoring: {estado or 'sin respuesta'}", t
    t.mide("oferta", http, "POST", f"{MOBILE}/credit/applications/{c.application_id}/offer", None, token=c.token)
    t.mide("acepta", http, "POST", f"{MOBILE}/credit/applications/{c.application_id}/offer/accept", None,
           token=c.token)
    t.mide("contrato", http, "POST", f"{MOBILE}/credit/applications/{c.application_id}/contract", None,
           token=c.token)
    t.mide("firma", http, "POST", f"{MOBILE}/credit/applications/{c.application_id}/contract/sign", {
        "clabeAccount": siembra.clabe(), "signatureProof": f"OTP-{random.randint(100000, 999999)}",
        "documentRef": f"contrato-{c.application_id[:8]}.pdf"}, token=c.token)
    # La cuenta la crea credit-portfolio al consumir la firma; origination pasa a DISBURSED cuando
    # escucha la activación (con la instrucción de desembolso ya en camino a disbursement).
    fin = t.mide("cuenta activa", espera, estado_de, lambda s: s == "DISBURSED", 300, 0.05)
    return ("completa" if fin == "DISBURSED" else f"firma sin activar ({estado_de()})"), t


def mide_originacion(token_staff, producto, journeys, paralelo, lotes):
    rnd = random.Random(int(time.time()))
    marca = datetime.datetime.now().strftime("%H%M%S")
    catalogo = type("Catalogo", (), {"elige": lambda self, r: (producto[0], producto[1], producto[1], producto[2],
                                                              producto[2])})()

    def cliente(i):
        c = siembra.Cliente(rnd, i, catalogo)
        c.email = f"carga-{marca}-{i}@example.mx"  # se distinguen de la siembra en cualquier consulta
        return c

    def corre(c):
        try:
            return originacion(c, token_staff, producto)
        except Exception as ex:  # el motivo va al reporte; un journey caído no tumba la medición
            return f"{type(ex).__name__}: {str(ex)[:120]}", None

    log(f"originación · {journeys} en serie")
    serie = [corre(cliente(i)) for i in range(journeys)]
    log(f"originación · {lotes} con {paralelo} en paralelo")
    inicio = time.perf_counter()
    with futures.ThreadPoolExecutor(max_workers=paralelo) as pool:
        lote = list(pool.map(corre, [cliente(1000 + i) for i in range(lotes)]))
    ventana = time.perf_counter() - inicio

    def resume(resultados):
        completas = [t for r, t in resultados if r == "completa"]
        pasos = {}
        for t in completas:
            for k, v in t.pasos.items():
                pasos.setdefault(k, []).append(v)
        humano = {}
        for t in completas:
            for k, v in t.humano.items():
                humano.setdefault(k, []).append(v)
        sistema = [sum(t.pasos.values()) for t in completas]
        return dict(completas=len(completas), total=len(resultados),
                    motivos=[r for r, _ in resultados if r != "completa"],
                    pasos={k: dict(p50=percentil(v, .5), p95=percentil(v, .95)) for k, v in pasos.items()},
                    humano={k: dict(p50=percentil(v, .5)) for k, v in humano.items()},
                    sistema=dict(p50=percentil(sistema, .5), p95=percentil(sistema, .95)))

    paralelo_r = resume(lote)
    paralelo_r.update(paralelo=paralelo, ventana=ventana,
                      por_minuto=round(paralelo_r["completas"] / ventana * 60, 1))
    return dict(producto=dict(tipo=producto[0], monto=producto[1], plazo=producto[2]),
                serie=resume(serie), paralelo=paralelo_r)


def ms(x):
    return "   –  " if x is None else f"{x * 1000:>5.0f}ms"


def log(msg):
    print(f"▸ {msg}", file=sys.stderr, flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--segundos", type=int, default=15)
    ap.add_argument("--journeys", type=int, default=8)
    ap.add_argument("--paralelo", type=int, default=6)
    ap.add_argument("--lotes", type=int, default=24)
    ap.add_argument("--solo", choices=["lectura", "originacion"])
    ap.add_argument("--rutas", nargs="*", help="sólo estas rutas de lectura (catalogo, tablero, cartera)")
    args = ap.parse_args()

    http = siembra.http
    staff = http("POST", f"{siembra.BACKOFFICE}/auth/staff/login",
                 {"email": siembra.ADMIN[0], "password": siembra.ADMIN[1]})["accessToken"]
    productos = [p for p in http("GET", f"{siembra.BACKOFFICE}/products", token=staff) or []
                 if p.get("status") == "ACTIVE" and p.get("productType") == "PERSONAL_LOAN"
                 and p.get("behavior") == "INSTALLMENT" and p.get("minAmount")]
    if not productos:
        sys.exit("no hay un préstamo personal activo en el catálogo")
    p = min(productos, key=lambda x: float(x["minAmount"]))
    producto = ("PERSONAL_LOAN", int(float(p["minAmount"])), max(int(p["minTerm"]), 12 if int(p["maxTerm"]) >= 12 else int(p["minTerm"])))

    resultado = dict(fecha=datetime.datetime.now().isoformat(timespec="minutes"), entorno=entorno(),
                     slo=dict(p95_ms=int(SLO_P95 * 1000), errores=SLO_ERRORES))
    if args.solo != "originacion":
        # Un cliente con sesión para la ruta móvil: el que deja el primer journey de prueba.
        c = siembra.Cliente(random.Random(), 0, type("C", (), {"elige": lambda s, r: (producto[0], producto[1],
                                                                                     producto[1], producto[2], producto[2])})())
        c.email = f"carga-sesion-{int(time.time())}@example.mx"
        _, tc = originacion(c, staff, producto)
        rutas = {
            "catalogo": ("mobile.localhost", "/credit/products", c.token),
            "tablero": ("backoffice.localhost", "/dashboard/summary", staff),
            "cartera": ("backoffice.localhost", "/portfolio?size=20", staff),
        }
        espera_sano.token = staff
        if args.rutas:
            rutas = {k: v for k, v in rutas.items() if k in args.rutas}
        resultado["lectura"] = mide_lectura(rutas, args.segundos)
        # El token de staff dura 15 minutos: se renueva antes de la parte de originación.
        staff = http("POST", f"{siembra.BACKOFFICE}/auth/staff/login",
                     {"email": siembra.ADMIN[0], "password": siembra.ADMIN[1]})["accessToken"]
    if args.solo != "lectura":
        resultado["originacion"] = mide_originacion(staff, producto, args.journeys, args.paralelo, args.lotes)

    carpeta = DESTINO / datetime.datetime.now().strftime("%Y%m%d-%H%M")
    carpeta.mkdir(parents=True, exist_ok=True)
    (carpeta / "resultado.json").write_text(json.dumps(resultado, ensure_ascii=False, indent=2))
    resumen = DESTINO / "capacidad.json"
    previo = json.loads(resumen.read_text()) if resumen.exists() else {}
    previo.update({k: v for k, v in resultado.items()})
    previo["lectura"] = {**(json.loads(resumen.read_text()).get("lectura", {}) if resumen.exists() else {}),
                         **resultado.get("lectura", {})}
    resumen.write_text(json.dumps(previo, ensure_ascii=False, indent=2))
    print(carpeta / "resultado.json")


def entorno():
    """Dónde se midió: sin esto, una cifra de capacidad no significa nada."""
    r = subprocess.run(["docker", "info", "--format", "{{.NCPU}} {{.MemTotal}}"], capture_output=True, text=True)
    cpus, mem = (r.stdout.split() + ["?", "0"])[:2]
    servicios = subprocess.run(["docker", "ps", "-q"], capture_output=True, text=True).stdout.split()
    return dict(cpus=cpus, memoria_gb=round(int(mem) / 2 ** 30, 1) if mem.isdigit() else None,
                contenedores=len(servicios), replicas="1 por servicio", heap="512 MB por servicio",
                postgres="una instancia compartida", maquina="laptop (Docker Desktop)")


if __name__ == "__main__":
    main()
