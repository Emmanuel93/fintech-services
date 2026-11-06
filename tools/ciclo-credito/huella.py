"""
The run's fingerprint: what the platform holds at the end of a run, domain by domain, by business keys and amounts
(scenario, period, account, date; never ids nor timestamps), with a SHA-256 of each domain.

Two identifiers are not business keys and stay out. A contract number is random in each run: the contract is named by
the scenario that opened it (its evidence folder holds the number). A tracking key's sequence within a day follows the
order in which the day's orders reached STP: an STP order is read by its Banxico date, amount and outcome.

Two runs from a virgin platform, with the same scenarios and seed, must leave the same fingerprint. When they do not,
the rows that differ say where the run depended on timing: a payment valued on the next day, an order on another
Banxico date, a statement issued after the clock moved. This is the proof of determinism of F6; it does not verify the
business (the services' self-checks and the chain's conciliation do), it compares two runs.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import re
import subprocess

PG = ["docker", "exec", "-i", "fintech-services-postgres-1", "psql", "-U", "fintech", "-d", "fintech", "-At", "-F", "\x1f"]

# Each domain, read by business keys only. Ordered, so the same holdings read the same text.
DOMINIOS = {
    "contabilidad": """
        SELECT period, debit_account, credit_account, trigger_event, coalesce(product_type, '-'), sum(amount), count(*)
          FROM accounting.journal_entries GROUP BY 1, 2, 3, 4, 5 ORDER BY 1, 2, 3, 4, 5""",
    "cierres": """
        SELECT period, status FROM accounting.accounting_periods ORDER BY 1""",
    "cartera": """
        SELECT contract_number, product_type, status, principal_balance, accrued_interest_balance, iva_balance,
               penalty_balance, days_delinquent
          FROM credit_portfolio.credit_accounts ORDER BY 1""",
    "movimientos": """
        SELECT a.contract_number, e.event_type, e.value_date, sum(e.delta_amount), count(*)
          FROM credit_portfolio.balance_events e JOIN credit_portfolio.credit_accounts a USING (credit_account_id)
         GROUP BY 1, 2, 3 ORDER BY 1, 2, 3""",
    "cuotas": """
        SELECT a.contract_number, i.installment_number, i.due_date, i.principal_amount, i.interest_amount, i.status,
               coalesce(i.paid_on::text, '-')
          FROM credit_portfolio.installments i JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = i.schedule_id
         ORDER BY 1, 2, 3""",
    "estados": """
        SELECT contract_number, cutoff_date, cycle_number, balance_at_cutoff, amount_due, coalesce(minimum_payment, 0),
               payment_due_date, status
          FROM statement.statements ORDER BY 1, 2, 3""",
    "comisiones": """
        SELECT coalesce(p.rfc, '-'), coalesce(b.product_type, '-'), b.period, b.status, b.subtotal, b.iva_amount,
               b.isr_withheld, b.iva_withheld, b.net_amount, b.records_count, b.payout_attempts
          FROM commission.liquidation_batches b
          LEFT JOIN commission.beneficiary_profiles p ON p.party_id = b.beneficiary_party_id
         ORDER BY 1, 2, 3""",
    "tesoreria": """
        SELECT business_date, movement_type, direction, sum(amount), count(*)
          FROM banking.internal_movements GROUP BY 1, 2, 3 ORDER BY 1, 2, 3""",
    "fondeo": """
        SELECT kind, contributed, principal, interest_payable, accrued_through FROM banking.funding_sources ORDER BY 1""",
    "stp": """
        SELECT business_date, amount, status, count(*) FROM stp.payment_orders GROUP BY 1, 2, 3 ORDER BY 1, 2, 3""",
}

CONTRATO = re.compile(r"CTR-\d{6}-[0-9A-F]{8}")


def _filas(sql: str) -> list[str]:
    r = subprocess.run(PG + ["-c", sql], capture_output=True, text=True, check=True)
    return [linea.replace("\x1f", " | ") for linea in r.stdout.splitlines() if linea]


def escenarios(carpeta: pathlib.Path) -> dict:
    """Contract number → the scenario that opened it (E01, E02…), from the run's evidence."""
    etiquetas = {}
    for f in sorted((carpeta / "escenarios").glob("*/cuenta.json")):
        try:
            numero = json.loads(f.read_text(encoding="utf-8")).get("contractNumber")
        except (OSError, ValueError):
            continue
        if numero:
            etiquetas[numero] = f.parent.name.split("-")[0]
    return etiquetas


def toma(etiquetas: dict | None = None) -> dict:
    """
    The fingerprint of what the platform holds now: per domain, its rows and their SHA-256. Each contract is named by
    its scenario, and the rows sorted again after naming, so the same holdings read the same text. A contract no
    scenario opened keeps its number: it shows as a difference, which it is.
    """
    etiquetas = etiquetas or {}
    huella = {}
    for dominio, sql in DOMINIOS.items():
        filas = sorted(CONTRATO.sub(lambda m: etiquetas.get(m.group(0), m.group(0)), f) for f in _filas(sql))
        huella[dominio] = {"sha256": hashlib.sha256("\n".join(filas).encode()).hexdigest(), "filas": len(filas),
                           "contenido": filas}
    huella["_todo"] = {"sha256": hashlib.sha256("".join(huella[d]["sha256"] for d in DOMINIOS).encode()).hexdigest()}
    return huella


def guarda(carpeta: pathlib.Path) -> dict:
    h = toma(escenarios(carpeta))
    (carpeta / "huella.json").write_text(json.dumps(h, ensure_ascii=False, indent=1), encoding="utf-8")
    return h


def compara(a: dict, b: dict, muestra: int = 6) -> list[str]:
    """What differs between two fingerprints, domain by domain: the rows only one of them holds."""
    diferencias = []
    for dominio in DOMINIOS:
        x, y = a.get(dominio, {}), b.get(dominio, {})
        if x.get("sha256") == y.get("sha256"):
            continue
        solo_a = sorted(set(x.get("contenido", [])) - set(y.get("contenido", [])))
        solo_b = sorted(set(y.get("contenido", [])) - set(x.get("contenido", [])))
        diferencias.append(f"{dominio}: {x.get('filas')} filas / {y.get('filas')} filas")
        diferencias += [f"    sólo en la primera: {f}" for f in solo_a[:muestra]]
        diferencias += [f"    sólo en la segunda: {f}" for f in solo_b[:muestra]]
    return diferencias


def compara_carpetas(primera: pathlib.Path, segunda: pathlib.Path) -> bool:
    a = json.loads((primera / "huella.json").read_text(encoding="utf-8"))
    b = json.loads((segunda / "huella.json").read_text(encoding="utf-8"))
    diferencias = compara(a, b)
    if not diferencias:
        print(f"✓ las dos corridas dejaron la misma huella ({a['_todo']['sha256'][:16]}): "
              + ", ".join(f"{d} {a[d]['filas']}" for d in DOMINIOS))
        return True
    print(f"✗ las corridas difieren en {sum(1 for d in diferencias if not d.startswith(' '))} dominios:")
    print("\n".join(diferencias))
    return False
