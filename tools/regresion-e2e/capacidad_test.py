#!/usr/bin/env python3
"""Pruebas de la medición de capacidad.

    .venv/bin/python -I capacidad_test.py

Lo que invalidaría las cifras del video: un percentil mal calculado, un escalón que no cuenta los
errores o los reinicios, y —la que más importa— que el generador de carga fuera el cuello de
botella. Si el cliente no puede pedir más rápido de lo que el sistema responde, la cifra mide al
cliente. Se comprueba contra un servidor local que responde al instante: el generador debe dar
holgadamente más de lo que se midió en la plataforma.
"""
import http.server
import importlib.util
import json
import threading
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("capacidad", AQUI / "capacidad.py")
cap = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cap)


class Eco(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # conexión persistente, como el gateway

    def do_GET(self):
        cuerpo = b"{}" if not self.path.startswith("/falla") else b"no"
        self.send_response(200 if not self.path.startswith("/falla") else 503)
        self.send_header("Content-Length", str(len(cuerpo)))
        self.end_headers()
        self.wfile.write(cuerpo)

    def log_message(self, *a):
        pass


class CapacidadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Eco)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.original = (cap.GATEWAY, cap.consumo, cap.reinicios)
        cap.GATEWAY = ("127.0.0.1", cls.srv.server_address[1])
        cap.consumo = lambda: [("eco", 0.0)]
        cap.reinicios = lambda: {}

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cap.GATEWAY, cap.consumo, cap.reinicios = cls.original

    def test_percentiles(self):
        xs = [i / 100 for i in range(1, 101)]
        self.assertAlmostEqual(cap.percentil(xs, .5), 0.505, places=6)
        self.assertAlmostEqual(cap.percentil(xs, .95), 0.9505, places=6)
        self.assertIsNone(cap.percentil([], .95))

    def test_un_escalon_cuenta_los_errores(self):
        e = cap.escalon("eco", "/falla", "x", 2, 1)
        self.assertEqual(e["errores"], 1.0)
        self.assertEqual(e["rps"], 0)

    def test_el_generador_no_es_el_cuello(self):
        e = cap.escalon("eco", "/ok", "x", 16, 3)
        # Contra lo medido si hay medición; si no, contra el orden de magnitud que da hoy la plataforma.
        resumen = cap.DESTINO / "capacidad.json"
        medido = json.loads(resumen.read_text())["lectura"] if resumen.exists() else {}
        tope = max([r["maximo"]["rps"] for r in medido.values()] or [1700])
        self.assertGreater(e["rps"], tope * 1.5,
                           f"el generador da {e['rps']:.0f} req/s; con un tope medido de {tope:.0f} no hay holgura")


if __name__ == "__main__":
    unittest.main(verbosity=2)
