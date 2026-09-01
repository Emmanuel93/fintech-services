# D15 — Banking [Supporting]

> **Tesorería.** Nuestras cuentas en instituciones financieras, **de cuál sale cada pago**, por qué rail y con qué proveedor, y qué dice el banco que pasó. No conoce el dominio de crédito: recibe una petición de pago con su referencia opaca y responde por dónde sale.

**Servicio:** `banking-service` · **Schema:** `banking` · **Puerto:** 8104 (bootRun) · 8080 (Docker)

> Escrito contra el código (2026-08). Fases 1 y 2 implementadas: BK-03 … BK-10.

## 1. El hueco que cierra

La CLABE de la que salía el dinero vivía **dentro del conector de STP** (`stp.ordering_accounts`) y se elegía con un `is_default` por empresa: ciego al saldo, al costo y al horario. Eso convierte una decisión de tesorería en un detalle de cómo se firma una cadena original, y obliga a que cada proveedor nuevo traiga su propia copia del catálogo de cuentas propias. En paralelo, `disbursement.routing_rules` decidía el **proveedor** por (empresa, rail, monto) y **nunca la cuenta**.

El dueño de «por dónde sale» y el dueño de «qué reporta el banco» son el mismo, y no era ninguno de los dos servicios que lo tenían.

## 2. La frontera, en una línea cada una

| | |
|---|---|
| **banking** | De qué cuenta nuestra sale, por dónde, y qué reporta el banco. |
| **disbursement** (D11) | Qué hay que pagar, a quién, y que no se pague dos veces. |
| **stp** (D12) | Cómo se le habla a STP. |
| **closing** (D14) | Dispara la fase de conciliación y consume el sello como cifra de control. **No es su dueño.** |
| **accounting** (T4) | Las pólizas. Banking dice qué cuentas usar; no postea. |

## 3. Agregados

| Agregado / VO | Rol |
|---|---|
| `BankAccount` [root] | Una cuenta **nuestra**: institución, CLABE, titular, y **tres** cuentas del mayor. |
| `Clabe` [VO] | CLABE con dígito verificador comprobado; se expone **enmascarada**. |
| `PayoutRoute` | (empresa, rail, monto) → **cuenta + rail + proveedor**. Cambiar de ruta es un `INSERT`. |
| `BankStatementLine` | Un movimiento tal como lo reporta el banco. Dato **externo**: se guarda crudo y no se corrige. |
| `BankMatch` | El cruce, con **método y confianza**. |
| `SuspenseEntry` | Lo no identificado, como **partida en conciliación**. |
| `BankCloseSeal` | El sello del día/mes por cuenta. |

**Invariantes:**
**BK-01** una cuenta propia no se da de alta sin dígito verificador válido — el error de captura se atrapa en el único momento en que corregirlo es gratis ·
**BK-02** la CLABE **nunca** sale completa: ni en respuesta, ni en bitácora, ni en mensaje de error ·
**BK-03** una cuenta `SUSPENDED` deja de rutear pero **conserva su historia**: lo que ya salió por ella se concilia durante meses ·
**BK-04** una cuenta `CLOSED` no vuelve — reabrirla sería otra cuenta ·
**BK-05** cada cuenta declara sus **tres** cuentas del mayor: la que se concilia y las **dos** puentes ·
**BK-06** un movimiento del banco se reingiere sin duplicarse — `UNIQUE (bank_account_id, external_id)` ·
**BK-07** un movimiento se cruza **una sola vez** — `UNIQUE (line_id)` en `bank_matches` ·
**BK-08** todo cruce guarda **cómo** se cruzó (`DETERMINISTIC` · `HEURISTIC` · `MANUAL`) — una conciliación que no explica por qué cuadró no es auditable ·
**BK-09** si ninguna ruta aplica, el pago **no sale**: una orden detenida y visible antes que un pago por una cuenta que nadie eligió.

## 4. Las dos cuentas puente, y por qué son dos

El plan pedía una: «1109 Depósitos por identificar». Una sola obliga a que los cargos no aclarados vivan en una cuenta de naturaleza deudora llamada «depósitos», con saldo del signo contrario al de su nombre. Las dos direcciones ocurren y tienen naturaleza **opuesta**:

| Dirección | Qué es | Cuenta |
|---|---|---|
| Llegó dinero y no sabemos de quién | Lo **debemos** hasta demostrar lo contrario → **pasivo** | `2109 Depósitos por identificar` |
| El banco nos cargó y no sabemos por qué | Un derecho por aclarar → **activo** | `1109 Cargos bancarios por aclarar` |

Presentarlas juntas obligaría a compensar activo con pasivo, que es justo lo que un catálogo existe para impedir.

**La reclasificación no registra el hecho de negocio.** Cuando un depósito se identifica como el pago de una cuenta, el flujo de pagos postea su `PAYMENT_APPLIED` (`1101 → 1201`) por su cuenta. Si el asiento de identificación cargara además contra `1201`, el banco subiría **dos veces** por el mismo depósito. Por eso `BANK_DEPOSIT_IDENTIFIED` sale contra `1101`: deshace la pata bancaria de la puente y deja que el flujo real ponga la suya.

`suspense_entries.status` admitía `WRITTEN_OFF` sin contrapartida contable — la partida se cerraba en banking y en el mayor seguía viva para siempre. `4105` y `5105` la cierran.

## 5. La ecuación del sello

```
saldo de la cuenta contable  +  partidas en conciliación  ==  saldo del estado de cuenta
```

Si no cuadra, **no se sella** y se levanta un hallazgo `LEDGER_VS_BANK` en `closing`.

## 6. Entradas / Salidas

**API:** `POST /api/v1/bank-accounts` (ADMIN) · `GET /api/v1/bank-accounts[/{id}]` (ADMIN · OPS_SUPERVISOR · AUDITOR) · `POST /{id}/suspend` · `POST /{id}/reactivate` · **`POST /api/v1/payouts/route`** (SERVICE) · `POST`/`GET`/`DELETE` `/api/v1/payout-routes` (ADMIN).

**Contrato con `disbursement`:** el orquestador pregunta por HTTP y recibe la decisión completa; la cuenta viaja luego en `disbursement.stp-requested` hasta el conector. Ni banking importa clases de disbursement ni al revés — los dos `ArchUnit` lo imponen. **Tesorería caída no es «no hay ruta»**: el orquestador distingue los dos casos y en el primero la orden espera sin gastar intento.

**Autenticación:** header-trust, como el resto del monorepo. El alta es **ADMIN y nada menos**: quien puede dar de alta una CLABE ordenante puede, en el siguiente paso, apuntarle una ruta y hacer que el dinero salga por ella.

## 7. Deuda declarada

| # | Deuda |
|---|---|
| 1 | **El dígito verificador de la CLABE está escrito tres veces**: `stp.ClabeValidator`, `disbursement.ClabeValidator` y `banking.Clabe`. Mismo algoritmo de Banxico, tres copias. Consolidar en `shared` toca el camino caliente del dinero en dos servicios que la Fase 3 va a reescribir — se hace **después**, no antes (BK-49). |
| 2 | **`stp.ordering_accounts` sigue viva** como caída de la migración: si una orden llega sin cuenta ordenante, el conector cae al catálogo local. La segunda mitad (BK-07b) agota esas órdenes, retira la caída y tira la tabla. |
| 3 | Las tablas de conciliación existen **sin código**: la ingesta y el matching son BK-37 … BK-40. |

## 8. Decisiones de diseño

| # | Decisión | Por qué |
|---|---|---|
| 1 | Banking absorbe cuenta ordenante **y** ruteo de proveedor | El dueño de «por dónde sale» es uno solo. Dividirlo entre el conector y el orquestador es lo que produjo el `is_default` ciego. |
| 2 | Las 5 tablas `bank_*` se mudan de `closing` | Quien dispara la conciliación no es su dueño. El esquema no tenía **ni una clase Java** que lo tocara: mover costó cero migración (AN-12). |
| 3 | El sello bancario lo emite banking; closing lo **consume** | El cierre pregunta «¿cuadró?»; no calcula la respuesta. |
| 4 | `Clabe` es un **VO**, no un validador estático | Enmascarar, extraer la institución y validar son la misma responsabilidad; separarlas es lo que deja que una CLABE cruda llegue a una bitácora. |
| 5 | Dos cuentas puente, no una | Un abono sin dueño es pasivo y un cargo sin aclarar es activo. Ver §4. |
| 6 | Sin ruta aplicable, el pago **no sale**, y no es configurable | No hay cuenta de respaldo a la que caer. Un default silencioso es exactamente el defecto que este servicio existe para corregir; un interruptor para reactivarlo sería reintroducirlo. |
