# Los ciclos de facturación vienen del producto — análisis

> Verificado leyendo código el **2026-10-14**. Cada afirmación lleva archivo y línea.

## La pregunta

> *«Los ciclos deben venir por configuración del producto, ya que tiene que tener periodicidad de
> pago acorde se configure al producto: semanal, mensual, quincenal.»*

## La respuesta corta

Tiene razón, y el hueco es **más profundo** que la periodicidad: el motor de cortes está construido
y probado, y **nadie lo alimenta ni lo dispara**.

| # | Hallazgo | Evidencia |
|---|---|---|
| 1 | 🔴 **El ciclo de un revolvente es mensual a fuerza**, diga lo que diga el producto | `CutoffScheduleDeriver:85` → `case CYCLE_FROM_ACTIVATION -> activatedOn.plusMonths(cycle)` |
| 2 | 🔴 **`closing-service` no consume NADA.** Cero `@KafkaListener`, cero endpoints, `adapter/in/` vacío | `find .../adapter/in -type f` → **0 archivos** |
| 3 | 🔴 **Nada dispara un día de cierre.** Cero `@Scheduled`; `BusinessDayRunner` sólo tiene llamadores de prueba | `grep -rn "@Scheduled" closing-service/src/main` → vacío |
| 4 | 🔴 **El README afirma lo contrario** | dice *«Consume: `credit-portfolio.credit-account-activated` (con cadencia y plazo…)»* — y no existe ese consumidor |
| 5 | 🔴 **El pago mínimo es una constante** del 5 %, igual para todos los productos | `CutoffPhaseProcessor:43` → `MINIMUM_PAYMENT_RATE = 0.05` |
| 6 | ⚠️ **`PaymentCadence.from` cae a MONTHLY en silencio** ante nulo o basura | `PaymentCadence:33` |

---

## 1 · El ciclo de un revolvente ignora la configuración

`CutoffRule` tiene cuatro reglas y **sólo una** honra la cadencia:

| Regla | Qué hace hoy | Honra la periodicidad |
|---|---|---|
| `INSTALLMENT_DUE_DATE` | `PaymentCadence.from(profile.getPaymentFrequency()).advance(activatedOn, cycle)` | ✅ sí |
| `CYCLE_FROM_ACTIVATION` | `activatedOn.plusMonths(cycle)` | 🔴 **no — mensual fijo** |
| `DAY_OF_MONTH` | día fijo del mes | 🔴 no, y por construcción no puede |
| `NONE` | sin corte | — |

El javadoc del enum lo dice sin disimulo: *«Revolvente: ciclo **mensual** anclado al día de
activación»*. Así que **una tarjeta configurada como `WEEKLY` o `BIWEEKLY` corta mensual**, y nada
falla: el calendario se deriva, se persiste y se sella con la periodicidad equivocada.

Lo que agrava el caso es que el dato **sí está configurado y sí viaja**:

* `credit_product.payment_frequencies` declara el conjunto permitido por producto, con
  `CHECK (payment_frequency IN ('WEEKLY','BIWEEKLY','MONTHLY'))`.
* `credit_product_definitions.default_payment_frequency` lleva el de por defecto.
* `CreditAccountActivatedEvent:49` **publica `paymentFrequency`** junto con `termPeriods`,
  `nominalRate` y `principalBalance`.
* `AccountCloseProfile:36` **tiene la columna** `payment_frequency` para recibirlo.

Es decir: el productor lo publica, el consumidor tiene dónde guardarlo, y el derivador lo ignora
justo en la rama de los revolventes — que son los únicos productos para los que un «ciclo de
facturación» significa algo.

## 2 · Y nadie alimenta el motor

Esto es lo que convierte el punto anterior en algo secundario:

```
services/closing-service/src/main/java/.../infrastructure/adapter/in/
├── api/          ← vacío
└── messaging/    ← vacío
```

`AccountProjectionService.onAccountActivated(...)` está **escrito, es idempotente y materializa el
calendario completo** al activar una cuenta. No tiene **ningún llamador**. Lo mismo con
`BusinessDayRunner.runBusinessDay(...)`: sólo lo invoca `DailyClosingIT`.

Consecuencia medida: `account_close_profiles` se puebla **únicamente desde las pruebas**, que llaman
al repositorio directo. En producción la tabla nace vacía y se queda vacía, así que el derivador
recorre cero cuentas y el cierre diario no tiene nada que cerrar.

> Es el mismo patrón que esta rama ya encontró tres veces: un caso de uso completo, probado contra
> Postgres real, **sin un solo llamador de producción**. `recordIdentityVerdict` era igual, y
> `UIFReport` no tenía escritor. Compila, pasa, y no hace nada.

## 3 · El pago mínimo no es configurable

```java
/** Porcentaje del saldo exigible como pago mínimo de un revolvente. */
private static final BigDecimal MINIMUM_PAYMENT_RATE = new BigDecimal("0.05");
```

Cambiar el mínimo de un producto exige un despliegue, y **es el mismo 5 % para todos**. La
infraestructura para configurarlo ya existe y está sin usar: `close_cycle_policies` tiene
`scope_type`/`scope_value`, `version`, `status` y `effective_date` — o sea, política por producto,
versionada y con vigencia. Le falta la columna.

## 4 · Y el silencio de la cadencia por defecto

```java
public static PaymentCadence from(String value) {
    if (value == null || value.isBlank()) return MONTHLY;
    return switch (value.trim().toUpperCase()) { … default -> MONTHLY; };
}
```

«No configurado» y «configurado mensual» producen el mismo resultado y son hechos distintos. Con el
hueco 2 encima, **toda** cuenta tendría frecuencia nula y por tanto mensual — y el log no diría nada.

---

## Lo que hay que construir

En orden de dependencia: sin el primero, los demás no cambian nada.

| # | Qué | Dónde |
|---|---|---|
| **1** | **Los dos consumidores que el README ya promete**: `credit-account-activated` (puebla el perfil con cadencia y plazo, y materializa el calendario) y `balance-updated` (mantiene los saldos con los que se sella el corte) | `closing` · `adapter/in/messaging` |
| **2** | **El ciclo avanza con la cadencia del producto**, también en `CYCLE_FROM_ACTIVATION`: semanal, quincenal o mensual | `CutoffScheduleDeriver` |
| **3** | **«Sin configurar» deja de ser «mensual»**: se distingue y se grita | `PaymentCadence` + el derivador |
| **4** | **El pago mínimo sale de la política del producto**, versionada y con vigencia | `close_cycle_policies` + `ClosePolicy` + `CutoffPhaseProcessor` |
| **5** | **Un disparador para el día de cierre** — hoy no hay ninguno | `closing` · endpoint de operación |
| **6** | **Corregir el README**, que hoy afirma consumidores que no existen | `closing-service/README.md` |

## Lo que NO cambia

* **El cierre sigue sin consultar cartera en línea.** La cadencia llega en el evento de activación y
  se guarda; el aislamiento de la ventana de cierre no se toca. Es la razón por la que `CutoffRule`
  existe en vez de leer `installments.due_date`, y sigue siendo válida.
* **Un corte sellado sigue siendo inmutable.** Cambiar la cadencia de un producto no reescribe los
  cortes ya sellados de una cuenta viva: se aplica desde el siguiente ciclo. Lo contrario sería
  reescribir el pasado de un estado de cuenta que el cliente ya recibió.
