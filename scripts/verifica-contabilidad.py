#!/usr/bin/env python3
"""Comprueba que la contabilidad diga lo mismo que la fuente de cada hecho.

`verifica-cuadre.py` valida lo que la consola enseña: que el tablero, el árbol y la cartera coincidan,
y que la balanza cuadre. Nada de eso detecta el error más caro de este módulo, porque **la balanza
cuadra igual cuando el importe está mal**: cada asiento es un par que cuadra consigo mismo, así que
multiplicar un devengo por quince deja la balanza tan cuadrada como estaba.

Lo que sí lo detecta es salirse del mayor y preguntarle al servicio que originó el hecho. Un devengo
de interés nace en `charges` con su base y su tasa; si contabilidad asentó otra cifra, no hay
interpretación posible: uno de los dos miente y la fuente es `charges`.

Esto pasó de verdad. Contabilidad deducía el importe restando saldos en vez de recibirlo, y con dos
eventos del mismo crédito procesados fuera de orden la magnitud de un pago de $2.6 M acabó asentada
como el devengo de un día: la cuenta 4101 salió en $2,233,339 contra $142,618 reales, quince veces,
sin que ninguna pantalla lo delatara.

Va por SQL y no por el BFF a propósito: se está comparando lo que dos servicios **guardaron**, no lo
que una pantalla presenta. Sale con código 1 si algo no cuadra.

    python3 scripts/verifica-contabilidad.py
"""
from __future__ import annotations

import subprocess
import sys

PG = ["docker", "exec", "fintech-services-postgres-1",
      "psql", "-U", "fintech", "-d", "fintech", "-t", "-A", "-F", "|", "-c"]

# Los servicios redondean a dos decimales en puntos distintos del camino; exigir igualdad exacta
# produce falsos negativos que enseñan a ignorar el aviso.
TOLERANCIA = 1.0

# ── Créditos incorporados a media vida ───────────────────────────────────────
#
# Cuando contabilidad conoce una cuenta *después* de que ya se le cobró algo, el primer delta que ve
# no es el importe de ese hecho: es el saldo acumulado. `PostingService` lo detecta y asienta una
# **incorporación** (`trigger_event = 'OPENING_BALANCE'`) contra capital, porque incorporar un saldo
# preexistente no es ganarlo. Es correcto y es deliberado: sin eso, un préstamo de $20,000 con 1% de
# comisión producía una póliza de «comisión de apertura» de $20,200 e inflaba el ingreso cien veces.
#
# La consecuencia para estas comprobaciones es que los cargos anteriores a la incorporación **no
# aparecen** en su cuenta de resultados, y no deben aparecer. Compararlos contra `charges` sin
# excluirlos produce un falso negativo que además es intermitente —depende de una carrera entre el
# evento de activación y el del cargo—, y que en la última corrida hizo salir con código 1 a una
# siembra que estaba bien, abortando el respaldo del final.
#
# Se excluyen del lado del **origen**, que es donde sobran.

fallos: list[str] = []


def incorporados() -> list[tuple[str, str]]:
    """Los créditos que contabilidad tomó a media vida, con el instante de su asiento de apertura."""
    filas = q("select credit_account_id::text, min(posting_date)::text "
              "  from accounting.journal_entries "
              " where trigger_event = 'OPENING_BALANCE' and credit_account_id is not null "
              " group by 1")
    return [(f[0], f[1]) for f in filas if f[0]]


def salvo_incorporados(ids: list[tuple[str, str]]) -> str:
    """Predicado SQL para el lado del origen. Vacío si no hubo ninguna incorporación.

    Acota **por crédito y por fecha**, no por crédito a secas. Lo que la incorporación se tragó son
    los cargos *anteriores* a ella; a partir de ahí la cuenta asienta como cualquier otra y sus
    devengos sí llegan a 4101 y a 2110. Excluir el crédito entero descuadraba el interés y el IVA en
    sentido contrario — el error opuesto y del mismo tamaño.
    """
    if not ids:
        return ""
    return "".join(
        f" and not (credit_account_id = '{cid}' and created_at < timestamptz '{t}')"
        for cid, t in ids)


def q(sql):
    r = subprocess.run(PG + [sql], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip()[:200])
    return [l.split("|") for l in r.stdout.strip().splitlines() if l.strip()]


def dinero(x):
    return f"${float(x or 0):,.2f}"


def revisa(ok, mensaje):
    print(f"    {'✓' if ok else '✗'} {mensaje}")
    if not ok:
        fallos.append(mensaje)


def compara(titulo, sql_origen, sql_contable, cuenta):
    """Un concepto contra su fuente. El detalle por crédito sólo se pide si el total no cuadra."""
    origen = float(q(sql_origen)[0][0] or 0)
    contable = float(q(sql_contable)[0][0] or 0)
    print(f"\n  {titulo}")
    print(f"        origen {dinero(origen)} · contabilidad ({cuenta}) {dinero(contable)}")
    ok = abs(origen - contable) <= TOLERANCIA
    revisa(ok, f"{titulo}: contabilidad coincide con su fuente")
    if not ok:
        print(f"        diferencia: {dinero(contable - origen)} "
              f"({contable / origen:.1f}x)" if origen else "")
    return ok


def main():
    print("\n  Contabilidad contra la fuente de cada hecho")

    # Los créditos que contabilidad incorporó con saldo previo no asientan como resultado los
    # cargos anteriores a esa incorporación — por diseño. Se sacan del lado del origen para no
    # comparar contra algo que nunca debió estar ahí.
    tomados = incorporados()
    salvo = salvo_incorporados(tomados)
    if tomados:
        print(f"\n  ({len(tomados)} crédito(s) incorporados con saldo previo: sus cargos previos "
              f"entraron como asiento de apertura, no como resultado — se excluyen del origen)")

    # ── Interés ordinario: charges es quien lo calcula ───────────────────────
    compara(
        "1 · Interés ordinario devengado",
        "select coalesce(sum(amount),0) from charges.charge_records "
        f"where charge_type='ORDINARY_INTEREST' and status<>'REVERSED'{salvo}",
        "select coalesce(sum(amount),0) from accounting.journal_entries where credit_account='4101'",
        "4101")

    # ── Comisiones ───────────────────────────────────────────────────────────
    compara(
        "2 · Comisiones cobradas al acreditado",
        "select coalesce(sum(amount),0) from charges.charge_records "
        "where charge_type in ('OPENING_FEE','ADMIN_FEE','PREPAYMENT_FEE','INSURANCE_PREMIUM') "
        f"and status<>'REVERSED'{salvo}",
        "select coalesce(sum(amount),0) from accounting.journal_entries where credit_account='4103'",
        "4103")

    # ── IVA ──────────────────────────────────────────────────────────────────
    compara(
        "3 · IVA trasladado",
        # **Sin** la exclusión de los incorporados, y no por descuido. La incorporación absorbe
        # *saldo* —capital, interés devengado, moratorios—, que es lo que compone el delta contra
        # cero. El IVA no entra en ese delta: es un pasivo que se asienta desde su propio evento
        # `CHARGE_IVA`, y sigue asentándose con normalidad aunque el cargo que lo originó se haya
        # incorporado. Comprobado en la corrida de esta siembra: de los dos créditos incorporados,
        # sus $1,225 de comisión no llegaron a 4103 y sus $196 de IVA sí llegaron a 2110.
        "select coalesce(sum(total_amount),0) from charges.charge_records "
        "where charge_type='IVA' and status<>'REVERSED'",
        "select coalesce(sum(amount),0) from accounting.journal_entries where credit_account='2110'",
        "2110")

    # ── Capital colocado: lo sabe cartera ────────────────────────────────────
    compara(
        "4 · Capital colocado",
        "select coalesce(sum(principal_balance),0) + "
        "       coalesce((select sum(amount) from accounting.journal_entries "
        "                  where credit_account='1201'),0) "
        "  from credit_portfolio.credit_accounts",
        "select coalesce(sum(amount),0) from accounting.journal_entries where debit_account='1201'",
        "1201")

    # ── El auxiliar de intereses tiene que poder bajar ───────────────────────
    #
    # No es una comparación contra otra fuente: es una propiedad que 1203 debe cumplir. Si sólo
    # recibe cargos, ningún pago se está aplicando a interés devengado y ningún quebranto lo está
    # dando de baja; el auxiliar crece para siempre y la balanza deja de ser presentable.
    print("\n  5 · El auxiliar de intereses por cobrar se mueve en los dos sentidos")
    filas = q("select coalesce(sum(case when debit_account='1203' then amount else 0 end),0), "
              "       coalesce(sum(case when credit_account='1203' then amount else 0 end),0) "
              "  from accounting.journal_entries")
    cargos, abonos = float(filas[0][0]), float(filas[0][1])
    print(f"        1203: cargos {dinero(cargos)} · abonos {dinero(abonos)}")
    revisa(abonos > 0, "el auxiliar de intereses por cobrar (1203) se abona alguna vez")

    # ── Ninguna póliza descuadrada ───────────────────────────────────────────
    print("\n  6 · Ninguna póliza descuadrada")
    filas = q("select count(*) from accounting.vouchers where total_debit <> total_credit")
    revisa(int(filas[0][0]) == 0, f"todas las pólizas cuadran ({filas[0][0]} descuadradas)")

    print()
    if fallos:
        print(f"  ✗ {len(fallos)} comprobaciones fallaron:")
        for f in fallos:
            print(f"      · {f}")
        sys.exit(1)
    print("  ✓ la contabilidad coincide con la fuente de cada hecho\n")


if __name__ == "__main__":
    main()
