# La siembra de demostración — qué hace, en qué orden y qué garantiza

**Punto de entrada:** `./scripts/reinicia-y-siembra.sh`
**⚠️ Borra los volúmenes.** Todo lo que haya en la base se pierde.

```bash
./scripts/reinicia-y-siembra.sh                                  # 100 clientes, 10 distribuidoras, 8 meses de vida (~1 h 15 min)
./scripts/reinicia-y-siembra.sh --mini                           # 30 clientes, 5 distribuidoras, 4 meses (~25 min)
./scripts/reinicia-y-siembra.sh --build                          # reconstruye las imágenes antes, en serie
./scripts/reinicia-y-siembra.sh --clientes 45 --distribuidoras=5 --meses=5
```

Son **datos mock para enseñarle al cliente**: muchos y con historia, pero correctos, porque cada
saldo tiene sus asientos, cada póliza su hecho y cada estado de cuenta su libro. Es un objetivo
distinto del de las **pruebas E2E del ciclo de crédito** (`tools/ciclo-credito/`), y no son la base
de esas pruebas: el E2E arranca de la plataforma virgen (`reinicia-entorno.sh --solo-plataforma`) con
su propia configuración fija (`tools/ciclo-credito/configura.py`) y sus propias altas
(`tools/ciclo-credito/altas.py`), y no importa ningún script de esta siembra. Lo único que se comparte
es el motor que corre el reloj día por día: la demo lo usa, con su personal (`motor.PERSONAL_DEMO`),
para hacer vivir su cartera.

---

## La regla que gobierna todo esto

**Nada se inserta. Todo se origina.** Cada persona pasa por OTP, KYC, alta, solicitud, scoring,
decisión, oferta, contrato y firma, igual que lo haría desde el teléfono. Cada pago entra por el
canal de pagos, cada mora sale de **no pagar** con el reloj de negocio corriendo día por día, cada
póliza de que contabilidad consuma un evento con la fecha del hecho.

No es purismo. Una inserción directa produce estados que el dominio no genera —una distribuidora sin
rol, un beneficiario sin vínculo, una revolvente con calendario de cuenta— y ésos son exactamente
los datos que hacen confiar en una pantalla que miente. Si un endpoint no acepta lo que se le manda
aquí, tampoco lo aceptaría la app; que la siembra falle es información.

---

## El orden, y por qué es ése

| # | Paso | Script | Por qué va ahí |
|---|---|---|---|
| 1 | Entorno virgen con configuración: (reconstruir imágenes), bajar y **borrar volúmenes**, levantar, recargar el gateway, esperar, estructura comercial, personal | `reinicia-entorno.sh` → `seed-sales-org.py`, `seed-commercial-staff.py`, `seed-demo-staff.py`, `01-seed.sh` del backoffice | Todo lo demás cuelga de la estructura y se autentica contra identity. nginx cachea las IPs al cargar; recrear contenedores las cambia |
| 2 | **El reloj de negocio se pone meses atrás** | `tools/ciclo-credito/corre.py reloj AAAA-MM-DD` | La fecha de activación decide desde cuándo devenga y cuándo corta cada crédito: para tener historia hay que originar en el pasado, no inventar asientos |
| 3 | Clientes y cartera | `seed-portfolio.py --mora 0` | Cada cliente se cuelga de un ejecutivo, que ya debe existir. Sin envejecer nada a mano |
| 4 | Documentos del expediente | `seed-expedientes.py` | Necesita prospectos con solicitud |
| 5 | **Distribuidoras B2B2C** | `seed-distribuidoras.py --sin-envejecer` | Cuelgan de ejecutivos (paso 1); colocan el día de la originación |
| 6 | Tarjetas y líneas | `seed-revolventes.py` | Compras, meses sin intereses, BNPL, salto de pago |
| 7 | Perfiles fiscales | `seed-perfiles-fiscales.py` | Antes del ciclo, que es quien factura |
| 8 | **Ciclo de vida, día por día hasta ayer** | `seed-ciclo-credito.py --desde … --hasta …` | Cada día: pagos de quien paga; cada noche: foto → devengo → cierre → corte → estados de cuenta → riesgo → cobranza; fin de mes: facturación y cierre contable; día 5: comisiones. Al terminar, reloj real |
| 9 | Navegación por nivel | — | Para que la bitácora tenga entradas de todos los perfiles |
| 10 | Verificaciones | `verifica-identidad-auditoria.py`, `verifica-distribuidoras.py`, `repara-cartera-sin-ejecutivo.py` + `verifica-cuadre.py`, `verifica-contabilidad.py`, `tools/ciclo-credito/concilia.sql` | |
| 11 | Respaldo | `respalda-siembra.sh` | La siembra cuesta una hora; perderla por un `down -v` es evitable |

**El reloj va antes de originar, y el ciclo después de todo lo que origina.** Lo que se origine con
el reloj ya devuelto al presente nace hoy y no tiene historia que contar.

---

## Qué produce cada paso

### `seed-portfolio.py` — la cartera B2C/B2B

Recorre el journey completo por cliente y **deja a propósito solicitudes en varios estados**: una
bandeja donde todo está desembolsado no ejercita ni la mesa de análisis ni los filtros.

| Desenlace | Peso |
|---|---|
| desembolsado | 55 % |
| en revisión | 13 % |
| ofertado | 12 % |
| firmando | 10 % |
| solicitado | 10 % |

Mezcla de producto: `PERSONAL_LOAN` 62 % · `PAYROLL_LOAN` 24 % · `SME_LOAN` 14 %. Los montos **se
leen del catálogo**, no se escriben a mano: escribirlos producía solicitudes fuera de los límites
del producto y el rechazo llegaba tres pasos después.

**La forma del monto: muchos chicos y pocos grandes.** Un uniforme sobre el rango completo da una
cartera de puros créditos al máximo; un uniforme sobre el tercio bajo —lo que había— da la
deformación contraria: todos parecidos y ninguno grande, con la cartera PyME pesando casi nada. Se
usa un uniforme al cuadrado sobre el 85 % del rango, que concentra abajo y deja cola:

| Producto | Mediana | Media |
|---|---|---|
| `PERSONAL_LOAN` | ~20 000 | ~29 500 |
| `PAYROLL_LOAN` | ~25 000 | ~29 700 |
| `SME_LOAN` | ~1 095 000 | ~1 440 000 |

Con 100 clientes eso coloca ~12.5 MDP, que tras los pagos y quebrantos del paso 10 quedan cerca de
11 MDP — más las líneas de las distribuidoras. Es también lo que hace que los tramos de monto del
tablero tengan algo que separar: un PyME grande pesa lo que cincuenta personales.

> **La decisión de mesa es parte del journey.** La política de scoring de PyME deja la banda de
> auto-aprobación inalcanzable a propósito: **todas** sus solicitudes van a comité. Hasta que esto
> se corrigió, el script se rendía ahí y **ningún crédito PyME llegaba a existir** — las únicas
> solicitudes grandes de la cartera ($50 k a $5 M) se quedaban paradas y la cartera valía 2 MDP en
> vez de once. Ahora la decisión se pide por el mismo endpoint que aprieta un analista en la
> consola. Los casos que deben quedarse esperando los pone `llenar_mesa` al final, contados.

### `seed-distribuidoras.py` — el B2B2C

Diez distribuidoras, cada una con su línea revolvente, cinco beneficiarios y sus colocaciones.

**Una distribuidora = una cuenta de crédito.** Sus «créditos» son colocaciones: disposiciones de su
única línea a nombre de un beneficiario. Sembrar una cuenta por beneficiario produciría cien
deudores en vez de diez y la cartera dejaría de sumar.

**Cada colocación lleva su propia tabla de amortización** (`scheduleId = dispositionId`), a su
plazo — 6, 9, 12, 18 o 24 meses. Es la mecánica de una tarjeta con compras a meses, y es lo único
que le da a una línea algo que vencer.

**Del beneficiario, sólo el expediente y su vínculo.** No tiene cuenta de crédito, ni mora, ni ECL,
ni reporte a buró: quien responde por el saldo íntegro es la distribuidora.

Los perfiles sembrados deciden cuánto coloca cada distribuidora (con disponible, poco dispuesta,
línea agotada, sin disponer). **Su mora ya no se siembra aquí**: con `--sin-envejecer` ningún
calendario se retrocede; la produce el ciclo de vida (paso 8) cuando a una distribuidora le toca
dejar de pagar sus cortes quincenales, y los días de atraso los cuenta la foto diaria de cartera
desde la cuota de colocación más vieja sin pagar.

> **La mora no se escribe: se produce.** Un DPD escrito a mano se vería idéntico en la pantalla sin
> haber pasado por la regla que lo produce, y entonces no probaría nada. Retroceder un calendario
> (`age-schedule`) producía DPD reales pero intereses, cortes y estados de cuenta incoherentes con
> ellos: por eso se dejó de usar en la demo (sigue disponible para la siembra suelta).

### `seed-ciclo-credito.py` — que la cartera viva y la contabilidad tenga qué contar

Corre el reloj de negocio **día por día** desde la fecha en que se originó la cartera hasta ayer, con
el motor de `tools/ciclo-credito/motor.py` y un plan aleatorio de semilla fija, repartido **por
familia** (préstamos, tarjetas y líneas, distribuidoras) para que cada una tenga de todo: una parte
liquida, otra paga con algún atraso (y paga moratorios), otra deja de pagar y se recupera (los
revolventes pagan lo que dejaron de pagar y siguen), otra negocia (promesa, reestructura), otra cae en
mora al final de la historia y otra nunca paga (y, con siete meses, se quebranta). Las tarjetas y
líneas compran cada pocas semanas (alguna compra a meses en tarjeta) y las distribuidoras colocan a
sus clientes a lo largo de la historia. Cada noche corre la
misma cadena que el cron de las 00:05 —foto, devengo, cierre de cartera, corte, estados de cuenta,
riesgo, cobranza— y a fin de mes la facturación y el cierre del período contable.

Ya no usa `rewind-accrual-schedules` ni `run-daily-accrual?date=` ni `shift-due-date`: el interés
sale de la foto de cada día, la mora de no pagar, la reserva del recálculo nocturno. Por eso la
cadena entera concilia al terminar (`tools/ciclo-credito/concilia.sql`): libro = saldos, cargos =
cartera, foto = cargo, corte = foto, estado de cuenta = libro, contabilidad = fuente.

---

## Las verificaciones

Una siembra que no se verifica es una siembra en la que no se puede confiar. **El modo de fallar de
estos scripts no es tronar: es dejar la mitad de las cosas y terminar con código 0.**

`verifica-cuadre.py` comprueba que **el tablero, el árbol comercial y el listado digan el mismo
número**. Tres pantallas leen la misma cartera por caminos distintos y cuando no coinciden nadie
sabe cuál creer:

| | Qué |
|---|---|
| **1** | El capital del tablero = la suma del listado paginado |
| **2** | Ningún crédito activo sin ejecutivo ni sin sucursal sellada |
| **3** | El árbol comercial sumado por rama = el tablero |
| **4** | La suma por producto = el capital colocado — una sola cartera, no dos |

> **Un crédito sin ejecutivo no cuelga de ninguna rama**, así que el árbol suma menos que el tablero
> y no hay forma de ver por qué. Lo causaba un fallo de codificación: la búsqueda del cliente metía
> el apellido crudo en la URL y `urllib` la codifica en ASCII, así que **cada apellido con acento
> reventaba** —cinco de los veintidós— y el 23 % de la cartera se quedaba huérfana. El error se
> tragaba en un `except` y la siembra terminaba con código 0.
>
> `repara-cartera-sin-ejecutivo.py` arregla lo ya sembrado sin volver a sembrar: **recupera la plaza
> de la CURP** (posiciones 12–13 son la clave del estado), que es la misma que usó la siembra para
> elegir sucursal, así que el crédito vuelve a la rama que le tocaba en vez de repartirse con una
> heurística que nadie podría auditar.

`verifica-distribuidoras.py` comprueba, contra la API:

| | Qué |
|---|---|
| **S1** | Hay distribuidores, con código y party resoluble, y el BFF los sabe listar |
| **S2** | Una distribuidora, **una** cuenta de crédito |
| **S3** | Los beneficiarios tienen expediente y rastro de quién los capturó |
| **S4** | **Ningún beneficiario tiene cuenta de crédito** — protege el modelo entero |
| **S5** | Hay variedad: al corriente, en mora, sin disponer |
| **S6** | Las colocaciones están atribuidas por `promoterCode` |

---

## Trampas conocidas

**La lista de imágenes a reconstruir se quedaba corta sola** — ya no hay lista. Fallaba del peor
modo: el servicio arranca, contesta, y sirve código viejo. Un endpoint agregado hace un rato
devuelve 404 y parece un problema de rutas; un campo nuevo del evento llega nulo y el consumidor
cae a su valor por defecto sin quejarse. Se perdió una tarde con `wallet` y `commission`; después
volvió a pasar con `beneficiary-service` —que llevaba semanas con una imagen que ni compilaba—,
`payments-service`, `channels-service`, `configuration-service` y el propio `gateway-service`, cuya
imagen era de junio contra un fuente de agosto.

Ahora la lista **se deriva de `docker-compose.yml`**: entra todo servicio con sección `build`, y
agregar uno nuevo al compose lo mete en la reconstrucción sin que nadie tenga que acordarse. No
cuesta lo que parece — Docker cachea por contenido, así que lo que no cambió se resuelve en
segundos.

**El respaldo automático del paso 13 nunca corrió.** `respalda-siembra.sh` moría en
`ARCHIVO: unbound variable`: `"$ARCHIVO…"` hacía que bash tomara los bytes de los puntos
suspensivos como parte del nombre de la variable, y con `set -u` eso aborta. El fallo quedaba
escondido tras el `|| echo "(respaldo omitido)"` del orquestador, así que la siembra decía haber
respaldado y no había ni un archivo en `./respaldos`. Corregido con `${ARCHIVO}…`.

**Una persona arrastra dos identificadores.** `prospectId` y `partyId` son distintos, y cada dominio
habla en uno:

| Habla en `prospectId` | Habla en `partyId` |
|---|---|
| `credit_accounts.obligor_party_id` | `party.parties` |
| `commission.credit_promoter_assignments` | `party.party_roles` |
| `org_units.party_ref` | `party.party_relationships` |
| El sujeto del JWT móvil (`X-User-Id`) | |

Confundirlos **no truena**: devuelve 404 o una consulta vacía, y el script lo da por bueno. Se
resuelve con `GET /parties/by-prospect/{id}`.

**`X-User-Id` siempre en las llamadas internas.** `credit-portfolio` autentica con esa cabecera;
sin ella responde 401, y como los helpers se tragan los errores para no tumbar la siembra, la
consulta falla en silencio.

**`/internal/*` no se enruta desde el gateway** — es soporte de pruebas, no API. Se llama por la red
de Docker. Llamarlo por el gateway devuelve 404 y el script lo daría por hecho.

**Cada sembrador tiene su propia semilla fija, y las tres son distintas.** `seed-portfolio.py` usa 20260811,
`seed-distribuidoras.py` 20260812 y `seed-revolventes.py` 20260813: con la misma semilla los tres generaban
las mismas personas, y el dominio —con razón— rechaza una CURP o un teléfono ya registrados con 409 (el 2 de
noviembre se perdieron así cuatro de los doce escenarios revolventes y un beneficiario). Una semilla fija
reproduce la misma cartera en un entorno virgen; para sembrar dos veces sobre el mismo entorno hay que pasar
`--semilla` distinta. `seed-revolventes.py --hoy` fecha el programa de apoyo con el día de negocio en que se
siembra (el reloj está meses atrás), no con el del host.
