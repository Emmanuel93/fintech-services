#!/usr/bin/env python3
"""Arma el video de demostración de un caso: una historia, no una sucesión de grabaciones.

    python3 video.py <carpeta-del-caso> <ruta-al-repo-del-backoffice>

Qué hace con las grabaciones de cada acto (app, backoffice y página de alta):

  1. Las pone a tamaño legible dentro de un marco —un teléfono para la app y la liga, una ventana de
     navegador para el backoffice— sobre el fondo de la marca.
  2. Al lado, un panel cuenta el paso: quién actúa, qué está pasando y los momentos del acto. El
     momento en curso se ilumina **sincronizado con la pantalla**: las pruebas marcan cada momento
     con `historia(clave)` y el orquestador anotó en qué segundo de la grabación ocurrió.
  3. Recorta lo que no es producto: el home del iPhone mientras se instala la app y su cierre.
  4. Abre con portada y personajes, intercala el momento del depósito y cierra con la conclusión.

El guion (STORY) vive aquí y no en las pruebas: el copy se ajusta y el video se regenera sin volver a
grabar nada.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

W, H = 1920, 1080

# Geometría de los dos marcos. El tamaño del hueco es el de la grabación, para no reescalar: el
# backoffice se graba a 1280×800 y la liga a 432×936 (ver los `test.use` de los specs).
TELEFONO = {"x": 1300, "y": 72, "w": 432, "h": 936, "r": 52}
NAVEGADOR = {"x": 64, "y": 176, "w": 1280, "h": 800, "r": 14}
# Demo completa: el backoffice casi a pantalla completa (1280×800 escalado ×1.15) para que se lean
# las cifras de pólizas, bancos y cartera; la narración va arriba.
# Dimensiones PARES: con 4:2:0 ffmpeg redondea la imagen escalada a un número par, y un hueco de
# 925 px de alto dejaba la grabación (926) más grande que su marco — la composición fallaba.
AMPLIO = {"x": 224, "y": 136, "w": 1472, "h": 920, "r": 16}

# Los roles se nombran por su función y no por quien lo ocupa: «Cliente final», no «Su clienta». El
# video se le enseña a redes de cualquier composición, y el copy no debe suponer el género de nadie.
PERSONAS = {
    "distribuidor": ("LM", "Laura", "Distribución · Culiacán, Sinaloa"),
    "cliente": (None, "{cliente}", "Cliente final"),
    "olivia": ("OP", "Olivia Paz", "Cumplimiento"),
    "ulises": ("UW", "Ulises Ward", "Mesa de crédito"),
    "equipo": ("KR", "Tu equipo", "Operación y cartera"),
    "fernanda": ("FN", "Fernanda Nava", "Finanzas"),
    "admin": ("AD", "Administración", "Accesos y auditoría"),
}

# ── El guion ──────────────────────────────────────────────────────────────────────────────────
# Por acto: canal, quién actúa, el titular y los momentos (clave de `historia()` → texto).
# Reglas del copy: verbo en presente y voz activa; un momento cabe en una línea (≈ 35 caracteres);
# sin jerga interna («cuelga», «huella», «acomoda»); neutro en género.
STORY = {
    "01": dict(canal="App de distribución", url=None, persona="distribuidor",
               titulo="Laura se registra en minutos",
               beats=[("proposito", "Elige prestar a su red"),
                      ("celular", "Valida su celular con un código"),
                      ("documentos", "Sube INE, selfie y comprobante"),
                      ("datos", "Confirma los datos de su INE"),
                      ("contrasena", "Crea su contraseña y entra")]),
    "02": dict(canal="Backoffice · Cumplimiento", url="backoffice.kredius.mx/cumplimiento", persona="olivia",
               titulo="Olivia valida la identidad de Laura",
               beats=[("cola", "Laura entra a la cola de revisión"),
                      ("expediente", "Abre su expediente"),
                      ("documentos", "Compara INE contra selfie"),
                      ("listas", "Revisa listas PLD"),
                      ("firma", "Firma: identidad comprobada")]),
    "03": dict(canal="Backoffice · Crédito", url="backoffice.kredius.mx/solicitudes", persona="ulises",
               titulo="Ulises aprueba la línea",
               beats=[("bandeja", "La solicitud llega a su bandeja"),
                      ("roles", "Solo decide quien tiene el rol"),
                      ("ficha", "Revisa buró y evaluación"),
                      ("aprueba", "Aprueba la línea de Laura")]),
    "04": dict(canal="App de distribución", url=None, persona="distribuidor",
               titulo="Laura le presta a {cliente}",
               beats=[("linea", "Consulta su línea disponible"),
                      ("clienta", "Registra a {cliente}"),
                      ("monto", "Define monto y plazo: {monto}"),
                      ("liga", "Comparte la liga de registro")]),
    "05": dict(canal="Registro web", url=None, persona="cliente",
               titulo="{cliente} se registra desde su celular",
               beats=[("bienvenida", "Abre la liga y ve su crédito"),
                      ("celular", "Valida su celular con un código"),
                      ("ine", "Fotografía su INE, frente y reverso"),
                      ("selfie", "Se toma una selfie"),
                      ("datos", "Revisa sus datos y su CLABE"),
                      ("firma", "Firma su autorización con un código")]),
    "06": dict(canal="Backoffice · KYC", url="backoffice.kredius.mx/beneficiarios", persona="olivia",
               titulo="Olivia valida la identidad de {cliente}",
               beats=[("expediente", "Su expediente llega completo"),
                      ("documentos", "Compara INE contra selfie"),
                      ("comprobada", "Comprobada: se libera el depósito")]),
    "07": dict(canal="App de distribución", url=None, persona="distribuidor",
               titulo="Laura decide",
               beats=[("lista", "{cliente} ya pasó la verificación"),
                      ("historial", "Consulta su historial de buró"),
                      ("aprueba", "Asume el riesgo y aprueba")]),
    "08-liquidar": dict(canal="App de distribución", url=None, persona="distribuidor",
                        titulo="Los pagos se aplican solos",
                        beats=[("entra", "Pasan cuatro quincenas"),
                               ("linea", "Pagos e intereses, al día"),
                               ("desenlace", "Crédito liquidado")]),
    "08-mora": dict(canal="App de distribución", url=None, persona="distribuidor",
                    titulo="La mora se detecta sola",
                    beats=[("entra", "Pasan 95 días sin pago"),
                           ("linea", "Su línea muestra el atraso"),
                           ("desenlace", "Cobranza recibe el caso")]),
    "09-cartera": dict(canal="Backoffice · Cartera", url="backoffice.kredius.mx/cartera", persona="equipo",
                       titulo="Tu equipo ve toda la cartera",
                       beats=[("cuenta", "La línea de Laura en cartera"),
                              ("calendario", "Su calendario de pagos"),
                              ("pagos", "Pagos aplicados al día")]),
    # ── Demo completa: lo que pasa detrás ──────────────────────────────────────────────────
    "10": dict(canal="Backoffice · Colocaciones", url="backoffice.kredius.mx/beneficiarios", persona="olivia",
               titulo="Cada colocación, a la vista",
               beats=[("lista", "La colocación de {cliente}: depositada"),
                      ("ficha", "Quién comprobó su identidad y cuándo"),
                      ("cartera", "El crédito, dentro de la línea de Laura")]),
    "11": dict(canal="Backoffice · Red comercial", url="backoffice.kredius.mx/estructura", persona="olivia",
               titulo="Laura se integra a su región",
               beats=[("arbol", "Recorre la red: región, zona, sucursal"),
                      ("acomoda", "Le asigna un ejecutivo en Culiacán"),
                      ("sucursal", "Su cartera suma a la sucursal")]),
    "12": dict(canal="Backoffice · Contabilidad", url="backoffice.kredius.mx/contabilidad", persona="fernanda",
               titulo="Contabilidad sin captura manual",
               beats=[("resumen", "El periodo cuadra"),
                      ("polizas", "Las pólizas de la línea de Laura"),
                      ("egreso", "Desembolso: cargo cartera, abono bancos"),
                      ("devengo", "Interés e IVA, devengados a diario"),
                      ("pago", "Pago: entra a bancos, salda la cartera"),
                      ("balanza", "La balanza cuadra")]),
    "13": dict(canal="Backoffice · Tesorería", url="backoffice.kredius.mx/contabilidad/tesoreria", persona="fernanda",
               titulo="El SPEI, rastreado de punta a punta",
               beats=[("cuentas", "Cuenta ordenante ligada a contabilidad"),
                      ("dispersion", "{monto_solo} a la cuenta de {cliente}"),
                      ("rastreo", "Clave de rastreo, liquidación y CEP")]),
    "14": dict(canal="Backoffice · Accesos y auditoría", url="backoffice.kredius.mx/permisos", persona="admin",
               titulo="Quién puede hacer qué, y quién lo hizo",
               beats=[("usuarios", "Usuarios y sus roles"),
                      ("roles", "Qué puede autorizar cada rol"),
                      ("permisos", "La matriz de permisos, completa"),
                      ("auditoria", "Bitácora del caso: quién y cuándo")]),
    "09-cobranza": dict(canal="Backoffice · Cobranza", url="backoffice.kredius.mx/cobranza", persona="equipo",
                        titulo="Cada atraso llega a cobranza",
                        beats=[("caso", "El caso aparece en la bandeja"),
                               ("tramo", "Con su tramo de atraso y su deuda"),
                               ("resumen", "Y la siguiente acción")]),
}


def log(msg):
    print(f"▸ {msg}", file=sys.stderr, flush=True)


def guion_de(acto):
    pre = acto[:2]
    for clave in (f"{pre}-liquidar" if "liquidar" in acto else None,
                  f"{pre}-mora" if "mora" in acto else None,
                  f"{pre}-cobranza" if "cobranza" in acto else None,
                  f"{pre}-cartera" if pre == "09" and "cobranza" not in acto else None,
                  pre):
        if clave and clave in STORY:
            return STORY[clave]
    return None


def ffmpeg_exe():
    """El ffmpeg estático del entorno local de la herramienta (imageio-ffmpeg, versión fija).

    Corre sobre la Mac y no en Docker: con el stack completo levantado a la máquina virtual de
    Docker le queda ~1 GB, y componer un acto 1080p con su panel la hacía matar el proceso
    (exit 137) incluso con una imagen nativa de arm64.
    """
    if os.environ.get("FFMPEG"):
        return os.environ["FFMPEG"]
    import imageio_ffmpeg  # instalado por une-video.sh en .venv
    return imageio_ffmpeg.get_ffmpeg_exe()


def ff(caso, args):
    r = subprocess.run([ffmpeg_exe(), "-hide_banner", "-loglevel", "error", "-y"] + args,
                       cwd=caso, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg falló (código {r.returncode}): {r.stderr[-1500:]}")


def sonda(caso, rel):
    """Ancho, alto y duración. Sin ffprobe: imageio-ffmpeg sólo trae ffmpeg, y su `-i` lo dice."""
    r = subprocess.run([ffmpeg_exe(), "-hide_banner", "-i", rel], cwd=caso, capture_output=True, text=True)
    m = re.search(r"Video: .*?(\d{2,5})x(\d{2,5})", r.stderr)
    d = re.search(r"Duration: (\d+):(\d+):([\d.]+)", r.stderr)
    if not (m and d):
        raise RuntimeError(f"no pude leer {rel}: {r.stderr[-400:]}")
    dur = int(d.group(1)) * 3600 + int(d.group(2)) * 60 + float(d.group(3))
    return int(m.group(1)), int(m.group(2)), dur


def fin_de_sesion(caso, rel, desde, hasta, despues_de):
    """Dónde empieza el «salir» al final de una grabación del backoffice, o None.

    Los specs cierran sesión al terminar, y la grabación sigue unos segundos en la pantalla de
    login: en el video eso se ve como si el paso se hubiera caído. Con sesión, el menú lateral es
    azul marino; en el login esa franja es clara. Se mide su luminosidad dos veces por segundo y
    se corta en el primer instante —posterior al último momento de la historia— a partir del cual
    queda clara hasta el final. Un modal abierto oscurece la franja en vez de aclararla, así que
    no se confunde con salir.
    """
    r = subprocess.run([ffmpeg_exe(), "-hide_banner", "-ss", f"{desde:.2f}", "-t", f"{hasta - desde:.2f}",
                        "-i", rel, "-vf", "fps=2,crop=110:420:4:220,signalstats,"
                        "metadata=print:key=lavfi.signalstats.YAVG", "-f", "null", "-"],
                       cwd=caso, capture_output=True, text=True)
    tiempos = [float(x) for x in re.findall(r"pts_time:([\d.]+)", r.stderr)]
    lumas = [float(x) for x in re.findall(r"YAVG=([\d.]+)", r.stderr)]
    muestras = list(zip(tiempos, lumas))
    if not muestras or not any(y < 90 for _, y in muestras):
        return None  # nunca hubo menú oscuro: no es una sesión del backoffice
    corte = None
    for t, y in reversed(muestras):
        if y > 170:
            corte = t
        else:
            break
    if corte is None or corte <= despues_de - desde:
        return None
    return desde + max(0.0, corte - 0.4)


def leer(p, defecto=None):
    try:
        return Path(p).read_text().strip()
    except OSError:
        return defecto


def marcas(path):
    out = []
    for linea in (leer(path, "") or "").splitlines():
        if "|" in linea:
            t, k = linea.split("|", 1)
            try:
                out.append((float(t), k.strip()))
            except ValueError:
                pass
    return out


def handoff(caso, clave):
    for log_ in sorted(Path(caso).glob("acto*.log")):
        m = re.search(rf"##HANDOFF## {clave}=(.+)", log_.read_text(errors="ignore"))
        if m:
            return m.group(1).strip()
    return None


def nombres_de_pila(completo):
    p = (completo or "").split()
    if len(p) >= 4:
        return f"{p[0]} {p[1]}"
    return p[0] if p else "su cliente"


def dinero(v):
    try:
        return f"${float(v):,.0f}"
    except (TypeError, ValueError):
        return None


# ── Las versiones ────────────────────────────────────────────────────────────────────────────
# short: sólo lo que vive en el celular —alta, vale, registro del cliente final, aprobación—
#        acelerado para caber en 90 s. middle: el journey completo con el backoffice. demo_completa:
#        además sigue a la colocación por contabilidad, bancos, región y operación.
VERSIONES = {
    "short": dict(actos=["01", "04", "05", "07"], tope=90, personajes=False,
                  portada=("Crédito por distribución", "Crédito que viaja por tu red.",
                           "Del alta al depósito, todo desde el celular."),
                  guion={"04": dict(titulo="Laura emite un vale a {cliente}",
                                    beats=[("linea", "Consulta su línea disponible"),
                                           ("clienta", "Registra a {cliente}"),
                                           ("monto", "Define monto y plazo"),
                                           ("liga", "Comparte el vale con una liga")]),
                         "07": dict(titulo="Laura aprueba el vale",
                                    beats=[("lista", "{cliente} ya pasó la verificación"),
                                           ("historial", "Consulta su historial de buró"),
                                           ("aprueba", "Aprueba: el dinero se deposita")])}),
    "middle": dict(actos=["01", "02", "03", "04", "05", "06", "07", "08", "09"], tope=None, personajes=True,
                   portada=("Crédito por distribución", "Crédito que viaja por tu red.",
                            "Del registro al depósito, en minutos."), guion={}),
    "demo_completa": dict(actos=None, tope=None, personajes=True, amplio=True,
                          portada=("Crédito por distribución · demo completa", "Crédito que viaja por tu red.",
                                   "Del registro al depósito, y todo lo que pasa detrás."),
                          guion={}),
}



def analiza(caso, videos, acto_de):
    """Lo que no depende de la versión: recortes y momentos de cada grabación."""
    out = {}
    for v in videos:
        acto = acto_de(v)
        g = guion_de(acto) or dict(canal="Kredius", url=None, persona="equipo", titulo=acto, beats=[])
        rel = str(v.relative_to(caso))
        ancho, alto, dur = sonda(caso, rel)

        # Recorte: lo anterior a la prueba y lo posterior a ella no es producto.
        inicio = float(leer(f"{v}.inicio", "0") or 0)
        fin = leer(f"{v}.fin")
        desde = max(0.0, inicio - 1.0)
        hasta = min(dur, float(fin) - 1.5) if fin else dur
        if hasta - desde < 3:
            desde, hasta = 0.0, dur

        # Momentos: de la app vienen junto al video; del backoffice, en la carpeta del acto.
        ms = marcas(f"{v}.historia") or marcas(caso / acto / "historia.txt")
        claves = [k for k, _ in g["beats"]]

        # El acto empieza un instante antes de su primer momento. Lo de antes es preparación que
        # no cuenta nada —un login, la página en blanco mientras se dibujan los documentos de
        # muestra— y con el panel ya diciendo el primer paso, esa espera se ve como un desfase.
        propios = [t for t, k in ms if k in claves]
        if propios and min(propios) - 0.8 > desde and min(propios) - 0.8 < hasta - 3:
            desde = min(propios) - 0.8
        # Y termina antes de cerrar sesión: el login al final no es parte del paso.
        if alto <= ancho and propios:
            corte = fin_de_sesion(caso, rel, desde, hasta, max(propios))
            if corte and corte - desde > 3:
                hasta = corte
        out[acto] = dict(acto=acto, rel=rel, telefono=alto > ancho, desde=desde,
                         largo=hasta - desde, marcas=[(t - desde, k) for t, k in ms])
    return out


def inicios_de(claves, marcas_, largo):
    """Cuándo empieza cada momento del panel, en segundos del acto ya recortado."""
    tiempos = {}
    for t, k in marcas_:
        if k in claves and k not in tiempos:
            tiempos[k] = max(0.0, t)
    inicios = []
    for j, k in enumerate(claves):
        if k in tiempos:
            inicios.append(min(tiempos[k], largo))
        else:
            # Sin marca: se queda en el momento anterior en vez de saltar a uno inventado.
            inicios.append(inicios[-1] if inicios else 0.0)
    if not tiempos and claves:
        inicios = [largo * j / len(claves) for j in range(len(claves))]
    if inicios:
        inicios[0] = 0.0
    return inicios or [0.0]


def compone(caso, tmp, bo_dir, version, analizados, ctx):
    cfg = VERSIONES[version]
    fmt = lambda s: s.format(**ctx)
    cliente = ctx["cliente"]
    elegidos = [a for a in sorted(analizados) if cfg["actos"] is None or a[:2] in cfg["actos"]]
    if not elegidos:
        log(f"{version}: no hay actos grabados para esta versión — se omite")
        return None
    total = len(elegidos)
    vtmp = tmp / version
    vtmp.mkdir(parents=True, exist_ok=True)

    # Tarjetas sin fecha: la pieza se reutiliza en cualquier momento y una fecha la envejece.
    k, t, sub = cfg["portada"]
    tarjetas = [{"archivo": "portada.png", "tipo": "portada", "fecha": "", "kicker": k, "titulo": t, "sub": sub},
                {"archivo": "personajes.png", "tipo": "personajes", "fecha": "",
                 "kicker": "Quién participa", "titulo": "Cuatro roles, un solo flujo.",
                 "elenco": [
                     {"iniciales": "LM", "nombre": "Laura", "rol": "Distribución", "canal": "App"},
                     {"iniciales": "".join(w[0] for w in cliente.split()[:2]).upper(), "nombre": cliente,
                      "rol": "Cliente final", "canal": "Registro web"},
                     {"iniciales": "OP", "nombre": "Olivia Paz", "rol": "Cumplimiento", "canal": "Backoffice"},
                     {"iniciales": "UW", "nombre": "Ulises Ward", "rol": "Mesa de crédito", "canal": "Backoffice"},
                 ]},
                {"archivo": "dinero.png", "tipo": "momento", "fecha": "", "kicker": "El dinero llega",
                 "titulo": fmt("Depósito por SPEI a {cliente}"), "monto": f"{ctx['monto_solo']} MXN",
                 "sub": "El mismo día, a su nombre y con cada control cumplido."},
                # El cierre es el eslogan y nada más, con la misma composición que la portada. Una
                # lista de beneficios al final compite con la frase que el cliente tiene que
                # llevarse; lo que hace la plataforma ya lo vio en el video.
                {"archivo": "cierre.png", "tipo": "portada", "fecha": "",
                 "kicker": "Crédito por distribución",
                 "titulo": "Tu red presta. Tú mantienes el control.",
                 "sub": "Agenda tu demo · ventas@kredius.mx"}]

    # Duraciones de las tarjetas y, si la versión tiene tope, la velocidad que lo respeta.
    seg = dict(portada=3.5 if cfg["tope"] else 4.5, personajes=6, dinero=3.5 if cfg["tope"] else 4.5,
               cierre=5 if cfg["tope"] else 7)
    fijo = seg["portada"] + seg["dinero"] + seg["cierre"] + (seg["personajes"] if cfg["personajes"] else 0)
    bruto = sum(analizados[a]["largo"] for a in elegidos)
    velocidad = max(1.0, bruto / (cfg["tope"] - fijo - 1)) if cfg["tope"] else 1.0

    plan = []
    for i, acto in enumerate(elegidos, start=1):
        a = analizados[acto]
        g = dict(guion_de(acto) or dict(canal="Kredius", url=None, persona="equipo", titulo=acto, beats=[]))
        g.update(cfg["guion"].get(acto[:2], {}))
        layout = "telefono" if a["telefono"] else ("amplio" if cfg.get("amplio") else "navegador")
        marco = {"telefono": TELEFONO, "amplio": AMPLIO, "navegador": NAVEGADOR}[layout]
        claves = [c for c, _ in g["beats"]]
        largo = a["largo"] / velocidad
        inicios = [x / velocidad for x in inicios_de(claves, a["marcas"], a["largo"])]
        p = PERSONAS[g["persona"]]
        persona = {"iniciales": p[0] or "".join(w[0] for w in cliente.split()[:2]).upper(),
                   "nombre": fmt(p[1]), "rol": p[2]}
        estados = []
        for j in range(max(1, len(claves))):
            archivo = f"a{i:02d}-{j}.png"
            beats = [{"texto": fmt(texto),
                      "estado": "hecho" if b < j else ("actual" if b == j else "pendiente")}
                     for b, (_, texto) in enumerate(g["beats"])]
            tarjetas.append({"archivo": archivo, "tipo": "panel", "fecha": "",
                             "layout": layout,
                             "hueco": marco, "url": g.get("url"),
                             "paso": f"Paso {i} de {total}", "canal": g["canal"],
                             "titulo": fmt(g["titulo"]), "persona": persona, "beats": beats})
            estados.append(archivo)
        plan.append(dict(i=i, acto=acto, rel=a["rel"], marco=marco, desde=a["desde"],
                         largo_fuente=a["largo"], largo=largo, estados=estados, inicios=inicios))

    (vtmp / "tarjetas.json").write_text(json.dumps(tarjetas, ensure_ascii=False))
    here = Path(__file__).resolve().parent
    subprocess.run(["node", str(here / "tarjetas.cjs"), str(vtmp / "tarjetas.json"), str(vtmp),
                    str(bo_dir / "node_modules")], check=True)

    rel_tmp = vtmp.relative_to(caso)
    lista = []
    # En qué segundo del video empieza cada parte: la música resuelve justo en el depósito y en
    # el cierre, y cambia de carácter cuando empieza lo que pasa detrás.
    momentos, reloj = {}, [0.0]

    def clip_tarjeta(png, segundos, salida):
        ff(caso, ["-loop", "1", "-t", f"{segundos}", "-i", f"{rel_tmp}/{png}",
                  "-vf", "fps=30,format=yuv420p,setsar=1",
                  "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", f"{rel_tmp}/{salida}"])
        lista.append(salida)
        momentos.setdefault(png.removesuffix(".png"), reloj[0])
        reloj[0] += segundos

    clip_tarjeta("portada.png", seg["portada"], "c-portada.mp4")
    if cfg["personajes"]:
        clip_tarjeta("personajes.png", seg["personajes"], "c-personajes.mp4")

    for a in plan:
        m = a["marco"]
        # Los estados del panel van como UNA pista: las imágenes encadenadas, cada una con su
        # duración. Cinco imágenes 1080p con transparencia en bucle a la vez agotaban la memoria.
        cortes = a["inicios"] + [a["largo"]]
        guion = []
        for j, png in enumerate(a["estados"]):
            guion += [f"file '{png}'", f"duration {max(0.04, cortes[j + 1] - cortes[j]):.2f}"]
        guion.append(f"file '{a['estados'][-1]}'")  # el concat de imágenes ignora la duración del último
        (vtmp / f"panel{a['i']:02d}.txt").write_text("\n".join(guion) + "\n")
        args = ["-ss", f"{a['desde']:.2f}", "-t", f"{a['largo_fuente']:.2f}", "-i", a["rel"],
                "-f", "concat", "-safe", "0", "-i", f"{rel_tmp}/panel{a['i']:02d}.txt"]
        acelera = f"setpts=(PTS-STARTPTS)/{velocidad:.3f}," if velocidad > 1 else ""
        filtro = (f"[0:v]{acelera}scale={m['w']}:{m['h']}:force_original_aspect_ratio=decrease,"
                  f"pad={m['w']}:{m['h']}:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,"
                  f"pad={W}:{H}:{m['x']}:{m['y']}:color=0x0A3578,fps=30[base];"
                  f"[1:v]fps=30,format=rgba[panel];"
                  f"[base][panel]overlay=0:0:eof_action=repeat,format=yuv420p[out]")
        salida = f"v{a['i']:02d}.mp4"
        ff(caso, args + ["-filter_complex", filtro, "-map", "[out]", "-t", f"{a['largo']:.2f}",
                         "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                         f"{rel_tmp}/{salida}"])
        lista.append(salida)
        momentos.setdefault("cuerpo", reloj[0])
        if int(a["acto"][:2]) >= 10:
            momentos.setdefault("detras", reloj[0])
        reloj[0] += a["largo"]
        log(f"{version} · acto {a['i']:02d} compuesto: {a['acto']} ({a['largo']:.0f} s"
            f"{f', x{velocidad:.2f}' if velocidad > 1 else ''})")
        if a["acto"].startswith("07"):
            clip_tarjeta("dinero.png", seg["dinero"], "c-dinero.mp4")

    clip_tarjeta("cierre.png", seg["cierre"], "c-cierre.mp4")

    (vtmp / "lista.txt").write_text("".join(f"file '{x}'\n" for x in lista))
    nombre = f"Kredius-credito-por-distribuidoras-{version}.mp4"
    ff(caso, ["-f", "concat", "-safe", "0", "-i", f"{rel_tmp}/lista.txt", "-c", "copy",
              f"{rel_tmp}/sin-audio.mp4"])
    ff(caso, ["-i", f"{rel_tmp}/sin-audio.mp4"] + pista(vtmp, reloj[0], momentos)
       + ["-map", "0:v", "-c:v", "copy", "-movflags", "+faststart", nombre])
    return caso / nombre


def pista(vtmp, duracion, momentos):
    """Los argumentos de ffmpeg que le ponen música al video.

    Por defecto, la pieza original de `musica.py`, compuesta a la medida de este corte. Con
    `MUSICA=<archivo>` se usa una pista con licencia propia en su lugar (se repite si es más
    corta y se desvanece al final); con `MUSICA=no`, el video sale mudo como antes.
    """
    elegida = os.environ.get("MUSICA", "")
    if elegida == "no":
        return ["-an"]
    if elegida:
        fuera = max(0.0, duracion - 4)
        return ["-stream_loop", "-1", "-i", str(Path(elegida).resolve()), "-map", "1:a",
                "-af", f"afade=in:d=1.5,afade=out:st={fuera:.2f}:d=4", "-t", f"{duracion:.2f}",
                "-c:a", "aac", "-b:a", "192k"]
    # Se carga por ruta: con `python -I` el directorio del script no está en sys.path.
    import importlib.util
    spec = importlib.util.spec_from_file_location("musica", Path(__file__).resolve().parent / "musica.py")
    musica = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(musica)  # numpy, instalado por une-video.sh en .venv
    wav = vtmp / "musica.wav"
    musica.escribe_wav(wav, musica.compone(duracion, momentos))
    return ["-i", str(wav), "-map", "1:a", "-t", f"{duracion:.2f}", "-c:a", "aac", "-b:a", "192k"]


def main():
    caso = Path(sys.argv[1]).resolve()
    bo_dir = Path(sys.argv[2]).resolve()
    versiones = sys.argv[3:] or list(VERSIONES)
    tmp = caso / ".video"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir()

    cliente = nombres_de_pila(handoff(caso, "BENEFICIARIA_NOMBRE"))
    alta = json.loads(leer(caso / "estado-tras-alta-web.json", "{}") or "{}")
    placement = alta.get("placement", {}) if isinstance(alta, dict) else {}
    monto = dinero(placement.get("amount")) or "$45,000"
    plazo = placement.get("termFortnights") or 24
    ctx = {"cliente": cliente, "monto": f"{monto} a {plazo} quincenas", "monto_solo": monto}

    videos = sorted(v for v in caso.rglob("*")
                    if v.suffix in (".mp4", ".webm") and "report" not in v.parts
                    and ".video" not in v.parts and not v.name.startswith(("Kredius-", "E2E-")))
    def acto_de(v):
        rel = v.relative_to(caso)
        return rel.parts[0] if len(rel.parts) > 1 else v.stem
    analizados = analiza(caso, videos, acto_de)

    hechos = []
    for version in versiones:
        # La demo completa sólo existe si se grabaron los actos que la distinguen; si no, sería
        # la middle con otro nombre.
        if version == "demo_completa" and not any(int(a[:2]) > 9 for a in analizados if a[:2].isdigit()):
            log("demo_completa: faltan los actos de contabilidad, bancos, región y operación — se omite")
            continue
        salida = compone(caso, tmp, bo_dir, version, analizados, ctx)
        if salida:
            hechos.append(salida)
    if not os.environ.get("CONSERVA_TEMPORALES"):
        shutil.rmtree(tmp, ignore_errors=True)
    for h in hechos:
        print(h)


if __name__ == "__main__":
    main()
