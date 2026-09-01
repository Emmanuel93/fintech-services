#!/usr/bin/env python3
"""Verifica que el carril del dinero funcione de punta a punta, contra el stack corriendo.

**Por qué existe.** Las pruebas de la JVM demuestran que cada pieza hace lo suyo; ninguna demuestra
que las piezas *encajen* en un ambiente real. Y este trabajo encontró tres tablas de configuración
que nadie sembró nunca —`routing_rules`, `ordering_accounts`, `company_mappings`— precisamente
porque el carril completo jamás se ejecutó.

Esto lo ejecuta. No inserta nada: pregunta.

    python3 scripts/verifica-carril-del-dinero.py
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

AQUI = Path(__file__).resolve().parent
BACKOFFICE = "http://backoffice.localhost:8090"
BANKING = "http://localhost:8104"


def psql(sql: str) -> str:
    """Consulta directa a la base. Es verificación, no operación: no escribe nada."""
    r = subprocess.run(
        ["docker", "exec", "fintech-services-postgres-1",
         "psql", "-U", "fintech", "-d", "fintech", "-tAc", sql],
        capture_output=True, text=True, timeout=30)
    return r.stdout.strip()


def http(url, token=None, timeout=15):
    req = urllib.request.Request(
        url, headers={"Authorization": f"Bearer {token}"} if token else {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


class Verificacion:
    """Una comprobación con su nombre, lo que espera y por qué importa."""

    def __init__(self):
        self.resultados = []
        self.sin_verificar = []

    def no_aplica(self, nombre, porque):
        """
        Una comprobación que no se pudo hacer NO es una comprobación que pasó.

        <p>La del carril está acotada a las últimas 24 h y se saltaba sola cuando la siembra
        envejecía. El resumen entonces decía «16/16» —todo verde— con la única comprobación que
        de verdad importa sin correr. Un total que cuenta lo que no se miró es peor que un rojo:
        el rojo se investiga.
        """
        self.sin_verificar.append((nombre, porque))
        print(f"  ⃞ {nombre}")
        print(f"       sin verificar — {porque}")

    def comprueba(self, nombre, condicion, detalle="", porque=""):
        self.resultados.append((nombre, bool(condicion), detalle, porque))
        marca = "✅" if condicion else "❌"
        print(f"  {marca} {nombre}")
        if detalle:
            print(f"       {detalle}")
        if not condicion and porque:
            print(f"       ↳ {porque}")
        return bool(condicion)

    def resumen(self):
        fallidas = [r for r in self.resultados if not r[1]]
        total = len(self.resultados) + len(self.sin_verificar)
        print(f"\n{len(self.resultados) - len(fallidas)}/{total} comprobaciones")
        if self.sin_verificar:
            print(f"{len(self.sin_verificar)} sin verificar:")
            for nombre, porque in self.sin_verificar:
                print(f"  ⃞ {nombre} — {porque}")
        return 0 if not fallidas else 1


def main():
    v = Verificacion()

    print("\n── El carril existe: la configuración que nadie sembró nunca ──")

    ruta = psql("""SELECT r.rail||'/'||r.provider FROM banking.payout_routes r
                    JOIN banking.bank_accounts a USING (bank_account_id)
                   WHERE r.enabled AND a.status='ACTIVE' LIMIT 1;""")
    v.comprueba("tesorería sabe por dónde sale un pago", ruta, f"ruta: {ruta}",
                "banking.payout_routes vacía → toda orden muere con NO_ROUTING_RULE")

    mapeo = psql("SELECT source_system||'/'||source_key FROM disbursement.company_mappings "
                 "WHERE enabled LIMIT 1;")
    v.comprueba("el orquestador puede resolver la empresa", mapeo, f"mapeo: {mapeo}",
                "company_mappings vacía → DB-07 rechaza toda orden con UNRESOLVED_COMPANY")

    sin_catalogo = psql("SELECT to_regclass('stp.ordering_accounts') IS NULL;")
    v.comprueba("el conector YA NO tiene catálogo de cuentas propias (BK-07b)",
                sin_catalogo == "t", "",
                "mientras exista, alguien puede darle de alta una fila y creer que el dinero sale por ahí")

    print("\n── Las dos cuentas puente, y por qué son dos (BK-06) ──")

    puentes = psql("""SELECT string_agg(code||' '||type, ' · ' ORDER BY code)
                        FROM accounting.ledger_accounts WHERE code IN ('1109','2109');""")
    v.comprueba("un abono sin dueño es PASIVO y un cargo sin aclarar es ACTIVO",
                "1109 ASSET" in puentes and "2109 LIABILITY" in puentes, puentes,
                "con una sola cuenta habría que compensar activo con pasivo")

    prescripcion = psql("""SELECT count(*) FROM accounting.posting_rules
                            WHERE trigger_event IN ('BANK_SUSPENSE_WRITTEN_OFF','BANK_CHARGE_WRITTEN_OFF');""")
    v.comprueba("una partida que prescribe tiene contrapartida contable",
                prescripcion == "2", f"{prescripcion} reglas",
                "el estado WRITTEN_OFF cerraba la partida y en el mayor seguía viva para siempre")

    disposicion = psql("""SELECT debit_account||'→'||credit_account FROM accounting.posting_rules
                           WHERE trigger_event='DISPOSITION_SELF_USE';""")
    v.comprueba("toda disposición sale por BANCO, no a monedero (wallet en hold)",
                disposicion == "1201→1101", f"póliza: {disposicion}",
                "con 2101 el dinero se quedaba en la plataforma y no había contraparte conciliable")

    print("\n── La mora existe y se cobra sobre lo vencido (BK-18/19) ──")

    base = psql("""SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='charges' AND table_name='accrual_schedules'
                      AND column_name IN ('overdue_principal','oldest_due_date');""")
    v.comprueba("charges conoce el CAPITAL VENCIDO, no sólo el saldo", base == "2",
                f"{base}/2 columnas",
                "con el saldo completo, un crédito de $10 000 con $1 000 vencidos cobraba 10× de más")

    gracia = psql("""SELECT column_default FROM information_schema.columns
                      WHERE table_schema='closing' AND table_name='close_cycle_policies'
                        AND column_name='payment_due_offset_days';""")
    v.comprueba("la gracia no diverge entre servicios (BK-21)", gracia.startswith("3"),
                f"default: {gracia}",
                "el default decía 0 mientras charges y el seed decían 3")

    print("\n── La tarjeta nace revolvente y el corte le da algo que vencer ──")

    modo = psql("""SELECT count(*) FROM information_schema.columns
                    WHERE table_schema='credit_portfolio' AND table_name='dispositions'
                      AND column_name IN ('plan_mode','billed_cycle','deferred_at');""")
    v.comprueba("una compra puede nacer sin calendario (BK-24)", modo == "3",
                f"{modo}/3 columnas",
                "sin plan_mode toda compra nace parcializada al plazo por defecto del producto")

    msi = psql("""SELECT pg_get_constraintdef(oid) FROM pg_constraint
                   WHERE conname='ck_rc_nominal_rate';""")
    v.comprueba("un MSI se puede configurar (BK-25b)", ">= (0)" in msi or ">= 0" in msi, msi,
                "CHECK (nominal_rate > 0) rechazaba la tasa cero en la base de datos")

    tarjeta = psql("""SELECT capabilities->'opcionesDePago'->>'installmentPlanMode'
                        FROM credit_product.credit_product_definitions
                       WHERE product_code='CC-IND-STD-V1';""")
    v.comprueba("la tarjeta difiere DESPUÉS de la compra", tarjeta == "POST_HOC",
                f"modo: {tarjeta}",
                "sin declararlo, el producto no lo tiene: el default está todo apagado")

    print("\n── BNPL: se aplica lo que se pidió, no el tope (BK-29) ──")
    #
    # `bnplMaxDeferralDays` es un TOPE y se usaba como la cifra a aplicar, sin mirar si alguien lo
    # había pedido. Como el préstamo personal lo trae habilitado, NINGUNO empezaba a pagar cuando
    # debía: el plan entero nacía corrido el máximo, para todo el mundo. Lo que se comprueba aquí
    # es lo contrario — que sin solicitud el plan arranca en el período siguiente.

    # Acotada a lo recién activado y sin apoyo, que es la única población cuyas fechas nadie ha
    # movido a propósito. `seed-ciclo-credito` corre el reloj y los programas de apoyo desplazan
    # vencimientos: medir sobre esa cartera daría un verde que no puede ponerse rojo, y un verde
    # que no puede fallar es exactamente lo que este verificador acaba de dejar de hacer.
    # Y sólo los que NO lo pidieron: el que sí pidió 30 días debe empezar a los 61 —treinta más un
    # período— y contarlo como violación sería exigirle a BNPL que no funcione. La solicitud vive
    # en originación, que es donde se firmó.
    recientes_sin_apoyo = int(psql("""
        SELECT count(*) FROM credit_portfolio.credit_accounts a
          LEFT JOIN origination.credit_applications ca ON ca.contract_number = a.contract_number
         WHERE a.product_type = 'PERSONAL_LOAN'
           AND a.activated_at > now() - interval '24 hours'
           AND ca.bnpl_deferral_days IS NULL
           AND NOT EXISTS (SELECT 1 FROM credit_portfolio.relief_enrollments e
                            WHERE e.credit_account_id = a.credit_account_id);""") or 0)

    if recientes_sin_apoyo == 0:
        v.no_aplica("el préstamo que no pidió BNPL empieza a pagar en el período siguiente",
                    "no hay préstamos activados en las últimas 24 h sin programa de apoyo; "
                    "siembra para poder afirmarlo")
    else:
        corridos = psql("""
            SELECT count(*) FROM credit_portfolio.credit_accounts a
              LEFT JOIN origination.credit_applications ca ON ca.contract_number = a.contract_number
              JOIN LATERAL (SELECT min(due_date) AS primera
                              FROM credit_portfolio.installments i
                             WHERE i.schedule_id = a.credit_account_id) x ON TRUE
             WHERE a.product_type = 'PERSONAL_LOAN'
               AND a.activated_at > now() - interval '24 hours'
               AND ca.bnpl_deferral_days IS NULL
               AND x.primera IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM credit_portfolio.relief_enrollments e
                                WHERE e.credit_account_id = a.credit_account_id)
               AND x.primera - a.activated_at::date > 45;""")
        v.comprueba("el préstamo que no pidió BNPL empieza a pagar en el período siguiente",
                    corridos == "0",
                    f"{recientes_sin_apoyo} recientes · {corridos} con la primera cuota a más de 45 días",
                    "el tope de BNPL se está aplicando como valor: el plan nace corrido para quien "
                    "no pidió nada")

    print("\n── La cadena del dinero es consultable (BK-41/42) ──")

    traza = psql("SELECT to_regclass('banking.movement_trace') IS NOT NULL;")
    v.comprueba("existe la traza que enhebra los eslabones", traza == "t", "",
                "recorrerla exigía abrir cinco servicios; nadie lo hace en una investigación real")

    try:
        salud = http(f"{BANKING}/actuator/health")
        v.comprueba("tesorería responde", salud.get("status") == "UP", f"health: {salud}")
    except Exception as e:
        v.comprueba("tesorería responde", False, "", f"{type(e).__name__}: {e}")

    print("\n── Y lo único que de verdad importa: ¿corrió un peso? ──")
    #
    # Todo lo de arriba comprueba que el carril esté CABLEADO. Se puede tener las trece en verde
    # y no haber movido nunca un centavo — pasó: cuatro disposiciones autorizadas, cuatro muertas
    # en la DLT con UNRESOLVED_COMPANY, cero órdenes de pago, y ni una línea de log. Que las
    # órdenes fueran cero no lo miraba nadie, así que la configuración correcta se leía como un
    # carril que funciona.

    # La ventana es de un día a propósito. Un "ninguna disposición sin orden" sobre toda la
    # historia nunca podría ponerse en verde —arrastra las que se marcaron completadas con el
    # stub, cuando el dinero no salía— y una comprobación que no puede pasar deja de leerse.
    # El rezago histórico se reporta como cifra, no como fallo: es deuda con dueño, no regresión.

    recientes = int(psql("""SELECT count(*) FROM credit_portfolio.dispositions
                             WHERE created_at > now() - interval '24 hours';""") or 0)
    sin_orden = int(psql("""SELECT count(*) FROM credit_portfolio.dispositions d
                             WHERE d.created_at > now() - interval '24 hours'
                               AND NOT EXISTS (SELECT 1 FROM disbursement.disbursement_orders o
                                                WHERE o.source_event_id = d.disposition_id::text);""") or 0)

    if recientes == 0:
        v.no_aplica("las disposiciones del día llegan a ser orden de pago",
                    "no hay disposiciones de las últimas 24 h; siembra o corre el ciclo para "
                    "afirmar algo del carril")
    else:
        v.comprueba("las disposiciones del día llegan a ser orden de pago", sin_orden == 0,
                    f"{recientes} disposiciones · {recientes - sin_orden} con orden · {sin_orden} sin ella",
                    "cero órdenes con disposiciones vivas = el carril está cableado y no corre; "
                    "mira la DLT de credit-portfolio.disposition-authorized")

    rezago = psql("""SELECT count(*) FROM credit_portfolio.dispositions d
                      WHERE d.status = 'PROCESSING'
                        AND d.created_at <= now() - interval '24 hours'
                        AND NOT EXISTS (SELECT 1 FROM disbursement.disbursement_orders o
                                         WHERE o.source_event_id = d.disposition_id::text);""")
    if rezago != "0":
        print(f"       ⚠ {rezago} disposiciones anteriores siguen sin orden — dinero que nadie mandó")

    sin_empresa = psql("""SELECT count(*) FROM disbursement.company_mappings
                           WHERE source_key = '*' AND enabled AND source_system = 'credit-portfolio';""")
    v.comprueba("cartera tiene empresa por omisión", sin_empresa != "0", "",
                "sin companyId, sin clave y sin comodín, la orden no se crea y muere en la DLT")

    # La misma empresa tiene que existir en los dos lados. Sembrar sólo uno deja el carril
    # cortado en el último eslabón: la orden sale DISPATCHED, el conector no puede firmarla, y
    # el síntoma —una orden sin clave de rastreo— no se parece en nada a "falta una fila".
    huerfanas_en_stp = psql("""SELECT count(*) FROM disbursement.company_mappings m
                                WHERE m.enabled
                                  AND NOT EXISTS (SELECT 1 FROM stp.companies c
                                                   WHERE c.company_id = m.company_id
                                                     AND c.status = 'ACTIVE');""")
    v.comprueba("cada empresa del orquestador existe en el conector", huerfanas_en_stp == "0",
                f"sin contraparte en stp.companies: {huerfanas_en_stp}",
                "el pago llega hasta STP y no hay identidad con la cual firmarlo ante Banxico")

    # Ya mordió dos veces. Una configuración cambiada fuera de la API no emite `product-activated`,
    # así que cartera conserva la copia con la que el producto se activó y **se comporta según
    # ella**. El síntoma aparece lejos y sin relación aparente: el catálogo dice que el producto
    # admite saltar pagos y cartera responde que no, con el código de las dos partes correcto.
    # La comparación es SEMÁNTICA: se quitan las claves en nulo de los dos lados antes de comparar.
    # Cartera guarda la configuración como objeto tipado y al reserializarla escribe los campos que
    # no aplican como `null`; el catálogo simplemente no trae esas claves. Un nulo declarado y una
    # clave ausente se comportan igual, y marcar eso como desincronización convertiría el aviso en
    # ruido permanente — que es la forma más segura de que nadie lo mire el día que sea de verdad.
    desincronizados = psql("""
        WITH normalizadas AS (
          SELECT c.product_code,
                 -- Un objeto vacío, un JSON `null` y una clave ausente significan lo mismo: sin
                 -- opciones declaradas. Y una clave con valor nulo significa lo mismo que no
                 -- traerla. Cartera guarda la configuración como objeto tipado y al reserializarla
                 -- escribe ambas formas; el catálogo simplemente no las trae. Comparar en crudo
                 -- marcaría nueve productos idénticos como desincronizados, y un aviso que siempre
                 -- está encendido es la forma más segura de que nadie lo mire el día que sea real.
                 CASE WHEN jsonb_typeof(c.capabilities->'opcionesDePago') = 'object'
                      THEN jsonb_strip_nulls(c.capabilities->'opcionesDePago')
                      ELSE '{}'::jsonb END AS catalogo,
                 CASE WHEN jsonb_typeof(p.capabilities->'opcionesDePago') = 'object'
                      THEN jsonb_strip_nulls(p.capabilities->'opcionesDePago')
                      ELSE '{}'::jsonb END AS cartera
            FROM credit_product.credit_product_definitions c
            LEFT JOIN credit_portfolio.product_config_versions p
                   ON p.product_code = c.product_code
                  AND p.product_version = c.product_version
           WHERE c.status = 'ACTIVE')
        SELECT count(*) FROM normalizadas WHERE catalogo IS DISTINCT FROM cartera;""")
    v.comprueba("cartera tiene la configuración vigente de cada producto", desincronizados == "0",
                f"desincronizados: {desincronizados}",
                "cartera se comporta según su copia; republica con "
                "POST /api/v1/credit-products/{code}/republish")

    return v.resumen()


if __name__ == "__main__":
    sys.exit(main())
