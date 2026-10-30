#!/usr/bin/env python3
"""Pruebas del video de arquitectura.

    .venv/bin/python -I arquitectura_test.py

No juzgan el diseño —eso se mira—, sino lo que rompería la pieza: un momento que nombra una pieza
que no existe (el diagrama se queda quieto y el panel habla de otra cosa), un copy que no cabe en
su línea, una parte que no dice si lo que muestra corre hoy o es propuesta, un short que pasa de
90 s, y que capturar los cuadros sea lento o dé cuadros distintos con la misma escena.
"""
import hashlib
import importlib.util
import json
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("arquitectura", AQUI / "arquitectura.py")
arq = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(arq)

NODE_MODULES = (AQUI / "../../../fintech-backoffice-web/node_modules").resolve()
MOMENTO_MAX = 38  # caracteres: lo que cabe en una línea del panel
SENAL_MAX = 40


def todas():
    return [(v, e) for v in arq.VERSIONES for e in arq.escenas_de(v)]


class GuionTest(unittest.TestCase):
    def test_cada_momento_nombra_piezas_que_existen(self):
        for version, e in todas():
            d = e["diagrama"]
            ids = {x["id"] for x in d["grupos"] + d["nodos"] + d["aristas"]}
            for a in d["aristas"]:
                for extremo in (a["de"], a["a"]):
                    if isinstance(extremo, str):
                        self.assertIn(extremo, ids, f"{version}/{e['clave']}: arista {a['id']} → {extremo}")
            for j, m in enumerate(e["momentos"]):
                nombrados = (m["ve"] if m["ve"] != "*" else []) + m["foco"] + m["flujo"]
                nombrados += [s["en"] for s in m["marcas"] if isinstance(s["en"], str)]
                for id in nombrados:
                    self.assertIn(id, ids, f"{version}/{e['clave']} momento {j + 1}: «{id}» no existe")

    def test_ningun_id_se_repite_en_una_escena(self):
        for version, e in todas():
            d = e["diagrama"]
            ids = [x["id"] for x in d["grupos"] + d["nodos"] + d["aristas"]]
            self.assertEqual(len(ids), len(set(ids)), f"{version}/{e['clave']}: ids repetidos")

    def test_el_copy_cabe_en_su_linea(self):
        for version, e in todas():
            for m in e["momentos"]:
                self.assertLessEqual(len(m["texto"]), MOMENTO_MAX, f"{version}/{e['clave']}: «{m['texto']}»")
                for s in m["marcas"]:
                    self.assertLessEqual(len(s["t"] or ""), SENAL_MAX, f"{version}/{e['clave']}: «{s['t']}»")

    def test_cada_parte_dice_si_corre_hoy_o_es_propuesta(self):
        for version, e in todas():
            self.assertIn(e["estado"]["t"], {"En el código hoy", "Propuesta", "Código + propuesta", "Hoja de ruta",
                                             "En el código · STP simulado", "Medido en local"}, f"{version}/{e['clave']}")

    def test_lo_propuesto_se_marca_en_las_partes_mixtas(self):
        """Una parte «Código + propuesta» sin nada punteado no le dice al equipo qué es qué."""
        for version, e in todas():
            if e["estado"] is arq.MIXTO:
                self.assertTrue(any(n.get("objetivo") for n in e["diagrama"]["nodos"])
                                or any(s["t"] and s["t"].startswith(("Propuesta", "Hoy")) for m in e["momentos"]
                                       for s in m["marcas"]) or any(m["texto"].startswith("Propuesta")
                                                                     for m in e["momentos"]),
                                f"{version}/{e['clave']}: mixta sin nada marcado como propuesta")

    def test_cada_diagrama_queda_centrado_en_su_lienzo(self):
        """Márgenes iguales a cada lado: la caja de zonas y nodos, centrada y dentro del lienzo."""
        W, H = arq.LIENZO
        for version, e in todas():
            cajas = e["diagrama"]["grupos"] + e["diagrama"]["nodos"]
            x0, y0 = min(c["x"] for c in cajas), min(c["y"] for c in cajas)
            x1, y1 = max(c["x"] + c["w"] for c in cajas), max(c["y"] + c["h"] for c in cajas)
            self.assertLessEqual(abs(x0 - (W - x1)), 1, f"{version}/{e['clave']}: izq {x0} · der {W - x1}")
            self.assertLessEqual(abs(y0 - (H - y1)), 1, f"{version}/{e['clave']}: arriba {y0} · abajo {H - y1}")
            self.assertGreaterEqual(min(x0, y0), 0, f"{version}/{e['clave']}: se sale del lienzo")

    def test_las_partes_se_numeran_en_orden(self):
        for version in arq.VERSIONES:
            es = arq.escenas_de(version)
            for i, e in enumerate(es, start=1):
                self.assertTrue(e["panel"]["kicker"].startswith(f"Parte {i} de {len(es)} · "))

    def test_el_short_dura_menos_de_90_segundos(self):
        cfg = arq.VERSIONES["short"]
        es = arq.escenas_de("short")
        momentos = sum(len(e["momentos"]) for e in es)
        fijo = 3.5 + 5  # portada y cierre con tope
        ritmo = min(cfg["ritmo"], (cfg["tope"] - fijo - 1) / momentos)
        total = fijo + sum(arq.duracion(m, cfg, ritmo) for e in es for m in e["momentos"])
        self.assertLessEqual(total, 90)
        self.assertGreaterEqual(ritmo, 3.5, "a menos de 3.5 s por momento no da tiempo de leer")

    def test_preparar_el_guion_es_instantaneo(self):
        inicio = time.perf_counter()
        for _ in range(20):
            for v in arq.VERSIONES:
                arq.escenas_de(v)
        self.assertLess(time.perf_counter() - inicio, 2.0)


@unittest.skipUnless((NODE_MODULES / "playwright").exists() and shutil.which("node"), "sin Playwright del backoffice")
class CapturaTest(unittest.TestCase):
    """Captura de verdad una escena chica: rapidez y que la misma escena dé los mismos cuadros."""

    def captura(self, salida):
        e = arq.escena_kubernetes("")
        e.update(id="prueba", clave="kubernetes", colores=arq.COLORES, aristas=arq.ARISTAS,
                 etiquetaObjetivo="propuesta", momentos=e["momentos"][:2])
        e["panel"].update(seccion="Arquitectura", kicker="Parte 1 de 1 · Prueba", estado=e["estado"])
        entrada = salida / "escenas.json"
        entrada.write_text(json.dumps({"escenas": [e]}, ensure_ascii=False))
        inicio = time.perf_counter()
        r = subprocess.run(["node", str(AQUI / "diagramas.cjs"), str(entrada), str(salida), str(NODE_MODULES)],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("no cabe", r.stderr)
        return time.perf_counter() - inicio, salida / "prueba"

    def test_captura_rapido_y_completo(self):
        with tempfile.TemporaryDirectory() as tmp:
            segundos, d = self.captura(Path(tmp))
            cuadros = json.loads((d / "cuadros.json").read_text())
            esperados = sum(c["transicion"] + c["bucle"] for c in cuadros)
            self.assertEqual(len(list(d.glob("*.jpg"))), esperados)
            # Dos momentos, uno con paquetes en marcha: ~76 cuadros. Con margen para una máquina lenta.
            self.assertLess(segundos, 30, f"capturar {esperados} cuadros tardó {segundos:.1f} s")

    def test_misma_escena_mismos_cuadros(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            _, da = self.captura(Path(a))
            _, db = self.captura(Path(b))
            for nombre in ("b0-t07.jpg", "b1-l15.jpg"):
                ha = hashlib.sha1((da / nombre).read_bytes()).hexdigest()
                hb = hashlib.sha1((db / nombre).read_bytes()).hexdigest()
                self.assertEqual(ha, hb, f"{nombre} cambió entre corridas")


if __name__ == "__main__":
    unittest.main(verbosity=2)
