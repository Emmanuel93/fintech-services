# La siembra de demostración — qué hace, en qué orden y qué garantiza

**Punto de entrada:** `./scripts/reinicia-y-siembra.sh`
**⚠️ Borra los volúmenes.** Todo lo que haya en la base se pierde.

```bash
./scripts/reinicia-y-siembra.sh                                  # 100 clientes, 10 distribuidoras
./scripts/reinicia-y-siembra.sh --sin-build                      # si las imágenes están al día
./scripts/reinicia-y-siembra.sh --clientes 45 --distribuidoras=5
```

---

## La regla que gobierna todo esto

**Nada se inserta. Todo se origina.** Cada persona pasa por OTP, KYC, alta, solicitud, scoring,
decisión, oferta, contrato y firma, igual que lo haría desde el teléfono. Cada pago entra por el
canal de pagos, cada mora sale de correr el envejecido, cada póliza de que contabilidad consuma un
evento.

No es purismo. Una inserción directa produce estados que el dominio no genera —una distribuidora sin
rol, un beneficiario sin vínculo, una revolvente con calendario de cuenta— y ésos son exactamente
los datos que hacen confiar en una pantalla que miente. Si un endpoint no acepta lo que se le manda
aquí, tampoco lo aceptaría la app; que la siembra falle es información.

---

## El orden, y por qué es ése

| # | Paso | Script | Por qué va ahí |
|---|---|---|---|
| 1 | Reconstruir imágenes | — | Ver §Trampas |
| 2 | Bajar y **borrar volúmenes** | — | |
| 3 | Levantar el stack, recargar el gateway, esperar | — | nginx cachea las IPs al cargar; recrear contenedores las cambia |
| 4 | Estructura comercial | `seed-sales-org.py` | 4 regiones, 8 zonas, 22 sucursales, 52 ejecutivos. Todo lo demás cuelga de aquí |
| 5 | Un responsable por nivel | `seed-commercial-staff.py` | Para poder probar el alcance por subárbol |
| 6 | Roles funcionales | `seed-demo-staff.py` | Analista, ejecutivo, comité, producto — el acceso rápido de la consola |
| 7 | Clientes y cartera | `seed-portfolio.py` | Cada cliente se cuelga de un ejecutivo, que ya debe existir |
| 8 | Documentos del expediente | `seed-expedientes.py` | Necesita prospectos con solicitud |
| 9 | **Distribuidoras B2B2C** | `seed-distribuidoras.py` | Cuelgan de ejecutivos (paso 4) y su mora debe existir antes del paso 10 |
| 10 | Ciclo de vida | `seed-ciclo-credito.py` | Devengo, pagos, mora, promesas, reestructuras, quebrantos |
| 11 | Navegación por nivel | — | Para que la bitácora tenga entradas de todos los perfiles |
| 12 | Verificaciones | `verifica-identidad-auditoria.py`, `verifica-distribuidoras.py`, `repara-cartera-sin-ejecutivo.py` + `verifica-cuadre.py` | |
| 13 | Respaldo | `respalda-siembra.sh` | La siembra cuesta una hora; perderla por un `down -v` es evitable |

**El 9 antes del 10 no es cosmético.** Los atrasos de las distribuidoras tienen que estar puestos
cuando corra el envejecido; al revés, las diez salen al corriente y el caso «distribuidora morosa»
no existe.

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

Los diez estados sembrados, uno por distribuidora:

| Estado | Cómo se produce |
|---|---|
| Al corriente, con disponible | coloca y paga |
| Al corriente, poco dispuesto | coloca menos |
| Línea agotada | coloca cerca del límite |
| DPD 30 / 60 / 90 | se envejece el calendario de una colocación 60 / 90 / 120 días |
| En cobranza con promesa | atraso + promesa de pago |
| Reestructurada | convenio ejecutado |
| Quebrantada | atraso de 230 días |
| Aprobada sin disponer | línea activa, saldo cero |

> **La mora no se escribe: se produce.** No se pone un número de días en la cuenta; se corre el
> calendario de una colocación hacia atrás y se deja que el envejecido **cuente** los días desde la
> cuota vencida más antigua. Un DPD escrito a mano se vería idéntico en la pantalla sin haber
> pasado por la regla que lo produce, y entonces no probaría nada.
>
> Por eso los DPD observados son 29, 39, 44, 59, 89 y 199 y no redondos: el envejecimiento menos el
> mes de gracia de la primera cuota. Que no sean redondos **es la señal de que son reales**.

### `seed-ciclo-credito.py` — que la contabilidad tenga qué contar

Devengo de 30 días, liquidaciones, abonos parciales, mora, promesas, reestructuras, quebrantos y la
reconciliación de la sucursal de origen. Sin esto la contabilidad enseña media balanza en ceros y no
se puede distinguir «esta cuenta no se usa» de «esta cuenta no se está posteando».

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

**La semilla es nueva en cada corrida.** Repetirla genera las mismas CURP y teléfonos, y el dominio
—con razón— rechaza al segundo con 409.
