# Resultados del análisis previo (AN-01 … AN-28)

> Ejecutado sobre `feature/clousures` el 2026-08-26. Cada resultado con la evidencia que lo sostiene.
> **Ninguna línea de producción tocada.**

## Leyenda

| | |
|---|---|
| ✅ | El plan acertó — la tarea procede tal como está |
| 🔀 | El plan acertó pero el resultado **cambia el alcance** |
| ⚠️ | Desviación — la tarea se ajusta |
| ⏳ | Requiere el stack levantado |

---

## Bloque 1 · Camino del dinero (AN-01 … AN-10)

### 🔀 AN-06 — El contrato de STP **sí** expone abonos recibidos

**El resultado más importante del bloque.** El puerto lo documenta explícitamente:

```java
/** @param tipoOrden {@code "E"} enviadas · {@code "R"} recibidas */
ReconciliationPage queryReconciliation(String stpEmpresa, String tipoOrden, LocalDate businessDate,
                                       int page, String signature);
```

`TIPO_ORDEN_ENVIADAS = "E"` es una constante **del llamador** (`SettlementPollingService:65`), no una
limitación del contrato. `RestClientStpGateway` y `StubStpGateway` ya reciben `tipoOrden` como
parámetro.

**Impacto en el alcance — se reduce:** la ingesta del estado de cuenta **extiende el poller que ya
existe**. No hay que construir un ingestor de archivo con su formato, validación y reproceso.
**Resuelve BK-02 sin necesidad de un ADR de alternativas.**

### ✅ AN-01 — `SpeiDispatchPort` tiene un solo llamador

`CreditAccountService:303`:
```java
: speiDispatch.dispatch(disposition.getDispositionId(), cmd.amount(), cmd.payeeAccount());
```
Un único punto. **BK-11 procede sin sorpresas.**

### ✅ AN-02 — El camino correcto del retiro ya existe

`wallet.withdrawal-completed` lo consumen `accounting` (`WithdrawalCompletedListener`) y
`disbursement` (`WalletWithdrawalCompletedListener`). **`WalletDispatchPort` es un duplicado que
dispara antes del evento.** BK-12 lo borra sin sustituto.

### 🔴 AN-03 — Confirmado: dos fuentes para el mismo dato

| Camino | Línea | De dónde toma `dispositionType` |
|---|---|---|
| **Activación** | `CreditAccountService:165` | `resolveDispositionType(caps)` — **del producto** ✅ |
| **Disposición por wallet** | `:295` | `parseDispositionType(cmd.dispositionType())` — **de la petición** 🔴 |
| Guarda de beneficiario | `:414` | ídem, de la petición 🔴 |

Con el fallback silencioso a `SELF_USE` ante un valor inválido, **una petición puede desviar el
destino del dinero**. BK-13 procede y sube de prioridad.

### ✅ AN-04 — Una sola fuente de cuentas propias

Sólo `stp-service` conoce `ordering_accounts`. Ningún otro servicio guarda CLABE propia. **La
migración de BK-07 es de un solo origen.**

### ✅ AN-05 — Un solo lector de `routing_rules`

Sólo `disbursement-service`. **BK-08 no afecta a terceros.**

### ✅ AN-07 — El payload de la instrucción, completo

`DisbursementInstruction` lleva once campos: `dispositionId`, `companyId`, `dispositionType`,
`amount`, `currency`, `beneficiaryName`, `beneficiaryAccount`, `beneficiaryAccountType`,
`beneficiaryTaxId`, `beneficiaryInstitution`, `concept`.

**Lo que NO lleva y banking debe añadir:** la **cuenta ordenante** y el **proveedor**. Hoy los
resuelve el conector por `is_default`. Es exactamente el hueco que BK-09 llena.

### ✅ AN-09 — El mock, y por qué es la plantilla correcta

`StubScenario.forAmount(BigDecimal)` — **los centavos del monto eligen el escenario**. El javadoc lo
explica: *«Técnica clásica de sandbox de pagos… Mandas once órdenes y ejercitas las once ramas del
servicio sin un solo mock.»*

Once escenarios: `SETTLED`, `DUPLICATE_TRACKING_KEY`, `REJECTED_PLD`, `RETRYABLE`, `RETURNED`,
`CANCELLED`, `NEVER_SETTLES`, `NAME_MISMATCH`, `INVALID_SIGNATURE`, `UNMATCHED_ENTRY`, `TIMEOUT`.

**Es la plantilla de BK-15.**

### ⚠️ AN-10 — Dos calendarios, y el de pagos ya tiene días hábiles

`OperatingWindow` es **envolvente** (abre 17:10, cierra 16:50 del día siguiente; la franja cerrada es
la corta) y lleva su propio `Set<DayOfWeek> days`.

**Desviación:** el plan asumía que había que unificarlo con `closing.calendar_days`. Son dos cosas
distintas: la ventana operativa de SPEI es **intradía y de rail**; el calendario de negocio es **de
fecha contable**. Unificarlos sería un error.

**Ajuste:** BK-09 respeta `OperatingWindow` para el despacho y usa `calendar_days` sólo para la
fecha de negocio del cierre. **No se unifican.**

### ⏳ AN-08 — Órdenes históricas

Requiere el stack levantado. Se ejecuta antes de BK-07, que es la primera tarea que migra datos.

---

## Resumen del bloque 1

| | Nº | Tareas |
|---|---|---|
| ✅ Confirmados | 6 | AN-01, AN-02, AN-04, AN-05, AN-07, AN-09 |
| 🔀 Cambian el alcance | 1 | **AN-06 — la conciliación se reduce a extender el poller** |
| 🔴 Hallazgo confirmado | 1 | **AN-03 — el destino del dinero se puede desviar por petición** |
| ⚠️ Ajuste al plan | 1 | AN-10 — los dos calendarios **no** se unifican |
| ⏳ Pendiente de stack | 1 | AN-08 |

**Ninguna tarea de implementación queda invalidada.** Dos se ajustan: BK-02 se simplifica y BK-09
respeta la separación de calendarios.

---

## Bloque 2 · Contabilidad y conciliación (AN-11 … AN-14)

### ✅ AN-11 — No hay cuenta puente

Cuentas de activo en el catálogo: `1101 Bancos / SPEI` · `1201 Cartera vigente` ·
`1203 Intereses y comisiones por cobrar` · `1210 Cartera castigada` · `1290 Estimación preventiva`.

**No existe cuenta puente.** BK-06 crea `1109 Depósitos por identificar`.

### ✅ AN-12 — Las 5 tablas `bank_*` no tienen una sola clase Java

Verificado tabla por tabla: cero referencias en `src/main/java` de `closing-service`. **Moverlas a
`banking` no cuesta migración.** BK-04 procede.

### 🔀 AN-13 — La cadena es reconstruible: todos los eslabones existen

| Tramo | Llave persistida |
|---|---|
| Cartera → contabilidad | `balance_events.source_event_id` ↔ `vouchers.source_event_id` (`uq_vouchers_source`) |
| Cartera → disbursement | `source_system` + `source_reference` + `source_metadata` (JSONB) |
| disbursement → stp | `payment_request_id` |
| **stp → banco** | **`tracking_key`** con `UNIQUE (company_id, tracking_key)` |
| **banco → nosotros** | `settlement_observations.tracking_key`, con índice |

**Mejor de lo esperado:** `settlement_observations` **ya es la proyección de lo que el banco
reporta**. Con `tipoOrden="R"` (AN-06) esa misma tabla recibiría los abonos. Refuerza que la
conciliación se apoya en infraestructura existente.

Lo único que falta es **la tabla que une los eslabones** — BK-41.

### 🔴 AN-14 — Confirmado: cartera no guarda la fecha del hecho

```sql
applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

Es la única columna temporal de `balance_events`. **Sólo la fecha de proceso.** Confirmado el
bloqueador de los cuadres C1 y C2.

---

## Bloque 3 · Mora (AN-15 … AN-18)

### 🔴 AN-15 — `charges` no escucha la mora

Sus tres listeners: `balance-updated`, `charge-rejected`, `credit-account-activated`. **No consume
`delinquency-status-updated`.** Falta un listener y una llamada. BK-18 procede.

### 🔴 AN-16 — El moratorio se calcula sobre el principal COMPLETO

```java
BigDecimal basis = schedule.getPrincipalBalance();
```

En `accrueMoratoriumForSchedule`. **No es el saldo vencido: es todo el saldo.** Sobre $20 000 con una
cuota vencida de $2 000, cobraría mora sobre los $20 000.

**Nota de secuencia:** si BK-18 conectara el listener **antes** que BK-19 corrija la base, el bug
pasaría de latente a activo. **BK-19 va primero, o los dos en el mismo cambio.**

### ⚠️ AN-17 — Dos fuentes de la gracia, y **discrepan**

| Fuente | Default |
|---|---|
| `charges` · `ChargesProperties.gracePeriodDays` + `application.yml` | **3** |
| `closing` · `close_cycle_policies.payment_due_offset_days` | **0** |

Peor que dos fuentes: dos fuentes **con valores distintos**. BK-21 unifica en la política del
producto, y hay que decidir cuál gana — **propuesta: 3**, que es el comportamiento vigente.

### 🔴 AN-18 — Nadie apaga la mora

La única asignación de `moratoriumActive = false` está en `AccrualSchedule.create()` — la
inicialización. **No hay desactivación al curar.** BK-20 procede.

---

## Bloque 4 · Parcialidades, BNPL, skip y config (AN-19 … AN-28)

### ✅ AN-19 — Las `Capabilities` **sí** están sincronizadas

Los 9 campos de negocio son idénticos en los dos servicios. `credit-portfolio` añade uno derivado
(`revolving`), que no es divergencia. **Un campo nuevo se replica en dos sitios, como decía el plan.**

### 🔴 AN-20 — Sin calendario, una revolvente no puede caer en mora

`DelinquencyAccountProcessor` busca `Installment` vencidos —
`vencidasDeSusDisposiciones` → `diasDesdeLaMasAntigua`. **Si una compra de tarjeta nace sin
calendario (BK-24), el DPD no encuentra nada que vencer.**

**Consecuencia para el plan:** BK-24 **no puede entregarse solo**. Va atado a BK-22 (la fecha
exigible sale del corte) y a BK-27 (el exigible del corte). Los tres son un mismo cambio.

### ✅ AN-23 — El corte del DPD es un solo punto

`DelinquencyAccountProcessor.process()` líneas 58-69: calcula, llama `updateDelinquency` y publica.
Una cuenta bajo programa de apoyo **no entra al método**. Cambio mínimo.

### ✅ AN-25 — El maker-checker ya existe

`configuration.config_parameters` tiene `created_by NOT NULL` + `approved_by`. **Es el patrón a
reusar** para el `ReliefProgram`, no uno nuevo.

### 🔴 AN-27 — La restricción de `rate_cards` prohíbe la tasa cero

```sql
CONSTRAINT ck_rc_nominal_rate CHECK (nominal_rate > 0 AND nominal_rate < 1)
```

**Un MSI no se puede configurar.** BK-25b la relaja a `>= 0` para la nominal; la moratoria se queda
en `> 0`.

### ✅ AN-28 — El motor ya soporta tasa cero

```java
if (r.compareTo(BigDecimal.ZERO) == 0) return principal.divide(BigDecimal.valueOf(n), MC);
```

`AmortizationEngine.fixedPayment`. **Un MSI genera plan de puro capital sin tocar el motor.** Sólo
falta la prueba.

---

# Resumen global

| | Nº | Detalle |
|---|---|---|
| ✅ Confirmados sin cambio | 13 | El plan acertó |
| 🔀 Reducen el alcance | 2 | **AN-06** (conciliación extiende el poller) · **AN-13** (todos los eslabones existen) |
| 🔴 Hallazgo confirmado | 7 | AN-03, AN-14, AN-15, AN-16, AN-18, AN-20, AN-27 |
| ⚠️ Ajustan el plan | 3 | AN-10 (calendarios no se unifican) · AN-17 (los defaults discrepan) · AN-19 (era falsa alarma) |
| ⏳ Requieren stack | 3 | AN-08, AN-21, AN-22 |

## Ajustes al plan derivados del análisis

| # | Ajuste |
|---|---|
| 1 | **BK-02 se simplifica**: no hace falta ADR de alternativas — el contrato ya soporta recibidas |
| 2 | **BK-09 no unifica calendarios**: `OperatingWindow` es intradía y de rail; `calendar_days` es de fecha contable |
| 3 | **BK-19 va antes que BK-18**, o juntos: conectar el listener sin corregir la base activaría el cobro excesivo |
| 4 | **BK-21 decide la gracia**: propuesta 3 días, el comportamiento vigente |
| 5 | **BK-24 + BK-22 + BK-27 son un solo cambio**: sin calendario no hay mora, salvo que el exigible salga del corte |
