#!/usr/bin/env python3
"""Arma el video de la arquitectura de referencia: la receta del video comercial, para un equipo técnico.

    .venv/bin/python -I arquitectura.py <carpeta-salida> <ruta-al-repo-del-backoffice> [short] [middle] [completa]

Lo mismo que `video.py` —portada, partes con un panel que narra sincronizado, un momento, el cierre y
la música original—, pero cada parte es un diagrama animado (`diagramas.cjs`) en lugar de una
grabación: aparecen las capas, se ilumina lo que el panel nombra, un número en el diagrama coincide
con el del momento y los paquetes recorren las aristas cuando hay flujo.

Un equipo de arquitectura va a preguntar qué de lo que ve ya existe. Por eso cada parte lleva su
estado —«En el código hoy», «Propuesta» o ambos— y, donde se mezclan, lo propuesto va con borde
punteado. La fuente de cada afirmación es el código de este repo y el diseño del repo de
infraestructura (`fintech-infraestructure`, que hoy es diseño sin Terraform aplicado); lo que falta
para producción no se esconde: es la última parte, la ruta.

El guion vive aquí, como en `video.py`: se corrige el copy y se regenera sin tocar los diagramas.
"""
import copy
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

AQUI = Path(__file__).resolve().parent
# video.py da el ffmpeg local, el registro y la música: la misma maquinaria que el video comercial.
_spec = importlib.util.spec_from_file_location("video", AQUI / "video.py")
video = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(video)
log, ff = video.log, video.ff

FPS = 30

# ── Código de color: una capa, un color, en todas las escenas ────────────────────────────────
COLORES = {
    "canal": "#90CAF9", "borde": "#FFB74D", "proxy": "#B39DDB", "bff": "#FFE082",
    "dominio": "#4FC3F7", "nucleo": "#FFD54F", "eventos": "#00D3A6", "datos": "#FF8A65",
    "obs": "#F48FB1", "externo": "#B0BEC5", "plataforma": "#AED581", "alerta": "#FF6B6B",
    "hoy": "#00D3A6", "propuesta": "#FFCC80",
}
ARISTAS = {
    "sync": {"color": "#A9C8F2", "ancho": 2.5, "nombre": "Llamada síncrona"},
    "async": {"color": "#00D3A6", "ancho": 2.5, "guion": "9 7", "marcha": 32, "relleno": "#04241C",
              "nombre": "Evento en Kafka"},
    "red": {"color": "#FFB74D", "ancho": 3, "nombre": "Tráfico de entrada"},
    "datos": {"color": "#FF8A65", "ancho": 2.5, "guion": "3 5", "marcha": 16, "nombre": "Lectura y escritura"},
    "telemetria": {"color": "#F48FB1", "ancho": 2.5, "guion": "3 5", "marcha": 16, "nombre": "Telemetría"},
    "veto": {"color": "#FF6B6B", "ancho": 2.5, "guion": "6 6", "nombre": "Denegado"},
}
HOY = {"t": "En el código hoy", "c": "hoy"}
PROPUESTA = {"t": "Propuesta", "c": "propuesta"}
MIXTO = {"t": "Código + propuesta", "c": "propuesta"}
RUTA = {"t": "Hoja de ruta", "c": "propuesta"}


def N(id, x, y, w, h, t, s=None, c="dominio", i="cubo", **kw):
    return dict(id=id, x=x, y=y, w=w, h=h, t=t, s=s, c=c, icono=i, **kw)


def G(id, x, y, w, h, t, c, estilo="banda", **kw):
    return dict(id=id, x=x, y=y, w=w, h=h, t=t, c=c, estilo=estilo, **kw)


def A(id, de, a, tipo="sync", t=None, **kw):
    return dict(id=id, de=de, a=a, tipo=tipo, t=t, **kw)


def S(en, t=None, pos="arriba", **kw):
    return dict(en=en, t=t, pos=pos, **kw)


def M(texto, ve=(), foco=(), flujo=(), marcas=(), **kw):
    return dict(texto=texto, ve=list(ve), foco=list(foco), flujo=list(flujo), marcas=list(marcas), **kw)


# ── El mapa: toda la arquitectura en una pantalla ─────────────────────────────────────────────
# Siete capas de arriba abajo —canales, borde, proxy, BFF, dominio, eventos, datos— más los
# externos; a la derecha lo transversal: observabilidad y plataforma. Se usa en la vista general y
# otra vez en los pilares del Well-Architected, que iluminan sobre él lo que cumple cada uno.
DOMINIO = [["origination", "scoring", "party", "compliance"],
           ["credit-portfolio ★", "charges", "payments", "wallet"],
           ["collections", "risk", "closing", "beneficiary"],
           ["disbursement", "stp", "banking", "accounting"],
           ["notifications", "audit", "identity", "+6 servicios"]]


def chip_id(nombre):
    return "d_" + nombre.split()[0].replace("-", "").replace("+", "mas")


def mapa():
    bandas = [("canales", "Canales", "canal", 16, 88, 1280), ("borde", "Borde", "borde", 114, 88, 1280),
              ("proxy", "Proxy", "proxy", 212, 88, 1010), ("bff", "BFF", "bff", 310, 88, 1010),
              ("dominio", "Dominio", "dominio", 408, 266, 1010), ("eventos", "Eventos", "eventos", 684, 88, 1010),
              ("datos", "Datos", "datos", 782, 88, 1010), ("externos", "Externos", "externo", 880, 88, 1010)]
    grupos = [G(id, 16, y, w, h, t, c, lado=True) for id, t, c, y, h, w in bandas]
    grupos += [G("k8s", 8, 206, 1030, 572, "Clúster Kubernetes", "dominio", "limite"),
               G("obs", 1046, 212, 250, 560, "Observabilidad", "obs"),
               G("plat", 1046, 782, 250, 186, "Plataforma", "plataforma")]
    nodos = [N("app", 190, 30, 330, 60, "App móvil", "Flutter · mobile.*", "canal", "movil"),
             N("bo", 572, 30, 330, 60, "Consola de backoffice", "React · Module Federation", "canal", "web"),
             N("kyc", 954, 30, 330, 60, "Registro web", "kyc-web · liga de alta", "canal", "web"),
             N("dns", 190, 128, 250, 60, "DNS", "un host por canal", "borde", "globo", objetivo=True),
             N("cdn", 471, 128, 250, 60, "CDN", "estáticos", "borde", "nube", objetivo=True),
             N("waf", 752, 128, 250, 60, "WAF", "OWASP · tasa", "borde", "escudo", objetivo=True),
             N("lb", 1034, 128, 250, 60, "Balanceador L7", "TLS administrado", "borde", "balanceador", objetivo=True),
             N("ing", 190, 226, 380, 60, "Ingress", "ingress-nginx · TLS y host", "proxy", "proxy", objetivo=True),
             N("gw", 638, 226, 380, 60, "gateway-service", "OpenResty · JWT RS256", "proxy", "candado"),
             N("bffm", 190, 324, 380, 60, "channel-mobile", "BFF de la app", "bff", "capas"),
             N("bffb", 638, 324, 380, 60, "channel-backoffice", "BFF de la consola", "bff", "capas"),
             N("kafka", 190, 698, 600, 60, "Apache Kafka", "un tópico por hecho de dominio", "eventos", "cola"),
             N("pg", 618, 796, 400, 60, "PostgreSQL 16", "un esquema por servicio", "datos", "bd"),
             N("redis", 190, 796, 400, 60, "Redis 7", "tokens · caché · candados", "datos", "bd"),
             N("x_stp", 190, 894, 195, 60, "STP · SPEI", None, "externo", False, centro=True),
             N("x_buro", 401, 894, 195, 60, "Buró", None, "externo", False, centro=True),
             N("x_pac", 612, 894, 195, 60, "PAC · CFDI", None, "externo", False, centro=True),
             N("x_msg", 823, 894, 195, 60, "Mensajería", None, "externo", False, centro=True),
             N("otel", 1062, 256, 218, 60, "OpenTelemetry", "agente + collector", "obs", False),
             N("tempo", 1062, 352, 218, 60, "Tempo", "trazas", "obs", False),
             N("prom", 1062, 448, 218, 60, "Prometheus", "métricas", "obs", False),
             N("es", 1062, 544, 218, 60, "Elasticsearch", "logs", "obs", False),
             N("graf", 1062, 640, 218, 60, "Grafana", "tableros", "obs", False),
             N("tf", 1062, 820, 218, 56, "Terraform", "un stack por nube", "plataforma", False, objetivo=True),
             N("sec", 1062, 892, 218, 56, "Secretos", "KMS · identidad", "plataforma", False, objetivo=True)]
    for r, fila in enumerate(DOMINIO):
        for c, nombre in enumerate(fila):
            nodos.append(N(chip_id(nombre), 190 + c * 211, 420 + r * 50, 195, 42, nombre, None, "dominio",
                           False, mini=True, centro=True))
    aristas = [A("c1", "app", "waf", "red", lados=["b", "t"], db=40), A("c2", "bo", "waf", "red"),
               A("c3", "kyc", "waf", "red"), A("c4", "bo", "cdn", "red"),
               A("b1", "waf", "lb", "red"), A("b2", "lb", "ing", "red", lados=["b", "t"], my=204),
               A("p1", "ing", "gw"), A("p2", "gw", "bffm", lados=["b", "t"], da=-80),
               A("p3", "gw", "bffb"), A("f1", "bffm", "dominio"), A("f2", "bffb", "dominio"),
               A("k1", "dominio", "kafka", "async", doble=True, d=-200),
               A("k2", "dominio", "kafka", "async", doble=True, d=200),
               A("d1", "dominio", "pg", "datos"), A("o1", "dominio", "obs", "telemetria")]
    return dict(grupos=grupos, nodos=nodos, aristas=aristas)


CHIPS = [chip_id(n) for fila in DOMINIO for n in fila]
CANALES = ["canales", "app", "bo", "kyc"]
BORDE = ["borde", "dns", "cdn", "waf", "lb"]
PROXY = ["k8s", "proxy", "ing", "gw"]
BFF = ["bff", "bffm", "bffb"]
EXTERNOS = ["externos", "x_stp", "x_buro", "x_pac", "x_msg"]
OBS = ["obs", "otel", "tempo", "prom", "es", "graf"]
PLAT = ["plat", "tf", "sec"]

PANEL_MAPA = dict(capa="Vista general", sub="Siete capas y lo transversal", color="dominio", icono="capas")


def escena_vista(variante):
    if variante == "short":
        momentos = [
            M("App, consola y registro web", ve=CANALES, foco=["app", "bo", "kyc"]),
            M("Borde y proxy: WAF, ingress y gateway", ve=BORDE + PROXY, flujo=["c1", "c2", "c3", "b1", "b2", "p1"],
              marcas=[S("gw", "JWT RS256, sin llamar a identity", "abajo")]),
            M("Un BFF por canal", ve=BFF, flujo=["p2", "p3"]),
            M("27 servicios, uno por dominio", ve=["dominio"] + CHIPS, flujo=["f1", "f2"],
              marcas=[S("d_creditportfolio", "Saldos y disposiciones", "der", sep=440)]),
            M("Se hablan por eventos en Kafka", ve=["eventos", "kafka"], flujo=["k1", "k2"]),
            M("Datos, externos y telemetría", ve=["datos", "pg", "redis"] + EXTERNOS + OBS + PLAT,
              flujo=["d1", "o1"]),
        ]
    else:
        momentos = [
            M("App, consola y registro web", ve=CANALES, foco=["app", "bo", "kyc"],
              marcas=[S("bo", "Shell + 10 microfrontends", "abajo")]),
            M("El borde filtra antes de entrar", ve=BORDE, flujo=["c1", "c2", "c3", "b1"],
              marcas=[S("waf", "OWASP Top 10 y límite de tasa", "abajo")]),
            M("Dos proxies: plataforma y aplicación", ve=PROXY, flujo=["b2", "p1"],
              marcas=[S("ing", "Del clúster: TLS y host", "abajo"),
                      S("gw", "De la app: JWT y límites", "abajo")]),
            M("Un BFF por canal", ve=BFF, flujo=["p2", "p3"],
              marcas=[S("bffb", "Compone pantallas y aplica permisos", "abajo")]),
            M("27 servicios, uno por dominio", ve=["dominio"] + CHIPS, flujo=["f1", "f2"],
              marcas=[S("d_creditportfolio", "El núcleo: saldos y disposiciones", "der", sep=440)]),
            M("Los dominios se hablan por eventos", ve=["eventos", "kafka"], flujo=["k1", "k2"],
              marcas=[S("kafka", "Hechos, no comandos", "der", sep=24)]),
            M("Una base por servicio, sin joins", ve=["datos", "pg", "redis"], flujo=["d1"],
              marcas=[S("pg", "Un esquema por servicio", "arriba", dx=-100)]),
            M("Al lado: externos, telemetría e IaC", ve=EXTERNOS + OBS + PLAT, flujo=["o1"],
              marcas=[S("graf", "Trazas, métricas y logs", "abajo", sep=12)]),
        ]
    return dict(corto="Vista general", estado=MIXTO, panel=dict(PANEL_MAPA, titulo="La plataforma en siete capas"),
                diagrama=mapa(), momentos=momentos, leyenda=["sync", "async", "objetivo"])


# ── Proxy: norte-sur ──────────────────────────────────────────────────────────────────────────
def diagrama_proxy():
    fases = [("f1", 136, "1 · Host → canal", "mobile · backoffice · kyc", "red"),
             ("f2", 264, "2 · ¿Ruta pública?", "límite de tasa por IP", "reloj"),
             ("f3", 392, "3 · Verifica el JWT", "RS256 · exp · iss · canal", "candado"),
             ("f4", 520, "4 · Header-trust", "borra e inyecta X-User-Id", "escudo"),
             ("f5", 776, "5 · Traza y bitácora", "traceparent · JSON", "grafica")]
    nodos = [N("lb", 24, 440, 200, 76, "Balanceador", "L7 de la nube", "borde", False, objetivo=True),
             N("ing", 284, 440, 220, 76, "Ingress", "ingress-nginx", "proxy", "proxy", objetivo=True)]
    nodos += [N(id, 572, y, 308, 84, t, s, "proxy", False) for id, y, t, s, i in fases]
    nodos += [N("r404", 960, 140, 310, 76, "404", "host desconocido", "alerta", "escudo"),
              N("ben", 960, 268, 310, 76, "beneficiary · pública", "solo /public, sin JWT", "dominio", "cubo"),
              N("r401", 960, 396, 310, 76, "401 · 429", "token inválido · exceso", "alerta", "escudo"),
              N("bffm", 960, 524, 310, 76, "channel-mobile", "BFF de la app", "bff", "capas"),
              N("bffb", 960, 652, 310, 76, "channel-backoffice", "exige canal BACKOFFICE", "bff", "capas")]
    grupos = [G("k8s", 252, 36, 1044, 912, "Clúster Kubernetes", "dominio", "limite"),
              G("gwg", 548, 96, 356, 820, "gateway-service · OpenResty", "proxy", "limite")]
    aristas = [A("e_lb", "lb", "ing", "red"), A("e_ing", "ing", "f1", lados=["r", "l"]),
               A("e12", "f1", "f2"), A("e23", "f2", "f3"), A("e34", "f3", "f4"), A("e45", "f4", "f5"),
               A("e404", "f1", "r404", "veto"), A("eben", "f2", "ben"), A("e401", "f3", "r401", "veto"),
               A("em", "f4", "bffm"), A("eb", "f4", "bffb", mx=922)]
    return dict(grupos=grupos, nodos=nodos, aristas=aristas)


def escena_proxy(variante):
    if variante == "short":
        momentos = [
            M("Ingress: TLS y host, en cualquier nube", ve=["lb", "k8s", "ing", "gwg", "f1", "r404"],
              flujo=["e_lb", "e_ing"], marcas=[S("ing", "Igual en GKE, EKS y AKS", "abajo")]),
            M("Gateway: JWT RS256 y límite de tasa", ve=["f2", "f3", "ben", "r401"], flujo=["e12", "e23"],
              marcas=[S("f3", "Sin llamar a identity", "izq")]),
            M("El dominio recibe identidad, no tokens", ve=["f4", "f5", "bffm", "bffb"],
              flujo=["e34", "em", "eb"], marcas=[S("f4", "X-User-Id · X-Roles · X-Channel", "izq")]),
        ]
    else:
        momentos = [
            M("Ingress: la entrada estándar", ve=["lb", "k8s", "ing"], flujo=["e_lb"],
              marcas=[S("ing", "Igual en GKE, EKS y AKS", "abajo")]),
            M("El gateway enruta por subdominio", ve=["gwg", "f1", "r404"], flujo=["e_ing", "e404"],
              marcas=[S("r404", "Deny by default", "abajo")]),
            M("Rutas públicas, con límite de tasa", ve=["f2", "ben"], flujo=["e12", "eben"],
              marcas=[S("f2", "login 5/min · OTP 3/min", "izq")]),
            M("Rutas protegidas: JWT RS256 local", ve=["f3", "r401"], flujo=["e23", "e401"],
              marcas=[S("f3", "Sin llamar a identity", "izq")]),
            M("Borra e inyecta la identidad", ve=["f4", "bffm", "bffb"], flujo=["e34", "em", "eb"],
              marcas=[S("f4", "X-User-Id · X-Roles · X-Channel", "izq")]),
            M("Cada request deja traza y bitácora", ve=["f5"], flujo=["e45"],
              marcas=[S("f5", "traceparent W3C y log JSON", "izq")]),
        ]
    return dict(corto="Proxy", estado=MIXTO,
                panel=dict(capa="Capa de proxy", sub="Norte-sur · L7", color="proxy", icono="proxy",
                           titulo="Dos proxies, dos responsabilidades"),
                diagrama=diagrama_proxy(), momentos=momentos, leyenda=["sync", "red", "veto", "objetivo"])


# ── Kubernetes: este-oeste ───────────────────────────────────────────────────────────────────
def escena_kubernetes(_):
    nodos = [N("ajeno", 40, 120, 300, 76, "pod ajeno", "sin permiso", "alerta", "cubo"),
             N("bff", 40, 320, 300, 76, "channel-backoffice", "pod del BFF", "bff", "capas"),
             N("dns", 40, 520, 300, 76, "CoreDNS", "nombre → ClusterIP", "proxy", "globo"),
             N("svc", 420, 320, 280, 76, "Service", "credit-portfolio:8080", "proxy", False),
             N("kp", 420, 520, 280, 76, "kube-proxy", "iptables · IPVS", "proxy", "balanceador"),
             N("p1", 790, 180, 240, 76, "pod 1", "readiness ✓", "dominio", "cubo"),
             N("p2", 790, 320, 240, 76, "pod 2", "readiness ✓", "dominio", "cubo"),
             N("p3", 790, 460, 240, 76, "pod 3", "arrancando", "externo", "cubo"),
             N("hpa", 1092, 200, 176, 76, "HPA", "2 → 6 réplicas", "plataforma", False),
             N("pdb", 1092, 400, 176, 76, "PDB", "mín. 2 en pie", "plataforma", False),
             N("od", 200, 830, 420, 84, "On-demand", "base estable", "plataforma", "cubo"),
             N("spot", 692, 830, 420, 84, "Spot", "ráfagas · tolera desalojo", "plataforma", "cubo")]
    grupos = [G("ns", 24, 40, 1264, 680, "namespace · crédito", "dominio", "limite"),
              G("dep", 770, 140, 280, 420, "Deployment", "dominio", "limite"),
              G("pool", 24, 760, 1264, 200, "Grupos de nodos", "plataforma")]
    aristas = [A("e_bs", "bff", "svc"), A("e_bd", "bff", "dns"), A("e_s1", "svc", "p1", mx=745),
               A("e_s2", "svc", "p2"), A("e_aj", "ajeno", "svc", "veto", "NetworkPolicy", lados=["r", "t"], pos=0.3),
               A("e_sk", "svc", "kp", flecha=False), A("e_hpa", "hpa", "dep", "sync", c="plataforma")]
    momentos = [
        M("Un Deployment por servicio", ve=["ns", "dep", "p1", "p2", "p3"], foco=["dep"],
          marcas=[S("dep", "Réplicas sin estado", "der", sep=34)]),
        M("Un Service da un nombre estable", ve=["bff", "svc", "dns"], flujo=["e_bs", "e_bd"],
          marcas=[S("svc", "El mismo nombre que en Docker", "arriba")]),
        M("kube-proxy reparte entre pods listos", ve=["kp"], flujo=["e_s1", "e_s2"],
          marcas=[S("p3", "Readiness: aún sin tráfico", "abajo"),
                  S("kp", "Spring Boot expone liveness y readiness", "abajo")]),
        M("HPA escala; PDB protege", ve=["hpa", "pdb"], foco=["hpa", "pdb"], flujo=["e_hpa"],
          marcas=[S("hpa", "Escala por CPU", "abajo"), S("pdb", "Un drenado no lo tumba", "abajo")]),
        M("NetworkPolicy: solo entra quien debe", ve=["ajeno"], flujo=["e_aj"],
          marcas=[S("ajeno", "Denegado por defecto", "abajo")]),
        M("Nodos spot sobre una base on-demand", ve=["pool", "od", "spot"], foco=["od", "spot"],
          marcas=[S("spot", "Ráfagas a menor costo", "arriba")]),
    ]
    return dict(corto="Kubernetes", estado=PROPUESTA,
                panel=dict(capa="Proxy de Kubernetes", sub="Este-oeste · dentro del clúster", color="proxy",
                           icono="timon", titulo="Nombres estables, pods que van y vienen"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["sync", "veto"])


# ── Dentro de un servicio: hexagonal ─────────────────────────────────────────────────────────
def escena_servicio(_):
    nodos = [N("ai1", 24, 220, 220, 64, "REST", "@RestController", "canal", False),
             N("ai2", 24, 440, 220, 64, "Kafka", "@KafkaListener", "eventos", False),
             N("ai3", 24, 660, 220, 64, "Reloj", "@Scheduled", "canal", False),
             N("pi1", 274, 224, 230, 56, "RegisterPaymentOrder", None, "proxy", False, mini=True, centro=True),
             N("pi2", 274, 444, 230, 56, "PollSettlements", None, "proxy", False, mini=True, centro=True),
             N("pi3", 274, 664, 230, 56, "RelayOutbox", None, "proxy", False, mini=True, centro=True),
             N("d1", 534, 260, 244, 64, "StpPaymentOrder", "agregado", "nucleo", False),
             N("d2", 534, 440, 244, 64, "CadenaOriginal", "firma la orden", "nucleo", False),
             N("d3", 534, 620, 244, 64, "OutboxMessage", "orden pendiente", "nucleo", False),
             N("po1", 800, 224, 246, 56, "StpGatewayPort", None, "proxy", False, mini=True, centro=True),
             N("po2", 800, 444, 246, 56, "OutboxMessageRepository", None, "proxy", False, mini=True, centro=True),
             N("po3", 800, 664, 246, 56, "StpEventPublisher", None, "proxy", False, mini=True, centro=True),
             N("ao1", 1068, 140, 220, 64, "STP real", "HTTP firmado", "externo", False),
             N("ao2", 1068, 290, 220, 64, "Simulador", "nunca en prod", "externo", False),
             N("ao3", 1068, 440, 220, 64, "PostgreSQL", "JPA · Liquibase", "datos", False),
             N("ao4", 1068, 660, 220, 64, "Kafka", "tras el commit", "eventos", False)]
    grupos = [G("svc", 12, 60, 1288, 900, "stp-service", "dominio", "limite"),
              G("app", 258, 120, 796, 760, "application · casos de uso y puertos", "proxy"),
              G("dom", 520, 200, 272, 580, "domain", "nucleo", "limite")]
    aristas = [A("x1", "ai1", "pi1"), A("x2", "ai2", "pi1", mx=259), A("x3", "ai3", "pi2", mx=259),
               A("x3b", "ai3", "pi3"), A("y1", "pi1", "dom"), A("y2", "pi2", "dom"), A("y3", "pi3", "dom"),
               A("z1", "dom", "po1"), A("z2", "dom", "po2"), A("z3", "dom", "po3"),
               A("w1", "po1", "ao1", mx=1057), A("w2", "po1", "ao2", mx=1057), A("w3", "po2", "ao3"),
               A("w4", "po3", "ao4", "async")]
    momentos = [
        M("El dominio no conoce adaptadores", ve=["svc", "app", "dom", "d1", "d2", "d3"], foco=["dom"],
          marcas=[S("d2", "La firma vive en el dominio", "der", sep=40)]),
        M("Entra por REST, por evento o por reloj", ve=["ai1", "ai2", "ai3", "pi1", "pi2", "pi3"],
          flujo=["x1", "x2", "x3", "x3b"]),
        M("Sale por puertos que define el dominio", ve=["po1", "po2", "po3"], flujo=["z1", "z2", "z3"],
          marcas=[S("po1", "La interfaz es nuestra", "arriba")]),
        M("Cada puerto, uno o más adaptadores", ve=["ao1", "ao2", "ao3", "ao4"], flujo=["w1", "w3", "w4"]),
        M("El simulador no arranca en producción", foco=["ao2"], flujo=["w2"],
          marcas=[S("ao2", "Lista blanca de perfiles", "izq", sep=290)]),
        M("Otra nube, otro adaptador", foco=["ao1", "ao3", "ao4"],
          marcas=[S("ao3", "Cloud SQL, RDS o Azure", "izq", sep=290)]),
    ]
    return dict(corto="Servicio", estado=HOY,
                panel=dict(capa="Servicio de dominio", sub="Hexagonal · ejemplo: stp-service", color="nucleo",
                           icono="hexagono", titulo="El dominio al centro, la nube en los bordes"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["sync", "async"])


# ── Procesos asíncronos ──────────────────────────────────────────────────────────────────────
CONSUMIDORES = ["accounting", "charges", "collections", "risk", "wallet",
                "notifications", "closing", "audit", "payments", "commission"]


def escena_eventos(variante):
    nodos = [N("cp", 40, 250, 260, 80, "credit-portfolio", "productor", "dominio", "cubo"),
             N("ob", 40, 420, 260, 80, "Outbox", "event_publication", "datos", "bd", objetivo=True),
             N("topic", 380, 180, 340, 64, "Tópico", "credit-portfolio.balance-updated", "eventos", False),
             N("t0", 380, 270, 340, 46, "Partición 0", None, "eventos", False, mini=True),
             N("t1", 380, 330, 340, 46, "Partición 1 · cuenta 7f3a…", None, "eventos", False, mini=True),
             N("t2p", 380, 390, 340, 46, "Partición 2", None, "eventos", False, mini=True),
             N("ld", 60, 740, 300, 80, "Tópico", "disbursement.stp-requested", "eventos", False),
             N("lc", 420, 740, 220, 80, "stp-service", "consumidor", "dominio", "cubo"),
             N("lr", 700, 740, 250, 80, "Reintentos", "1, 2, 4 y 8 s", "alerta", "reintento"),
             N("dlt", 1010, 740, 260, 80, "DLT", "<tópico>.dlt", "alerta", "doc"),
             N("idem", 420, 866, 360, 72, "Idempotencia", "UNIQUE(source_event_id)", "datos", "llave")]
    for k, nombre in enumerate(CONSUMIDORES):
        nodos.append(N(f"c_{nombre}", 852 + (k % 2) * 214, 96 + (k // 2) * 72, 196, 52, nombre, None,
                       "dominio", False, mini=True, centro=True))
    grupos = [G("tg", 364, 160, 372, 300, None, "eventos", "limite"),
              G("cg", 836, 60, 440, 440, "Consumidores · un grupo cada uno", "dominio", "limite"),
              G("lane", 24, 680, 1264, 280, "Si mueve dinero: stp y disbursement", "alerta")]
    aristas = [A("e_cp", "cp", "tg", "async"), A("e_cg", "tg", "cg", "async", d=-40),
               A("e_ob", "cp", "ob", "datos", tarde=True), A("e_obt", "ob", "tg", "async", tarde=True, lados=["r", "l"]),
               A("l1", "ld", "lc", "async"), A("l2", "lc", "lr", "sync", c="alerta"),
               A("l3", "lr", "dlt", "async", c="alerta"), A("l4", "lc", "idem", "datos")]
    con = [f"c_{n}" for n in CONSUMIDORES]
    if variante == "short":
        momentos = [
            M("Un hecho, un tópico; diez consumidores", ve=["cp", "tg", "topic", "t0", "t1", "t2p", "cg"] + con,
              flujo=["e_cp", "e_cg"], marcas=[S("cp", "Publica y no espera", "abajo")]),
            M("Si mueve dinero: reintento y DLT", ve=["lane", "ld", "lc", "lr", "dlt"], flujo=["l1", "l2", "l3"],
              marcas=[S("dlt", "Nada se pierde en silencio", "arriba")]),
            M("Reentregar es seguro: idempotencia", ve=["idem"], flujo=["l4"],
              marcas=[S("idem", "Un duplicado se ignora", "der")]),
        ]
    else:
        momentos = [
            M("Un hecho, un tópico: dominio.hecho", ve=["cp", "tg", "topic"], flujo=["e_cp"],
              marcas=[S("cp", "Publica y no espera a nadie", "abajo")]),
            M("La llave es el id: orden por cuenta", ve=["t0", "t1", "t2p"], foco=["t1"],
              marcas=[S("t1", "Misma cuenta, misma partición", "der", sep=150)]),
            M("Diez consumidores, cada uno a su ritmo", ve=["cg"] + con, flujo=["e_cg"],
              marcas=[S("c_accounting", "Su grupo, su offset", "izq", sep=34)]),
            M("El evento trae el estado completo", foco=["topic"],
              marcas=[S("topic", "Sin llamadas de vuelta", "arriba")]),
            M("Si mueve dinero: reintento y DLT", ve=["lane", "ld", "lc", "lr", "dlt"], flujo=["l1", "l2", "l3"],
              marcas=[S("dlt", "Nada se pierde en silencio", "arriba")]),
            M("Reentregar es seguro: idempotencia", ve=["idem"], flujo=["l4"],
              marcas=[S("idem", "Un duplicado se ignora", "der")]),
            M("Propuesta: outbox en cada productor", ve=["ob"], flujo=["e_ob", "e_obt"],
              marcas=[S("ob", "Spring Modulith ya trae la tabla", "abajo")]),
        ]
    return dict(corto="Eventos", estado=MIXTO,
                panel=dict(capa="Procesos asíncronos", sub="Kafka · coreografía", color="eventos", icono="cola",
                           titulo="Los dominios se enteran por eventos"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["async", "sync", "datos", "objetivo"])


# ── Un desembolso de punta a punta: diagrama de secuencia ───────────────────────────────────
VIDAS = [("h_or", "origination", "originación", "dominio"), ("h_cp", "credit-portfolio", "cuentas", "dominio"),
         ("h_di", "disbursement", "pagos", "dominio"), ("h_st", "stp", "conector SPEI", "dominio"),
         ("h_ex", "STP", "externo", "externo"), ("h_bk", "banking", "tesorería", "dominio"),
         ("h_re", "reaccionan", "4 servicios", "dominio")]
CX = [96 + 186 * k for k in range(7)]


def escena_spei(_):
    nodos, grupos = [], []
    for k, (id, t, s, c) in enumerate(VIDAS):
        nodos.append(N(id, CX[k] - 88, 24, 176, 64, t, s, c, False, mini=True, centro=True))
        grupos.append(G(f"v{k}", CX[k], 96, 0, 864, None, c, "vida"))
    x = dict(zip([v[0] for v in VIDAS], CX))

    def msg(id, de, a, y, t, tipo="async", **kw):
        return A(id, [x[de], y], [x[a], y], tipo, t, tarde=True, ey=-20, **kw)

    aristas = [msg("m1", "h_or", "h_cp", 170, "credit-product-creation-requested"),
               msg("m2", "h_cp", "h_di", 260, "credit-account-activated"),
               msg("m3a", "h_di", "h_bk", 350, "¿de qué cuenta sale? (REST)", "sync"),
               msg("m3", "h_di", "h_st", 440, "disbursement.stp-requested"),
               msg("m4", "h_st", "h_ex", 530, "registra la orden firmada", "sync", c="externo"),
               msg("m5", "h_st", "h_ex", 620, "consulta cada 3 min", "sync", c="externo"),
               msg("m5r", "h_ex", "h_st", 680, "LIQUIDADA · CEP", "sync", c="externo"),
               msg("m6", "h_st", "h_di", 770, "stp.order-settled"),
               msg("m6b", "h_st", "h_bk", 805, "stp.order-settled"),
               msg("m7", "h_di", "h_cp", 850, "disbursement.completed"),
               msg("m8", "h_cp", "h_re", 930, "disposition-completed · balance-updated")]
    vidas = [v[0] for v in VIDAS] + [f"v{k}" for k in range(7)]
    idx = {v[0]: k for k, v in enumerate(VIDAS)}
    actores = lambda *hs: [h for h in hs] + [f"v{idx[h]}" for h in hs]
    momentos = [
        M("Aprobada: originación pide la cuenta", ve=vidas, flujo=["m1"], foco=actores("h_or", "h_cp")),
        M("Cartera activa y pide el desembolso", flujo=["m2"], foco=actores("h_cp", "h_di"),
          marcas=[S([x["h_cp"] + 40, 262], "13 servicios escuchan este hecho", "abajo", sep=18)]),
        M("Disbursement elige la cuenta de salida", flujo=["m3a", "m3"], foco=actores("h_di", "h_bk", "h_st"),
          marcas=[S([x["h_bk"], 352], "Ruta de pago por REST", "abajo", sep=16)]),
        M("stp firma la orden y la registra", flujo=["m4"], foco=actores("h_st", "h_ex"),
          marcas=[S([x["h_st"] + 90, 532], "Outbox: hasta 6 intentos", "abajo", sep=16)]),
        M("Un poller concilia, sin webhooks", flujo=["m5", "m5r"], foco=actores("h_st", "h_ex"),
          marcas=[S([x["h_ex"] + 20, 682], "Sin tráfico entrante", "der", sep=60)]),
        M("Liquidada: el hecho vuelve por Kafka", flujo=["m6", "m6b", "m7"], foco=actores("h_st", "h_di", "h_bk", "h_cp"),
          marcas=[S([x["h_bk"], 807], "Rastro del dinero con CEP", "der", sep=24)]),
        M("Contabilidad y avisos reaccionan", flujo=["m8"], foco=actores("h_cp", "h_re"),
          marcas=[S("h_re", "Póliza y aviso, sin captura", "abajo", sep=26)]),
    ]
    return dict(corto="Un desembolso", estado={"t": "En el código · STP simulado", "c": "hoy"},
                atenua={"arista": 0.4, "nodo": 0.4, "grupo": 0.5},
                panel=dict(capa="Flujo de punta a punta", sub="Coreografía por eventos", color="eventos",
                           icono="rayo", titulo="Un SPEI, del contrato al CEP"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["async", "sync"])


# ── Datos ───────────────────────────────────────────────────────────────────────────────────
def escena_datos(_):
    servicios = [("s1", "sc1", "origination", "origination"), ("s2", "sc2", "credit-portfolio", "credit_portfolio"),
                 ("s3", "sc3", "accounting", "accounting"), ("s4", "sc4", "banking", "banking")]
    nodos, aristas = [], []
    for k, (s, sc, nombre, esquema) in enumerate(servicios):
        nodos.append(N(s, 40 + k * 250, 40, 220, 72, nombre, "servicio", "dominio", False))
        nodos.append(N(sc, 40 + k * 250, 250, 220, 72, esquema, "esquema", "datos", False))
        aristas.append(A(f"e_{s}", s, sc, "datos"))
    nodos += [N("s5", 1040, 40, 236, 72, "+23 servicios", None, "dominio", False),
              N("sc5", 1040, 250, 236, 72, "+23 esquemas", None, "datos", False),
              N("lq", 60, 420, 560, 72, "Liquibase", "~300 cambios, uno por servicio", "datos", "git"),
              N("ext", 660, 420, 600, 72, "Extensiones", "ltree · pg_trgm", "datos", "engrane"),
              N("redis", 24, 610, 600, 84, "Redis 7", "tokens · caché · candado de cierres", "datos", "bd"),
              N("m1", 24, 790, 400, 84, "Privada y cifrada", "IP privada · CMEK", "plataforma", "candado", objetivo=True),
              N("m2", 456, 790, 400, 84, "HA + PITR", "multizona en producción", "plataforma", "respaldo", objetivo=True),
              N("m3", 888, 790, 400, 84, "Un usuario por esquema", "mínimo privilegio", "plataforma", "llave",
                objetivo=True)]
    grupos = [G("pg", 24, 190, 1264, 340, "PostgreSQL 16 · una instancia", "datos", "limite")]
    aristas += [A("e_s5", "s5", "sc5", "datos"),
                A("x23", "sc2", "sc3", "veto", "sin joins ni FK", tarde=True, flecha=False, ey=58)]
    momentos = [
        M("Cada servicio es dueño de su esquema", ve=["s1", "s2", "s3", "s4", "s5", "pg", "sc1", "sc2", "sc3", "sc4", "sc5"],
          flujo=["e_s1", "e_s2", "e_s3", "e_s4", "e_s5"]),
        M("Referencias por UUID, nunca joins", ve=["x23"], foco=["sc2", "sc3", "x23"],
          marcas=[S("sc3", "Se integran por eventos", "abajo", sep=110)]),
        M("Liquibase versiona cada esquema", ve=["lq", "ext"], foco=["lq", "ext"],
          marcas=[S("lq", "JPA valida el esquema al arrancar", "abajo")]),
        M("Redis guarda estado efímero", ve=["redis"], foco=["redis"],
          marcas=[S("redis", "Nada de negocio vive solo aquí", "der")]),
        M("En la nube: privada, cifrada, con PITR", ve=["m1", "m2", "m3"], foco=["m1", "m2", "m3"],
          marcas=[S("m3", "Hoy: un usuario para todos", "arriba")]),
    ]
    return dict(corto="Datos", estado=MIXTO,
                panel=dict(capa="Datos", sub="Base por servicio", color="datos", icono="bd",
                           titulo="Cada servicio, dueño de sus datos"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["datos", "veto", "objetivo"])


# ── Seguridad ───────────────────────────────────────────────────────────────────────────────
def escena_seguridad(_):
    camino = [("l1", 40, "WAF", "OWASP · límite de tasa", "borde", "escudo", True),
              ("l2", 152, "TLS de punta a punta", "borde, ingress y mTLS", "borde", "candado", True),
              ("l3", 264, "gateway · JWT RS256", "firma, exp, iss y canal", "proxy", "candado", False),
              ("l4", 376, "Header-trust", "el dominio no ve el JWT", "proxy", "escudo", False),
              ("l5", 488, "BFF · capacidades", "rol → recurso.acción", "bff", "llave", False),
              ("l6", 600, "NetworkPolicy", "solo BFF → dominio", "alerta", "red", True),
              ("l7", 712, "Servicio de dominio", "ROLE_* desde headers", "dominio", "cubo", False),
              ("l8", 856, "audit-service", "bitácora append-only", "obs", "doc", False)]
    nodos = [N(id, 40, y, 520, 76, t, s, c, i, objetivo=o) for id, y, t, s, c, i, o in camino]
    nodos += [N("k1", 720, 152, 560, 84, "Llaves de firma", "private.pem solo en identity", "proxy", "llave"),
              N("k2", 720, 352, 560, 84, "Gestor de secretos", "Secret Manager · Key Vault", "plataforma", "llave",
                objetivo=True),
              N("k3", 720, 552, 560, 84, "Llaves de STP", "sobre AES-256-GCM", "datos", "candado"),
              N("k4", 720, 752, 560, 84, "Identidad de pod", "Workload Identity · IRSA", "plataforma", "usuario",
                objetivo=True)]
    aristas = [A(f"c{k}", camino[k][0], camino[k + 1][0]) for k in range(6)]
    aristas += [A("c7", "l7", "l8", "async"), A("kk", "k1", "l3", "sync", "llave pública", lados=["l", "r"])]
    momentos = [
        M("El borde frena el abuso", ve=["l1", "l2"], foco=["l1", "l2"], flujo=["c0"]),
        M("Firma identity; verifica el gateway", ve=["l3", "k1"], flujo=["c1", "kk"],
          marcas=[S("k1", "Nadie llama a identity por request", "abajo")]),
        M("El dominio confía en headers", ve=["l4"], flujo=["c2"],
          marcas=[S("l4", "Exige una red de confianza", "der", sep=60)]),
        M("El BFF aplica la matriz de permisos", ve=["l5"], flujo=["c3"],
          marcas=[S("l5", "14 roles · recurso.acción", "der", sep=60)]),
        M("NetworkPolicy y mTLS cierran la red", ve=["l6", "l7"], flujo=["c4", "c5"],
          marcas=[S("l6", "Así nadie falsifica un header", "der", sep=60)]),
        M("Secretos fuera de la imagen", ve=["k2", "k3", "k4"], foco=["k2", "k3", "k4"],
          marcas=[S("k3", "Hoy: cifradas con sobre", "abajo")]),
        M("Todo queda en bitácora", ve=["l8"], flujo=["c7"],
          marcas=[S("l8", "CNBV · CONDUSEF · UIF", "der", sep=60)]),
    ]
    return dict(corto="Seguridad", estado=MIXTO,
                panel=dict(capa="Seguridad", sub="Defensa en profundidad", color="alerta", icono="escudo",
                           titulo="Cada capa supone que la anterior falló"),
                diagrama=dict(grupos=[], nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["sync", "async", "objetivo"])


# ── Observabilidad ──────────────────────────────────────────────────────────────────────────
def escena_observabilidad(_):
    nodos = [N("s1", 40, 120, 300, 80, "gateway-service", "otel.lua · traceparent", "proxy", "candado"),
             N("s2", 40, 300, 300, 80, "Servicios Java", "agente OTel", "dominio", "cubo"),
             N("s3", 40, 480, 300, 80, "Logs JSON", "trace_id en cada línea", "dominio", "doc"),
             N("col", 440, 160, 280, 96, "OTel Collector", "memory_limiter · batch", "obs", False),
             N("fb", 440, 480, 280, 80, "Fluent Bit", "lee stdout", "obs", "proxy"),
             N("tempo", 820, 120, 230, 80, "Tempo", "trazas · 48 h", "obs", "grafica"),
             N("prom", 820, 300, 230, 80, "Prometheus", "cada 15 s", "obs", "grafica"),
             N("es", 820, 480, 230, 80, "Elasticsearch", "logs por día", "obs", "lupa"),
             N("graf", 1090, 280, 200, 120, "Grafana", "tableros", "obs", "grafica")]
    aristas = [A("a1", "s1", "col", "telemetria"), A("a2", "s2", "col", "telemetria", da=-22),
               A("a3", "col", "tempo", "telemetria"), A("a4", "s2", "prom", "telemetria", "/actuator/prometheus", pos=0.62),
               A("a5", "s3", "fb", "telemetria"), A("a6", "fb", "es", "telemetria"),
               A("g1", "tempo", "graf", mx=1070), A("g2", "prom", "graf"), A("g3", "es", "graf", mx=1070)]
    momentos = [
        M("Agente OTel en cada JVM, sin código", ve=["s2", "col"], flujo=["a2"],
          marcas=[S("s2", "JAVA_TOOL_OPTIONS", "abajo", sep=16)]),
        M("El gateway abre la traza", ve=["s1", "tempo"], flujo=["a1", "a3"],
          marcas=[S("s1", "traceparent W3C", "arriba", sep=20)]),
        M("Prometheus mide cada servicio", ve=["prom"], flujo=["a4"],
          marcas=[S("prom", "Tráfico, errores y p95/p99", "abajo")]),
        M("Logs con su trace_id", ve=["s3", "fb", "es"], flujo=["a5", "a6"],
          marcas=[S("es", "Del log a la traza", "abajo")]),
        M("Grafana une las tres señales", ve=["graf"], flujo=["g1", "g2", "g3"],
          marcas=[S("graf", "Propuesta: alertas de negocio", "abajo")]),
    ]
    return dict(corto="Observabilidad", estado=HOY,
                panel=dict(capa="Observabilidad", sub="OpenTelemetry · tres señales", color="obs", icono="grafica",
                           titulo="Cada request deja rastro"),
                diagrama=dict(grupos=[], nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["telemetria", "sync"])


# ── Cualquier nube ──────────────────────────────────────────────────────────────────────────
NUBES = [("gcp", "Google Cloud", [("GKE", "Kubernetes"), ("Cloud SQL", "PostgreSQL"), ("Cloud Armor", "WAF"),
                                 ("Secret Manager", "secretos"), ("Workload Identity", "identidad de pod"),
                                 ("Artifact Registry", "imágenes")]),
         ("aws", "AWS", [("EKS + Karpenter", "Kubernetes"), ("RDS", "PostgreSQL"), ("AWS WAF", "WAF"),
                         ("Secrets Manager", "secretos"), ("IRSA", "identidad de pod"), ("ECR", "imágenes")]),
         ("az", "Azure", [("AKS", "Kubernetes"), ("Database for PostgreSQL", "PostgreSQL"),
                          ("Front Door WAF", "WAF"), ("Key Vault", "secretos"),
                          ("Managed Identity", "identidad de pod"), ("ACR", "imágenes")])]


def escena_nube(variante):
    nodos = [N("tf", 24, 180, 250, 96, "Terraform", "un módulo por nube", "plataforma", False),
             N("code", 1038, 180, 250, 96, "El código", "sin SDK de nube", "dominio", "hexagono")]
    dentro = [("k_ing", "ingress-nginx", "cert-manager"), ("k_gw", "gateway-service", "OpenResty"),
              ("k_svc", "27 servicios", "Spring Boot · JRE 21"), ("k_kafka", "Kafka", "Strimzi · KRaft"),
              ("k_redis", "Redis", "en el clúster"), ("k_otel", "OTel Collector", "telemetría")]
    for k, (id, t, s) in enumerate(dentro):
        nodos.append(N(id, 324 + (k % 2) * 336, 80 + (k // 2) * 112, 316, 76, t, s, "dominio",
                       {"k_kafka": "cola", "k_redis": "bd", "k_otel": "red", "k_ing": "proxy"}.get(id, "cubo")))
    grupos = [G("cg", 300, 30, 712, 400, "Igual en las tres nubes", "dominio", "limite")]
    for k, (id, nombre, filas) in enumerate(NUBES):
        gx = 24 + k * 428
        grupos.append(G(id, gx, 480, 408, 480, nombre, "plataforma"))
        for r, (t, s) in enumerate(filas):
            nodos.append(N(f"{id}{r}", gx + 20, 524 + r * 70, 368, 58, t, s, "plataforma", False, mini=True))
    aristas = [A("t_gcp", "tf", "gcp", c="plataforma"),
               A("t_aws", "tf", "aws", c="plataforma", lados=["b", "t"], my=455),
               A("t_az", "tf", "az", c="plataforma", lados=["b", "t"], my=455),
               A("c_cg", "code", "cg")]
    filas = [f"{id}{r}" for id, _, fs in NUBES for r in range(len(fs))]
    nubes = ["gcp", "aws", "az"] + filas
    if variante == "short":
        momentos = [
            M("El clúster es igual en las tres nubes", ve=["cg"] + [d[0] for d in dentro], foco=["cg"]),
            M("Lo administrado cambia con Terraform", ve=["tf"] + nubes, flujo=["t_gcp", "t_aws", "t_az"]),
            M("El código no sabe en qué nube corre", ve=["code"], flujo=["c_cg"],
              marcas=[S("code", "Puertos y nombres de servicio", "abajo")]),
        ]
    else:
        momentos = [
            M("Dentro del clúster: los mismos charts", ve=["cg"] + [d[0] for d in dentro], foco=["cg"],
              marcas=[S("cg", "Un solo chart por servicio", "abajo", sep=10)]),
            M("Fuera: lo administrado de cada nube", ve=nubes, foco=["gcp", "aws", "az"],
              marcas=[S("gcp0", "GKE zonal: plano de control sin costo", "arriba", sep=40)]),
            M("Terraform: un módulo por nube", ve=["tf"], flujo=["t_gcp", "t_aws", "t_az"],
              marcas=[S("tf", "Un stack por nube y entorno", "abajo", sep=24)]),
            M("Kafka y Redis viajan con el clúster", foco=["k_kafka", "k_redis"],
              marcas=[S("k_kafka", "En prod: MSK, Event Hubs o gestionado", "der", sep=24)]),
            M("El código no sabe en qué nube corre", ve=["code"], flujo=["c_cg"],
              marcas=[S("code", "Puertos y nombres de servicio", "abajo", sep=24)]),
        ]
    return dict(corto="Cualquier nube", estado=PROPUESTA,
                panel=dict(capa="Portabilidad", sub="GCP · AWS · Azure", color="plataforma", icono="nube",
                           titulo="Mismo clúster, otra nube"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["sync"])


# ── Plataforma: de un commit al clúster ──────────────────────────────────────────────────────
def escena_plataforma(_):
    nodos = [N("g1", 24, 200, 224, 88, "Git", "un repo por capa", "plataforma", False),
             N("g2", 284, 200, 224, 88, "CI", "Gradle + pruebas", "plataforma", False, objetivo=True),
             N("g3", 544, 200, 224, 88, "Imagen", "JRE 21", "dominio", False),
             N("g4", 804, 200, 224, 88, "Registro", "imagen firmada", "plataforma", False, objetivo=True),
             N("g5", 1064, 200, 224, 88, "Argo CD", "GitOps", "plataforma", False, objetivo=True),
             N("ba", 804, 420, 224, 88, "Admisión", "solo firmadas", "alerta", False, objetivo=True),
             N("cl", 1064, 420, 224, 88, "Clúster", "converge al repo", "dominio", False),
             N("tf", 24, 660, 484, 88, "Terraform", "red · clúster · base · secretos", "plataforma", "capas",
               objetivo=True),
             N("nb", 544, 660, 744, 88, "Nube", "GCP primero · AWS y Azure listos", "plataforma", "nube"),
             N("sch", 544, 840, 744, 88, "Encendido programado", "demo: lunes a viernes, 10 a 22 h", "plataforma",
               "reloj", objetivo=True)]
    aristas = [A("p1", "g1", "g2"), A("p2", "g2", "g3"), A("p3", "g3", "g4"), A("p4", "g4", "ba"),
               A("p5", "ba", "cl"), A("p6", "g5", "cl"), A("p7", "tf", "nb"), A("p8", "sch", "nb", flecha=True)]
    momentos = [
        M("Una imagen por servicio, un Dockerfile", ve=["g3"], foco=["g3"],
          marcas=[S("g3", "Multietapa, usuario sin privilegios", "abajo")]),
        M("CI construye y prueba cada cambio", ve=["g1", "g2"], flujo=["p1", "p2"],
          marcas=[S("g2", "Hoy se prueba en local", "abajo")]),
        M("Solo imágenes firmadas entran", ve=["g4", "ba"], flujo=["p3", "p4"],
          marcas=[S("ba", "Binary Authorization o Kyverno", "abajo")]),
        M("GitOps: el clúster converge al repo", ve=["g5", "cl"], flujo=["p5", "p6"],
          marcas=[S("cl", "Sin kubectl a mano", "abajo")]),
        M("Terraform crea y apaga la nube", ve=["tf", "nb", "sch"], flujo=["p7", "p8"],
          marcas=[S("sch", "Demo en GCP: ~57 USD al mes", "arriba", dx=200)]),
    ]
    return dict(corto="Plataforma", estado=MIXTO,
                panel=dict(capa="Plataforma", sub="IaC · CI · GitOps", color="plataforma", icono="git",
                           titulo="De un commit al clúster, sin pasos a mano"),
                diagrama=dict(grupos=[], nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["sync", "objetivo"])


# ── Well-Architected: los pilares sobre el mapa ─────────────────────────────────────────────
# Los cinco que comparten AWS, Azure y Google Cloud. Cada pilar ilumina sobre el mapa las piezas
# que lo cumplen; la vista completa le dedica una parte a cada uno, con lo que hay y lo que falta.
PILARES = {
    "seguridad": dict(t="Seguridad", foco=["waf", "gw", "bffb", "sec"], momentos=[
        M("Hoy: JWT RS256 y header-trust", foco=["gw", "bffm", "bffb"],
          marcas=[S("gw", "Firma local, sin llamar a identity", "der", sep=40)]),
        M("Propuesta: WAF, TLS y mTLS en todo", foco=["waf", "lb", "ing"],
          marcas=[S("waf", "OWASP y límite de tasa", "arriba")]),
        M("Propuesta: KMS y mínimo privilegio", foco=["sec", "pg"],
          marcas=[S("sec", "Fuera de la imagen y de compose", "izq", sep=40)])]),
    "confiabilidad": dict(t="Confiabilidad", foco=["kafka", "d_stp", "d_disbursement"], momentos=[
        M("Hoy: eventos, idempotencia y DLT", foco=["kafka", "d_stp", "d_disbursement"],
          marcas=[S("kafka", "DLT en stp y disbursement", "der", sep=24)]),
        M("Propuesta: outbox en cada productor", foco=["kafka", "pg"],
          marcas=[S("pg", "Evento y dato en una transacción", "der", sep=40)]),
        M("Propuesta: probes, réplicas y RF=3", foco=["k8s", "kafka"],
          marcas=[S("k8s", "Readiness y apagado gentil", "der", sep=12)])]),
    "rendimiento": dict(t="Eficiencia de rendimiento", foco=["dominio", "cdn", "redis"], momentos=[
        M("Hoy: cartera no consulta el catálogo", foco=["d_creditportfolio"],
          marcas=[S("d_creditportfolio", "Snapshot inmutable al originar", "der", sep=440)]),
        M("Propuesta: cada servicio escala solo", foco=["dominio"],
          marcas=[S("d_closing", "HPA por servicio", "der", sep=230)]),
        M("Propuesta: estáticos desde CDN", foco=["cdn", "bo", "kyc"],
          marcas=[S("cdn", "Shell, MFE y kyc-web, fuera del clúster", "abajo")])]),
    "costos": dict(t="Optimización de costos", foco=["tf", "kafka", "redis"], momentos=[
        M("Nodos spot con una base on-demand", foco=["k8s"],
          marcas=[S("k8s", "Plano de control zonal sin costo", "der", sep=12)]),
        M("Demo: encendida de lunes a viernes", foco=["tf"],
          marcas=[S("tf", "10 a 22 h · ~57 USD al mes", "izq", sep=40)]),
        M("Kafka y Redis en el clúster, por ahora", foco=["kafka", "redis"],
          marcas=[S("kafka", "Administrados cuando la carga lo pida", "der", sep=24)])]),
    "operacion": dict(t="Excelencia operativa", foco=["obs", "tf"], momentos=[
        M("Hoy: trazas, métricas y logs ligados", foco=["obs"],
          marcas=[S("otel", "trace_id en cada log", "izq", sep=40)]),
        M("Hoy: regresión E2E con video", foco=["app", "bo", "kyc"],
          marcas=[S("bo", "51 aserciones, de la app al CEP", "abajo")]),
        M("Propuesta: Terraform, CI y GitOps", foco=["plat"],
          marcas=[S("tf", "Ningún cambio a mano", "izq", sep=40)])]),
}
ORDEN_PILARES = ["seguridad", "confiabilidad", "rendimiento", "costos", "operacion"]


def escena_pilares(variante):
    """Los cinco pilares en una parte: un momento por pilar."""
    momentos = []
    marcas = {"seguridad": [S("waf", "WAF y TLS en el borde", "arriba"), S("gw", "JWT RS256 local", "der", sep=40)],
              "confiabilidad": [S("kafka", "Reintento, DLT e idempotencia", "der", sep=24)],
              "rendimiento": [S("dominio", "Cada servicio escala solo", "der", sep=40)],
              "costos": [S("tf", "Spot y encendido programado", "izq", sep=40)],
              "operacion": [S("otel", "Una traza por request", "izq", sep=40)]}
    textos = {"seguridad": "Seguridad: defensa en capas", "confiabilidad": "Confiabilidad: fallas aisladas",
              "rendimiento": "Rendimiento: escala por servicio", "costos": "Costos: pagar solo lo encendido",
              "operacion": "Operación: todo observable y en código"}
    for p in ORDEN_PILARES:
        momentos.append(M(textos[p], foco=PILARES[p]["foco"], marcas=marcas[p]))
    return dict(corto="Well-Architected", estado=MIXTO, base="*",
                panel=dict(capa="Well-Architected", sub="Los 5 pilares de AWS, Azure y GCP", color="eventos",
                           icono="pilar", titulo="Los cinco pilares, sobre el mapa"),
                diagrama=mapa(), momentos=momentos, leyenda=["sync", "async", "objetivo"])


def escena_pilar(clave):
    p = PILARES[clave]
    n = ORDEN_PILARES.index(clave) + 1
    return dict(corto=f"Pilar {n} de 5", estado=MIXTO, base="*",
                panel=dict(capa="Well-Architected", sub=f"Pilar {n} de 5", color="eventos", icono="pilar",
                           titulo=p["t"]),
                diagrama=mapa(), momentos=copy.deepcopy(p["momentos"]), leyenda=["sync", "async", "objetivo"])


# ── La ruta: de lo que corre hoy a la referencia ───────────────────────────────────────────
FASES = [("f1", "Fase 1 · Seguridad base", "alerta", [
            ("TLS en todo el camino", "borde, Kafka, Redis y logs"), ("Secretos en KMS", "fuera de compose"),
            ("Un usuario por esquema", "mínimo privilegio"), ("Revocar access tokens", "hoy viven 15 min"),
            ("NetworkPolicy y mTLS", "cierra el header-trust")]),
         ("f2", "Fase 2 · Confiabilidad", "eventos", [
            ("Outbox en productores", "y la bitácora por Kafka"), ("DLT con reproceso", "en todos los consumidores"),
            ("Memoria y probes", "límites medidos · readiness"), ("Timeouts y cortacircuitos", "en BFF y clientes"),
            ("Tópicos declarados", "particiones · RF=3")]),
         ("f3", "Fase 3 · Plataforma", "plataforma", [
            ("Charts por servicio", "Helm o Kustomize"), ("CI por servicio", "build · prueba · firma"),
            ("Terraform aplicado", "GCP primero"), ("GitOps con Argo CD", "el clúster converge"),
            ("Alertas de negocio", "SLO del flujo de dinero")])]


def escena_ruta(_):
    grupos, nodos = [], []
    for k, (id, t, c, items) in enumerate(FASES):
        gx = 24 + k * 428
        grupos.append(G(id, gx, 60, 408, 880, t, c))
        for r, (it, s) in enumerate(items):
            nodos.append(N(f"{id}_{r}", gx + 20, 120 + r * 160, 368, 110, it, s, c,
                           ["candado", "reintento", "capas"][k]))
    aristas = [A("r12", "f1", "f2"), A("r23", "f2", "f3")]
    items = lambda f: [f"{f}_{r}" for r in range(5)]
    momentos = [
        M("Fase 1: cerrar la superficie de ataque", ve=["f1"] + items("f1"), foco=["f1"]),
        M("Fase 2: que ningún evento se pierda", ve=["f2"] + items("f2"), foco=["f2"], flujo=["r12"]),
        M("Fase 3: operar como producto", ve=["f3"] + items("f3"), foco=["f3"], flujo=["r23"]),
    ]
    return dict(corto="Ruta", estado=RUTA,
                panel=dict(capa="De hoy a la referencia", sub="Tres fases, en orden de riesgo", color="propuesta",
                           icono="git", titulo="Lo que falta para producción"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["sync"])


# ── Lo medido: originación salto por salto y capacidad ──────────────────────────────────────
# Las cifras salen de `capacidad.json`, que escribe `capacidad.py` contra el stack local. Nada de
# lo que se ve en estas dos partes está escrito a mano en el guion: si se vuelve a medir, el video
# cambia solo, y si no hay medición, el video no se arma (mejor eso que una cifra inventada).
MATERIAL = Path(os.environ.get("MATERIAL_DIR", Path.home() / "Documents" / "material-kredius"))
MEDICION = MATERIAL / "pruebas" / "capacidad" / "capacidad.json"


def medido():
    ruta = MEDICION
    if not ruta.exists():
        raise SystemExit("falta capacidad.json: corre `.venv/bin/python -I capacidad.py` con el stack arriba")
    return json.loads(ruta.read_text())


def tiempo(seg):
    if seg is None:
        return "–"
    if seg < 0.0005:
        return "al instante"
    return f"{seg * 1000:.0f} ms" if seg < 1 else f"{seg:.1f} s"


def rps(x):
    return f"{x:,.0f}"


# El journey, en el orden en que pasa, con quién trabaja en cada paso.
PASOS = [("otp", "OTP", "2 llamadas · gateway", "sync"), ("kyc", "Datos de KYC", "BFF móvil", "sync"),
         ("registro", "Registro", "origination", "sync"), ("credencial", "Credencial lista", "evento → identity", "async"),
         ("listas", "Listas PLD", "evento → compliance", "async"), ("dictamen KYC", "Dictamen de identidad", "persona", "humano"),
         ("propagación del dictamen", "Dictamen visible", "evento → origination", "async"),
         ("solicitud", "Solicitud", "origination", "sync"), ("scoring", "Scoring y buró", "evento → scoring", "async"),
         ("firma", "Oferta, contrato y firma", "4 llamadas", "sync"), ("cuenta activa", "Cuenta activa", "evento → cartera", "async"),
         ("spei", "SPEI liquidado", "conciliación cada 3 min", "humano")]


def escena_originacion(_):
    m = medido()["originacion"]["serie"]
    pasos = dict(m["pasos"])
    # Oferta, acepta, contrato y firma son cuatro llamadas seguidas del mismo paso de la app.
    pasos["firma"] = {"p50": sum((pasos.get(k) or {}).get("p50") or 0 for k in ("oferta", "acepta", "contrato", "firma"))}
    total = sum(pasos[k]["p50"] for k, *_ in PASOS if k in pasos)

    nodos = [N("app", 30, 30, 210, 64, "App", "Flutter", "canal", "movil"),
             N("gw", 280, 30, 230, 64, "gateway", "JWT · límites", "proxy", "candado"),
             N("bff", 550, 30, 260, 64, "channel-mobile", "BFF", "bff", "capas"),
             N("id", 30, 160, 228, 64, "identity", None, "dominio", False),
             N("or", 288, 160, 228, 64, "origination", None, "dominio", False),
             N("co", 546, 160, 228, 64, "compliance", None, "dominio", False),
             N("sc", 804, 160, 228, 64, "scoring", None, "dominio", False),
             N("cp", 1062, 160, 228, 64, "credit-portfolio", None, "dominio", False),
             N("kf", 30, 268, 1260, 52, "Kafka", None, "eventos", "cola"),
             N("di", 804, 360, 228, 64, "disbursement", None, "dominio", False),
             N("st", 1062, 360, 228, 64, "stp → STP", None, "dominio", False)]
    grupos = [G("crono", 30, 456, 1260, 504, "Cronómetro · p50 medido en local", "eventos", "limite")]
    aristas = [A("e1", "app", "gw"), A("e2", "gw", "bff"), A("e3", "bff", "or", lados=["b", "t"]),
               A("eid", "bff", "id", lados=["b", "t"], da=-60, tarde=True),
               A("k_or", "or", "kf", "async", doble=True), A("k_id", "kf", "id", "async"),
               A("k_co", "kf", "co", "async"), A("k_sc", "sc", "kf", "async", doble=True),
               A("k_cp", "cp", "kf", "async", doble=True), A("k_di", "kf", "di", "async"),
               A("d_st", "di", "st", "async")]
    # La cascada: cada paso empieza donde terminó el anterior, con largo proporcional a su p50. Lo que
    # hace una persona o un reloj externo no está a escala: va rayado y con su tiempo escrito.
    x0, ancho, y0, fila = 370, 620, 504, 36
    escala = ancho / total
    reloj = 0.0
    filas = {}
    for i, (k, t, s_, tipo) in enumerate(PASOS):
        y = y0 + i * fila
        ids = [f"t_{i}", f"b_{i}", f"v_{i}"]
        nodos.append(N(ids[0], 50, y - 2, 310, 34, t, None, "dominio", False, texto=True, mini=True))
        if tipo == "humano":
            w = 90
            nodos.append(N(ids[1], x0 + reloj * escala, y + 2, w, 24, "", None, "externo", False, barra=True, espera=True))
            valor = "minutos · persona" if k != "spei" else "≤ 3 min"
        else:
            seg = pasos.get(k, {}).get("p50") or 0
            w = max(6, seg * escala)
            nodos.append(N(ids[1], x0 + reloj * escala, y + 2, w, 24, "", None,
                           "eventos" if tipo == "async" else "dominio", False, barra=True))
            reloj += seg
            valor = tiempo(seg)
        nodos.append(N(ids[2], x0 + (reloj * escala if tipo != "humano" else reloj * escala + 90) + 12, y - 2, 190, 34,
                       valor, None, "dominio", False, texto=True, mini=True))
        filas[k] = ids
    f = lambda *ks: [i for k in ks for i in filas[k]]
    momentos = [
        M("Alta: OTP, datos y registro", ve=["app", "gw", "bff", "or", "crono"] + f("otp", "kyc", "registro"),
          flujo=["e1", "e2", "e3"], marcas=[S(filas["registro"][1], tiempo(sum(pasos[k]["p50"] for k in ("otp", "kyc", "registro"))) + " las tres", "der", sep=250)]),
        M("La credencial llega por evento", ve=["kf", "id"] + f("credencial"), flujo=["k_or", "k_id"],
          marcas=[S("id", "identity escucha el alta", "abajo", sep=110)]),
        M("Listas al alta; identidad, una persona", ve=["co"] + f("listas", "dictamen KYC", "propagación del dictamen"),
          flujo=["k_co"],
          marcas=[S(filas["dictamen KYC"][1], "Hoy: revisión manual", "izq", sep=20)]),
        M("Solicitud y scoring, por evento", ve=["sc"] + f("solicitud", "scoring"), flujo=["e3", "k_or", "k_sc"],
          marcas=[S("sc", "Buró simulado en local", "abajo", sep=110)]),
        M("Oferta, contrato y firma", ve=f("firma"), flujo=["e1", "e2", "e3"]),
        M("La cuenta se activa sola", ve=["cp", "di", "st"] + f("cuenta activa", "spei"), flujo=["k_or", "k_cp", "k_di", "d_st"],
          marcas=[S(filas["spei"][1], "STP concilia por consulta", "izq", sep=20)]),
        M(f"~{total:.1f} s de sistema por originación", foco=["crono"],
          marcas=[S("crono", "El resto es espera de personas", "arriba", sep=12)]),
    ]
    return dict(corto="Originación", estado={"t": "Medido en local", "c": "hoy"}, atenua={"nodo": 0.35, "grupo": 0.5},
                panel=dict(capa="Originación", sub="Flujo medido, salto por salto", color="eventos", icono="reloj",
                           titulo="Una originación, salto por salto"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=aristas), momentos=momentos,
                leyenda=["sync", "async"])


CPUS_LOCAL = 12  # lo que Docker Desktop tiene asignado en la máquina donde se midió
NUBE = [("Demo en GCP", "2 nodos × 2 vCPU", 4), ("Producción mínima", "3 nodos × 4 vCPU", 12),
        ("Producción con HPA", "hasta 10 nodos × 4 vCPU", 40)]
MARGEN = 0.5  # se planea a la mitad de lo medido: picos, réplicas cayendo y ruido de vecinos


def escena_capacidad(variante):
    """Cuánto aguanta, medido en local, y qué esperar en el clúster.

    La estimación del clúster es una regla de tres por vCPU sobre la ruta de costo medio (cartera),
    al 50 %: es lo que un equipo puede defender en una revisión, y dice dónde deja de valer (una
    sola base de datos). No es una medición del clúster, y el video lo dice.
    """
    med = medido()
    lec, ori = med["lectura"], med["originacion"]["paralelo"]
    rutas = [("catalogo", "Catálogo", "app → credit-product"), ("cartera", "Cartera", "consola → credit-portfolio"),
             ("tablero", "Tablero", "consola → agregados")]
    tope = max(lec[k]["maximo"]["rps"] for k, *_ in rutas)
    x0, ancho = 330, 600
    esc = ancho / tope
    grupos = [G("lect", 30, 30, 1250, 452, "Lectura · gateway → BFF → dominio", "dominio", "limite"),
              G("escr", 30, 512, 610, 196, "Escritura · originación completa", "eventos", "limite"),
              G("cuello", 670, 512, 610, 196, "Dónde se rompe", "alerta", "limite"),
              G("clu", 30, 738, 1250, 222, "En el clúster · estimado, al 50 %", "plataforma", "limite")]
    nodos = []
    for i, (k, t, sub) in enumerate(rutas):
        y, tol, mx = 92 + i * 128, lec[k]["tolerado"], lec[k]["maximo"]
        nodos += [N(f"l_{k}", 56, y, 260, 64, t, sub, "dominio", False, texto=True),
                  N(f"b_{k}", x0, y + 14, max(8, (tol or mx)["rps"] * esc), 36, "", None, "eventos", False,
                    barra=True),
                  N(f"v_{k}", x0 + (tol or mx)["rps"] * esc + 18, y, 320, 64,
                    f"{rps((tol or mx)['rps'])} req/s",
                    f"p95 {tiempo((tol or mx)['p95'])} · {(tol or mx)['concurrencia']} clientes",
                    "dominio", False, texto=True)]
    nodos += [N("o_n", 56, 566, 560, 64, f"{ori['por_minuto']:.0f} originaciones por minuto",
                f"{ori['paralelo']} en paralelo · p95 {tiempo(ori['sistema']['p95'])}", "eventos", False, texto=True),
              N("o_s", 56, 640, 560, 44, f"{ori['completas']} de {ori['total']} completas", None, "eventos", False,
                texto=True, mini=True)]
    # Cada caída medida, con su causa comprobada: OOM por el evento de Docker (código 137) y la
    # bitácora de accesos por el código (`AccessAuditInterceptor` hace un POST por consulta, sin cola).
    CAUSA = {"credit-portfolio-service": "rebasa 512 MB: OOM", "audit-service": "un POST por consulta, sin cola"}
    caidas = [(k, e) for k, *_ in rutas for e in lec[k]["escalones"] if e.get("reinicios")]
    rotura = caidas[0] if caidas else None
    lineas = []
    for k, e in caidas[:2]:
        svc = e["reinicios"][0]
        lineas.append((f"{e['concurrencia']} clientes en {dict((r, t) for r, t, _ in rutas)[k].lower()}: "
                       f"cae {svc.removesuffix('-service')}", CAUSA.get(svc, "se reinicia")))
    for i, (t, sub) in enumerate(lineas or [("Ninguna caída hasta 64 clientes", "")]):
        nodos.append(N(f"s_{i}", 696, 552 + i * 72, 560, 64, t, sub, "alerta", False, texto=True))
    n_lineas = max(1, len(lineas))
    base = (lec["cartera"]["tolerado"] or lec["cartera"]["maximo"])["rps"]
    for i, (t, sub, vcpu) in enumerate(NUBE):
        x = 56 + i * 410
        nodos.append(N(f"n_{i}", x, 790, 380, 64, t, sub, "plataforma", False, texto=True))
        nodos.append(N(f"nv_{i}", x, 866, 380, 64, f"~{rps(round(base * vcpu / CPUS_LOCAL * MARGEN, -1))} req/s",
                       "si PostgreSQL acompaña" if i == 2 else None, "plataforma", False, texto=True))
    filas = lambda k: [f"l_{k}", f"b_{k}", f"v_{k}"]
    # ¿Es un techo? Si el escalón siguiente da menos req/s, más clientes ya no compran capacidad.
    def techo(k):
        es = lec[k]["escalones"]
        i = next((j for j, e in enumerate(es) if e is (lec[k]["tolerado"] or lec[k]["maximo"])), None)
        i = i if i is not None else max(range(len(es)), key=lambda j: es[j]["rps"])
        return i + 1 < len(es) and es[i + 1]["rps"] < es[i]["rps"]
    cat, car, tab = (lec[k]["tolerado"] or lec[k]["maximo"] for k in ("catalogo", "cartera", "tablero"))
    clu = ["clu"] + [f"n_{i}" for i in range(3)] + [f"nv_{i}" for i in range(3)]
    if variante == "short":
        momentos = [
            M(f"Lectura: hasta {rps(cat['rps'])} req/s", foco=["lect"],
              ve=["lect"] + filas("catalogo") + filas("cartera") + filas("tablero"),
              marcas=[S("v_catalogo", "p95 ≤ 300 ms, < 1 % de errores", "abajo", sep=8)]),
            M(f"Originación: {ori['por_minuto']:.0f} por minuto", ve=["escr", "o_n", "o_s"] + clu,
              foco=["escr", "clu"]),
        ]
    else:
        momentos = [
            M("Objetivo: p95 ≤ 300 ms, < 1 % errores", ve=["lect", "l_catalogo", "l_cartera", "l_tablero"],
              foco=["lect"]),
            M(f"Catálogo: {rps(cat['rps'])} req/s", ve=filas("catalogo"), foco=filas("catalogo"),
              marcas=[S("b_catalogo", "Con más clientes ya no sube: es el techo", "abajo", sep=14)]
              if techo("catalogo") else []),
            M(f"Cartera: {rps(car['rps'])} req/s", ve=filas("cartera"), foco=filas("cartera")),
            M(f"Tablero: {rps(tab['rps'])} req/s", ve=filas("tablero"), foco=filas("tablero"),
              marcas=[S("l_tablero", "Una pantalla, varias consultas", "abajo", sep=4)]),
            M(f"Originación: {ori['por_minuto']:.0f} por minuto", ve=["escr", "o_n", "o_s"], foco=["escr"]),
            M(f"Se rompe a {rotura[1]['concurrencia']} clientes, por memoria" if rotura else "Sin caídas hasta 64 clientes",
              foco=["cuello"], ve=["cuello"] + [f"s_{i}" for i in range(n_lineas)]),
            M("En el clúster: planear al 50 %", ve=clu, foco=["clu"]),
        ]
    return dict(corto="Capacidad", estado={"t": "Medido en local", "c": "hoy"},
                panel=dict(capa="Capacidad", sub=f"{med['entorno']['cpus']} CPU · 1 réplica por servicio",
                           color="eventos", icono="escala", titulo="Cuánto aguanta, medido"),
                diagrama=dict(grupos=grupos, nodos=nodos, aristas=[]), momentos=momentos, leyenda=[])


ESCENAS = {"vista": escena_vista, "proxy": escena_proxy, "originacion": escena_originacion, "capacidad": escena_capacidad, "kubernetes": escena_kubernetes,
           "servicio": escena_servicio, "eventos": escena_eventos, "spei": escena_spei, "datos": escena_datos,
           "seguridad": escena_seguridad, "observabilidad": escena_observabilidad, "nube": escena_nube,
           "plataforma": escena_plataforma, "pilares": escena_pilares, "ruta": escena_ruta}
for _p in ORDEN_PILARES:
    ESCENAS[f"pilar_{_p}"] = (lambda clave: lambda _v: escena_pilar(clave))(_p)

# ── Versiones ───────────────────────────────────────────────────────────────────────────────
# short: el mapa, el proxy, los eventos, la nube y los pilares en 90 s. middle: capa por capa,
# el desembolso de punta a punta, los pilares y la ruta. completa: además el servicio por dentro,
# la plataforma y un pilar por parte.
PIE = "Arquitectura de referencia · core de crédito"
VERSIONES = {
    "short": dict(tope=90, ritmo=4.0,
                  portada=("Arquitectura de referencia", "Un core de crédito que corre en cualquier nube.",
                           "Siete capas, eventos y los cinco pilares del Well-Architected."),
                  partes=["portada", "vista:short", "proxy:short", "eventos:short", "capacidad:short", "nube:short",
                          "pilares", "cierre"]),
    "middle": dict(tope=None, ritmo=5.0,
                   portada=("Arquitectura de referencia", "Un core de crédito que corre en cualquier nube.",
                            "Kubernetes, eventos y una base por servicio, vistos con el Well-Architected Framework."),
                   partes=["portada", "vista", "proxy", "kubernetes", "eventos", "originacion", "spei", "momento", "datos",
                           "seguridad", "observabilidad", "capacidad", "nube", "pilares", "ruta", "cierre"]),
    "completa": dict(tope=None, ritmo=5.5,
                     portada=("Arquitectura de referencia · completa", "Un core de crédito que corre en cualquier nube.",
                              "Capa por capa, y pilar por pilar del Well-Architected Framework."),
                     partes=["portada", "vista", "proxy", "kubernetes", "servicio", "eventos", "originacion", "spei", "momento",
                             "datos", "seguridad", "observabilidad", "capacidad", "nube", "plataforma"]
                     + [f"pilar_{p}" for p in ORDEN_PILARES] + ["ruta", "cierre"]),
}
TARJETAS = {"portada", "momento", "cierre"}


LIENZO = (1312, 984)


def centra(e):
    """Deja el diagrama con el mismo margen a cada lado del lienzo.

    Cada escena se dibujó en sus propias coordenadas, y unas quedaban pegadas a un borde y con aire
    del otro. Se mide la caja de zonas y nodos y se traslada todo —puntos sueltos, desvíos y señales
    incluidos— para centrarla. Las distancias relativas (desplazamientos de etiquetas y de salida de
    las aristas) no cambian.
    """
    d = e["diagrama"]
    cajas = d["grupos"] + d["nodos"]
    x0, y0 = min(c["x"] for c in cajas), min(c["y"] for c in cajas)
    x1, y1 = max(c["x"] + c["w"] for c in cajas), max(c["y"] + c["h"] for c in cajas)
    dx, dy = round((LIENZO[0] - (x1 - x0)) / 2 - x0), round((LIENZO[1] - (y1 - y0)) / 2 - y0)
    mueve = lambda p: [p[0] + dx, p[1] + dy]
    for c in cajas:
        c["x"] += dx
        c["y"] += dy
    for a in d["aristas"]:
        for k in ("de", "a"):
            if isinstance(a[k], list):
                a[k] = mueve(a[k])
        if a.get("via"):
            a["via"] = [mueve(v) for v in a["via"]]
        if a.get("mx") is not None:
            a["mx"] += dx
        if a.get("my") is not None:
            a["my"] += dy
    for m in e["momentos"]:
        for sen in m["marcas"]:
            if isinstance(sen["en"], list):
                sen["en"] = mueve(sen["en"])
    return e


def escenas_de(version):
    cfg = VERSIONES[version]
    claves = [p for p in cfg["partes"] if p not in TARJETAS]
    out = []
    for i, clave in enumerate(claves, start=1):
        base, _, variante = clave.partition(":")
        e = centra(copy.deepcopy(ESCENAS[base](variante)))
        e["id"] = f"{version}-{base}"
        e["clave"] = base
        e["colores"], e["aristas"] = COLORES, ARISTAS
        e["etiquetaObjetivo"] = "propuesta"
        e["panel"].update(seccion="Arquitectura", kicker=f"Parte {i} de {len(claves)} · {e['corto']}",
                          estado=e["estado"], pie=PIE)
        out.append(e)
    return out


def duracion(m, cfg, ritmo):
    """Segundos de un momento: el ritmo de la versión y, si no hay tope, tiempo para leer lo largo."""
    if cfg["tope"]:
        return ritmo
    letras = len(m["texto"]) + sum(len(s.get("t") or "") for s in m["marcas"])
    return round(ritmo + min(2.0, 0.04 * max(0, letras - 45)), 2)


def compone(version, salida, trabajo, bo_dir):
    cfg = VERSIONES[version]
    cuadros = trabajo / ".cuadros"
    vtmp = trabajo / ".video" / version
    shutil.rmtree(vtmp, ignore_errors=True)
    vtmp.mkdir(parents=True)
    escenas = escenas_de(version)

    seg = dict(portada=3.5 if cfg["tope"] else 4.5, momento=4.5, cierre=5 if cfg["tope"] else 7)
    ritmo = cfg["ritmo"]
    if cfg["tope"]:
        fijo = sum(seg[p] for p in cfg["partes"] if p in TARJETAS)
        momentos_ = sum(len(e["momentos"]) for e in escenas)
        ritmo = min(ritmo, (cfg["tope"] - fijo - 1) / momentos_)

    (vtmp / "escenas.json").write_text(json.dumps({"escenas": escenas}, ensure_ascii=False))
    log(f"{version}: dibujando {len(escenas)} escenas")
    subprocess.run(["node", str(AQUI / "diagramas.cjs"), str(vtmp / "escenas.json"), str(cuadros),
                    str(bo_dir / "node_modules")], check=True)

    k, t, sub = cfg["portada"]
    tarjetas = [{"archivo": "portada.png", "tipo": "portada", "fecha": "", "kicker": k, "titulo": t, "sub": sub, "pie": PIE},
                {"archivo": "momento.png", "tipo": "momento", "fecha": "", "kicker": "Un desembolso, de punta a punta",
                 "titulo": "Ocho servicios. Cero pasos a mano.",
                 "sub": "Cada salto es un evento en Kafka, y el rastro se consulta completo: de la disposición al CEP.",
                 "pie": PIE},
                {"archivo": "cierre.png", "tipo": "portada", "fecha": "", "kicker": "Arquitectura de referencia",
                 "titulo": "Mismo código. Cualquier nube.", "sub": "Agenda una revisión técnica · ventas@kredius.mx",
                 "pie": PIE}]
    (vtmp / "tarjetas.json").write_text(json.dumps(tarjetas, ensure_ascii=False))
    subprocess.run(["node", str(AQUI / "tarjetas.cjs"), str(vtmp / "tarjetas.json"), str(vtmp),
                    str(bo_dir / "node_modules")], check=True)

    lista, momentos, reloj = [], {}, [0.0]

    def clip_tarjeta(nombre, segundos):
        salida_ = vtmp / f"c-{nombre}.mp4"
        ff(salida, ["-loop", "1", "-t", f"{segundos}", "-i", str(vtmp / f"{nombre}.png"),
                    "-vf", f"fps={FPS},format=yuv420p,setsar=1", "-c:v", "libx264", "-preset", "veryfast",
                    "-crf", "18", str(salida_)])
        lista.append(salida_)
        momentos.setdefault({"momento": "dinero"}.get(nombre, nombre), reloj[0])
        reloj[0] += segundos

    porclave = {e["clave"]: e for e in escenas}
    for parte in cfg["partes"]:
        if parte in TARJETAS:
            clip_tarjeta(parte, seg[parte])
            continue
        e = porclave[parte.partition(":")[0]]
        d = cuadros / e["id"]
        info = json.loads((d / "cuadros.json").read_text())
        momentos.setdefault("cuerpo", reloj[0])
        if e["clave"].startswith("pilar"):
            momentos.setdefault("detras", reloj[0])
        for j, m in enumerate(e["momentos"]):
            dur = duracion(m, cfg, ritmo)
            salida_ = vtmp / f"e-{e['clave']}-{j:02d}.mp4"
            # La transición se ve una vez y el segundo de paquetes en marcha se repite lo que dure.
            ff(salida, ["-framerate", f"{FPS}", "-i", str(d / f"b{j}-t%02d.jpg"),
                        "-framerate", f"{FPS}", "-loop", "1", "-i", str(d / f"b{j}-l%02d.jpg"),
                        "-filter_complex", f"[0:v][1:v]concat=n=2:v=1:a=0,fps={FPS},format=yuv420p,setsar=1[v]",
                        "-map", "[v]", "-t", f"{dur:.3f}", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                        str(salida_)])
            lista.append(salida_)
            reloj[0] += dur
        log(f"{version} · {e['corto']}: {len(info)} momentos")

    (vtmp / "lista.txt").write_text("".join(f"file '{x}'\n" for x in lista))
    nombre = salida / f"Kredius-arquitectura-de-referencia-{version}.mp4"
    ff(salida, ["-f", "concat", "-safe", "0", "-i", str(vtmp / "lista.txt"), "-c", "copy", str(vtmp / "sin-audio.mp4")])
    ff(salida, ["-i", str(vtmp / "sin-audio.mp4")] + video.pista(vtmp, reloj[0], momentos)
       + ["-map", "0:v", "-c:v", "copy", "-movflags", "+faststart", str(nombre)])
    log(f"{version}: {reloj[0]:.1f} s")
    return nombre


def main():
    salida = Path(sys.argv[1]).resolve()
    bo_dir = Path(sys.argv[2]).resolve()
    versiones = sys.argv[3:] or list(VERSIONES)
    salida.mkdir(parents=True, exist_ok=True)
    # El video va a la carpeta de material, fuera del código; los cuadros y los temporales, al
    # temporal del sistema: se regeneran solos. Se guardan entre corridas mientras el sistema no los
    # limpie: una escena que no cambió no se vuelve a capturar.
    trabajo = Path(tempfile.gettempdir()) / "kredius-arquitectura"
    hechos = [compone(v, salida, trabajo, bo_dir) for v in versiones]
    if not os.environ.get("CONSERVA_TEMPORALES"):
        shutil.rmtree(trabajo / ".video", ignore_errors=True)
    for h in hechos:
        print(h)


if __name__ == "__main__":
    main()
