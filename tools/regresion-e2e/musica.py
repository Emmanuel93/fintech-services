#!/usr/bin/env python3
"""Música ambiental para los videos, compuesta aquí nota por nota.

Es original: no es de un catálogo, así que no hay licencia que revisar ni atribución que poner en
el video, y se puede enseñar a un cliente, subir a una red o proyectar en un evento sin riesgo.

El estilo es el de una marca financiera: ambient corporativo cálido. Va a 80 BPM, en Re mayor y
con acordes de novena y suspendidos, que transmiten confianza y avance sin dramatismo. Lleva pads
suaves, un piano de fieltro, un bajo discreto y un pulso apenas audible. No hay batería ni melodía
protagonista: acompaña la pantalla, no compite con ella ni con quien presenta encima.

La pieza sigue la historia del video, que le pasa en qué segundo empieza cada parte:

  intro    portada y personajes: sólo pads y unas notas sueltas
  cuerpo   el journey: entran el bajo, el arpegio y el pulso
  dinero   el depósito: una cadencia que resuelve justo en la tarjeta, con un brillo arriba
  detras   la demo completa (contabilidad, bancos, accesos): progresión con más movimiento
  cierre   resuelve en Re y se apaga con el eslogan

    python3 musica.py <salida.wav> <duración> [cuerpo=12.5] [dinero=210] [detras=380] [cierre=700]

Con la misma entrada sale el mismo audio (semilla fija): regenerar el video no cambia la música.
"""
import math
import sys
import wave

import numpy as np

SR = 44100
BPM = 80
BEAT = 60 / BPM
BAR = 4 * BEAT  # 3 s: un acorde dura dos compases, 6 s

# Por acorde: bajo, pad (sin la fundamental, que la pone el bajo) y los tonos del arpegio.
ACORDES = {
    "D": (38, [50, 57, 61, 64, 66], [69, 73, 74, 76, 78]),     # Dmaj9
    "Bm": (35, [54, 57, 62, 64, 66], [66, 69, 71, 73, 74]),    # Bm(add11)
    "G": (43, [50, 54, 57, 59, 62], [67, 71, 74, 78, 81]),     # Gmaj9
    "Asus": (45, [52, 57, 59, 62, 64], [69, 71, 74, 76, 81]),  # Asus4(add9)
    "A": (45, [52, 57, 61, 64, 71], [69, 73, 76, 78, 81]),     # A(add9)
    "F#m": (42, [52, 57, 61, 64, 66], [66, 69, 73, 76, 78]),   # F#m7
    "Em": (40, [50, 55, 59, 62, 66], [64, 67, 71, 74, 78]),    # Em9
}
PROGRESIONES = {
    "intro": ["D", "G"],
    "cuerpo": ["D", "Bm", "G", "Asus", "D", "F#m", "Em", "Asus"],
    "dinero": ["D"],
    "detras": ["G", "A", "F#m", "Bm", "Em", "G", "Asus", "A"],
    "cierre": ["D"],
}
# Dónde cae una nota del arpegio dentro del compás, en corcheas (0–7).
PATRONES = {"intro": [0, 6], "cuerpo": [0, 3, 6], "detras": [0, 2, 3, 5, 6], "cierre": []}


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def _tabla_pad():
    """Un ciclo de la onda del pad: armónicos que caen rápido, cálida y sin estridencia."""
    n = np.arange(2048) / 2048
    w = sum(a * np.sin(2 * np.pi * k * n) for k, a in [(1, 1), (2, .35), (3, .18), (4, .08), (5, .04)])
    return (w / np.abs(w).max()).astype(np.float32)


TABLA = _tabla_pad()


def secciones(duracion, momentos):
    """[(inicio, fin, tipo, desfase)] a partir de los momentos del video."""
    marcas = [(0.0, "intro", 0)]
    if "cuerpo" in momentos:
        marcas.append((momentos["cuerpo"], "cuerpo", 0))
    if "dinero" in momentos:
        marcas.append((momentos["dinero"], "dinero", 0))
        # Tras el depósito sigue el journey, pero sin repetir el acorde con que resolvió.
        marcas.append((momentos["dinero"] + 2 * BAR, "cuerpo", 1))
    if "detras" in momentos:
        marcas.append((momentos["detras"], "detras", 0))
    if "cierre" in momentos:
        marcas.append((momentos["cierre"], "cierre", 0))
    marcas = sorted((t, k, d) for t, k, d in marcas if 0 <= t < duracion)
    out = []
    for i, (t, k, d) in enumerate(marcas):
        fin = marcas[i + 1][0] if i + 1 < len(marcas) else duracion
        if fin - t > 0.05:
            out.append((t, fin, k, d))
    return out


def acordes(secs, momentos):
    """[(inicio, fin, acorde)]: cada sección con su progresión, y las cadencias de llegada."""
    segs = []
    for t0, t1, tipo, desfase in secs:
        prog = PROGRESIONES[tipo]
        t, i = t0, desfase
        while t < t1 - 0.05:
            segs.append((t, min(t1, t + 2 * BAR), prog[i % len(prog)]))
            t += 2 * BAR
            i += 1
    # El depósito y el cierre llegan por una cadencia (suspendido → dominante → tónica): el oído
    # siente que algo se resuelve justo cuando la pantalla lo dice.
    for clave in ("dinero", "cierre"):
        ev = momentos.get(clave)
        if ev is None or ev - 2 * BAR < 0:
            continue
        segs = _parcha(segs, ev - 2 * BAR, ev - BAR, "Asus")
        segs = _parcha(segs, ev - BAR, ev, "A")
    return segs


def _parcha(segs, a, b, acorde):
    out = []
    for t0, t1, c in segs:
        if t1 <= a or t0 >= b:
            out.append((t0, t1, c))
            continue
        if t0 < a:
            out.append((t0, a, c))
        if t1 > b:
            out.append((b, t1, c))
    out.append((a, b, acorde))
    return sorted(out)


def acorde_en(segs, t):
    for t0, t1, c in segs:
        if t0 <= t < t1:
            return c
    return segs[-1][2]


def _suma(buf, i0, mono, gan, pan):
    i1 = min(buf.shape[1], i0 + len(mono))
    if i1 <= i0:
        return
    v = mono[: i1 - i0] * gan
    buf[0, i0:i1] += v * math.cos(pan * math.pi / 2)
    buf[1, i0:i1] += v * math.sin(pan * math.pi / 2)


def pad(buf, t0, t1, notas, gan, rng, cola=1.8, ataque=1.2):
    """Un acorde sostenido: dos voces desafinadas por nota, abiertas en estéreo, que respiran."""
    n = int((t1 - t0 + cola) * SR)
    t = np.arange(n, dtype=np.float32) / SR
    env = np.sin(np.minimum(1.0, t / ataque) * np.pi / 2) ** 2
    env *= np.cos(np.clip((t - (t1 - t0)) / cola, 0, 1) * np.pi / 2) ** 2
    for m in notas:
        for cents, p in ((-6, 0.22), (6, 0.78)):
            f = hz(m) * 2 ** (cents / 1200)
            fase = (f * t + rng.random()) % 1.0
            onda = TABLA[(fase * 2048).astype(np.int32)]
            lfo = 1 + 0.12 * np.sin(2 * np.pi * (0.06 + 0.05 * rng.random()) * t + 6.28 * rng.random())
            _suma(buf, int(t0 * SR), onda * env * lfo, gan, p)


def bajo(buf, t0, t1, m, gan):
    n = int((t1 - t0 + 1.0) * SR)
    t = np.arange(n, dtype=np.float32) / SR
    env = np.minimum(1.0, t / 0.5) * np.cos(np.clip((t - (t1 - t0)) / 1.0, 0, 1) * np.pi / 2) ** 2
    # Con más segundo armónico: en bocinas de laptop la fundamental (60–110 Hz) casi no suena.
    s = np.sin(2 * np.pi * hz(m) * t) + 0.5 * np.sin(4 * np.pi * hz(m) * t)
    _suma(buf, int(t0 * SR), s * env, gan, 0.5)


_TECLAS = {}


def tecla(m):
    """Piano de fieltro: ataque blando y armónicos que se apagan antes que la fundamental."""
    if m not in _TECLAS:
        t = np.arange(int(2.6 * SR), dtype=np.float32) / SR
        f = hz(m)
        k = (f / 440) ** 0.3  # las notas agudas duran menos, como en un piano
        s = (np.sin(2 * np.pi * f * t) * np.exp(-t * 1.6 * k)
             + 0.45 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t * 3.0 * k)
             + 0.16 * np.sin(2 * np.pi * 3 * f * t) * np.exp(-t * 5.0 * k)
             + 0.05 * np.sin(2 * np.pi * 4.01 * f * t) * np.exp(-t * 8.0 * k))
        s *= np.minimum(1.0, t / 0.008)
        s[-3000:] *= np.linspace(1, 0, 3000, dtype=np.float32)
        _TECLAS[m] = s.astype(np.float32)
    return _TECLAS[m]


def _tick():
    rng = np.random.default_rng(7)
    r = rng.standard_normal(int(0.05 * SR)).astype(np.float32)
    r = np.diff(np.diff(r, prepend=0), prepend=0)  # sólo los agudos: un shaker, no un golpe
    r *= np.exp(-np.arange(len(r)) / SR / 0.012).astype(np.float32)
    return r / np.abs(r).max()


def respuesta_sala(rt60=2.6, largo=3.0):
    """La sala: ruido que decae, sin graves que embarren ni agudos que silben; un canal por lado."""
    n = int(largo * SR)
    t = np.arange(n) / SR
    rng = np.random.default_rng(11)
    fr = np.fft.rfftfreq(n, 1 / SR)
    forma = 1 / np.sqrt(1 + (fr / 5000) ** 4) * np.sqrt(np.minimum(1, fr / 180))
    out = []
    for _ in range(2):
        r = np.fft.irfft(np.fft.rfft(rng.standard_normal(n)) * forma, n) * np.exp(-6.9 * t / rt60)
        pre = int(0.025 * SR)
        out.append(np.concatenate([np.zeros(pre), r[:-pre]]))
    h = np.array(out)
    return (h / np.sqrt((h ** 2).sum(axis=1, keepdims=True))).astype(np.float32)


def convoluciona(x, h):
    """Convolución por bloques (overlap-add): una pieza de 12 minutos no cabe en una sola FFT."""
    m, bloque = len(h), 1 << 16
    n = 1 << math.ceil(math.log2(bloque + m - 1))
    H = np.fft.rfft(h, n)
    y = np.zeros(len(x) + m, np.float32)
    for i in range(0, len(x), bloque):
        x_i = x[i:i + bloque]
        y_i = np.fft.irfft(np.fft.rfft(x_i, n) * H, n)[: len(x_i) + m - 1]
        y[i:i + len(y_i)] += y_i
    return y[: len(x)]


def compone(duracion, momentos, semilla=2026):
    rng = np.random.default_rng(semilla)
    n = int(duracion * SR)
    seco = np.zeros((2, n), np.float32)
    envio = np.zeros((2, n), np.float32)  # lo que va a la sala
    secs = secciones(duracion, momentos)
    segs = acordes(secs, momentos)
    tipo_en = lambda t: next((k for a, b, k, _ in secs if a <= t < b), "cuerpo")

    for t0, t1, c in segs:
        tipo = tipo_en(t0)
        raiz, notas, _ = ACORDES[c]
        g = {"intro": .030, "cierre": .034}.get(tipo, .026)
        pad(seco, t0, t1, notas, g, rng)
        pad(envio, t0, t1, notas, g * .55, rng)
        if tipo != "intro":
            bajo(seco, t0, t1, raiz, .04 if tipo in ("cuerpo", "detras") else .03)

    # Arpegio: cada sección en su propia rejilla, y el tono sale del acorde que suena en ese
    # instante (también en las cadencias). Una caminata por los tonos, no saltos al azar.
    tick = _tick()
    for t0, t1, tipo, _ in secs:
        idx = 2
        if tipo == "dinero":
            # El brillo del depósito: semicorcheas que suben, una octava arriba, y se desvanecen.
            for j in range(32):
                t = t0 + j * BEAT / 4
                if t >= min(t1, duracion):
                    break
                tonos = ACORDES[acorde_en(segs, t)][2]
                m = tonos[j % len(tonos)] + 12 * (1 if j < 16 else 0)
                gan = .065 * (1 - j / 40)
                _suma(seco, int(t * SR), tecla(m), gan, .3 + .4 * (j % 2))
                _suma(envio, int(t * SR), tecla(m), gan * 1.2, .5)
            continue
        if tipo == "cierre":
            # Un motivo que baja y se posa en la tónica, con el eslogan en pantalla.
            for beat, m, gan in ((0, 78, .09), (1, 76, .08), (2, 74, .08), (4, 69, .07), (6, 74, .09)):
                t = t0 + beat * BEAT
                if t < duracion:
                    _suma(seco, int(t * SR), tecla(m), gan, .45)
                    _suma(envio, int(t * SR), tecla(m), gan * 1.1, .5)
            continue
        compas = 0
        t_compas = t0
        while t_compas < t1:
            for corchea in PATRONES[tipo]:
                t = t_compas + corchea * BEAT / 2
                if t >= t1:
                    break
                tonos = ACORDES[acorde_en(segs, t)][2]
                idx = int(np.clip(idx + rng.choice([-2, -1, 1, 1, 2]), 0, len(tonos) - 1))
                gan = (.075 if tipo == "intro" else .065) * rng.uniform(.75, 1.0) * (1.15 if corchea == 0 else 1)
                pan = .5 + rng.uniform(-.3, .3)
                _suma(seco, int(t * SR), tecla(tonos[idx]), gan, pan)
                _suma(envio, int(t * SR), tecla(tonos[idx]), gan * 1.1, pan)
            if tipo in ("cuerpo", "detras"):
                # El pulso: un shaker en corcheas, más presente a contratiempo.
                for corchea in range(8):
                    t = t_compas + corchea * BEAT / 2
                    if t >= t1:
                        break
                    gan = .010 if corchea % 2 else .005
                    _suma(seco, int(t * SR), tick, gan, .35 if corchea % 4 < 2 else .65)
                    _suma(envio, int(t * SR), tick, gan * .6, .5)
            compas += 1
            t_compas = t0 + compas * BAR

    h = respuesta_sala()
    mezcla = seco + np.stack([convoluciona(envio[0], h[0]), convoluciona(envio[1], h[1])])

    # Nivel de fondo (≈ -21 dBFS RMS): música de presentación, para que alguien pueda hablar
    # encima. Los picos se redondean en vez de recortarse.
    rms = float(np.sqrt(np.mean(mezcla ** 2))) or 1.0
    mezcla *= 10 ** (-21 / 20) / rms
    mezcla = np.tanh(mezcla * 1.2) / 1.2
    t = np.arange(n, dtype=np.float32) / SR
    mezcla *= np.minimum(1.0, t / 1.5)
    mezcla *= np.minimum(1.0, (duracion - t) / 4.0).clip(0, 1)
    return mezcla


def escribe_wav(ruta, mezcla):
    pcm = (np.clip(mezcla, -1, 1) * 32767).astype("<i2").T.copy()
    with wave.open(str(ruta), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    ruta, duracion = sys.argv[1], float(sys.argv[2])
    momentos = {k: float(v) for k, v in (a.split("=", 1) for a in sys.argv[3:])}
    escribe_wav(ruta, compone(duracion, momentos))


if __name__ == "__main__":
    main()
