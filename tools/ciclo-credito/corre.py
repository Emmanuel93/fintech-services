#!/usr/bin/env python3
"""Runs the credit life cycle end to end against the local stack and hands over the evidence.

    python3 corre.py preparar              # platform with migrations only + the E2E's own configuration (configura.py)
    python3 corre.py simular [--suite productos|distribuidoras|PERSONAL_LOAN|…] [--desde …] [--hasta …] [--semilla N]
                             [--solo E01,E05] [--evidencia DIR]
    python3 corre.py entregar DIR          # statements, vouchers, ledgers, reconciliation of a finished run
    python3 corre.py veredicto DIR         # did every scenario end as planned and does the chain reconcile? exit 1 if not
    python3 corre.py estados DIR           # every statement of the environment, by product and client, with an index
    python3 corre.py reloj 2026-04-01      # today is that date in every service (the demo seed originates in the past)
    python3 corre.py reloj-real            # every service back on the real clock
    python3 corre.py puente up|down        # the port bridge the engine uses

Before: `corre.py preparar` — a virgin platform (`scripts/reinicia-entorno.sh --solo-plataforma`) and the E2E's
own fixed configuration (`configura.py`: products checked, a minimal sales structure, one person per role). The
demo seed is never its base: the run signs its people up itself (`altas.py`). The run
leaves the environment with every simulated day closed; the real nightly chain continues from the
day after `--hasta`, so the default end is the real yesterday when the plan was written.
"""
from __future__ import annotations

import argparse
import datetime
import json
import pathlib
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import configura
import entrega
import escenarios
import huella
import motor
import puente
import veredicto


def carpeta_de_evidencia(nombre: str | None, suite: str = "productos") -> pathlib.Path:
    if nombre:
        return pathlib.Path(nombre).expanduser()
    raiz = pathlib.Path.home() / "Documents" / "material-kredius" / "pruebas"
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=motor.RAIZ).stdout.strip() or "sin-commit"
    if subprocess.run(["git", "diff", "--quiet", "HEAD"], cwd=motor.RAIZ).returncode != 0:
        commit += "-sucio"
    sufijo = "" if suite == "productos" else f"{suite.lower().replace('_', '-')}-"
    return raiz / commit / f"ciclo-credito-{sufijo}{time.strftime('%Y%m%d-%H%M%S')}"


def suite_de(evidencia: pathlib.Path) -> str:
    """The suite a run's evidence belongs to (suite.json); a run made before suites existed is «productos»."""
    f = evidencia / "suite.json"
    return json.loads(f.read_text()).get("suite", "productos") if f.exists() else "productos"


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("simular")
    s.add_argument("--suite", default="productos", choices=sorted(escenarios.SUITES),
                   help="las pruebas de un producto (o de todos menos la distribuidora, o de la distribuidora)")
    s.add_argument("--desde", help="por omisión, el inicio de la suite")
    s.add_argument("--hasta", help="por omisión, el fin de la suite")
    s.add_argument("--semilla", type=int, help="por omisión, la de la suite")
    s.add_argument("--solo", help="claves de escenario separadas por coma")
    s.add_argument("--evidencia")
    s.add_argument("--sin-entrega", action="store_true")
    s.add_argument("--reanudar", help="carpeta de evidencia de una corrida interrumpida: sigue desde su último día")
    sub.add_parser("preparar", help="plataforma virgen (sólo migraciones) y la configuración propia del E2E")
    e = sub.add_parser("entregar")
    e.add_argument("dir")
    v = sub.add_parser("veredicto", help="compara el desenlace de cada escenario con lo esperado y concilia la cadena")
    v.add_argument("dir")
    est = sub.add_parser("estados", help="baja todos los estados de cuenta del entorno, por producto y cliente, con índice")
    est.add_argument("dir")
    sub.add_parser("reloj-real")
    r = sub.add_parser("reloj", help="pone «hoy» en esa fecha en los 14 servicios (la siembra de demo origina en el pasado)")
    r.add_argument("fecha")
    h = sub.add_parser("huellas", help="compara la huella de dos corridas: dos corridas idénticas dejan la misma")
    h.add_argument("primera")
    h.add_argument("segunda")
    p = sub.add_parser("puente")
    p.add_argument("accion", choices=["up", "down", "status"])
    args = ap.parse_args()

    if args.cmd == "puente":
        getattr(puente, args.accion)()
        return
    if args.cmd == "huellas":
        sys.exit(0 if huella.compara_carpetas(pathlib.Path(args.primera).expanduser(),
                                              pathlib.Path(args.segunda).expanduser()) else 1)
    if args.cmd == "preparar":
        r = subprocess.run([str(motor.RAIZ / "scripts/reinicia-entorno.sh"), "--solo-plataforma"], cwd=motor.RAIZ)
        if r.returncode != 0:
            sys.exit(r.returncode)
        configura.main()
        return
    puente.up()
    if args.cmd == "reloj-real":
        motor.reloj_real()
        print("✓ reloj real en todos los servicios")
        return
    if args.cmd == "reloj":
        motor.reloj(datetime.date.fromisoformat(args.fecha))
        print(f"✓ hoy es {args.fecha} en los {len(motor.CON_RELOJ)} servicios")
        return
    if args.cmd == "estados":
        destino = pathlib.Path(args.dir).expanduser()
        indice = entrega.exportar_estados(destino)
        print(f"✓ {len(indice)} estados de cuenta en {destino} (índice en indice.md)")
        return
    if args.cmd in ("entregar", "veredicto"):
        evidencia = pathlib.Path(args.dir).expanduser()
        plan = escenarios.SUITES[suite_de(evidencia)]["escenarios"]
        if args.cmd == "entregar":
            entrega.entregar(evidencia, plan)
            return
        sys.exit(0 if veredicto.juzgar(evidencia, plan) else 1)

    suite = escenarios.SUITES[args.suite]
    plan = suite["escenarios"]
    if args.solo:
        claves = [c.strip() for c in args.solo.split(",")]
        plan = {k: v for k, v in plan.items() if k in claves}
    evidencia = (pathlib.Path(args.reanudar).expanduser() if args.reanudar
                 else carpeta_de_evidencia(args.evidencia, args.suite))
    desde = datetime.date.fromisoformat(args.desde) if args.desde else suite["inicio"]
    hasta = datetime.date.fromisoformat(args.hasta) if args.hasta else suite["fin"]
    semilla = args.semilla if args.semilla is not None else suite["semilla"]
    print(f"▸ Suite «{args.suite}»: {suite['titulo']} · {len(plan)} escenarios")
    ayer = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=-6))).date() - datetime.timedelta(days=1)
    if hasta != ayer:
        print(f"  aviso: la simulación termina el {hasta} y el ayer de negocio es {ayer}; la cadena nocturna real seguirá desde el día siguiente a {hasta}")
    corrida = motor.Corrida(desde, hasta, semilla, evidencia, plan)
    # The configuration the run exercised, with the evidence: rates, frequencies, cycles and interest modes.
    evidencia.mkdir(parents=True, exist_ok=True)
    (evidencia / "suite.json").write_text(json.dumps({"suite": args.suite, "titulo": suite["titulo"]}, ensure_ascii=False),
                                          encoding="utf-8")
    (evidencia / "configuracion.json").write_text(
        json.dumps(configura.productos(motor.login_admin()), ensure_ascii=False, indent=1), encoding="utf-8")
    if args.reanudar:
        corrida.reanudar()
    try:
        corrida.correr()
    finally:
        motor.reloj_real()
        print("  reloj real restablecido en todos los servicios")
    if not args.sin_entrega:
        entrega.entregar(evidencia, plan)
    # A run that finishes is not a run that passed: the verdict reads every outcome back and fails on any gap.
    sys.exit(0 if veredicto.juzgar(evidencia, plan) else 1)


if __name__ == "__main__":
    main()
