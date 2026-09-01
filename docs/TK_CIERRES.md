# TK — Cierres y cortes distribuidos

> Rama: **`feature/clousures`** · Plan de referencia: [`ANALISIS_CIERRES_Y_CORTES.md`](ANALISIS_CIERRES_Y_CORTES.md)
> Arranque: 2026-08-24

## Decisiones que enmarcan estos TK

| # | Decisión | Consecuencia |
|---|---|---|
| D1 | **Candado distribuido en Redis.** Ningún `FOR UPDATE` ni `SKIP LOCKED` en base de datos | El reparto entre pods es TK-01. La corrección la sigue dando la clave natural (TK-02) |
| D5 | **Redisson es el proveedor predeterminado**, con la implementación sobre `StringRedisTemplate` detrás del mismo puerto (`fintech.lock.provider`) | Cambiar de una a otra es una propiedad, no una migración. La batería de integración corre sobre **las dos** |
| D7 | **`closing-service` absorbe bancos.** No existe servicio de tesorería en la plataforma; la conciliación bancaria vive aquí como paquete propio | Su ciclo de vida es el del cierre: "el saldo del banco al cierre del día D". Separarla obligaría a coordinar dos servicios para responder una sola pregunta. La frontera queda trazada por si crece |
| D6 | **Sin scripts Lua en los servicios.** El proveedor `redis-template` usa `WATCH`/`MULTI`/`EXEC` con `SessionCallback` | Lua queda acotado al gateway. Redisson usa scripts internamente, pero eso es asunto de la librería, no código nuestro |
| D2 | **Outbox e inbox: fuera de alcance.** Sin Spring Modulith; servicios independientes | La conciliación **detecta** el evento perdido, no lo previene. Asumido y documentado |
| D3 | Un solo componente de candado en `shared`, con llave de unicidad, reutilizable por cualquier servicio | TK-01 sirve al cierre, al período contable y a cualquier job futuro |
| D4 | `closing-service` es servicio nuevo, aislado, sin escrituras cruzadas | TK-03 en adelante |

---

## Tablero

| TK | Título | Depende de | Estado |
|---|---|---|---|
| TK-01 | `shared`: candado distribuido en Redis | — | ✅ **43 pruebas** |
| TK-02 | Claves naturales de idempotencia | — | ✅ **6 IT** |
| TK-03 | `closing-service`: esqueleto + esquema completo | — | ✅ **7 pruebas IT** |
| TK-04 | Calendario de negocio y políticas por producto | TK-03 | ✅ **23 pruebas** |
| TK-05 | Motor de corridas: runs, units, claim, reaper, sello | TK-01, TK-04 | ✅ **10 IT** |
| TK-06 | Proyección por evento + calendario de corte | TK-05 | ✅ **13 IT** |
| TK-07 | Cierre diario de cartera | TK-06 | ✅ **10 IT** |
| TK-07 | Cierres y cortes de cobranza | TK-05 | ⬜ |
| TK-08 | Cierre contable diario y mensual | TK-05 | ⬜ |
| TK-09 | Cierre de bancos diario y mensual | TK-08 | ⬜ |

---

## Análisis previo a TK-06/TK-07 — «replicar al 100% el comportamiento en cartera»

> Base medida el 2026-08-24: **160 pruebas en verde** (`credit-portfolio` 133 · `charges` 27), más las 43 de TK-01.

### A. Cómo se comporta hoy un crédito NO revolvente

Dos relojes distintos, y hoy nadie los cruza:

| | Quién lo produce | Base de cálculo | Cadencia |
|---|---|---|---|
| **Plan de pagos** | `AmortizationEngine` (credit-portfolio), al activar | `tasa / periodosPorAño` — **30/360 implícito** | `WEEKLY` 52 · `BIWEEKLY` 26 · `MONTHLY` 12 |
| **Devengo** | `InterestAccrualService` (charges), un cargo por día | `tasa / 360` × **días naturales** | Diaria, idempotente por `last_accrual_date` |

**El resultado numérico** — `PERSONAL_LOAN` $20,000 a 32% anual, mensual, francés:

| Concepto | Importe | Desvío |
|---|---|---|
| Interés de la cuota 1 del plan | `20000 × 0.32/12` = **533.33** | — |
| Devengado en un mes de 30 días | `17.78 × 30` = **533.40** | +0.01 % |
| Devengado en un mes de **31 días** | `17.78 × 31` = **551.18** | **+3.35 %** |
| Devengado en **febrero** (28 días) | `17.78 × 28` = **497.84** | **−6.65 %** |

El plan usa **30/360** y el devengo **actual/360**. Ninguna de las dos convenciones está mal —las dos existen en la industria— pero **no son la misma**, y hoy nada lo declara ni lo prueba. El cliente ve un plan que no coincide con lo devengado, y el descuadre no rompe nada: cada pieza cuadra consigo misma.

**Esto es exactamente lo que el E2E tiene que fijar.** No para «arreglarlo» por cuenta propia —cuál convención es la correcta es decisión de negocio— sino para que quede **declarada, medida y bajo prueba**, y para que el cierre diario devengue según la convención del producto y no según la que le tocó al servicio.

**Decisión que hay que tomar** (la anoto, no la tomo):

| Opción | Efecto |
|---|---|
| `ACTUAL_360` (lo que hace hoy charges) | Febrero cobra menos y marzo más que el plan. Es la convención de crédito al consumo mexicano |
| `THIRTY_360` (lo que asume el plan) | Devengo y plan coinciden al centavo. Cada mes devenga 30 días, sea cual sea |
| `ACTUAL_365` | Ni una ni otra; hay que decidirlo explícitamente |

Va a `close_cycle_policies.definition.accrualBasis` (§16 del plan), **por producto**, y el motor devenga con la del producto.

### B. La cobranza no sabe de productos. En absoluto.

`DunningService.runDailyCycle()` **no menciona `productType` en ninguna línea**. Lo que hace:

```java
List<CollectionCase> candidatos =
        caseRepository.findByStatusInAndDaysDelinquentGreaterThanEqual(ACTIVOS, 1);
DunningStep step = DunningStep.forDay(c.getDaysDelinquent());   // ciclo plano de 5 días
```

Un ciclo fijo de 5 días por días de mora, **idéntico para una tarjeta, una nómina y una línea de distribuidor**. No existe fecha de corte configurada, no existe cadencia por producto, y no existe corrida que pregunte «¿a qué cuentas les toca corte hoy?».

**Lo que pediste no está a medias: no está.**

### C. Para un no revolvente, el corte es la fecha de la cuota

No hay que inventar un ciclo: **ya existe y está en `installments.due_date`**, generado por `AmortizationEngine` según la `paymentFrequency` del producto. Un `PERSONAL_LOAN` mensual tiene corte el día de cada cuota; uno quincenal, cada 14 días.

Regla de `cutoffRule` por comportamiento del producto:

| Comportamiento | `cutoffRule` | De dónde sale la fecha |
|---|---|---|
| `INSTALLMENT` (no revolvente) | `INSTALLMENT_DUE_DATE` | La próxima cuota `PENDING` del calendario |
| `REVOLVING` | `CYCLE_FROM_ACTIVATION` o `DAY_OF_MONTH(n)` | El ciclo de corte, hoy inexistente (§4.3) |

Y el **cierre de cobranza del día** evalúa, sobre las cuentas cuyo corte cae hoy, si la cuota se pagó dentro de la gracia (`grace-period-days: 3`), produciendo DPD, bucket, caso y cadencia.

### D. Diseño del E2E — `CreditLifecycleClosingE2EIT`

Un crédito no revolvente, día a día, con el reloj corrido por el motor de cierre. Cada paso es una aserción, no una narración.

| # | Paso | Se verifica |
|---|---|---|
| 1 | Activar `PERSONAL_LOAN` $20,000 · 32% · 12 meses · francés | 12 cuotas; primera a `activación + 1 mes`; interés de cuota 1 = 533.33 |
| 2 | Correr el cierre diario 30 días | 30 cargos de devengo, **uno por día**, ni uno más al repetir la corrida |
| 3 | Comparar devengado contra plan | El desvío es **el de la convención declarada del producto**, y no otro |
| 4 | Llegar a la fecha de corte | La cuenta entra al corte **de ese día y no antes** |
| 5 | Pagar la cuota dentro de la gracia | Cuota `PAID`, saldo baja, DPD sigue en 0, **no se abre caso** |
| 6 | No pagar la siguiente | Pasada la gracia: DPD>0, bucket `B1_30`, caso abierto, cadencia arranca |
| 7 | Backoffice | `GET /portfolio` muestra el saldo · `GET /portfolio/{id}` el calendario con la cuota pagada · `GET /collections/cases` el caso |
| 8 | Sello del día | Las cifras de control del sello **cuadran contra la suma de la cartera** |
| 9 | Correr dos veces el mismo día | Cero movimientos nuevos: idempotente de punta a punta |
| 10 | Tres pods a la vez | Ninguna cuenta se devenga dos veces (candado TK-01 + clave natural TK-02) |

**Matriz por producto** — el mismo escenario sobre las cadencias que el motor ya soporta:

| Producto | Frecuencia | Amortización | Qué prueba |
|---|---|---|---|
| `PERSONAL_LOAN` | `MONTHLY` | `FRENCH` | El caso base |
| `PAYROLL_LOAN` | `BIWEEKLY` | `FRENCH` | Corte cada 14 días, no mensual |
| `MICRO_LOAN` | `WEEKLY` | `FRENCH` | 52 cortes al año |
| `SME_LOAN` | `MONTHLY` | `GERMAN` | Capital fijo, interés decreciente |
| — | `MONTHLY` | `BULLET` | Sólo interés; capital al vencimiento |

> **Prerequisito reconocido:** el E2E completo necesita el motor (TK-03 → TK-05). Los pasos 1-3 y 5-7 se pueden fijar **ya** contra el comportamiento actual, y sirven de red mientras se construye: si el motor cambia un importe, la prueba lo dice.

---

## E2E — ciclo de vida completo por producto

> Requisito literal: *«pruebas E2E de cada producto generando cierres y ver que avancen las fechas para probar todos los escenarios: pagos, mora, liquidación, mora dentro de cada bucket»* y *«cada devengamiento tiene que haber sido aplicado por los cierres»*.

### La máquina del tiempo

Nada de `Thread.sleep` ni de esperar a que pase un día. El motor **recibe** la fecha de negocio; no la lee del reloj. Eso es lo que hace la prueba posible:

```java
// Avanza el reloj de negocio un día y corre TODAS las fases de ese día.
// No simula el cierre: ejecuta el mismo código que corre en producción.
clock.avanzarA(fecha);
closingEngine.runBusinessDay(fecha);
```

**Regla del E2E, y es la que le da valor:** ningún saldo se escribe a mano. Todo movimiento tiene que haber entrado **por una corrida de cierre**. Si un devengo aparece sin corrida que lo produjera, la prueba falla — es literalmente lo que se pide verificar.

### Matriz de productos

| Producto | Comportamiento | Cadencia | Amortización | Corte | Qué prueba que los demás no |
|---|---|---|---|---|---|
| `PERSONAL_LOAN` | INSTALLMENT | MONTHLY | FRENCH | `INSTALLMENT_DUE_DATE` | El caso base y el cuadre mes a mes |
| `PAYROLL_LOAN` | INSTALLMENT | BIWEEKLY | FRENCH | `INSTALLMENT_DUE_DATE` | 26 cortes al año: el corte **no** es mensual |
| `MICRO_LOAN` | INSTALLMENT | WEEKLY | FRENCH | `INSTALLMENT_DUE_DATE` | 52 cortes; el devengo entre cortes es de 7 días |
| `SME_LOAN` | INSTALLMENT | MONTHLY | GERMAN | `INSTALLMENT_DUE_DATE` | Capital fijo: el interés decrece y el devengo también |
| `GROUP_LOAN` | INSTALLMENT | MONTHLY | FRENCH | `INSTALLMENT_DUE_DATE` | La unidad de cierre es el **grupo**, no la cuenta |
| `CREDIT_CARD` | REVOLVING | — | — | `CYCLE_FROM_ACTIVATION` | Corte, pago mínimo y estado de cuenta — **hoy no existen** |
| `REVOLVING_LINE` | REVOLVING | — | — | `CYCLE_FROM_ACTIVATION` | Disposiciones múltiples entre cortes |
| `DISTRIBUTOR_LINE` | REVOLVING | — | — | `CYCLE_FROM_ACTIVATION` | Comisión anclada al corte + tolerancia cero |

### Escenarios del ciclo de vida

Cada uno sobre la matriz completa, con el reloj corrido día a día:

| # | Escenario | Aserciones clave |
|---|---|---|
| **E1** | **Alta y primer devengo** | El plan se genera; el primer devengo lo produce **la corrida del día siguiente**, no la activación |
| **E2** | **Devengo hasta el primer corte** | Exactamente *n* cargos, uno por día de negocio; el acumulado coincide con la convención `accrual_basis` **declarada del producto** |
| **E3** | **Pago puntual** | Cuota `PAID`, saldo baja, DPD sigue en 0, **no se abre caso**, el arrastre C1 cierra |
| **E4** | **Pago parcial** | Se aplica en jerarquía moratorios→interés→capital; la cuota queda `PARTIAL`; el resto sigue devengando |
| **E5** | **Pago dentro de la gracia** | Día `vencimiento + 3`: no hay mora ni moratorios |
| **E6** | **Impago: recorrido de buckets** | Día a día por `B1_30 → B31_60 → B61_90 → B91_120 → B121_180 → B181_PLUS`; en cada frontera: bucket correcto, caso escalado, provisión recalculada |
| **E7** | **Moratorios** | Arrancan pasada la gracia, sobre el saldo vencido, y **cada uno viene de una corrida** |
| **E8** | **Pago que cura la mora** | DPD vuelve a 0, el caso cierra, los moratorios dejan de devengar |
| **E9** | **Liquidación anticipada** | Saldo a cero, cuenta `SETTLED`, el devengo **para** ese día, el calendario de corte se cancela |
| **E10** | **Liquidación al vencimiento** | Última cuota paga el residuo de redondeo; suma de cuotas == capital |
| **E11** | **Quebranto** | Saldo a cero, `WRITTEN_OFF`, cuentas de orden cargadas, provisión consumida |
| **E12** | **Recuperación post-quebranto** | Abona a `4104`, no reabre la cuenta |
| **E13** | **Corte del ciclo** | `amountDue` == Σ movimientos del ciclo (**cuadre C5**); el corte queda inmutable |
| **E14** | **Movimiento extemporáneo** | Llega con fecha anterior a un corte sellado: **no lo reabre**, entra como ajuste del ciclo siguiente |
| **E15** | **Día inhábil** | El corte que cae en domingo se corre según `non_business_day_shift` |
| **E16** | **Revolvente: disposiciones entre cortes** | Varias disposiciones en un ciclo; el corte las agrega y calcula el pago mínimo |

### Los cuadres, verificados todos los días de la simulación

En **cada** día simulado, y no sólo al final:

| Cuadre | Aserción |
|---|---|
| **C1** cartera consigo misma | `Saldo(D−1) + Σ flujos(D) == Saldo(D)`, cuenta por cuenta |
| **C2** cartera ↔ mayor | `Δ cartera(D) == Δ(1201+1203)(D)` |
| **C3** cartera ↔ banco | Pagos aplicados == abonos identificados ± partidas **explicadas** |
| **C4** mayor ↔ banco | `Δ 1101(D) == Δ estado de cuenta(D) ± partidas` |
| **C5** corte ↔ movimientos | `amountDue == Σ` movimientos entre cortes |
| **Balanza** | `Σ cargos == Σ abonos` del día |

**Y el escenario que prueba la alineación de calendarios** (§13.6) — el que contesta la pregunta de raíz:

> **E17 — Calendarios desalineados.** Tres cuentas del mismo producto activadas en días distintos, con cortes el 5, el 12 y el 20. Pagos entrando en días arbitrarios, algunos a las 23:50. Se corre un mes completo.
>
> **Se verifica que los cuatro cuadres diarios cierran todos los días**, aunque ningún corte coincida con ningún otro y ningún pago caiga en la fecha de su corte. Es la demostración de que el cuadre va por **flujo sobre la fecha de negocio** y no por corte.

### Distribución, dentro del mismo E2E

| Prueba | Qué fija |
|---|---|
| Tres pods sobre la misma corrida | Cada cuenta se devenga **una sola vez**; los tres hicieron trabajo |
| Pod muerto a media corrida | El reaper devuelve el lote, otro lo toma, el conteo final cuadra |
| Con el candado apagado | La clave natural **sigue** impidiendo el dato duplicado — se degrada el rendimiento, no la corrección |

### Backoffice, al final de cada escenario

| Endpoint | Qué muestra |
|---|---|
| `GET /portfolio` | La cuenta con su saldo del día y sus días de atraso |
| `GET /portfolio/{id}` | El calendario con las cuotas pagadas, parciales y pendientes |
| `GET /collections/cases` | El caso, su bucket y su cadencia |
| `GET /accounting/trial-balance` | La balanza cuadrada del período |
| `GET /accounting/accounts/{id}/ledger` | El mayor de la cuenta, póliza por póliza |

---

## TK-01 — `shared`: candado distribuido en Redis

**Objetivo.** Un componente de exclusión distribuida reutilizable por todo el monorepo, identificado por una llave de unicidad compuesta.

**Entrega**

```
shared/src/main/java/com/fintech/shared/lock/
├── LockKey.java              value object: fintech:lock:<dominio>:<propósito>:<discriminador>
├── LockHandle.java           key + token + fencingToken + expiresAt
├── DistributedLock.java      puerto
├── LockAcquisitionException.java
└── redis/
    ├── RedisDistributedLock.java     SET NX PX + WATCH/MULTI/EXEC + INCR de fencing
    └── LockAutoConfiguration.java    @ConditionalOnClass(StringRedisTemplate)
```

**Reglas**
1. Adquisición atómica: `SET key token NX PX ttl`.
2. Liberación con **comparación de token dentro de `WATCH`/`MULTI`/`EXEC`** — nunca `GET` + `DEL` sueltos. Todo con `SessionCallback` de Spring Data Redis: **sin scripts Lua en los servicios**.
3. Extensión con la misma transacción optimista + `EXPIRE`.
4. **Fencing token** monótono por llave (`INCR`), incluido en el handle.
5. `withLock` libera siempre, incluso ante excepción.
6. Redis caído ⇒ no se adquiere, se propaga el fallo. **Nunca se degrada a "seguir sin candado".**

**Entregado**

```
shared/src/main/java/com/fintech/shared/lock/
├── LockKey.java                 llave de unicidad + llave del contador de fencing
├── LockHandle.java              token de propiedad + fencing token + expiración
├── DistributedLock.java         puerto (withLock / runWithLock por defecto)
├── LockProperties.java          provider · flavor · defaultTtl · waitTime
├── LockAcquisitionException.java
├── redis/
│   ├── RedisDistributedLock.java     SET NX PX + WATCH/MULTI/EXEC (sin dependencias nuevas)
│   └── LockAutoConfiguration.java    elige proveedor por propiedad
└── redisson/
    └── RedissonDistributedLock.java  RLock + watchdog + contador de fencing
```

**Dos hallazgos que sólo salieron al correr contra Redis real**

| # | Hallazgo | Corrección |
|---|---|---|
| 1 | **`RLock` de Redisson es reentrante.** El mismo hilo que ya posee el candado vuelve a entrar y sólo sube el contador. Con un pool de hilos, una segunda petición servida por el mismo hilo **pasaría de largo** — justo lo contrario de «la primera se procesa y las demás se descartan» | Se comprueba `isHeldByCurrentThread()` **antes** de tomar y se responde "ocupado" |
| 2 | **Deshacer la re-entrada con `unlock()` reestablece el arrendamiento al valor por defecto de Redisson (30 s)**, no al TTL pedido. Una llave de 800 ms se convertía en una de 30 s, y el pod que muere retenía el candado mucho más de lo pactado | Por eso la comprobación va antes de tomar, no después |

> Ninguno de los dos aparece en las pruebas unitarias con mocks. Salieron de la batería contra Redis real — que es exactamente para lo que existe.

**Pruebas — 43 en verde**

| Suite | Nº | Cubre |
|---|---|---|
| `LockKeyTest` | 6 | Composición de la llave, segmentos vacíos, espacio de nombres del contador |
| `RedisDistributedLockTest` | 12 | `SET NX PX`, CAS en `WATCH`/`MULTI`/`EXEC`, liberación ajena, Redis caído, TTL inválido |
| `RedissonDistributedLockTest` | 11 | `tryLock` con espera 0, fencing creciente, **re-entrada rechazada**, flavor, watchdog, titularidad |
| `DistributedLockIT` | 14 | **Redis real (Testcontainers), la misma batería sobre los dos proveedores**: sólo el primero entra · el TTL suelta el candado solo · fencing estrictamente creciente · 32 hilos con exclusión estricta · `withLock` libera ante excepción |

## TK-02 — Claves naturales de idempotencia ✅

**Objetivo.** Que un hecho no pueda existir dos veces, venga de donde venga. Es el cimiento del candado y no su sustituto: **el candado evita el trabajo duplicado; esto evita el dato duplicado.**

### Un hallazgo que habría roto producción

El plan proponía `UNIQUE (credit_account_id, charge_type, accrual_date)`. **Habría roto el devengo moratorio de toda cuenta en mora**: un mismo día genera **dos** cargos de IVA —el del interés ordinario y el del moratorio— y la restricción total los habría hecho chocar.

La clave natural real es distinta por tipo de cargo:

| Cargo | Clave natural | Restricción |
|---|---|---|
| `ORDINARY_INTEREST`, `MORATORIUM_INTEREST` | cuenta + tipo + día | Índice único **parcial** |
| `IVA` | el cargo **padre** del que se deriva | Único por `linked_charge_id` |
| `OPENING_FEE` | cuenta, una vez para siempre | Único parcial por cuenta |

### El reloj del moratorio, separado

`accrueMoratoriumForSchedule` **nunca llamaba a `markAccruedFor`**: se repetía sin resistencia. Pero no puede compartir `last_accrual_date` con el ordinario — si el job moratorio corriera primero y marcara el día, `needsAccrual` daría falso y **el interés ordinario de ese día no se devengaría**. Se cambiaría un cargo duplicado por uno omitido, que es peor y más difícil de ver.

Se añade `last_moratorium_accrual_date`, y `rewindAllTo` retrocede los dos juntos.

### Pruebas — 6 contra Postgres real

| Prueba | Qué fija |
|---|---|
| Ordinario dos veces el mismo día | Un solo cargo |
| **Moratorio dos veces el mismo día** | Un solo cargo — **antes daba 2 con una sola réplica** |
| Ordinario y moratorio el mismo día | Dos IVA conviven: la restricción es parcial por eso |
| El reloj del moratorio no le roba el día al ordinario | Los dos devengan aunque el moratorio corra primero |
| Días distintos | La guarda no bloquea el avance del reloj |
| **8 hilos concurrentes, sin candado** | **Un solo cargo.** Si el candado fallara, la clave natural sostiene la corrección |

Y `commission.liquidation_batches` gana `UNIQUE (beneficiary_party_id, period)`: lo que había era un índice, que acelera la consulta y no impide nada — dos corridas del mismo período creaban dos lotes y el doble pago a un tercero no se corrige con un rollback.

## TK-03 — `closing-service`: esqueleto y esquema ✅

**Creado.** Módulo Gradle registrado, puerto **8103**, schema `closing`, hexagonal según `base-service.md`, registrado en `docker-compose.yml` con Redis y Postgres como dependencias sanas.

### Lo que faltaba y ahora existe

| Hueco verificado en el as-is | Cubierto por |
|---|---|
| No hay servicio de cierres — los 14 jobs viven dentro de los servicios de dominio | `closing-service`, *deployment aparte* |
| **No hay servicio de bancos ni de tesorería** en toda la plataforma | `bank_accounts`, `bank_statement_lines`, `bank_matches`, `suspense_entries`, `bank_close_seals` |
| La conciliación cartera↔contabilidad estaba declarada y sin emisor (`publishReconciliationAlert`) | `reconciliation_findings` con las **tres puntas** |
| `hasCutoffDate` sembrado en `true` y jamás leído | `cutoff_schedules` + `cutoff_rule` por producto |
| El devengo y el plan de pagos usan convenciones distintas y nada lo declaraba | `close_cycle_policies.accrual_basis`, **por producto** |
| La fecha del cierre era el reloj del pod | `business_calendars` / `calendar_days` |

### 14 tablas

| Grupo | Tablas |
|---|---|
| Calendario | `business_calendars` · `calendar_days` |
| Política | `close_cycle_policies` — versionada, un `ACTIVE` por alcance impuesto por índice parcial |
| Proyección | `account_close_profiles` — el desacople de cartera |
| **Corte** | `cutoff_schedules` — **propiedad del cierre**, ver abajo |
| Motor | `close_runs` · `close_units` (con `fencing_token`) · `close_seals` |
| Conciliación | `reconciliation_findings` — `PORTFOLIO_VS_LEDGER`, `LEDGER_VS_BANK`, `PORTFOLIO_VS_BANK` |
| **Bancos** | `bank_accounts` · `bank_statement_lines` · `bank_matches` · `suspense_entries` · `bank_close_seals` |

### El calendario de corte es del cierre, no de cartera

Para un no revolvente la cadencia coincide con el vencimiento de la cuota, así que la tentación es leer `installments.due_date`. **No se hace**, por tres razones:

1. Leer cartera en línea durante la ventana **rompe el aislamiento** — es justo lo que hoy hace que el barrido nocturno compita con la API del backoffice.
2. El corte es una decisión de **política**, no del plan: un producto puede cortar N días antes del vencimiento, o correrse si cae inhábil. El plan de pagos no sabe nada de eso.
3. **Un corte sellado es inmutable.** Si cartera regenera el calendario por una reestructura, el corte ya emitido no cambia retroactivamente: el ajuste va al ciclo siguiente.

El cierre **deriva** el calendario de la política del producto y de lo que aprendió por evento —activación, cadencia, plazo— y lo **persiste** en `cutoff_schedules`. Cartera sigue siendo dueña del plan de cara al cliente; el cierre es dueño de cuándo cierra.

### Pruebas — 7 contra Postgres real

| Prueba | Qué fija |
|---|---|
| El changelog crea las 14 tablas | El esquema completo aplica |
| Una sola política `ACTIVE` por alcance | Sin el índice parcial, cuál gana dependería del orden de las filas |
| Políticas sembradas por producto | `INSTALLMENT_DUE_DATE` para no revolventes, `CYCLE_FROM_ACTIVATION` para revolventes |
| Corrida única por fecha/fase/alcance | Segunda red si el candado fallara |
| Fecha límite nunca antes del corte | Invariante del corte |
| Las tres conciliaciones contempladas | Cartera, contabilidad **y bancos** |
| Movimiento bancario no duplicable | Los bancos reenvían archivos: la reingesta es la norma |

## TK-04 — Calendario de negocio y política por producto ✅

**Entregado**

```
domain/    AccrualBasis · CutoffRule · NonBusinessDayShift · ClosePhase
           CalendarDay · ClosePolicy
application/service/  BusinessCalendarService · ClosePolicyResolver
infrastructure/adapter/out/persistence/  adaptadores JPA de los dos puertos
```

### `AccrualBasis` — la convención, por fin declarada

Las tres conviven detrás del mismo tipo, y las pruebas fijan **los números exactos** sobre $20,000 al 32 %:

| Convención | Marzo (31 d) | Febrero (28 d) | Contra el plan ($533.33) |
|---|---|---|---|
| `ACTUAL_360` *(lo que hace hoy charges)* | **551.11** | **497.78** | +3.3 % / −6.7 % |
| `THIRTY_360` | 533.33 | 533.33 | **coincide al centavo** |
| `ACTUAL_365` | 543.56 | 490.74 | otro número distinto |

Se sembró `ACTUAL_360` porque es lo que el sistema hace hoy: **la política preserva el comportamiento actual y lo deja declarado**, en vez de cambiarlo por la puerta de atrás. Cambiar a `THIRTY_360` es editar una fila.

Una prueba fija además que **la suma de los cargos diarios reproduce el interés del tramo** dentro del redondeo — si divergieran, el devengo diario y el mensual contarían historias distintas.

### `ClosePhase` — el orden como dependencia, no como hora

`RECONCILE → ACCRUAL → DELINQUENCY → RISK → CUTOFF → POSTING_DRAIN → SEAL → PROPAGATE`, cada una declarando cuál exige. Una prueba recorre la cadena y verifica que ninguna fase dependa de otra posterior.

### `BusinessCalendarService` — la fecha de negocio

La tabla guarda **sólo excepciones**: lo no registrado se resuelve por la regla general (L–V se opera). Un calendario nuevo funciona desde el primer día sin sembrarle 365 filas.

### `ClosePolicyResolver` — precedencia y congelado

Producto › tipo › global, con la regla en el **dominio** (`specificity()`) y no en un `ORDER BY` — es una decisión de negocio y tiene que probarse sin base de datos. Y siempre la vigente **el día del cierre**, no la de hoy: sin eso, un cambio de política reescribiría el pasado en la siguiente re-corrida.

Sin política vigente **falla explícito**. No hay default en código: inventarle uno a un producto que no la declaró es cómo se acaba devengando con una convención que nadie eligió.

### Pruebas — 23

| Suite | Nº | Cubre |
|---|---|---|
| `AccrualBasisTest` | 7 | Los tres números por convención, bisiesto, suma diaria vs tramo, saldo cero |
| `ClosePhaseTest` | 4 | `RECONCILE` primero, barreras, `PROPAGATE` tras el sello, cadena sin ciclos |
| `CalendarioYPoliticaIT` | 12 | Regla general sin sembrar, festivo que gana, corte en domingo con `NEXT`/`PREV`/`NONE`, días hábiles, precedencia de alcance, vigencia por fecha, fallo explícito, tolerancia cero del distribuidor |

## TK-05 — Motor de corridas ✅

**Entregado.** `CloseRun` · `CloseUnit` · `CloseRunPlanner` · `CloseRunWorker` · `LeaseReaper`, con adaptadores JPA y **cero candados de base de datos**.

### La secuencia del reparto

1. **Planificar** bajo `closing:run:<fecha>:<fase>:<alcance>`. Un pod gana; los demás **no esperan** — se ponen a trabajar unidades que otro materializó. `uq_close_run` queda como segunda red por si el candado fallara.
2. **Leer candidatas** con un `SELECT` normal, con desplazamiento por pod para que las réplicas no compitan siempre por las mismas filas.
3. **Ganar el candado** de la unidad y confirmar con **CAS optimista** (`UPDATE … WHERE status='PENDING'`). Cero filas = otro se adelantó; se sigue con la siguiente.
4. **Procesar** en transacción corta propia.
5. **Sellar** — nunca con unidades fallidas.

### Tres trampas que costaron una corrección cada una

| # | Trampa | Corrección |
|---|---|---|
| 1 | **`@Transactional` en método llamado desde la misma clase no pasa por el proxy.** Sería un `REQUIRES_NEW` decorativo, y el primer fallo marcaría la transacción del lote como rollback-only, revirtiendo el trabajo ya hecho | `TransactionTemplate`, igual que `OutboxRelayService` ya documenta en este repo |
| 2 | La marca de `FAILED` en la misma transacción que el trabajo **se la lleva el rollback** | Dos transacciones: una hace y deshace, otra graba la causa |
| 3 | **`@ConditionalOnBean` depende del orden de evaluación.** La autoconfiguración del candado se procesaba antes que la de Redisson, el cliente no existía todavía, la condición fallaba **en silencio** y el bean no aparecía | `@AutoConfiguration(afterName = …)`, con cada configuración registrada por separado |

### Pruebas — 10 contra Postgres **y** Redis reales

| Prueba | Qué fija |
|---|---|
| 6 pods planificando a la vez | **Exactamente uno** crea la corrida; las unidades se materializan una sola vez |
| Barrera de fase | `ACCRUAL` no arranca si `RECONCILE` no selló — sustituye a los offsets de reloj |
| **3 pods, 60 unidades** | Ninguna se procesa dos veces; todas se procesan |
| Unidad que falla | La corrida sigue; la causa **se persiste** (el as-is la pierde en un log) |
| Unidad omitida | `SKIPPED` no cuenta como fallo y no bloquea el sello |
| **Pod muerto** | Con el candado vivo el reaper **la respeta**; vencido, la devuelve y otro pod la termina |
| **Fencing token** | El titular de un candado vencido **no puede escribir** |
| Sello a medias | No se sella con fallidas |
| Re-corrida | Cero reprocesos |

> El servicio **no arranca sin candado**, y es deliberado. Las pruebas que no van sobre el reparto usan un doble en memoria (`InMemoryLockConfig`) en vez de relajar esa regla.

## TK-06 — Cierre diario de cartera

**Entrega.** Fases `ACCRUAL` y `DELINQUENCY` orquestadas por unidad, con sello diario y cifras de control.

**Pruebas** — unitarias, aceptación e integración
- [x] Devengo por unidad, idempotente por fecha de negocio
- [x] DPD recalculado por unidad
- [x] Barrera: `DELINQUENCY` no arranca sin el sello de `ACCRUAL`
- [x] Sello con cifras de control correctas
- [x] Cuenta terminal se omite (`SKIPPED`)

---

## TK-07 — Cierres y cortes de cobranza

**Entrega.** Fase `COLLECTIONS_CLOSE`: promesas vencidas, convenios expirados, candidatos a quebranto y corte de gestión del día.

**Pruebas**
- [x] Promesa vencida se marca rota una sola vez
- [x] Convenio fuera de ventana expira una sola vez
- [x] Candidatos a quebranto por umbral de días
- [x] Corte de gestión: cifras del día por bucket
- [x] Doble corrida: sin duplicar gestiones

---

## TK-08 — Cierre contable diario y mensual

**Entrega**
- **Diario**: `POSTING_DRAIN` + balanza diaria congelada (`daily_trial_balance`) + sello
- **Mensual**: cierre de período bajo candado `accounting:period-close:<YYYYMM>`, con checklist previo
- Conciliación cartera ↔ mayor sobre las cifras del sello

**Pruebas**
- [x] Balanza diaria cuadra: `Σ` cargos = `Σ` abonos
- [x] La balanza sellada no cambia al llegar un extemporáneo
- [x] El extemporáneo entra al primer período abierto marcado
- [x] Cierre mensual bloqueado si el diario no selló
- [x] Diferencia cartera↔mayor dispara `reconciliation-alert`
- [x] Cierre de período idempotente y bajo candado

---

## TK-09 — Cierre de bancos diario y mensual

**Entrega**
- `bank_accounts`, `bank_statement_lines`, `bank_matches`, `suspense_entries`, `bank_close_seals`
- Matching de tres pasadas: determinista, heurística, manual
- **Diario**: saldo mayor + partidas en conciliación = saldo del estado de cuenta
- **Mensual**: sello del período y reporte de partidas

**Pruebas**
- [x] Match determinista por clave de rastreo
- [x] Match heurístico dentro de tolerancia
- [x] No identificado va a cuenta puente y sale como partida en conciliación
- [x] La ecuación del cierre cuadra
- [x] Diferencia dispara la alerta
- [x] Cierre mensual consolida los diarios del período


---

## TK-06 — Proyección por evento y calendario de corte ✅

**El calendario de corte es del cierre, no de cartera.** Aunque para un no revolvente la cadencia coincida con el vencimiento de la cuota, el cierre **no** lee `installments.due_date`: deriva de la política del producto y de lo que aprendió por evento, y lo persiste en `cutoff_schedules`.

### El prerequisito que faltaba

`CreditAccountActivatedEvent` **no traía la cadencia ni el plazo**. Sin ellos el cierre no puede derivar nada y tendría que consultar cartera — justo lo que rompe el aislamiento. Se enriquece el evento con `paymentFrequency` y `termPeriods`, que cartera ya tiene a mano al activar (`config.getPaymentFrequency()`, `account.getAssignedTerm()`).

**Antes de tocarlo se verificó que los once consumidores toleran campos nuevos.** Diez llevan `@JsonIgnoreProperties(ignoreUnknown = true)`; wallet no, y por eso se escribió `ContratoDeEventosTest`, que ejercita **el mismo deserializador que usa el contenedor** y demuestra que tolera igual — Spring Kafka construye su `JsonDeserializer` con `JacksonUtils.enhancedObjectMapper()`, que ya desactiva `FAIL_ON_UNKNOWN_PROPERTIES`. Queda como guardia de regresión del contrato.

### Reglas de corte por producto

| Regla | Producto | De dónde sale la fecha |
|---|---|---|
| `INSTALLMENT_DUE_DATE` | `PERSONAL_LOAN`, `PAYROLL_LOAN`, `MICRO_LOAN`, `SME_LOAN` | La cadencia del plan anclada al alta: mensual, quincenal o semanal |
| `CYCLE_FROM_ACTIVATION` | `CREDIT_CARD`, `REVOLVING_LINE`, `DISTRIBUTOR_LINE` | Ciclo mensual anclado al **día** de activación |
| `DAY_OF_MONTH` | Configurable | Día fijo, igual para todo el producto |

### Pruebas — 13 contra Postgres real

| Prueba | Qué fija |
|---|---|
| Mensual, quincenal, semanal | 12 cortes al mes · **cada 14 días** · 52 al año |
| No corta más allá del plazo | Un préstamo a plazo no corta tras su última cuota |
| Tarjeta: ciclo desde el alta | El corte que **hoy no existe** en la plataforma |
| **Dos tarjetas, dos días de corte** | La raíz del problema de conciliación, fijada en prueba |
| Corte en inhábil | Se corre según `non_business_day_shift` |
| Límite nunca antes del corte | Invariante, incluso tras el corrimiento |
| Alta reentregada | No duplica el calendario |
| **Corte sellado inmutable** | No se reabre: el estado de cuenta no cambia tras enviarse |
| Consulta diaria | Sólo los agendados de ese día; el sellado no se repite |
| Saldo fuera de orden | No retrocede lo recordado |
| Cuenta liquidada | Terminal, sale de las corridas |


---

## TK-07 — Cierre diario de cartera ✅

`BusinessDayRunner` corre el día completo: planifica cada fase, la trabaja y la sella, avanzando por **dependencia y no por hora**.

**El día se recibe, no se lee del reloj.** Es lo que permite reproducir el cierre del 15 el día 20, sembrar historia, y recorrer el ciclo de vida de un crédito sin esperar meses reales.

### Qué hace el cierre y qué deliberadamente no

| Fase | Quién calcula | Por qué |
|---|---|---|
| `ACCRUAL` | **charges** | El cierre publica que la ventana está abierta. Meter aquí la aritmética del interés crearía una segunda fuente de verdad sobre el mismo número |
| `DELINQUENCY` | **credit-portfolio** | Ídem, y sólo corre con el devengo del día ya sellado |
| `CUTOFF` | **el cierre** | El corte es suyo. Sella el saldo y calcula el **pago mínimo** de revolventes, que es un número que nace del corte |

**El importe de la cuota de un producto a plazo no lo inventa el cierre.** Sale del plan de amortización, que es de cartera; duplicar la aritmética aquí produciría dos números que divergen al primer redondeo. El corte viaja con el saldo y cartera completa el exigible al proyectarlo de vuelta. Hay una prueba que lo fija.

### Un bug que la prueba destapó

**`CloseSeal.isIntact()` devolvía `false` para todo sello leído de la base.** La huella se calculaba sobre `toPlainString()` de los importes, y `NUMERIC(19,4)` devuelve siempre escala 4: se resumía `"20000"` al sellar y `"20000.0000"` al releer. La detección de manipulación quedaba inservible **justo cuando se usa** — al auditar un sello viejo. Se normaliza a la escala de la columna antes de resumir.

### Pruebas — 10

| Prueba | Qué fija |
|---|---|
| El día corre y sella | Las cuatro fases, en orden |
| El devengo abre ventana por cuenta | El cierre **pide**, no calcula |
| Sin saldo se omite | `SKIPPED` no bloquea el sello |
| Liquidada sale del cierre | Ni se materializa |
| El corte se sella el día que toca | Y sólo ese día |
| Pago mínimo sólo de revolventes | El cierre no inventa la cuota |
| Tras el corte, el ciclo sigue | Y **corrido si cae en domingo** |
| El sello lleva cifras y huella | Con la normalización correcta |
| El día es idempotente | Cero reprocesos, cero re-sellos |
| **30 días → 30 ventanas de devengo** | El requisito literal: cada devengamiento viene de un cierre |
