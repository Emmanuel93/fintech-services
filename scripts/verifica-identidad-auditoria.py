#!/usr/bin/env python3
"""Comprueba que la bitácora identifica plenamente a quien actúa y sobre quién.

Antes de este cambio, 1,509 de 2,615 entradas (57%) no tenían ni nombre ni correo, y de 78 actores
distintos sólo se resolvía uno: el admin de arranque. La causa no era falta de datos sino tres
defectos concretos, y este script comprueba que ninguno volvió:

  1. El canal resolvía el nombre del empleado con **el token del propio empleado** contra un
     endpoint de ADMIN. Quien no fuera administrador recibía 403 pidiendo su propio nombre. Ahora
     el canal pregunta con su credencial de servicio.
  2. El canal móvil no resolvía nada: pasaba `null` literal en el nombre y el correo, así que
     ningún cliente tenía nombre jamás.
  3. `correlation_id` salía vacío en el 100% de las entradas —se leía de la petición cuando el
     filtro lo deja en el MDC— y `domain_source` decía «backoffice» para todos los canales.

No consulta la base directamente: **navega el backoffice y la app como lo haría una persona**, y
luego lee lo que la bitácora escribió. Un script que insertara filas comprobaría que sabe insertar
filas; lo que interesa es que el camino real deje el rastro completo.

    python3 scripts/verifica-identidad-auditoria.py
    python3 scripts/verifica-identidad-auditoria.py --sin-corte-de-identity   # omite la prueba 4
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

BACKOFFICE = "http://backoffice.localhost:8090"
ADMIN = ("admin@kredius.mx", "Backoffice#2026")
# Un rol NO-ADMIN: es el caso que quedaba anónimo y la razón de ser del cambio.
NO_ADMIN = ("ejecutivo.cul@kredius.mx", "Backoffice#2026")

PSQL = ["docker", "exec", "fintech-services-postgres-1",
        "psql", "-U", "fintech", "-d", "fintech", "-t", "-A", "-F", "\t", "-c"]

fallos: list[str] = []


def sql(query: str) -> list[list[str]]:
    out = subprocess.run(PSQL + [query], capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        sys.exit(f"psql falló: {out.stderr.strip()[:200]}")
    return [l.split("\t") for l in out.stdout.strip().splitlines() if l.strip()]


def call(base, method, path, body=None, token=None, timeout=30):
    req = urllib.request.Request(
        f"{base}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def login(email, password):
    return call(BACKOFFICE, "POST", "/auth/staff/login",
                {"email": email, "password": password})["accessToken"]


def check(nombre: str, ok: bool, detalle: str = ""):
    print(f"  {'✓' if ok else '✗'} {nombre}{(' — ' + detalle) if detalle else ''}")
    if not ok:
        fallos.append(nombre)


# ── 1. Un rol no-ADMIN deja su nombre, CURP y correo ──────────────────────────

def prueba_no_admin():
    print("\n1. Un ejecutivo (no-ADMIN) navega y queda identificado")
    try:
        token = login(*NO_ADMIN)
    except urllib.error.HTTPError as e:
        check("login del ejecutivo", False, f"HTTP {e.code} — ¿corriste seed-commercial-staff.py?")
        return
    marca = f"/dashboard/summary"
    call(BACKOFFICE, "GET", marca, token=token)
    time.sleep(2)  # la escritura es fire-and-forget: no bloquea la respuesta

    filas = sql("""
        SELECT actor_name, actor_curp, actor_email, actor_roles, correlation_id, domain_source
        FROM audit.audit_entries
        WHERE category='ACCESS' AND actor_channel='BACKOFFICE'
          AND actor_roles NOT LIKE '%ADMIN%'
        ORDER BY occurred_at DESC LIMIT 1;""")
    if not filas:
        check("hay una entrada del ejecutivo", False, "ninguna entrada no-ADMIN")
        return
    nombre, curp, correo, roles, corr, source = (filas[0] + [""] * 6)[:6]
    check("nombre del ejecutivo", bool(nombre), nombre or "vacío")
    check("CURP del ejecutivo", bool(curp), curp or "vacía")
    check("correo del ejecutivo", bool(correo), correo or "vacío")
    check("correlation_id poblado", bool(corr), corr or "vacío")
    check("atribuido al backoffice", source == "channel-backoffice-service", source)


# ── 2. El sujeto sobre el que se actúa ────────────────────────────────────────

def prueba_sujeto():
    print("\n2. Abrir el expediente de un cliente registra sobre QUIÉN se actuó")
    token = login(*ADMIN)
    clientes = call(BACKOFFICE, "GET", "/clients?page=0&size=1", token=token)
    contenido = (clientes or {}).get("content") or []
    if not contenido:
        check("hay un cliente que abrir", False, "sin clientes — ¿corriste seed-portfolio.py?")
        return
    party_id = contenido[0]["partyId"]
    call(BACKOFFICE, "GET", f"/clients/{party_id}", token=token)
    time.sleep(2)

    filas = sql(f"""
        SELECT party_id, subject_name, subject_curp, subject_email, subject_phone
        FROM audit.audit_entries
        WHERE category='ACCESS' AND resource_type='clients' AND resource_id='{party_id}'
        ORDER BY occurred_at DESC LIMIT 1;""")
    if not filas:
        check("hay una entrada del expediente", False, "")
        return
    pid, nombre, curp, correo, tel = (filas[0] + [""] * 5)[:5]
    check("party_id poblado", pid == party_id, pid or "vacío")
    check("nombre del sujeto", bool(nombre), nombre or "vacío")
    check("CURP del sujeto", bool(curp), curp or "vacía")
    check("correo del sujeto", bool(correo), correo or "vacío")
    check("teléfono del sujeto", bool(tel), tel or "vacío")


# ── 3. El canal móvil, con su propio nombre ───────────────────────────────────

def prueba_movil():
    print("\n3. El canal móvil se identifica y se atribuye a sí mismo")
    filas = sql("""
        SELECT domain_source, count(*), count(actor_name), count(actor_curp),
               count(actor_phone), count(resource_id), count(correlation_id)
        FROM audit.audit_entries
        WHERE category='ACCESS' AND actor_channel='MOBILE'
        GROUP BY 1;""")
    if not filas:
        check("hay entradas móviles", False, "ninguna — ¿corrió seed-portfolio.py?")
        return
    for source, total, c_nombre, c_curp, c_tel, c_rid, c_corr in filas:
        check("atribuido al canal móvil", source == "channel-mobile-service", source)
        check("clientes con nombre", int(c_nombre) > 0, f"{c_nombre}/{total}")
        check("clientes con CURP", int(c_curp) > 0, f"{c_curp}/{total}")
        check("clientes con teléfono", int(c_tel) > 0, f"{c_tel}/{total}")
        check("correlation_id poblado", int(c_corr) > 0, f"{c_corr}/{total}")
        check("resourceId extraído de la ruta", int(c_rid) > 0, f"{c_rid}/{total}")


# ── 4. Identity caído: la navegación sigue, la entrada se escribe sin nombre ──

def prueba_identity_caido():
    print("\n4. Con identity-service caído, la navegación NO se rompe")
    token = login(*ADMIN)
    subprocess.run(["docker", "stop", "fintech-services-identity-service-1"],
                   capture_output=True, timeout=60)
    try:
        time.sleep(2)
        antes = int(sql("SELECT count(*) FROM audit.audit_entries WHERE category='ACCESS';")[0][0])
        try:
            call(BACKOFFICE, "GET", "/dashboard/summary", token=token, timeout=20)
            navega = True
        except urllib.error.HTTPError as e:
            navega = e.code < 500
        time.sleep(3)
        despues = int(sql("SELECT count(*) FROM audit.audit_entries WHERE category='ACCESS';")[0][0])
        check("la consola sigue respondiendo", navega)
        check("la entrada se escribe igual", despues > antes, f"{antes} → {despues}")
    finally:
        subprocess.run(["docker", "start", "fintech-services-identity-service-1"],
                       capture_output=True, timeout=120)
        print("     identity-service levantado de nuevo")
        for _ in range(30):
            time.sleep(3)
            try:
                login(*ADMIN)
                break
            except Exception:
                continue


# ── Resumen general ───────────────────────────────────────────────────────────

def resumen():
    print("\n── Cobertura de la bitácora ──────────────────────────────────")
    filas = sql("""
        SELECT domain_source, actor_channel, count(*),
               count(actor_name), count(actor_curp), count(correlation_id), count(party_id)
        FROM audit.audit_entries WHERE category='ACCESS'
        GROUP BY 1,2 ORDER BY 3 DESC;""")
    print(f"  {'origen':28s} {'canal':11s} {'total':>6s} {'nombre':>7s} {'curp':>6s} {'corr':>6s} {'party':>6s}")
    for source, canal, total, nom, curp, corr, party in filas:
        print(f"  {source:28s} {canal:11s} {total:>6s} {nom:>7s} {curp:>6s} {corr:>6s} {party:>6s}")

    staff = sql("SELECT count(*) FILTER (WHERE curp IS NOT NULL), count(*) FROM identity.staff_users;")
    con, tot = staff[0]
    print(f"\n  personal con CURP: {con}/{tot}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sin-corte-de-identity", action="store_true",
                    help="omite la prueba que tumba identity-service")
    args = ap.parse_args()

    print("Verificación de identidad en la bitácora de auditoría")
    print("=" * 62)
    prueba_no_admin()
    prueba_sujeto()
    prueba_movil()
    if not args.sin_corte_de_identity:
        prueba_identity_caido()
    resumen()

    print("\n" + "=" * 62)
    if fallos:
        print(f"✗ {len(fallos)} comprobación(es) fallaron:")
        for f in fallos:
            print(f"    · {f}")
        sys.exit(1)
    print("✓ todo verificado")


if __name__ == "__main__":
    main()
