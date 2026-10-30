#!/usr/bin/env python3
"""Pruebas de la música del video.

    .venv/bin/python -I musica_test.py

No juzgan si suena bonito —eso se escucha—, sino lo que rompería un video: que dure distinto que
la imagen, que recorte, que una sección quede en silencio, que la cadencia no caiga en el depósito,
que cambie entre corridas o que tarde tanto que armar la demo completa se vuelva un estorbo.
"""
import importlib.util
import time
import unittest
from pathlib import Path

import numpy as np

_spec = importlib.util.spec_from_file_location("musica", Path(__file__).resolve().parent / "musica.py")
musica = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(musica)

SR = musica.SR
# Como el corte short: portada 3.5 s, actos, depósito a los 62 s y cierre a los 84 s.
MOMENTOS = {"cuerpo": 3.5, "dinero": 62.0, "cierre": 84.0}


class MusicaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mezcla = musica.compone(89.0, MOMENTOS)

    def test_dura_lo_mismo_que_el_video(self):
        self.assertEqual(self.mezcla.shape, (2, int(89.0 * SR)))

    def test_no_recorta_y_queda_de_fondo(self):
        pico = float(np.abs(self.mezcla).max())
        rms_db = 20 * np.log10(float(np.sqrt(np.mean(self.mezcla ** 2))))
        self.assertLess(pico, 0.9, "un pico tan alto distorsiona al codificar en AAC")
        self.assertTrue(-26 < rms_db < -17, f"nivel de fondo fuera de rango: {rms_db:.1f} dBFS")

    def test_ninguna_seccion_queda_muda(self):
        for desde, hasta in ((0.5, 3.5), (5, 60), (62, 68), (84, 87)):
            tramo = self.mezcla[:, int(desde * SR):int(hasta * SR)]
            self.assertGreater(float(np.sqrt(np.mean(tramo ** 2))), 0.01, f"silencio entre {desde} y {hasta} s")

    def test_entra_y_sale_sin_golpe(self):
        self.assertLess(float(np.abs(self.mezcla[:, :int(0.05 * SR)]).max()), 0.02)
        self.assertLess(float(np.abs(self.mezcla[:, -int(0.05 * SR):]).max()), 0.02)

    def test_la_cadencia_resuelve_en_el_deposito_y_en_el_cierre(self):
        segs = musica.acordes(musica.secciones(89.0, MOMENTOS), MOMENTOS)
        for ev in (62.0, 84.0):
            self.assertEqual(musica.acorde_en(segs, ev - 4.5), "Asus")
            self.assertEqual(musica.acorde_en(segs, ev - 1.5), "A")
            self.assertEqual(musica.acorde_en(segs, ev + 0.1), "D")

    def test_cubre_todo_el_video_sin_huecos_entre_acordes(self):
        momentos = {"cuerpo": 10.5, "dinero": 210.0, "detras": 380.0, "cierre": 720.0}
        segs = musica.acordes(musica.secciones(727.0, momentos), momentos)
        self.assertAlmostEqual(segs[0][0], 0.0)
        self.assertAlmostEqual(segs[-1][1], 727.0)
        for (_, fin, _), (inicio, _, _) in zip(segs, segs[1:]):
            self.assertAlmostEqual(fin, inicio, places=6)

    def test_misma_entrada_mismo_audio(self):
        otra = musica.compone(89.0, MOMENTOS)
        self.assertTrue(np.array_equal(self.mezcla, otra))

    def test_la_demo_completa_se_compone_en_segundos(self):
        momentos = {"cuerpo": 10.5, "dinero": 210.0, "detras": 380.0, "cierre": 720.0}
        inicio = time.perf_counter()
        mezcla = musica.compone(727.0, momentos)  # 12:07, lo que dura la demo completa
        segundos = time.perf_counter() - inicio
        self.assertEqual(mezcla.shape[1], int(727.0 * SR))
        self.assertLess(segundos, 60, f"componer 12 min tardó {segundos:.1f} s")


if __name__ == "__main__":
    unittest.main(verbosity=2)
