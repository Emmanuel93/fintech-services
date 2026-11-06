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

# ── Ninguna cuenta se incorpora ──────────────────────────────────────────────
#
# Toda cuenta nace en la plataforma: contabilidad presencia su activación y desde ahí asienta cada hecho.
# Antes, si un cargo se procesaba antes que la activación (viajan por dos tópicos, en paralelo), contabilidad
# lo tomaba por un crédito «incorporado a media vida» y lo asentaba contra capital (3901): la comisión de
# apertura acababa como «moratorios incorporados» y el ingreso por comisiones quedaba corto. Este script
# excluía esos créditos del origen para que la comparación pasara, y con eso escondía el defecto (E2E 3-nov:
# $245 en dos cuentas). Ahora el cargo espera a la activación, y aquí una incorporación es una falla.

fallos: list[str] = []


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

    # ── Ninguna incorporación: cada cuenta nació aquí ────────────────────────
    print("\n  0 · Ninguna cuenta incorporada con saldo previo (3901)")
    filas = q("select count(*), coalesce(sum(amount),0) from accounting.journal_entries "
              "where trigger_event = 'OPENING_BALANCE' or credit_account = '3901' or debit_account = '3901'")
    n, importe = (filas[0] if filas else ("0", "0"))
    if int(n) == 0:
        print("    ✓ ninguna: toda cuenta asentó desde su activación")
    else:
        msg = f"0 · {n} asiento(s) de incorporación por ${float(importe):,.2f}: hechos asentados contra capital en vez de resultados"
        print(f"    ✗ {msg}")
        fallos.append(msg)

    # ── Interés ordinario: charges es quien lo calcula ───────────────────────
    compara(
        "1 · Interés ordinario devengado",
        "select coalesce(sum(amount),0) from charges.charge_records "
        "where charge_type='ORDINARY_INTEREST' and status<>'REVERSED'",
        # Stage 3 interest is revenue only on the amortised cost (IFRS 9 net basis): the part not recognized went to
        # the loss allowance (1290) with the same trigger. Revenue plus that part is what charges accrued.
        # A reversed charge is out of the source, and the ledger undoes it with its own entry, which debits the
        # account the charge credited (D63: a loan cancelled because its money never left gives back what it was
        # charged). Only the gross credits were summed before, so the first reversal looked like extra revenue.
        "select coalesce(sum(amount) filter (where credit_account='4101' "
        "                                       or (credit_account='1290' and trigger_event='CHARGE_ORDINARY_INTEREST')),0) "
        "     - coalesce(sum(amount) filter (where debit_account='4101' and trigger_event='CHARGE_REVERSED'),0) "
        "  from accounting.journal_entries",
        "4101 + no reconocido de etapa 3 − reversas")

    # ── Comisiones ───────────────────────────────────────────────────────────
    compara(
        "2 · Comisiones cobradas al acreditado",
        "select coalesce(sum(amount),0) from charges.charge_records "
        "where charge_type in ('OPENING_FEE','ADMIN_FEE','PREPAYMENT_FEE','INSURANCE_PREMIUM') "
        "and status<>'REVERSED'",
        # The opening fee is deferred over the loan's life (IFRS 9): what is not yet revenue is still in 2150, and
        # what was recognized is effective-rate interest revenue (4106, from 2150), not fee income.
        "select coalesce(sum(amount) filter (where credit_account in ('4103','2150') "
        "                                       or (credit_account='4106' and debit_account='2150')),0) "
        "     - coalesce(sum(amount) filter (where debit_account='2150'),0) from accounting.journal_entries",
        "4103 + 4106 desde 2150 + 2150 por devengar")

    # ── IVA ──────────────────────────────────────────────────────────────────
    compara(
        "3 · IVA trasladado",
        "select coalesce(sum(total_amount),0) from charges.charge_records "
        "where charge_type='IVA' and status<>'REVERSED'",
        # Same as the interest: a reversed IVA leaves 2110 by its own debit. A waived one stays in the source and in
        # the gross credits, so its debit is not subtracted.
        "select coalesce(sum(amount) filter (where credit_account='2110'),0) "
        "     - coalesce(sum(amount) filter (where debit_account='2110' and trigger_event='CHARGE_REVERSED'),0) "
        "  from accounting.journal_entries",
        "2110 − reversas")

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

    # ── El dinero se mueve en centavos ───────────────────────────────────────
    # Un asiento con fracción de centavo no lo liquida ningún pago: la comisión del distribuidor se
    # devengaba como base × tasa sin redondear (126.924) y 2120/5104 cargaban milésimas (E2E 3-nov).
    print("\n  7 · Todo importe asentado está en centavos")
    filas = q("select count(*), coalesce(string_agg(distinct trigger_event, ', '), '') from accounting.journal_entries "
              "where amount <> round(amount, 2)")
    revisa(int(filas[0][0]) == 0,
           f"todos los asientos en centavos ({filas[0][0]} con fracción de centavo{': ' + filas[0][1] if filas[0][1] else ''})")

    # ── Cada mes terminado se cerró conciliado ───────────────────────────────
    # El cierre de mes concilia antes de publicar: cartera completa al último día, reserva medida a esa fecha y el
    # mayor igual a cartera, reservas y calendarios de comisiones, crédito por crédito. Un mes anterior al último que
    # siga abierto, o cerrado sin cierre conciliado, es un mes cuyos números nadie comprobó.
    print("\n  8 · Cada mes terminado se cerró conciliado")
    filas = q("select count(*), coalesce(string_agg(p.period || ' ' || case when p.status = 'OPEN' then 'abierto' "
              "else coalesce(c.outcome, 'sin conciliación') end, ', ' order by p.period), '') "
              "  from accounting.accounting_periods p left join accounting.period_closes c on c.period = p.period "
              " where p.period < (select max(period) from accounting.accounting_periods) "
              "   and (p.status = 'OPEN' or c.outcome is distinct from 'RECONCILED')")
    revisa(int(filas[0][0]) == 0,
           f"todos los meses terminados cerrados y conciliados{' (fuera: ' + filas[0][1] + ')' if filas[0][1] else ''}")

    # ── De dónde viene el dinero que se presta ───────────────────────────────
    # El fondeo (capital aportado y líneas dispuestas) lo registra tesorería y contabilidad lo asienta: los saldos del
    # mayor son los de tesorería, y Bancos nunca queda en negativo al cerrar un mes.
    print("\n  9 · El fondeo del mayor es el de tesorería y Bancos nunca cierra un mes en negativo")
    compara("9a · Capital social aportado",
            "select coalesce(sum(contributed),0) from banking.funding_sources",
            "select coalesce(sum(amount) filter (where credit_account='3101'),0) "
            "     - coalesce(sum(amount) filter (where debit_account='3101'),0) from accounting.journal_entries", "3101")
    compara("9b · Líneas de fondeo dispuestas",
            "select coalesce(sum(principal),0) from banking.funding_sources",
            "select coalesce(sum(amount) filter (where credit_account='2201'),0) "
            "     - coalesce(sum(amount) filter (where debit_account='2201'),0) from accounting.journal_entries", "2201")
    filas = q("with m as (select period, sum(case when debit_account='1101' then amount else 0 end) "
              "                - sum(case when credit_account='1101' then amount else 0 end) neto "
              "             from accounting.journal_entries where '1101' in (debit_account, credit_account) group by period) "
              "select count(*) filter (where saldo < -0.005), coalesce(string_agg(period, ', ') filter (where saldo < -0.005), '') "
              "  from (select period, sum(neto) over (order by period) saldo from m) s")
    revisa(int(filas[0][0]) == 0,
           f"Bancos (1101) no cierra ningún mes en negativo{' (en negativo: ' + filas[0][1] + ')' if filas[0][1] else ''}")

    print()
    if fallos:
        print(f"  ✗ {len(fallos)} comprobaciones fallaron:")
        for f in fallos:
            print(f"      · {f}")
        sys.exit(1)
    print("  ✓ la contabilidad coincide con la fuente de cada hecho\n")


if __name__ == "__main__":
    main()
