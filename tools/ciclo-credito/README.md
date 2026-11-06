# Ciclo de crédito de punta a punta

Corre, contra el stack local, **todos los escenarios de un crédito** por las operaciones reales y
entrega lo que produce cada uno: estados de cuenta en PDF, flujo mes a mes, pólizas, libro de cartera,
foto diaria, cargos, cortes y la vista paso a paso que los ata. Sólo se siembra configuración; nada se inserta.

**La siembra de demo no es su base.** La demo (`scripts/reinicia-y-siembra.sh`) tiene otro objetivo
—muchos datos, aleatorios y concordantes, para mostrar— y sus scripts no se importan aquí. El E2E arranca
de una plataforma virgen y siembra su propia configuración, fija y determinista; las personas, las
distribuidoras, sus clientes, las compras, las colocaciones, los pagos y la mora los produce la corrida.

```
python3 tools/ciclo-credito/corre.py preparar # plataforma virgen + configuración propia del E2E
python3 tools/ciclo-credito/corre.py simular  # suite «productos»: 2026-03-02 → 2026-11-01, entrega y veredicto al final
python3 tools/ciclo-credito/corre.py simular --suite distribuidoras   # 2025-01-01 → 2026-11-03, 10 distribuidoras
python3 tools/ciclo-credito/corre.py simular --suite CREDIT_CARD      # un solo producto, con las fechas de «productos»
python3 tools/ciclo-credito/corre.py simular --solo E01,E10 --hasta 2026-06-30
python3 tools/ciclo-credito/corre.py entregar ~/Documents/material-kredius/pruebas/<commit>/ciclo-credito-<ts>
python3 tools/ciclo-credito/corre.py veredicto ~/Documents/material-kredius/pruebas/<commit>/ciclo-credito-<ts>
python3 tools/ciclo-credito/corre.py reloj-real
```

La corrida **sale con código 1** si el veredicto encuentra algo fuera de lo esperado.

## Las pruebas, por producto

Cada producto prueba lo suyo en su **suite** (`escenarios.SUITES`):
- cada suite tiene sus escenarios, sus fechas, su semilla y su carpeta de evidencia;
- la evidencia guarda `suite.json`, y `entregar` y `veredicto` juzgan con los escenarios de esa suite.

| Suite | Fechas | Qué |
|---|---|---|
| `productos` | 2026-03-02 → 2026-11-01 | todos los productos menos la línea de distribuidora (40 escenarios) |
| `PERSONAL_LOAN`, `PAYROLL_LOAN`, `MICRO_LOAN`, `SME_LOAN`, `GROUP_LOAN`, `CREDIT_CARD`, `REVOLVING_LINE`, `BUSINESS_REVOLVING_LINE` | las de `productos` | los escenarios de un solo producto |
| `distribuidoras` | 2025-01-01 → 2026-11-03 | la línea de distribuidora por quincenas (cortes el 6 y el 21): 10 distribuidoras con líneas de $100,000 a $250,000 (9 al corriente, 1 en mora), 42 clientes, 119 colocaciones |

La línea de distribuidora es un modelo de microcréditos: cada colocación es de $1,000 a $10,000, a 6–24 quincenas, al
95 % anual, y la distribuidora se lleva el 18 % del interés que cobran los pagos de sus clientes. La línea corta el
6 y el 21 de cada mes, como el sistema de referencia; el interés de cada quincena se cobra en el corte, con el IVA de
cada cuota; el estado de cuenta sale al día siguiente y se paga el 18 (el del 6) o el 3 del mes siguiente (el del 21),
al día hábil siguiente si no lo es. Cada colocación paga en los cortes de la línea, la primera en el primer corte después
de colocar (12 pagos son 12 quincenas). El veredicto lo comprueba en cada distribuidora (`juzga_quincenal`):
- cortes el 6 y el 21, uno por quincena desde el alta, todos sellados;
- fecha límite el 18 o el 3 (o el hábil siguiente), a más tardar el día del corte siguiente;
- interés sólo en los cortes, y el IVA de cada corte igual al de las cuotas de los clientes;
- cuotas en quincenas seguidas desde el primer corte;
- un estado por corte, emitido al día siguiente, con su periodo desde el día siguiente al corte anterior;
- al corriente, el total a pagar es la suma de los pagos de sus clientes, al centavo, y nunca menos;
- 95 %, CAT de la línea 153.88 % y el de cada colocación entre 153.8 % y 154.5 % (Circular 21/2009);
- montos de $1,000 a $10,000;
- la comisión de la distribuidora a la manera del mercado: el plan de cada colocación lleva el 18 % de cada pago del
  cliente, plano y nunca por delante del interés acumulado; cada neteo es el del plan al escalón del día en que entró
  el dinero (18/14/11/7 %, nada después del tercer día), medido contra la fecha límite del corte; ningún estado pagado
  dentro de la escalera se quedó sin su comisión; sus estados traen la escalera y lo descontado, igual que el libro;
  y los escalones que el plan espera (D02 todo a tiempo, D03 a dos días, D05/D10/D11 con estados fuera de la escalera);
- ninguna colocación autorizada con un estado vencido sin pagar, y las rechazadas en la puerta que el plan espera
  (`colocaciones_rechazadas`), todas por la línea bloqueada;
- el moratorio de cada día = capital vencido de la foto × 36 % / 360, y en ningún otro día (`04f`);
- el caso de cobranza como el plan lo espera: contactos del agente, mensajes automáticos, promesas, convenios.

El simulador de STP del stack local juega una falla sólo con los importes que la corrida arma
(`arma_escenarios_stp`: $4,000.02, rechazo por PLD; $3,000.04, devuelta por el banco); cualquier otra orden se
liquida. Sin eso, uno de cada nueve pagos que calcula la plataforma (un lote de comisiones, un pago de fondeo) caía en
los centavos de una falla.

## Lo que siembra el E2E (y nada más)

`corre.py preparar` = `scripts/reinicia-entorno.sh --solo-plataforma` (baja todo, borra los volúmenes,
levanta, crea tópicos; las migraciones traen el catálogo de productos, las políticas de scoring y de
reserva y los calendarios) + `configura.py`:

| Qué | Con qué datos |
|---|---|
| Productos | Los 9 tipos activos, verificados; su configuración queda en `configuracion.json` de cada corrida (tasa, frecuencia, amortización, ciclo de corte, días para pagar, gracia, mínimo y **modo de interés**) |
| Estructura comercial | Región `R_E2E`, zona `Z_E2E`, sucursales `S_E2E_NTE` y `S_E2E_CTR`, dos ejecutivos por sucursal (`e2e.ejecutivo.*`) con su nodo y su asignación |
| Personal por rol | `e2e.cumplimiento` (OPS_SUPERVISOR), `e2e.comite` (UNDERWRITER, COMMITTEE), `e2e.cobranza` (COLLECTIONS_AGENT), `e2e.riesgo` (RISK_ANALYST) |

Las altas las hace la corrida (`altas.py`): cada persona entra por la app (OTP, KYC con documentos,
registro), cumplimiento la dictamina por la consola, el ejecutivo de su sucursal la toma y la solicitud
recorre scoring → mesa o comité si la política lo pide → oferta → contrato → firma con CLABE válida. Todo
lo variable sale del generador de la semilla: dos corridas dan las mismas personas con los mismos datos.

## Cómo se cobra el interés: diario o por periodo

Es configuración del producto (`billingCycle.interestAccrual`):

| Modo | Productos | Cómo |
|---|---|---|
| `DAILY` | tarjeta, línea revolvente de uso propio, línea empresarial, préstamos | cada noche, tasa / 360 sobre el capital de la foto del día (sin las cuotas por vencer de compras a meses sin intereses) |
| `PER_PERIOD` | línea de distribuidora | cada colocación nace con su plan en las quincenas de la línea; el interés de cada quincena es el del plan (tasa / 24 sobre el saldo de la colocación; la primera, por los días que se tuvo el dinero) y se carga **en el corte que cierra la quincena**, no antes; si la línea ya no debe capital, no se cobra, ni las quincenas de una colocación que una liquidación ya pagó (D06). La distribuidora entrega cada estado menos su comisión (18 % de lo que pagan sus clientes a tiempo; 14/11/7 % uno, dos y tres días tarde; nada después), que cartera netea contra el estado y sale del interés; su línea no coloca con un estado vencido sin pagar |

El moratorio es diario en ambos, sobre lo vencido y después de la gracia. La conciliación revisa cada
modo por separado (`04` diario, `04e` por periodo contra el plan).

## Cómo corre un día

1. El **reloj de negocio** de los 17 servicios que sellan hechos o deciden por la hora se pone en D
   (`/internal/test-support/clock`, sólo con `TEST_SUPPORT_ENABLED=true`; dispersión y tesorería incluidas: el
   despacho decide por hora de negocio cuándo vence una orden, y tesorería juzga una traza detenida por su «hoy»). Desde ese momento, todo lo que ocurre lleva fecha D: la
   activación, los asientos, los cargos, los pagos, las pólizas y el día de operación de cada orden STP
   (la fecha con que el libro de caja de tesorería lleva la disposición; sin mover el reloj del conector,
   todas las disposiciones de la simulación caían en el mes real y `BANCOS_1101` no cerraba ningún mes).
2. Las **operaciones de D** salen del plan (`escenarios.py`): altas por la app (OTP, KYC, solicitud,
   scoring, mesa o comité, oferta, contrato, firma), pagos por el canal de pagos, compras por wallet,
   colocaciones de distribuidora, salto de pago, diferimiento, y cobranza por el backoffice (promesa,
   reestructura, quebranto). Antes de mover el reloj se **asienta** el día: cartera consumió lo que se
   publicó y cada disposición de D ya tiene su orden STP registrada (la orden toma el día Banxico en que se
   registra; registrada después del cambio de día, una disposición que el mayor asentó el 30 salía el 1).
3. El reloj pasa a D+1 y corre **la noche de D**, por las mismas compuertas que el cron de las 00:05:
   cargos pide la foto a cartera y devenga sobre ella → cartera cierra el día (mora, cuotas) → closing
   corta a quien le toca → statement-service rinde los PDF → riesgo recalcula la reserva → cobranza
   corre sus jobs diarios (y los lunes, candidatos a quebranto). Fin de mes: facturación, las **liquidaciones del mes** (toda orden STP
   del mes liquidada y en el libro de caja de tesorería; el poller se consulta a pedido con
   `/internal/test-support/run-settlement-poll`, porque un mes simulado dura minutos y el poller pregunta cada
   tres) y el cierre del período contable. La noche del 4: liquidación de comisiones, y su dinero se asienta esa
   misma noche (los lotes salen con su fecha Banxico antes de que el reloj se mueva).

**El reloj sólo se mueve en reposo.** Antes de cada movimiento se espera a que cada servicio diga de sí mismo
(`/internal/test-support/quiescence`) que consumió todo lo publicado a sus tópicos y que sus colas propias no
guardan trabajo vencido: la cola de despacho de dispersión, el outbox de STP. El rezago de Kafka sólo dice que la
petición se consumió, no que la orden salió: un lote liquidado la noche del 4 esperó el tick del despacho mientras
el reloj llegaba al sábado, y una corrida lo pagó el viernes 5 y la otra el lunes 8 (D65). No llegar al reposo es
una falla de la corrida («asentamiento»).

**Dos corridas, la misma huella.** `huella.json` resume lo que la plataforma guarda al terminar, por dominio, con
llaves de negocio y su SHA-256: cada contrato por el escenario que lo abrió (el número es aleatorio en cada corrida),
cada orden STP por su fecha Banxico, importe y desenlace (el consecutivo de la clave sigue el orden de llegada).
`python3 tools/ciclo-credito/corre.py huellas <evidencia-1> <evidencia-2>` compara dos corridas desde plataforma
virgen y lista las filas que sólo tiene una.

Las fechas de corte y de pago salen del **ciclo de facturación del producto** (mensual, quincenal,
semanal, día fijo), no del script.

## Quién hace cada cosa

Cada decisión la toma una persona distinta, con su propia sesión de backoffice (las da de alta
`configura.py`). La consola toma el autor de la sesión, no
del cuerpo de la petición, así que el maker-checker se ejercita de verdad:

| Rol | Persona | Qué hace en la corrida |
|---|---|---|
| Oficialía de Cumplimiento (`OPS_SUPERVISOR`) | `e2e.cumplimiento@kredius.mx` | Resuelve la identidad y las listas de cada alta: cola → ficha → dictamen |
| Comité de Crédito (`UNDERWRITER`, `COMMITTEE`) | `e2e.comite@kredius.mx` | Decide las solicitudes que la política manda a la mesa o a comité; autoriza convenios y quebrantos |
| Gestión de Cobranza (`COLLECTIONS_AGENT`) | `e2e.cobranza@kredius.mx` | Promesas, propone convenios y quitas, registra la aceptación del deudor, pide el quebranto |
| Analista de riesgo (`RISK_ANALYST`) | `e2e.riesgo@kredius.mx` | Propone el programa de apoyo; lo autoriza administración |

La mesa de análisis se alcanza por la política: el buró simulado contesta un perfil en el límite (CURP
que empieza con `ZZMR`, FICO 660) y la banda MEDIA del scoring manda la solicitud a revisión humana.

**Atajo que queda**: el rol `DISTRIBUTOR`, el `BENEFICIARY` y el vínculo entre ellos se otorgan directo en
party-service (`altas.interno`), porque todavía no existe la pantalla de alta de distribuidora ni el registro de
cliente final.

## El veredicto

Cada escenario declara en `espera` cómo debe terminar (estatus, al corriente o días de atraso, tasa,
eventos del libro de cartera, pólizas, cuotas por estatus, etapa IFRS 9, caso de cobranza, acciones
que debieron ocurrir). `veredicto.py` lo lee de vuelta de los servicios al final.

La mora («al corriente», días de atraso) se juzga con la foto diaria del último día de la corrida (FIN), no
con la cuenta viva: el veredicto corre con el reloj ya de vuelta en la fecha real y una cuota que vence el día
siguiente a FIN se vería vencida aunque la corrida nunca llegó a ese día. En tarjetas y líneas, un pago cubre
sólo los estados emitidos antes de su fecha valor: pagar el total de un corte no adelanta el mínimo del
siguiente (E34).

Además exige:

- cada fila de `concilia.sql` con `fuera = 0` (libro = saldos en todas las cuentas, cargos = cartera,
  devoluciones y reversas aplicadas, pagos confirmados, devengo terminado, tasa del cargo = tasa pactada,
  cortes sellados, estados generados, estado = libro, atraso del estado = foto del corte, intereses = 1203,
  pólizas cuadradas, 1201 = cartera, quebrantos y recuperaciones con póliza, reserva por cuenta =
  contabilidad, 1290 = reservas, sin reserva en cuentas cerradas, etapa coherente con los días de atraso,
  ninguna cuenta viva sin tasa de reserva, cada mes terminado cerrado por un cierre conciliado, bancos nunca
  negativo, fondeo de tesorería = mayor, ningún mensaje en DLT, ningún estado ni cuota que venza en día inhábil,
  bancos `1101` = libro de caja de tesorería mes por mes, y cada movimiento de tesorería recibido por contabilidad);
- `scripts/verifica-contabilidad.py` con código 0;
- ninguna noche con cuarentena o cuentas retenidas, ningún error ni rechazo que el plan no espere.

El resultado queda en `veredicto.md` y `veredicto.json`.

Al terminar cada mes la corrida lo cierra (`POST /periods/{p}/close`). El cierre concilia antes de publicar:
cartera cerró el último día, riesgo midió la reserva a esa fecha y el mayor es igual a cartera, reservas y
calendarios de comisiones, crédito por crédito, el fondeo es el de tesorería y bancos (`1101`) es el libro de caja de
tesorería. Si responde 409 porque el último cierre del día, las reservas, el devengo del fondeo o el libro de caja
todavía no llegan a contabilidad, la corrida espera hasta 90 s; cualquier otra diferencia se anota tal cual. El
reporte de cada mes queda en `cierres/AAAAMM.json` dentro de la evidencia.

## Lo que entrega

`~/Documents/material-kredius/pruebas/<commit>/ciclo-credito-<ts>/`

| Qué | Dónde |
|---|---|
| Resumen de escenarios, períodos, noches y conciliación | `RESUMEN.md` |
| Por escenario: vista paso a paso (foto, cargos, libro, pólizas, pagos, cortes) | `escenarios/<clave>/paso-a-paso.md` |
| Por escenario: cada estado de cuenta, corte por corte (saldo inicial, colocaciones/compras, interés, IVA, moratorio, pagos, saldo, exigible, mínimo, vencido, pagado) | `escenarios/<clave>/flujo-mes-a-mes.md` y `.csv` |
| Distribuidoras: cada colocación, cuota por cuota (fin del periodo, interés del plan, interés cobrado ese día) | `escenarios/<clave>/planes-de-colocaciones.csv` |
| La configuración de productos que ejercitó la corrida | `configuracion.json` |
| Estados de cuenta | `escenarios/<clave>/estados-de-cuenta/*.pdf` |
| Pólizas (con sus líneas) | `escenarios/<clave>/polizas.csv`, `polizas.json` |
| Libro de cartera, foto diaria, cargos, cortes, plan de pagos | `escenarios/<clave>/*.csv` |
| Riesgo, cobranza, comisiones | `escenarios/<clave>/*.json` |
| Balanza y resumen contable por período | `balanza-YYYYMM.csv`, `resumen-contable-YYYYMM.json` |
| Conciliación de la cadena (libro = saldos, cargos = cartera, foto = cargo, corte = foto, estado = libro, contabilidad = fuente) | `conciliacion.md` |
| Lo que hizo la corrida, en orden | `bitacora.jsonl`, `escenarios/<clave>/linea-de-tiempo.md` |

## Determinismo

Fecha de inicio fija (un lunes), número de días fijo, semilla fija para las personas (CURP, teléfonos,
nombres), importes y días del plan escritos. Dos corridas sobre un entorno virgen producen los mismos
números. Lo único que cambia son los identificadores que generan los servicios.

## Lo que no hace

- No usa `shift-due-date` ni `age-schedule` ni `rewind-accrual-schedules`: la mora nace de no pagar
  y el interés de la foto de cada día.
- No corre a la vez que la cadena real de las 00:05: si la simulación cruza esa hora, los crones
  encuentran los días ya cerrados y no hacen nada, pero conviene evitarlo.
- Al terminar restablece el reloj real. La cadena real sigue sola desde el día siguiente a `--hasta`
  (cierra cada día pendiente, del más viejo a ayer).

## Archivos

- `configura.py` — la configuración propia del E2E (productos verificados, estructura mínima, personal por rol).
- `altas.py` — las altas de la corrida: personas, distribuidoras, sus clientes, solicitudes.
- `escenarios.py` — el plan, como datos, con cómo debe terminar cada escenario.
- `motor.py` — el día simulado: reloj, operaciones, noche, fin de mes.
- `entrega.py` — lo que se entrega, leído de vuelta de los servicios y de la base.
- `concilia.sql` — las comprobaciones de la cadena (una fila por comprobación, columna `fuera` en cero).
- `veredicto.py` — cada escenario contra lo esperado, y la cadena en cero; código de salida.
- `huella.py` — la huella de la corrida y la comparación de dos corridas.
- `puente.py` — un contenedor `socat` que expone cada servicio en un puerto local; los servicios de
  dominio no publican puertos. **No lo reinicies mientras corra una simulación o la demo**: `up()` baja el
  puente que está en uso.

## Cobertura por producto

| Producto | Escenarios |
|---|---|
| Préstamo personal | al plazo (E01), atraso y recuperación (E02), nunca paga → promesa → quebranto → recuperación (E03), reestructura (E04), liquidación anticipada (E05), pago devuelto (E06), BNPL (E16), salto de pago (E17), mesa → rechazo (E18), se queda en mesa (E19), originado después (E20), quita y liquidación (E27) |
| Nómina (quincenal) | al plazo (E07), liquidación anticipada (E29), quincena tarde (E30), quebranto (E21) |
| Microcrédito (semanal) | semana tarde (E08), liquidación anticipada (E31), quebranto (E22) |
| PyME | comité y al plazo (E09), liquidación anticipada (E32), reestructura (E23) |
| Grupal (semanal, alemán) | programa de apoyo (E28), semana tarde (E33) |
| Tarjeta | mínimo y luego total (E10), meses sin intereses (E11), cae en mora y se pone al día (E34), quebranto (E24) |
| Línea revolvente (uso propio) | saldo cero y reuso (E12), cae en mora y se pone al día (E25), compras mes a mes pagando el total (E36) |
| Línea empresarial | exigible de cada corte (E13), mora y reestructura de tasa (E37) |
| Línea de distribuidora (suite `distribuidoras`, 12 distribuidoras, 82 clientes, 183 colocaciones de 12 a 24 quincenas) | diez clientes que renuevan y suben de monto (D01), paga tres días antes de la fecha límite (D02), paga dos días después, dentro de la gracia (D03), colocación rechazada por PLD (D04), deja dos cortes, la contacta cobranza y se pone al día (D05), liquida la línea y vuelve a colocar (D06), nueva en 2026 (D07), colocación devuelta por el banco (D08), dieciséis clientes de $1,000 a $10,000 (D09), deja de pagar en marzo de 2026: promesa rota, bloqueada con tres colocaciones rechazadas, reestructura que ignora y quita con la que liquida (D10), cae en mora, bloqueada, promete y cumple dentro de los 30 días (D11), mora con voluntad de pago: reestructura aceptada y se regulariza (D12) |
