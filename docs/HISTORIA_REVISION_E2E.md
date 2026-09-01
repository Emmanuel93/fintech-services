d49534a  READMEs al día, y notifications con las tres tablas cruzadas

El de notifications se reescribe sobre lo que hay, no sobre lo diseñado: los **doce** tópicos que
consume con qué hecho es cada uno y qué avisa, los **quince** EventType, y el cruce de las tres
piezas que un aviso necesita —consumo, política, plantilla—.

Cruzarlas es lo que dice qué funciona de verdad, y el resultado no es cómodo: **de quince tipos de
evento, salen cuatro**.

  · los ocho `COLLECTION_*` no tienen política ni plantilla, así que `dunning-requested` y
    `payment-thanks` se consumen, se procesan y mueren en un WARN — la ruta de cobranza entera
  · `DISBURSEMENT_COMPLETED`, `INSTALLMENT_PAID` y `LOAN_SETTLED` tienen política **sin**
    plantilla: el aviso llega al último paso y se cae al buscar qué decir. Es el peor de los tres
    estados, porque todo lo demás parece bien
  · dos de las tres claves de backoffice no las emite nadie

Las dos ausencias son deliberadas —el sistema no inventa canal ni texto— pero el efecto no lo es.
Cerrarlo es sembrar filas, no escribir código.

Y queda escrito el riesgo del diseño con su número: consumir hechos ajenos redeclarando payloads
ata este servicio al **esquema** de once tópicos, y hay **una** prueba de contrato de once.

Los demás:

  · **party** — `party.executive-assigned` como hecho, con por qué lleva los nombres, por qué la
    clave es el ejecutivo y por qué se publica después de guardar
  · **credit-product** — `republish`, y por qué se reemite el catálogo al arrancar: una
    instalación desde cero no podía originar ni un crédito
  · **origination** — la CLABE se validaba donde se paga y no donde entra; BNPL vive en el
    contrato y su tope se aplica en el destino, no en el camino
  · **credit-portfolio** — las tres reglas corregidas: BNPL como solicitud, dos apoyos que no se
    suman, configuraciones que se contradicen
  · **raíz** — la tabla de cuánto de lo construido en notificaciones llega a salir

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
885b983  Notifications consume el hecho; party no sabe que existe un notificador

Tenías razón y el commit anterior iba en la dirección contraria. Puse dentro de `party` el
vocabulario del notificador —`eventKey`, `recipientType`, `channels`,
`STAFF_CLIENTES_ASIGNADOS`— y con eso el núcleo del negocio pasó a saber que existe una campana
y cómo se le habla. Es acoplamiento igual, sólo que invertido.

**Y el enfoque de master consumer no era un cambio: ya era el diseño, y está bien hecho.** Medido
antes de opinar:

  · llamadas salientes de notifications: **cero**
  · imports de clases de dominio: **cero** — sólo `notifications` y `shared`
  · proyecciones propias: **cinco**, construidas del mismo flujo de eventos
  · coste por hecho nuevo: ~26 líneas de listener + ~18 de payload

Redeclara cada payload en vez de importarlo: es una capa anticorrupción hecha y derecha, y
enriquece desde su propio modelo de lectura. Mi única objeción seria —que enriquecer obligaría a
llamar a los dominios— no se sostiene contra el código. El plan llamó a eso «el acoplamiento que
queda»; la medición no respalda la frase.

Ahora: **party publica un hecho de dominio**, `party.executive-assigned`, sin mencionar
notificaciones. Le sirve también a auditoría, a la estructura comercial y a comisiones. La
interpretación —que ese hecho merece campana, para quién y con qué texto— vive en notifications,
que es quien sabe de campanas. El carril genérico se queda para lo que **no** es un hecho de
dominio: el backoffice pidiendo un aviso, o un sistema externo.

**Y el riesgo real de este diseño, mitigado.** Notifications queda atado al *esquema* de diez
payloads ajenos: un renombrado aguas arriba deja de poblar un campo **en silencio**. Es la misma
clase de defecto que costó una tarde con `DispositionAuthorizedEvent`, donde el beneficiario
viajaba anidado y llegaba nulo. Va con prueba de contrato del par, falsificada renombrando el
campo en el productor: se pone roja.

La clave del mensaje es el ejecutivo y no el cliente: quien consuma esto agrupa por ejecutivo
—su cartera, su bandeja, su aviso— y así todo lo suyo cae en la misma partición y en orden.

notifications 57 pruebas · party 69 · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
334731a  La campana deja de estar vacía: el primer emisor real

Nadie publicaba `notification-requested`, así que el carril genérico funcionaba y no recibía
nada. Ahora `party` pide el aviso cuando asigna un cliente a un ejecutivo — la operación ya
existía y sólo no avisaba, que es lo que el plan señalaba como «más valor por menos trabajo».

**Publica en el tópico genérico, no en uno propio de party.** Un `party.executive-assigned`
obligaría a notifications a escribirle un listener, que es exactamente el acoplamiento que el
carril genérico existe para quitar.

**Y corrijo lo que te propuse.** Dije «terminar la fase 5, es mecánico». La fase 5 es explícitamente
posterior y el plan explica por qué: «los diez de hoy funcionan y están probados, y romperlos para
ganar elegancia sería cambiar valor por forma». Lo que hacía falta eran los emisores. Las fases 1,
2 y 4 ya estaban hechas —carril, staff como destinatario, políticas sembradas—; faltaba sólo esto.

**La plantilla describía una operación que no existe.** Decía «Ahora llevas {{cantidad}} cliente(s)
más», escrita para una asignación masiva; `POST /clients/{id}/assign-executive` asigna de a uno y
no hay operación de lote en ningún sitio. Con el hecho real, `cantidad` sería siempre 1 y cargar
cuarenta clientes daría cuarenta campanas idénticas. Ahora dice quién es el cliente. Si algún día
existe la asignación masiva, emitirá su propio hecho con su propia clave — que es donde
`{{cantidad}}` significa algo.

**Y una prueba destapó que mi comentario mentía.** Escribí que un aviso fallido no debía tumbar la
asignación, y el código no lo garantizaba: el envío es asíncrono pero un error al serializar
revienta síncrono. La prueba que afirmaba la promesa falló, y el `catch` la volvió cierta.

`sourceEventId` determinista para que reasignar el mismo cliente al mismo ejecutivo no suene dos
veces: Kafka entrega al menos una vez.

party 69 pruebas · 2 nuevas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
ac170d2  🔴 La prueba no medía el código: medía el rebalanceo

`NotificationFlowIT` publicaba recién arrancado el contexto, cuando los consumidores todavía no
se habían unido al grupo, y los veinte segundos del `await` contaban desde ahí. No se perdía
ningún mensaje —el servicio lee `earliest`— pero el presupuesto se lo comía la unión al grupo:
con la suite completa compitiendo por la máquina, unirse tarda más y la prueba fallaba **sin que
nada del código cambiara**.

Ahora espera la asignación de particiones ANTES de publicar. Subir el número habría tapado el
síntoma con otro número arbitrario; lo que hacía falta era sacar de la medición lo que no se
está midiendo. Pasa de comerse el presupuesto a correr en **~1 s**.

**Y la deuda estaba mal escrita.** Decía que las dos pruebas esperaban con `await().atMost(20s)`
sobre Kafka. `CollectionsQueueIT` no tiene un solo `await`: la explicación era de una y se
extendió a las dos sin comprobarlo. Esa prueba pasa aislada (8/8) y ya está diseñada contra la
interferencia —filtra por gestores únicos y no afirma sobre totales globales—, así que si vuelve
a fallar acompañada la causa es otra y hay que verla con el fallo delante, no suponerla.

Una deuda mal descrita es peor que una sin describir: manda a arreglar lo que no está roto.

notifications 55 pruebas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
daf4538  BK-29 verificado vivo · 18/18

    cuenta      pidió   activada     primera cuota   días
    120c1370    —       2026-09-01   2026-10-01       30
    471a8404    30      2026-09-01   2026-11-01       61

El que no pidió paga a un período. El que pidió 30 días paga a 61 —treinta más un período—, que
es la regla del plan escrita tal cual. Y lo pedido queda guardado en originación, así que la
pregunta «yo no pedí empezar a pagar en noviembre» tiene respuesta.

La comprobación nueva salió mal dos veces antes de servir, las dos por lo mismo:

  · medía sobre toda la cartera, donde el ciclo corre el reloj y los apoyos desplazan
    vencimientos — salían días negativos y el verde era automático
  · no distinguía quién lo había pedido, así que marcaba como violación al préstamo que sí pidió
    BNPL: le exigía a la capacidad que no funcionara

Queda atada a la solicitud, que vive en originación. Falsificada bajando el umbral a 20 días.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
f4c9d04  S5 decía «con BNPL» y nunca lo pedía

El escenario se apoyaba en que el producto lo aplicara solo — que era exactamente el defecto:
el tope se usaba como valor y lo recibía todo el mundo, lo hubiera pedido o no. Ahora S5 pide
30 días al firmar, que es donde se toma la decisión, y el sembrador los propaga sólo si el
escenario los puso.

Y una comprobación nueva en el verificador, reescrita después de ver que la primera versión no
podía fallar. Medía «días hasta la primera cuota» sobre toda la cartera, y sobre esa cartera las
fechas están movidas a propósito: `seed-ciclo-credito` corre el reloj y los programas de apoyo
desplazan vencimientos. Salían valores negativos y el verde era automático.

Queda acotada a préstamos activados en las últimas 24 h y sin programa de apoyo — la única
población cuyas fechas nadie tocó. Cuando no hay ninguno, se declara **sin verificar** en vez de
pasar: acababa de escribir que un total que cuenta lo que no se miró es peor que un rojo, y
dejar un verde que no puede ponerse rojo habría sido lo mismo con otro nombre.

16/18 · 2 sin verificar, las dos nombradas con qué hacer para poder afirmarlas.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
b407f10  BK-29 completo: BNPL es una decisión que ahora alguien puede tomar

El campo existía en el comando de cartera y nadie lo llenaba. Quedó así a propósito —nulo es
«nadie lo pidió», que es distinto de «no sabemos», y sólo el primero permite no aplicarlo— pero
con eso BNPL no se podía usar: la capacidad estaba declarada en el producto y no había ningún
camino por el que un cliente la pidiera.

Ahora lo recorre entero: app → BFF → originación → contrato firmado → hecho → cartera.

Vive en el **contrato** y no en la solicitud porque es parte de lo que se firma: quien reclame
después «yo no pedí empezar a pagar en marzo» tiene la respuesta ahí, con la fecha de firma al
lado. La columna no tiene default — un default volvería a ser aplicar BNPL a quien no lo pidió.

**El tope no se valida en el camino, sólo en el destino.** Ni el BFF ni originación comparan lo
pedido contra `bnplMaxDeferralDays`: quien conoce el tope es el producto, y cartera recorta.
Negar la firma entera por pedir de más convertiría un límite en un obstáculo justo en el último
paso del alta, y duplicar la regla en tres sitios es la forma habitual de que se separen.

Cero y nulo significan lo mismo y se guarda nulo: una columna que distingue dos formas de «no
pidió» invita a leer el cero como «BNPL de cero días», que no es una decisión.

origination 151 pruebas · 3 nuevas · credit-portfolio 206 · channel-mobile 53 · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
57a3616  Un total que cuenta lo que no se miró es peor que un rojo

`verifica-carril-del-dinero` decía **16/16** —todo verde— con la comprobación que de verdad
importa sin correr. Está acotada a las últimas 24 h y se saltaba sola cuando la siembra
envejecía; cuatro días después, el verificador daba luz verde sin haber mirado el carril.

Una comprobación que no se pudo hacer no es una comprobación que pasó. Ahora se cuenta aparte y
se nombra: **16/17, 1 sin verificar**, con la razón y qué hacer para poder afirmarlo. El rojo se
investiga; el verde de más, no.

Se actualiza la deuda del README, que había quedado desfasada tras recrear la base:

  · Los $8 250 de interés duplicado **ya no existen** — eran datos, y la base se rehízo. El
    índice que impide que vuelvan sí es código y sigue puesto. Se tacha en vez de borrarse
    porque la lección es del catálogo de defectos, no de esta base.
  · La media política del backoffice queda **cerrada**: la matriz se relee cada cinco minutos.

Y se documentan en el plan los tres defectos que sólo existen en instalación limpia. Borrar la
base entera fue la verificación más productiva de la revisión: encontró lo que ninguna cantidad
de pruebas sobre una base viva podía encontrar, porque sólo se manifiesta cuando no hay nada.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
1862399  El orquestador sembraba entidades y nunca corría la vida

Con todo sembrado, `verifica-cuadre` daba **siete fallos**, y los siete decían lo mismo: la
cartera estaba viva y sana, y no había pasado nada después del alta. Cero devengo, cero
estimación preventiva, cero facturas, el auxiliar de intereses por cobrar sin un solo abono, 15
créditos sin ejecutivo y 29 sin sellar su sucursal.

Eso alcanza para ver cartera y no alcanza para ver el sistema: el mayor tenía altas y
desembolsos, y nada de lo que ocurre después.

Faltaban cuatro pasos que existían como script y que nadie encadenaba:

  · `asigna-cartera` — «sembrar cartera» y «que la cartera tenga dueño» son dos cosas distintas,
    y la segunda no ocurre sola
  · `seed-perfiles-fiscales` — sin perfil, la facturación no falla: falla **peor**, cayendo al
    RFC genérico. Va antes del ciclo, que es quien dispara las facturas
  · `seed-expedientes`
  · `seed-ciclo-credito` — el último y el más importante: devenga, cobra, deteriora, factura y
    cierra mes a mes

Después de encadenarlos, sobre la misma base recién creada:

    verifica-carril-del-dinero    17/17
    verifica-cuadre               ✓ el tablero, el árbol y la cartera cuadran
    verifica-contabilidad         ✓
    verifica-distribuidoras       ✓
    verifica-identidad-auditoria  ✓

79 disposiciones, 79 con orden de pago, **0 sin ella**. 76 liquidadas en el conector. Y el mayor
cuadra con el carril al peso: `1201 → 1101` suma **8 237 080**, que es exactamente lo liquidado
más lo que sigue en vuelo.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
cbc38fd  El sembrado desde cero destapó dos dependencias de la base anterior

**El segundo operador estaba puesto a mano.** `riesgo@kredius.mx` sólo existía en la base
acumulada de una sesión anterior: sobre instalación limpia el login daba 401 y el programa de
apoyo no se sembraba. Un sembrador que depende de datos de una corrida previa no siembra desde
cero, que es justo lo que tiene que saber hacer. Ahora resuelve el segundo operador **por rol**
contra el padrón de personal, y si no hay ningún RISK_ANALYST activo lo dice en vez de intentar
el maker-checker con una sola persona.

**Y el backoffice arrancó con media política**, que era la deuda #10 y deja de ser molestia para
ser bloqueo: leyó la matriz de identity mientras identity aún aplicaba migraciones, se quedó con
las capacidades de ese instante y siguió con ellas. El analista de riesgo recibía 403 sobre una
facultad que la base sí le concede, y el síntoma no se parece en nada a «el arranque fue a
destiempo».

El respaldo en código cubría «identity no contesta». No cubría «identity contesta a medias»,
que es peor porque una foto parcial se parece a una completa. Ahora la matriz se relee cada
cinco minutos: cualquier desfase se cierra solo en el siguiente ciclo, sin que nadie reinicie
nada. Cada cinco y no cada uno porque la matriz cambia con una edición humana, y ésas ya llaman
a `reload()` directamente — esto es la red de abajo, no el camino principal.

Verificado vivo, con el stack recién creado:

  · programa otorgado por dos operadores distintos — 35 elegibles, 35 inscritas
  · un segundo programa sobre la misma cartera: **1 inscrita**, las 35 ya apoyadas excluidas
  · 36 inscripciones · 36 cuentas · **0 duplicadas**

Es el hallazgo #15 comprobado en vivo: antes ese segundo otorgamiento habría corrido el
vencimiento de 35 cuentas por segunda vez.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
8d3b547  La comparación de configuración compara lo que significa, no cómo se escribe

Sobre base limpia el verificador marcaba ocho productos idénticos como desincronizados. La
diferencia era de forma: cartera guarda la configuración como objeto tipado y al reserializarla
escribe `deferralMaxTerm: null` donde el catálogo ya no trae la clave, y `opcionesDePago: null`
donde el catálogo no la trae en absoluto.

Un nulo declarado y una clave ausente producen exactamente el mismo comportamiento. Marcarlos
como desincronización convierte el aviso en ruido permanente, que es la forma más segura de que
nadie lo mire el día que la diferencia sea real.

Se normalizan las dos formas antes de comparar. 16/16 sobre base recién creada.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
9715ac7  🔴 Una instalación desde cero no podía originar ni un crédito

Borrar los volúmenes y levantar el stack limpio lo dejó a la vista: **el catálogo con nueve
productos activos y cartera con cero configuraciones**. Toda alta habría muerto con «Sin
configuración del producto», y nada lo decía — el catálogo se veía sano y el fallo aparecía tres
servicios más allá.

La causa es la misma de siempre, en su forma más completa: los productos se siembran con un
`INSERT` de Liquibase ya en `ACTIVE`, y un `INSERT` no emite `product-activated`. No es que se
perdiera un evento: **nunca hubo ninguno**. El `republish` que añadí resolvía el caso de una
configuración cambiada fuera de la API; no éste, porque para llamarlo hay que saber que hace
falta, y en una instalación nueva no hay síntoma hasta que alguien intenta dar de alta.

Ahora credit-product reemite el catálogo al arrancar. Es seguro porque el consumidor hace upsert
por (código, versión) —republicar lo mismo no cambia nada— y convierte una clase entera de
problema en autorreparable: un consumidor que perdió su copia, uno nuevo que se suma, o una
configuración tocada por fuera se arreglan con un reinicio en vez de con una llamada manual que
alguien tiene que acordarse de hacer.

Si uno falla, los demás se publican igual: un catálogo a medias es peor que uno con un hueco
conocido, porque el hueco al menos queda en el log.

Y el orquestador de siembra, que tampoco existía. El orden no estaba escrito en ningún sitio —
había que deducirlo leyendo el encabezado de cada script y saber que la cartera necesita las
sucursales que la siembra comercial crea antes. Sembrar en desorden no falla ruidosamente:
produce cartera sin ejecutivo, tableros en cero y escenarios a medias, que es peor porque parece
que funcionó. `scripts/siembra-completa.sh` los corre en orden y se detiene en el primero que
falle.

credit-product 75 pruebas · 3 nuevas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
308bbae  Las configuraciones de producto, precisas y sin contradecirse

Un producto no se configura campo a campo: los campos **se condicionan entre sí**, y una
combinación puede ser inválida sin que ninguno de sus valores lo sea. Eso no lo caza nadie
revisando el JSON a ojo, y no rompe nada — sólo miente sobre lo que el producto hace, con el
síntoma apareciendo lejos y sin relación aparente.

`OpcionesDePago.incoherencias()` devuelve cada contradicción con su frase, no lanza en la
primera: quien corrige una configuración quiere verlas todas de una vez, no descubrir la
siguiente en el intento siguiente.

  · `POST_HOC` sin ventana → una capacidad que no se puede ejercer nunca
  · `POST_HOC` con huecos o solapes entre bandas → plazos que el producto admite y no sabe cobrar,
    o dos tasas para el mismo plazo
  · Plazos declarados con `installmentPlanMode: NONE` → parámetros para algo que no hace
  · Plazo por omisión fuera del rango → lo recibe justo quien no elige
  · Salto ofrecido con tope 0, o tope sin salto
  · `bnplEnabled` con tope 0 → decir que se ofrece BNPL de cero días es no ofrecerlo

**`AT_DISPOSITION` sin bandas es correcto y la validación lo respeta**: ahí el plan nace con la
colocación y cobra la tasa del producto, así que no hay diferimiento que tarificar. Exigirle
tabla sería pedirle tarifa para algo que no ocurre.

Cartera **avisa y guarda igual**. Rechazar aquí dejaría al catálogo y a la cartera discrepando,
que es justo el problema que ya costó una tarde. Con el WARN, el día que alguien pregunte «por
qué este producto no deja saltar pagos si el catálogo dice que sí», la respuesta está en el log
del arranque y no en una tarde de bisección.

El changeset 019 quita de cada producto los parámetros de lo que no hace. El préstamo personal
declaraba plazos de diferimiento de 1 a 24 sobre `installmentPlanMode: NONE`: quien leyera esa
configuración para decidir si ofrecer «pagar a meses» encontraría un rango completo y se lo
creería. `deferralRates: []` se queda, porque una lista vacía dice «ninguna banda» a propósito y
eso es distinto de que el campo no exista.

Y un detalle del propio changeset: **pasaba por psql y fallaba por Liquibase**. Los operadores
JSONB con interrogación (`?`, `?|`) chocan con los marcadores de parámetro de JDBC. Verifiqué
por un camino distinto del que despliega, que es la misma clase de error que vengo persiguiendo
toda la sesión. Va con `jsonb_exists_any`, que es el mismo operador sin el signo.

credit-portfolio 206 pruebas · credit-product 72 · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
743e22a  🔴 Dos configuraciones válidas por separado, inválidas juntas

**El desplazamiento sí tenía explicación, y me equivoqué al llamarlo inexplicable.** Dije que
las primeras cuotas caían entre 152 y 242 días «por cuenta, no del producto» y que no lo había
diagnosticado. La causa: **dos programas de apoyo distintos**, ambos autorizados, ambos de tres
períodos, alcanzando la misma cartera. Cuarenta y dos cuentas recibieron los dos y se les corrió
el vencimiento **seis meses** — el doble de lo que nadie autorizó. Los creé yo al correr la
siembra varias veces, y que se pueda es el defecto.

La guarda que existía era **por programa**: impedía reotorgar el mismo lote dos veces, con su
comentario explicándolo. No impedía lo otro. Ninguno de los dos programas era incorrecto por
separado; el invariante no es de programa sino de cuenta — **un crédito no puede estar bajo dos
apoyos a la vez**. Si hace falta extender el apoyo se otorga un programa con más períodos, no
uno encima de otro.

El padrón ahora declara `yaApoyadas` junto a `cuentasElegibles`. Con sólo «0 elegibles» no se
distingue «el criterio no alcanza a nadie» de «toda esa cartera ya viene de otro programa», y
quien firma necesita esa diferencia antes de firmar.

**Y BNPL, que es la corrección pedida.** `bnplMaxDeferralDays` es un TOPE — el nombre lo dice— y
se usaba como la cifra a aplicar, sin mirar si alguien lo había pedido. Como PL-IND-STD-V1 lo
tiene habilitado, ningún préstamo personal empezaba a pagar cuando debía.

Ahora es una solicitud acotada por el tope: sin solicitud no hay BNPL; un producto que no lo
admite la ignora; pedir de más se recorta, porque el producto ya declaró hasta dónde espera y
negar el alta entera por pedir de más convierte un límite en un obstáculo.

La regla se movió a `OpcionesDePago`, con la configuración: es la configuración quien sabe qué
significa cada uno de sus campos, y tenerla suelta en el servicio fue lo que permitió confundir
un límite con un valor. El comando lleva `bnplDeferralDays` nulo hasta que originación lo
capture — nulo es «nadie lo pidió», que es distinto de «no sabemos», y sólo el primero permite
no aplicarlo.

Las dos correcciones falsificadas: cada una falla al devolver el código anterior.

credit-portfolio 197 pruebas · 8 nuevas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
1bf7950  🔴 BNPL se aplica a todos y usa el tope como si fuera el valor

Sembrando S5 salió que todo préstamo personal nace con un aplazamiento que nadie pidió.

`arranqueDeBnpl` devuelve `LocalDate.now().plusDays(bnplMaxDeferralDays)` en cuanto el producto
tenga `bnplEnabled`. Dos cosas, las dos contra lo que el plan dice:

  · El campo se llama `bnplMaxDeferralDays` — es un TOPE. El plan lo escribe como
    `alta + n días (tope: bnplMaxDeferralDays)`. El código lo usa como la cifra, así que todos
    reciben el máximo.
  · No hay decisión de nadie. El plan dice que BNPL «es una decisión del alta»; aquí basta con
    que el producto lo habilite para que TODA cuenta suya lo reciba. No existe camino por el que
    el cliente lo pida ni por el que la originación lo conceda.

`PL-IND-STD-V1` lo tiene habilitado: hoy ningún préstamo personal empieza a pagar cuando debería.

Se documenta también una observación que NO queda explicada por esto: las primeras cuotas caen
entre 152 y 242 días del alta, y varían entre cuentas del mismo día. El tope de 30 más un
período darían ~60. El 21 de agosto hubo una cuenta con 31 días y otra con 212, lo que dice que
el desplazamiento es por cuenta y no del producto. Queda escrito como observación con su
medición, no como causa: no lo he diagnosticado.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
f015bfa  🔴 El sembrador reportaba lo que quiso hacer, no lo que hizo

S2 y S3 imprimían «✓ compra a 6 MSI» y «✓ diferida a 12» sobre tarjetas sin un solo plan. Las
seis compras sembradas eran revolventes puras y ningún diferimiento había ocurrido.

`comprar()` devolvía la respuesta de `/credit/dispose`, que es `{"success": true}` y nada más —
la disposición se resuelve asíncrona, y el propio docstring lo decía: «la respuesta no trae el
id: hay que buscarlo después». Quien llamaba hacía `if compra.get("dispositionId")`, que nunca
era verdad, y seguía adelante declarando el escenario sembrado.

Un sembrador que reporta la intención en vez del hecho es peor que uno que falla: deja datos que
no son lo que dicen ser, y cualquier prueba que se apoye en ellos verifica otra cosa.

Ahora `comprar()` busca la disposición por importe y la devuelve; sin ella, el escenario no se
declara sembrado.

Verificado contra la base:

    S2 · 3 000 REVOLVING · 0 cuotas
    S2 · 6 000 AMORTIZED a 6 · interés 0.00      ← MSI
    S3 · 9 000 AMORTIZED a 12 · interés 1 219.13 ← banda 10-12

Las dos bandas de tasa del diferimiento (F4d), con datos reales.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
de0fc5e  Los siete escenarios revolventes se siembran · y la config desincronizada volvió a morder

Cuatro defectos del sembrador, cada uno tapando al siguiente:

**1 · Trataba como síncrono algo que no lo es.** La cuenta nace de un evento que cartera
consume; el sembrador preguntaba una sola vez, no la encontraba y reportaba «journey
incompleto» — el diagnóstico exactamente equivocado: el journey había ido bien y la cuenta
llegaba medio segundo después. Ahora espera hasta 20 s. Esperar no tapa nada: si no llega en
veinte segundos, eso sí es un hallazgo.

**2 · «journey incompleto» no dice nada.** Siete escenarios fallando con la misma frase no
distinguen un rechazo del dominio —que es información— de un servicio caído. Ahora dice en qué
paso se cortó.

**3 · S4 pagaba contra `/payments`, que no existe.** Es `/credit/payment`, y el BFF resuelve la
cuenta del usuario autenticado: mandarle la cuenta sería dejar que el cliente diga a cuál paga.

**4 · El apoyo se sembraba con el mismo token para las dos mitades.** El dominio rechaza —y hace
bien—, así que la siembra ejercitaba el camino de rechazo disfrazado de fallo. Ahora riesgo
propone y administración autoriza: **42 elegibles, 42 inscritas**.

Y en medio, el hallazgo #6 otra vez. S6 fallaba con «el producto PL-IND-STD-V1 no admite saltar
pagos» mientras el catálogo decía `skipPaymentEnabled: true`. Republiqué **una** tarjeta y di el
problema por cerrado; los otros ocho productos seguían con su copia vieja en cartera. Tres
estaban desincronizados. Republicados: 0 de 9.

El verificador ya lo mira —comprueba que cartera tenga la configuración vigente de **cada**
producto— porque es la segunda vez que este mismo hueco cuesta una tarde.

verifica-carril-del-dinero: 17/17

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
6d18498  💰 El carril del dinero está completo — el mismo número en los cuatro sitios

    cartera      disposition-authorized          4
    disbursement SETTLED                         4
    stp          SETTLED                         4 · $8 540 · con clave de rastreo
    cartera      COMPLETED                       4
    mayor        DISPOSITION_SELF_USE 1201→1101    $8 540

Es la verificación que el plan pedía desde el principio —«el importe del banco, el de cartera y
el del mayor son el mismo número»— y que hasta hoy no se había podido hacer, porque no había
salido nunca un peso. Y a diferencia de antes, ese abono a `1101` tiene contraparte bancaria
real: es lo que se ganó al matar el `Noop`.

Cuatro cortes en fila, cada uno escondido tras el anterior, ninguno visible desde las 1 759
pruebas. El cuarto es el instructivo por contraste: ahí sí había log y sí había reintento, y por
eso se veía. Los tres anteriores fallaban en silencio.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
81bc6f1  Mi propio guardián no vio el caso que más caro salió

`ConfiguracionQueElEntornoNoEntregaTest` nació mirando los `application.yml` de los servicios y
comparándolos contra lo que Compose entrega. Con ese alcance daba por buena esta línea:

    STP_KEK_LOCAL: ${STP_KEK_LOCAL:-}

Compose **declaraba** la variable, así que la comprobación la contaba como entregada. El default
vacío hacía que el conector no pudiera guardar ninguna llave de firma, y con ello el carril del
dinero no podía completarse en local. Nada decía que faltara una variable: el servicio arrancaba
sano y el sembrador del stub fallaba con un mensaje sobre criptografía.

Es exactamente la forma del defecto que este guardián existe para cazar, un nivel más arriba. Y
que fuera yo quien lo escribió no lo hace menos ciego: el alcance se eligió mirando dónde había
aparecido el problema la primera vez, no dónde podía aparecer.

Ahora también mira el propio Compose. La KEK local recibe un default obviamente-no-secreto, con
el mismo criterio que `JWT_SECRET` del configuration-service — un valor publicado en el
repositorio no es un secreto y no pretende serlo. En producción llega de fuera, del gestor de
secretos, como siempre.

`OTP_DEV_CODE` se declara como blanco aceptado con su motivo: ahí el blanco **es** el valor
correcto —sin código fijo, el OTP se valida de verdad— y lo excepcional es ponerlo.

Falsificado: falla al devolver la KEK a vacío, pasa al restaurarla.

shared 54 pruebas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
ae200c9  🔴 El mock no podía completar el flujo que existe para ejercitar

`StubVerificationKeySeeder` registraba la llave de VERIFICACIÓN y ninguna de FIRMA. Con eso, el
relay del outbox no podía firmar **ninguna** orden:

    La empresa 9b1d0000-…-0001 no tiene llave SIGNING activa

Cuatro órdenes paradas en PENDING con su clave de rastreo ya asignada, reintentando con
retroceso exponencial. Aquí sí hay log y sí hay reintento —el outbox está bien hecho y por eso
este eslabón se ve—, pero el pago no sale nunca.

Son dos llaves distintas y hacían falta las dos: la de verificación es la pública del stub, para
comprobar lo que STP responde; la de firma es la nuestra, para firmar lo que le mandamos. Sólo
se sembraba una.

La privada se genera en el arranque y **sólo en modo stub** —la clase entera es
`@ConditionalOnProperty(mode=stub)`—. En un ambiente real es nuestro certificado ante Banxico:
se carga del almacén y no la siembra nadie. Que se genere nueva en cada arranque es lo correcto
para un ambiente bajo: no queda ningún secreto escrito en ningún lado.

Es el cuarto corte del mismo carril y el cuarto de la misma forma: configuración que nadie
sembró, invisible hasta correrlo de verdad.

stp 44 pruebas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
c3eb20f  BK-25 verificado de punta a punta: seis meses sin intereses, desde la app

El titular ve sus tres compras, elige una y la difiere a seis. Sólo esa tiene plan; las otras
dos siguen revolventes con cero cuotas.

Y el plan es un MSI de verdad: 533.33 × 5 + 533.35, interés e IVA en cero, suma exacta de
3 200.00. El redondeo cae en la última cuota, así que el cliente paga el importe de la compra y
ni un peso más.

Cubre F4b y el núcleo de F17 contra datos reales, no contra un mock.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
29e20ba  Deuda: el backoffice puede arrancar con media política de autorización

Encontrado al desplegar. Lee la matriz de identity al arrancar y la guarda en memoria, con
respaldo en código si identity no contesta — las dos cosas deliberadas y documentadas.

Lo que no lo es: recreados los dos a la vez, leyó a mitad de la migración y se quedó con dos de
las cuatro capacidades nuevas, sin decir nada. El respaldo cubre «identity no contesta», no
«identity contesta a medias», y una foto parcial es peor que ninguna porque se parece a una
completa.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
3c22a5a  🔴 La misma empresa hacía falta en los dos lados y sólo se sembró uno

Con el carril ya desatascado, las cuatro disposiciones se convierten en orden de pago y salen
DISPATCHED — $8 540, la primera vez que este sistema mueve dinero. Y ahí se paran: **sin clave
de rastreo**, con cuatro mensajes en la DLT de `disbursement.stp-requested`.

    CompanyNotFoundException: No hay empresa activa con
    companyId=9b1d0000-0000-4000-8000-000000000001

`disbursement` resuelve esa empresa con su comodín y despacha. El conector recibe la orden y no
tiene con qué firmarla: `stp.companies` está vacía. El UUID es el mismo a propósito —es la
misma empresa vista desde dos servicios— y que sean tablas distintas es correcto: una guarda de
quién es el pago, la otra con qué identidad se firma ante Banxico. Lo que no es correcto es que
sólo una estuviera sembrada.

El síntoma no se parece a lo que es. Una orden DISPATCHED sin clave de rastreo se lee como un
proveedor lento, no como una fila que falta.

Ninguna prueba lo miraba porque ninguna cruza de disbursement a stp. El verificador tampoco:
comprobaba `company_mappings` de un lado y nada del otro. Ahora comprueba que **cada** empresa
habilitada en el orquestador tenga contraparte activa en el conector.

Reparadas también las 16 CLABE inválidas del stack —cartera, originación y colocaciones—
conservando el cuerpo y recalculando sólo el dígito que estaba mal, para poder verificar el
carril con los datos que ya existen.

verifica-carril-del-dinero: 15/16, y el rojo es éste hasta reconstruir stp.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
ce004b6  🔴 La CLABE se validaba donde se paga, no donde entra

Ocho de las diecinueve cuentas de la cartera tienen el dígito verificador equivocado. Sus
créditos están otorgados, activos y devengando contra cuentas bancarias que **no pueden
existir**, y el desembolso muere al final del todo.

El punto de validación existía y estaba llamado: `ContractService` invoca `clabeValidator` y
lanza con su código, `CM-06`. Lo que había detrás era un stub que comprobaba **dieciocho
dígitos y nada más** — es decir, no verificaba lo único que un dígito verificador sirve para
verificar. Nueve de cada diez CLABE mal capturadas pasaban.

Y pasaban lejos: scoring, oferta, contrato, firma y alta. El error salía a la luz en
`disbursement`, al ir a mandar el dinero, con el cliente ya debiendo. Ahí es tarde y de la peor
manera — la cuenta existe, debe, devenga, el desembolso muere en la cola, y quien lo atiende no
tiene de dónde saberlo.

El dígito verificador es local y determinista: el algoritmo de Banxico, el mismo que BK-49
consolidó en `shared` y que ya usan tesorería y el conector. **No hacía falta contrato con
nadie** para dejar de aceptar una CLABE imposible. Lo que sí exige integración —que la cuenta
esté abierta y admita abonos— sigue pendiente y esto no lo suple.

Los dos sembradores generaban el verificador al azar:

    f"0021801{random.randint(10**10, 10**11 - 1)}"[:18]

Ahora lo calculan. 500 de 500 válidas.

La prueba anterior moqueaba el validador, así que nada tocaba la implementación real. Van
cuatro, y la que importa es la del dígito equivocado: exactamente lo que el stub aceptaba.
Las cadenas son reales, sacadas de la cartera sembrada.

origination 148 pruebas · 4 nuevas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
5cfeeb4  Documentar los cuatro hallazgos de la verificación contra el stack

Los cuatro son de la misma familia y conviene que se lea junta: algo terminado que nadie podía
alcanzar. El código está bien, la prueba pasa, y entre las dos hay un tramo que no tiene dueño
en ninguna suite.

Incluye la corrección de lo que reporté sobre el "13/13" del carril, que se lee como que el
dinero fluye y no fluye.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
ad27191  🔴 Tres controles que nadie podía satisfacer — dos mesas de trabajo paradas

Un control que nombra a alguien que no existe se lee como un control y no lo es.

**Dos capacidades exigidas y jamás sembradas.** Cero roles las tienen en la base viva:

  · `applications.review-documents` — el analista no podía dictaminar un documento. La bandeja
    se veía, el botón estaba, y guardar respondía 403 a todo el mundo, incluido ADMIN.
  · `beneficiaries.review-identity` — la mesa de KYC no podía firmar el cotejo de identidad de
    una beneficiaria, que es la única decisión que esa mesa toma.

Es exactamente lo que el changeset 015 advirtió al arreglar `beneficiaries.view`, con esas
palabras: «una ruta protegida por una capacidad que nadie tiene no está protegida, está rota».
Volvió a pasar con otras dos.

**Y un rol que no existe.** Los programas de apoyo exigían `RISK_MANAGER`, que aparece en un
solo fichero del monorepo y que identity nunca ha emitido. Aquí el efecto no fue un 403 ruidoso
sino algo peor: la expresión era `hasAnyRole('ADMIN','RISK_MANAGER')`, así que **sólo ADMIN
pasaba** y el maker-checker de un apoyo masivo se resolvía entre dos administradores. La
separación por función que el rol nombraba no existía, y nada lo decía. Pasa a `RISK_ANALYST`,
que sí existe, y se siembra la capacidad separando ver el padrón de otorgarlo: simular a
cuántas cuentas alcanza no es moverles el vencimiento.

**El guardián**, porque van tres. `ControlesQueNadiePuedeSatisfacerTest` compara lo que el
código exige contra lo que identity emite. No juzga el reparto de facultades: sólo exige que
quien se nombra exista. Falsificado en las dos mitades — falla al devolver `RISK_MANAGER` y al
quitar el changeset de capacidades.

Las fuentes java y los changesets de identity se declaran como entradas de la tarea. Sin eso el
guardián se queda mirando una foto vieja justo cuando alguien introduce el defecto que vigila.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
d2c3ce2  El verificador del carril no miraba si había corrido un peso

Trece comprobaciones en verde sobre un carril que nunca movió dinero. Todas mira­ban la
configuración —rutas, mapeos, cuentas puente— y ninguna las órdenes, que eran cero.

Se añade lo que faltaba, en ventana de un día: una comprobación sobre toda la historia nunca
podría ponerse en verde —arrastra las disposiciones que el stub marcó completadas— y una
comprobación que no puede pasar deja de leerse. El rezago anterior se reporta como cifra con su
frase, no como fallo: es deuda con dueño, no regresión.

Hoy dice 14/15 y el rojo es el correcto.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
1c10b5e  🔴 TODA disposición del sistema murió en la DLT — el carril nunca movió un peso

Cuatro disposiciones autorizadas en la historia de esta base. Cuatro en la DLT. **Cero órdenes
de pago**, y ni una línea de log en disbursement: el offset se confirmó y la DLT se lo tragó.

    UnresolvedCompanyException: el evento de credit-portfolio no trae
    companyId ni sourceCompanyKey

Es mi arreglo anterior, que quedó a medias. Sembré el comodín `*` en `company_mappings` y puse
`sourceCompanyKey = account.getOriginUnitCode()`. Las dos mitades fallan por lo mismo:

  · `origin_unit_code` es **nulo en las 19 cuentas** del sistema, y lo es a propósito — el
    comentario de `CreditAccountService` lo explica: se dejó de sellar porque antes escribía el
    UUID del promotor creyendo que era la sucursal, y resolverla de verdad en el alta "es
    trabajo aparte". Colgué la resolución de empresa de un campo que el código documenta como
    normalmente nulo.

  · La guarda de «sin clave» lanzaba **antes** de mirar el comodín. El comodín estaba sembrado,
    habilitado y esperando una llamada que la guarda no dejaba llegar.

Ahora, sin clave, se va directo al comodín. No es adivinar: alguien lo sembró diciendo "para
este sistema origen, ésta es la empresa". Sin comodín sigue lanzando — firmar con la llave
equivocada saca dinero de la cuenta equivocada.

**Nada probaba esta clase.** Por eso el arreglo a medias sobrevivió a 1 759 pruebas verdes. Van
7, y la de la regresión está falsificada: con la guarda anterior falla.

Y una corrección de lo que reporté: dije "carril del dinero 13/13" junto al journey de la
tarjeta, lo que se lee como que el dinero fluye de punta a punta. No fluye. Ese verificador
comprueba que el carril esté **cableado** —su propio encabezado dice "la configuración que
nadie sembró nunca"— no que haya corrido un peso. Que las órdenes fueran cero no lo miraba
nadie.

disbursement 48 pruebas · 7 nuevas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
e67c669  Documentar BK-31 y la deuda de `republish`

La lista de deuda tenía dos veces los números 5 y 6 — una lista de pendientes mal numerada es
una lista que nadie citó nunca.

Se añade la #9: `republish` sólo se alcanza desde la red interna, y a propósito. Darle botón en
el backoffice legitima el cambio de configuración fuera de la API, que es el problema del que
`republish` es sólo la reparación.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
aa52a3a  BK-31: diferir y saltar existían, pero ningún cliente podía llegar

`defer` y `skip` estaban escritos en cartera desde su fase, con su ventana, su tope por ciclo
y sus modos GIFT/DEFERRAL. Ningún canal los exponía. El plan es explícito en que las dos son
decisiones **del cliente** —"la app permite elegir qué pago o compromiso se salta"— y el
camino app → BFF → cartera no existía: el caso de uso terminado y nadie con forma de invocarlo.

Van cuatro endpoints, no dos. Sin ver sus compras ni su calendario, "el cliente elige cuál
difiere" no significa nada:

    GET  /credit/dispositions              mis compras — cuál puedo diferir
    GET  /credit/schedule                  mis pagos — cuál puedo saltar
    POST /credit/dispositions/{id}/defer   diferir esa compra
    POST /credit/installments/{id}/skip    saltar ese pago

Ninguno recibe el id de la cuenta: se resuelve del usuario autenticado, igual que `/credit/pay`
y `/credit/dispose`. No existe la petición capaz de diferir la compra de otro ni de saltarle el
pago. La pertenencia no se comprueba —comprobarla admite olvidarla— sino que se construye.

El 409 del dominio viaja con su cuerpo. Fuera de ventana o con el tope de saltos agotado, el
motivo es la mitad de la respuesta; traducirlo a un fallo genérico deja al titular sin saber
si puede intentar otra cosa.

El sembrador apuntaba a `{BACKOFFICE}/api/v1/portfolio/.../defer`, que no existe en ningún
servicio: S2, S3 y S6 se habrían sembrado en silencio sin diferir ni saltar nada. Corregido a
las rutas reales.

El gateway ya las cubre por su catch-all. Se corrige el comentario que enumeraba rutas y había
quedado corto — una ruta que nadie enrutó se ve igual que una que no existe.

channel-mobile 53 pruebas · 9 nuevas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
a6778c0  🔴 El catálogo de productos era de sólo lectura y nadie lo sabía

`credit-product-service` validaba un JWT contra `${JWT_SECRET:}`. Compose nunca le entregaba
esa variable, así que el secreto era la cadena vacía y **todo** token —válido o no— resultaba
inválido. Cada escritura del catálogo respondía 401, sin cuerpo y sin log.

Crear, activar o retirar un producto desde el backoffice era imposible. La lectura sí
funcionaba (`permitAll`), de modo que la pantalla se veía sana: el catálogo se listaba y sólo
fallaba al guardar.

El defecto era de un solo lado. El BFF ya propagaba `X-User-Id`/`X-Roles` con
`DomainClientSupport.staffIdentity()`, como a los otros veinte servicios de dominio, y el
gateway limpia esos headers si vienen del cliente y los reescribe desde el token validado.
credit-product era el único que se salía de esa convención para pedir una credencial que en
esta arquitectura nadie emite.

Su prueba de integración no podía verlo: inyectaba el secreto ella misma vía
`DynamicPropertySource`. Probaba el filtro bajo una premisa que el despliegue jamás cumplía.
Ahora llama como llama el BFF, con headers.

Se retira el secreto —y con él `CreditProductProperties`, que no guardaba otra cosa— porque
dejarlo configurable sostiene la ilusión de que alguien lo entrega.

Y el guardián, que es lo que faltaba: `ConfiguracionQueElEntornoNoEntregaTest` compara lo que
cada servicio pide contra lo que Compose entrega. La forma del defecto es `${VARIABLE:}` —
default vacío: el servicio arranca sano, el health queda UP, y la capacidad que dependía de
esa variable no funciona. El blanco legítimo se declara con su motivo, que es justo lo que
aquí faltó.

Verificado en los dos sentidos: falla al reintroducir `${JWT_SECRET:}` y pasa al quitarlo. Los
ficheros que lee se declaran como entradas de la tarea; si no, Gradle daría la prueba por
actualizada y el guardián dejaría de mirar justo cuando alguien introduce el defecto.

credit-product 72 pruebas · shared 51 pruebas · 0 fallos

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
fc915cd  🔴 Un cambio de configuración de producto NO llegaba a cartera

BK-24 estaba bien implementado y no servía de nada: la compra de tarjeta seguía
naciendo AMORTIZED con 12 cuotas. La causa no estaba en el código de BK-24.

    catálogo:  CC-IND-STD-V1 → installmentPlanMode = POST_HOC
    cartera:   CC-IND-STD-V1 → installmentPlanMode = NONE

`product-activated` sólo se publica al ACTIVAR un producto. La configuración de
parcialidades se sembró con un UPDATE del JSONB (BK-23), y un UPDATE no emite
eventos: cartera conservó la copia con la que el producto se activó hace días.

Es un hueco general, no de este caso: cualquier corrección hecha fuera de la API
—un changeset, un ajuste directo— se queda en el catálogo, y el síntoma aparece
lejos y sin relación aparente. El producto dice una cosa y cartera se comporta
según otra, con todo el código correcto de las dos partes.

Se añade `POST /{productCode}/republish`: reemite la configuración vigente sin
cambiar nada. Idempotente por construcción — quien lo consume ya trata la
activación como tal. La autorización va en SecurityConfig como el resto del
controlador, para no tener dos fuentes de la misma regla.

Lo destapó el journey completo contra el stack. Ninguna prueba podía verlo: las
de credit-product verifican que el catálogo guarda POST_HOC, las de cartera
verifican qué hace con POST_HOC, y las dos pasan. Lo que falla es el tramo entre
ellas, que no tiene dueño en ninguna suite.

credit-product 72 pruebas · 0 fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
21807d9  El control muerto también bloqueaba: @NotBlank en wallet

Retirado `dispositionType` del BFF móvil, la compra empezó a rebotar con 400 en
wallet: su DTO lo exigía con @NotBlank. El mismo control muerto, una capa más
abajo, y ahora impidiendo la operación en vez de sólo confundir.

El campo se conserva OPCIONAL y renombrado a `dispositionTypeIgnorado` —para que
un cliente que aún lo mande no reciba un 400— y deja de publicarse en
`wallet.disposition-requested`: publicarlo mantendría vivo el campo que permitía
desviar el destino del dinero.

Es la tercera capa del mismo cambio. BK-13 lo quitó del comando de cartera, pero
el valor seguía viajando app → wallet → evento, y sólo dejaba de importar en el
último tramo. Quitarlo del origen destapó a los dos intermediarios de golpe.

wallet 40 pruebas · 0 fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
816032c  🔴 La app seguía ofreciendo dispositionType, un control que ya no hace nada

BK-13 quitó el tipo de disposición del comando de cartera: lo decide el producto,
porque mientras viajó en la petición una solicitud sobre una línea de distribuidor
que mandara "SELF_USE" acreditaba el dinero a la distribuidora en vez de a la
beneficiaria.

Pero el BFF móvil seguía aceptándolo en `POST /credit/dispose`, con default
"SELF_USE", y pasándolo a wallet. Cartera ya lo ignora, así que no había riesgo
funcional — el riesgo es de otro tipo: **un control que la API anuncia y que no
hace nada es peor que no tenerlo**, porque quien lo use va a creer que decide
adónde va su dinero, y nada le va a decir lo contrario.

Se retira del DTO, del controlador y de la llamada a wallet. Se deja de pasar en
vez de mandarlo en duro: un valor que el receptor ignora acaba pareciendo un
contrato.

`beneficiaryPartyId` se queda: en una línea de distribuidor hay que decir A QUIÉN
se le coloca, y eso no lo sabe el producto.

Encontrado leyendo el contrato real del endpoint al corregir el sembrador — no
por una prueba. Es la clase de resto que deja un cambio de dominio en las capas
de arriba.

channel-mobile 44 pruebas · 0 fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
21d3bd0  Documentar los dos hallazgos de la verificación contra el stack

Van al plan —donde queda el razonamiento— y al README —donde alguien busca qué
está pendiente.

Las tres barreras de la tarjeta, con la tabla de cuál escondía a cuál, y la nota
de que corrige dos veces mi propio análisis: atribuí la ausencia primero a la
mezcla de pesos del sembrador y después a su filtro por behavior, quedándome las
dos veces en la primera capa que encontré.

Y los $8 250 de interés duplicado suben a la lista de deuda conocida del README,
porque es lo único de esta sesión que queda ABIERTO: en charges están marcados
reversados, en el mayor siguen asentados. Es una decisión contable, no técnica.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
140ea79  🔴 La TERCERA barrera: el CHECK de la tabla tampoco listaba CREDIT_CARD

Corregido el enum, la solicitud avanzó un paso más y rebotó con 409:

    new row for relation "credit_applications" violates check constraint
    "ck_credit_app_product"

Son tres barreras independientes, y cada una escondía a la siguiente:

  1. `seed-portfolio.Catalogo` descarta todo behavior != INSTALLMENT
     → el sembrador nunca elegía una tarjeta.
  2. `origination.ProductType` no incluía CREDIT_CARD
     → la solicitud moría en la deserialización con un 400.
  3. `ck_credit_app_product` tampoco lo lista
     → la fila rebota en la base con un 409.

Sólo se ve la tercera al quitar las dos primeras. Es la razón de fondo por la que
la demo no tenía ni una tarjeta viva, y por la que ningún escenario del grupo B se
podía probar contra datos reales — se probaban con mocks, que es otra forma de
decir que no se probaban.

Vale la pena decir lo que esto corrige de mi propio análisis: atribuí la ausencia
de tarjetas primero a la mezcla de pesos del sembrador, luego a su filtro por
behavior. Las dos veces me quedé en la primera capa que encontré. Sólo ejecutar
el journey completo contra el stack las destapó todas.

El catálogo tiene CC-IND-STD-V1 y ML-IND-STD-V1 activos desde su seed, y scoring
tiene sus políticas de riesgo listas para los dos. Todo lo demás los soportaba.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
894c393  🔴 Una tarjeta de crédito NO se podía originar: faltaba en el enum

Al sembrar el primer escenario revolvente contra el stack reconstruido, la
solicitud rebotó con 400:

    Cannot deserialize value of type ProductType from String "CREDIT_CARD":
    not one of the values accepted for Enum class

`origination.ProductType` no incluía CREDIT_CARD ni MICRO_LOAN. Y no era una
decisión de alcance:

  · el catálogo tiene CC-IND-STD-V1 y ML-IND-STD-V1 ACTIVOS desde su seed;
  · scoring tiene sus políticas de riesgo listas y activas para los dos.

Todo lo de aguas abajo los soportaba. Sólo este enum los omitía, y con eso la
solicitud moría en la deserialización antes de llegar a ninguna regla de negocio.

Es la SEGUNDA barrera, independiente de la del sembrador. El plan atribuía la
ausencia de tarjetas en la demo a que `seed-portfolio.Catalogo` filtra por
behavior=INSTALLMENT; eso era cierto y no era suficiente. Aunque el sembrador la
eligiera, el alta la rechazaba. Dos barreras, y sólo se ve la segunda cuando se
quita la primera.

origination 144 pruebas · 0 fallos: ningún switch exhaustivo dependía de que el
enum tuviera exactamente esos siete valores.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
93beb1b  🔴 El índice de idempotencia encontró el defecto que fue escrito para impedir

Al levantar el stack reconstruido, `charges` entró en bucle de reinicio: el
changeset 006 de TK-02 no podía crear su índice único porque LOS DATOS YA LO
VIOLAN.

    165 grupos duplicados · 330 cargos de más · $8 250 de interés duplicado
    en 3 cuentas del ambiente de demostración

No es un caso hipotético que la restricción previene: es el cobro doble que ya
ocurrió, con el devengo protegido sólo por `last_accrual_date` —leer, comparar y
escribir en pasos separados—, y la migración lo destapó al intentar imponerse.

Los duplicados se marcan REVERSED, que es el mecanismo que el dominio ya tiene
para «este cargo no cuenta», conservando el más antiguo de cada grupo. NO se
borran: un cargo que se cobró y desaparece deja al mayor con un asiento sin
origen, y la bitácora existe precisamente para poder explicar cada peso. El IVA
ligado se va con su interés — reversar uno y dejar el otro deja un impuesto
trasladado sobre un ingreso que ya no existe.

660 registros marcados: los 330 cargos y sus 330 IVAs.

⚠️ ESTO NO CORRIGE EL MAYOR, y queda declarado en el propio changeset.
Contabilidad ya asentó esos cargos; reversarlos aquí deja los dos lados
desalineados hasta que alguien emita las pólizas de reversa. Un changeset de
esquema no debe postear en el libro mayor por su cuenta — esa es una decisión
contable con fecha y responsable.

Y el índice excluye lo reversado: contando los duplicados no se podría crear, y
tampoco tendría sentido, porque un cargo reversado no es un cobro.

Verificación del carril: 13/13 contra el stack reconstruido.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
805e0d0  Autorrevisión: dos defectos míos en el camino de la revolvente

Revisando mi propio código mientras corrían los builds, encontré dos cosas que
las pruebas que escribí no cubrían.

1 · EL CORTE REENTREGADO ROMPÍA LA RESTRICCIÓN ÚNICA. `CycleBillingService` crea
la cuota del ciclo con `número = ciclo` y `schedule_id = cuenta`, y existe
`uq_installment_number (schedule_id, installment_number)`. Kafka entrega al menos
una vez; si entre la primera entrega y la repetición llegaba tarde el evento de
una compra con fecha anterior al corte, se intentaba una SEGUNDA cuota del mismo
ciclo y el procesamiento del corte entero se caía.

La compra rezagada pertenece al ciclo SIGUIENTE, no a uno ya facturado y ya
comunicado al cliente. Ahora se detecta el ciclo ya facturado y se deja para el
corte que viene — sin perderla: sigue exigible y sin marcar.

2 · UN TARJETAHABIENTE NO PODÍA SALTAR EL PAGO DE SU CICLO. `SkipPaymentService`
buscaba, para una revolvente, sólo en los calendarios de las DISPOSICIONES. Pero
desde BK-27 el exigible de una revolvente vive en una cuota de ciclo que cuelga
de la CUENTA. Resultado: lo único que encontraba eran los planes de compras que
ya se habían diferido, y el pago que de verdad querría saltar era invisible.

Una revolvente tiene dos fuentes de cuotas y hay que mirar las dos.

Los dos son consecuencia del mismo cambio de modelo —BK-24/22/27 movió dónde vive
lo exigible— y ninguno lo cazaron las pruebas que escribí entonces porque las dos
miraban la pieza nueva en aislamiento, no su interacción con lo que ya existía.

credit-portfolio 189 pruebas · 0 fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
b1b9565  Guion de verificación del carril del dinero contra el stack corriendo

Las pruebas de la JVM demuestran que cada pieza hace lo suyo; ninguna demuestra
que las piezas ENCAJEN en un ambiente real. Y este trabajo encontró tres tablas
de configuración que nadie sembró jamás —routing_rules, ordering_accounts,
company_mappings— precisamente porque el carril completo nunca se ejecutó.

Trece comprobaciones, cada una con lo que espera y POR QUÉ importa: si falla,
dice la consecuencia («toda orden muere con NO_ROUTING_RULE») en vez de sólo el
valor esperado. Un verificador que reporta «false» obliga a reconstruir el
razonamiento cada vez que alguien lo corre.

No inserta nada: pregunta. Es verificación, no operación.

Corrido contra el stack a medio reconstruir, da 4/13 — las de banking, que es lo
único con imagen nueva. Que discrimine bien el estado intermedio es la prueba de
que sirve.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
6721b6e  Verificación global: 1 759 pruebas en los 26 servicios

Compilan los 26 módulos —main y test— y la suite completa corre. Dos fallos
aparecieron con todo en paralelo, y ninguno es de esta rama:

· CollectionsQueueIT falla IGUAL en `main`. Es anterior y de otra naturaleza.
· NotificationFlowIT falla sólo bajo carga: aislada pasa en esta rama y en main.

Se comprobó contra un worktree en `main` en vez de asumirlo, que era la única
forma de distinguir «lo rompí yo» de «ya estaba roto».

Las dos esperan con await().atMost(20s) sobre Kafka; con la suite completa del
monorepo en paralelo, veinte segundos no alcanzan. Queda anotado como deuda real:
una prueba que depende de cuánta máquina sobra no distingue «roto» de «ocupado».

Y se retira la deuda 3 del README: `scoring-service:compileTestJava` compila y
sus 53 pruebas pasan. La deuda se quedó escrita después de arreglarse, que es la
forma más barata de que una lista de pendientes deje de merecer confianza.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
cd63da8  Parte VI: cerrar la deuda de documentación del plan

`closing-service` no tenía README, ni doc de dominio, ni fila en la tabla de
servicios — y es de esta misma rama. `banking-service` tampoco aparecía en el
README. Un servicio que existe y que el índice no menciona es un servicio que
nadie encuentra hasta que algo falla en él.

También faltaba el `package-info.java` de credit-portfolio: el corazón del
sistema, sin alcance declarado. Queda escrito qué es suyo —el saldo, y que toda
variación entra por un hecho registrado— y sobre todo qué NO: no dispersa dinero,
no decide el tipo de disposición, no calcula intereses y no decide cuándo corre
el día. Las cuatro fueron defectos reales que este trabajo corrigió, así que
dejarlas en el javadoc es lo que evita que vuelvan.

Y el aviso del diagrama C4 decía «seis» servicios no dibujados mientras enumeraba
ocho. Ahora no lleva número: contarlos ahí garantiza que la cuenta se quede vieja
al siguiente servicio, y la lista ya dice cuántos son.

El ancla del índice cambió de 24 a 26 servicios, lo que rompe en silencio
cualquier enlace viejo. Se corrigió el único que había, en docs/modules.

credit-portfolio 186 · sales-org 46 · closing 63. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
1d70256  Cierre de docs: las dos deudas del carril del dinero quedan saldadas

El README declaraba como deuda conocida que cartera no emitía
`disposition-authorized` y que faltaba la reversa del principal en
`disbursement.failed`. Las dos se cerraron en las fases 3 y 4:

· cartera emite el hecho, el Noop desapareció, y el dinero sale por
  banking → disbursement → conector, sin marcar nada completado sin evidencia;
· cartera revierte saldo y cupo al fallar el pago y al recibir
  `disbursement.returned` — que se publicaba y no escuchaba nadie, así que el
  cliente quedaba debiendo un dinero que el banco ya había devuelto.

Y se limpian tres comentarios que apuntaban a clases ya borradas
(SpeiDispatchPort, WalletDispatchPort, NoopSpeiDispatchAdapter). En el de
notifications se deja escrito por qué su stub NO es el mismo caso: aquél
confirmaba un pago inexistente y hacía que el mayor asentara una salida de caja;
éste confirma un aviso, y el peor caso es que alguien no reciba una notificación.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
cbee551  BK-49 + BK-07b: un solo validador de CLABE, y el conector sin catálogo propio

BK-49 · el dígito verificador de Banxico estaba escrito tres veces —stp,
disbursement y banking— con la misma aritmética y tres redacciones distintas del
mismo bucle. No es duplicación inocente: el día que alguien corrigiera un caso
borde en una copia, las otras dos seguirían aceptando lo que aquélla rechaza, y
el síntoma sería que el mismo número pasa en un servicio y rebota en otro.

Vive ahora en shared.banking.ClabeCheckDigit. Las tres copias quedan como
fachadas que delegan, y no se borran porque StpDecouplingTest exige que el
conector se pueda extraer a otro repositorio: el único import que eso permite es
`shared`.

La consolidación expuso una diferencia real entre las copias: la de banking
recibía la CLABE ENTERA y leía sólo los primeros 17 caracteres, callada. La
compartida exige el cuerpo exacto y tiene razón — pasarle la cadena completa
oculta un malentendido sobre qué recibe. Veintitrés pruebas lo dijeron en la
primera corrida.

Y las tres copias tenían ENTRE LAS TRES una sola prueba de transposición.
Consolidar no fue sólo quitar líneas: fue poder escribir los casos borde una vez.

BK-07b · la caída al catálogo local cubría la ventana de la migración. Retirada,
stp NO PUEDE elegir por dónde sale el dinero: sin cuenta ordenante en la orden,
se rechaza con un mensaje que dice por qué. Se fue la tabla, sus cuatro clases y
el endpoint de alta.

Por qué se tira y no se conserva «por si acaso»: mientras exista, alguien puede
darle de alta una fila y creer que con eso el dinero sale por ahí. No saldría —
el ruteo lo decide banking, que no la mira. Dos catálogos de cuentas propias, uno
de ellos mudo, es peor que ninguno.

`ordering_clabe` pasa a NOT NULL: es lo que impide que una regresión reintroduzca
en silencio la firma contra el catálogo de hoy.

689 pruebas verdes en los once módulos tocados.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
99f3db2  BK-45..BK-47: sembrado de escenarios revolventes, y una causa más profunda

El plan decía que seed-portfolio.py no siembra revolventes porque su mezcla de
pesos sólo tiene PERSONAL_LOAN, PAYROLL_LOAN y SME_LOAN. Al corregirlo, la causa
resultó ESTRUCTURAL:

    if p.get("behavior") != "INSTALLMENT": continue

`Catalogo` descarta todo lo que no sea INSTALLMENT. Aunque alguien añadiera
CREDIT_CARD a PESOS_PRODUCTO, seguiría sin aparecer.

Comprobado contra el stack corriendo: 9 productos activos, 4 de ellos REVOLVING
—tarjeta, línea de uso propio, línea de distribuidor y línea empresarial— y el
sembrador general no puede ver ninguno.

seed-revolventes.py es el espejo: sólo admite REVOLVING y expone la misma
interfaz, así que Cliente no nota la diferencia. La regla rectora se conserva:
cada persona recorre el journey real de la app reutilizando el mismo código,
IMPORTADO y no copiado — doscientas líneas duplicadas se desincronizarían con el
primer cambio de la app.

Y los productos ahora declaran sus opciones de pago (BK-23, la mitad de seeds que
faltaba): la tarjeta difiere POST_HOC con 3 y 6 MSI, 9 al 18 % y 12 al 24 %; la
línea de distribuidor fija el plazo AL colocar y no difiere después; el préstamo
personal admite BNPL a 30 días y salta pagos como GIFT. Cuatro pruebas fijan esa
configuración: si alguien retira las bandas de MSI, el escenario S2 deja de poder
sembrarse y esto lo dice antes.

⚠️ Verificado sólo hasta donde el stack desplegado alcanza: sus imágenes son
anteriores a esta rama, así que los endpoints de diferir, saltar y apoyo no
existen ahí todavía. Lo confirmado empíricamente es el hueco del catálogo y que
CatalogoRevolvente encuentra los productos.

credit-product 72. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
3cd9668  BK-48: pruebas Spring de disbursement y stp — y los dos defectos que destaparon

Los dos servicios del carril del dinero tenían cero pruebas con contexto. Sus
listeners ACL —la frontera donde muere todo el vocabulario de crédito— no
estaban probados, y son justo el punto donde un cambio de contrato se manifiesta:
un campo que deja de llegar no rompe la compilación, rompe el pago.

🔴 En su PRIMERA ejecución encontraron dos cosas.

1 · El evento que yo mismo escribí en la Fase 3 tenía el beneficiario ANIDADO.
Cartera publicaba {destino: {beneficiaryName, …}} y disbursement lo declara
plano, como el resto de sus payloads. Los tres campos que deciden adónde va el
dinero llegaban nulos: la orden no se podía crear y la disposición se quedaba
PROCESSING para siempre sin que nadie supiera por qué.

2 · `company_mappings` tampoco la sembró nunca nadie — el mismo hueco que las
rutas, en otro sitio. Con la tabla vacía DB-07 rechaza TODA orden con
UNRESOLVED_COMPANY antes de llegar al proveedor. Y cartera publicaba companyId
nulo SIN clave de empresa, así que ninguna disposición habría sido pagable.

La corrección tiene dos mitades: cartera manda `sourceCompanyKey` con la unidad
de origen —no conoce el catálogo de empresas del orquestador, ni debe, pero sí
sabe de qué sucursal es la cuenta— y la resolución cae a un comodín `*` cuando no
hay mapeo específico. Es lo que evita que dar de alta una sucursal nueva rompa
sus pagos en silencio el día que alguien coloque el primer crédito ahí.

Y un tercer arreglo, en el arnés: AbstractIntegrationTest sobrescribía la URL
pero no el DRIVER, y varios servicios declaran el de Testcontainers en su perfil
`test`. El driver sobreviviente rechazaba la URL corriente con un «claims to not
accept jdbcUrl» que no menciona en ningún lado que el problema es el driver.

shared 43 · stp 44 · disbursement 41 · credit-portfolio 186 · banking 61.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
df34a8d  BK-41 + BK-42: la cadena del dinero, consultable en ambos sentidos

El problema no era que la cadena no existiera: cada eslabón guarda el id del
anterior. El problema es que recorrerla exige saltar de cartera a disbursement,
de ahí al conector, de ahí al estado de cuenta y de ahí al mayor — y nadie la
recorre así en una investigación real. Se pregunta por chat.

Las cuatro preguntas, un endpoint cada una: por crédito, por clave de rastreo,
por línea bancaria y por póliza.

🔑 Lo que hace útil la traza no es el sí, es el NO. `/traces/incomplete` devuelve
lo que se quedó a medias CON el eslabón en que se detuvo: SIN_RUTEAR,
SIN_DESPACHAR, SIN_CONCILIAR, SIN_ASENTAR. Saber que una traza está incompleta no
ayuda; saber que le falta conciliar dice a quién preguntarle.

Tolera el desorden a propósito: Kafka no garantiza orden entre topics, y la
conciliación de un pago puede llegar antes que su despacho si el poller del banco
corrió primero. Un tramo que llega antes que el anterior no se descarta —
descartarlo dejaría trazas eternamente incompletas por una carrera que no es un
error.

Es una proyección, no una fuente de verdad: cada eslabón sigue siendo dueño de su
dato, y se guarda de qué evento vino cada tramo para poder reconstruirla.

banking 61. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
92801ce  BK-37..BK-40: conciliación bancaria

AN-06 cayó del lado barato: el contrato de STP ya expone tipoOrden="R", así que
la ingesta extiende el poller en vez de construir un ingestor de archivo, que
era la rama cara de la bifurcación.

🔑 La regla que ordena el matching: CON DOS CANDIDATOS NO SE ELIGE. Cruzar con
«el primero» cuadraría el reporte y cruzaría el pago contra otra persona — el
total daría bien y dos cuentas individuales estarían mal. Es el peor desenlace
posible aquí porque nadie lo busca. Va a la puente y decide una persona.

Lo mismo con una clave conocida cuyo importe no coincide: no se cruza. Es la
señal más nítida de que algo salió mal, y taparla cuadrando el reporte la haría
invisible. El motivo se guarda con la partida y la ambigüedad se declara primero:
decir «sin movimiento equivalente» cuando había dos manda a buscar mal.

Una decisión que no estaba en el plan: `internal_movements`. El plan daba por
hecho que la conciliación se apoyaría en `settlement_observations` de stp. Al
implementarlo: conciliar es BARRER un día entero, no preguntar de uno en uno.
Una consulta por línea contra stp o disbursement convertiría cada cierre en
cientos de llamadas y ataría un proceso por lotes —tolerante a que el vecino
esté caído— a la disponibilidad de otro servicio. Banking proyecta los hechos
internos en su propia tabla, con idempotencia por source_event_id: un reintento
de Kafka volvería ambiguo un cruce que era determinista.

Y banking sigue sin conocer el dominio de crédito: recibe referencia opaca,
importe, fecha y clave de rastreo. Si tuviera que entender qué es una disposición
para conciliar, dejaría de ser tesorería.

El sello: si no cuadra, no se sella. Sellar «con observaciones» lo convierte en
trámite; su único valor es que un sello emitido signifique que ese día cuadró.
La alerta TIENE consumidor, que es lo que la distingue del
publishReconciliationAlert que el análisis encontró declarado en contabilidad sin
una sola invocación. Y las partidas abiertas se acumulan: una de hace tres días
sigue explicando la diferencia de hoy.

banking 54. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
edb857b  BK-32..BK-36: programa de apoyo por contingencia, como reestructura

Correr el monto a la siguiente fecha de pago, que no genere saldo por cobrar en
ese momento, y decidir a qué créditos se otorga, es jurídicamente un diferimiento
de pagos: una reestructura. El camino alterno —un tratamiento contable especial—
depende de una autorización que puede no estar vigente cuando la contingencia
ocurra, que es justo cuando hay que actuar rápido.

El costo se asume con los ojos abiertos: marca forborne, piso IFRS-9 STAGE_2,
reloj de cura reiniciado, y la reserva SUBE. A cambio el historial ante el buró
no se degrada — collections sólo reporta WRITE_OFF y QUITA_PARCIAL.

Maker-checker de verdad: quien propone no autoriza, y la identidad viene del
gateway, no del cuerpo — dejar que el cliente diga quién es convertiría la
separación de funciones en una declaración voluntaria. El padrón se simula ANTES
de autorizar: quien firma ve a cuántas cuentas alcanza antes de que se mueva un
solo vencimiento.

La fecha de corte de elegibilidad va antes de la vigencia y se valida. Sin eso,
un programa anunciado hoy alcanzaría a quien dejó de pagar al enterarse de que
venía: el apoyo taparía mora provocada por el propio anuncio.

Se corren TODAS las cuotas pendientes, no sólo las N diferidas — dejar las de
atrás quietas amontonaría los vencimientos justo cuando la vigencia termina. Y
el otorgamiento es idempotente por (programa, cuenta): es lo que pasa cuando
alguien reintenta un lote que pareció fallar.

🔑 El apoyo surte efecto por el mecanismo que YA existe. Con los vencimientos
corridos no queda cuota vencida, el DPD es cero, y un DPD en cero ya cierra el
caso de cobranza y apaga la mora por los caminos de siempre. No hace falta un
listener en collections que pregunte «¿está bajo apoyo?» — sería un segundo
camino para el mismo efecto, y el segundo es el que se queda atrás cuando la
regla cambia. Lo único que sí hizo falta: recalcular y publicar el DPD en el
acto, porque entre el otorgamiento y la medianoche cobranza seguiría escalando.

risk reutiliza `onRestructureExecuted`: un apoyo ES una reestructura, y tener dos
caminos que marcan forborne garantizaría que uno se quede atrás.

Segundo tropiezo con SMALLINT contra int: con `ddl-auto: validate`, SMALLINT
exige `short` del lado Java. Queda escrito en el changeset.

credit-portfolio 185 · risk 54. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
99b9ca2  BK-30 + BK-31: saltar un pago

Cero referencias en todo el monorepo antes de esto: ni la palabra ni el concepto.

Saltar congela la deuda, y qué significa congelarla lo decide el PRODUCTO: GIFT
no devenga el período (recompensa real), DEFERRAL sigue devengando y sólo aplaza.
Dejarlo a elección de quien pide convertiría una decisión de producto en una
preferencia.

Se corren también las cuotas de atrás. Mover sólo la elegida la dejaría encima
de la siguiente y el cliente tendría dos vencimientos el mismo día — lo contrario
de un respiro. Pero las de atrás NO consumen el tope de saltos: no son un
beneficio otorgado.

Un período de la cadencia del producto, no un mes fijo: una línea quincenal se
corre quince días.

La cuota vuelve a PENDING: una que ya se había vencido y se salta deja de estar
vencida. Sin eso, saltar un pago produciría exactamente la mora que pretende
evitar, porque el envejecido la seguiría encontrando al día siguiente.

`original_due_date` se guarda siempre — es lo que permite explicarle al cliente,
y a una revisión, de qué fecha a qué fecha se movió su compromiso y por qué.

credit-portfolio 169. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
b3dd55e  BK-28 + BK-29: BNPL corre el devengo Y el vencimiento

No existía forma de decir «este crédito empieza a pagar el 15 de septiembre».
`needsAccrual` devengaba desde que se creaba el calendario, así que un producto
BNPL cobraba interés desde el primer día — exactamente lo contrario de lo que
promete.

Los dos van juntos porque correr sólo uno es el error obvio y silencioso: con el
devengo corrido y el plan quieto, el cliente no paga interés pero su primera
cuota vence igual, y el «compra ahora, paga después» le llega con una cuota
exigible antes de haber empezado a pagar.

El día de arranque SÍ devenga: `isBefore`, no `isEqual`. Un tope que excluyera
su propio primer día regalaría una jornada de interés en cada crédito con BNPL —
poco por crédito, mucho por cartera.

Nulo significa «desde el alta», que es lo que son todos los créditos existentes.
Un default con fecha los pondría a todos a arrancar el día del despliegue.

Y la prueba de contrato del campo nuevo, porque es exactamente el defecto que
BK-44 cazó con `paymentFrequency`: un campo sin getter no se serializa, compila
igual y no rompe ninguna otra prueba.

credit-portfolio 160 · charges 50. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
0550367  BK-25 + BK-26: diferir una compra, y la reversa que el plan calculaba mal

Diferir es la mecánica opuesta a la del distribuidor: allá el plazo se fija AL
colocar, aquí la compra ya ocurrió y el titular decide después, desde la app,
antes de que corte el ciclo. La disposición pasa de REVOLVING a AMORTIZED y con
eso sale sola del exigible del corte — sin restar nada.

La tasa viaja en la configuración del producto, no se consulta al catálogo en el
momento de diferir: es una acción del cliente desde la app, y meterle una llamada
síncrona entre servicios la vuelve frágil justo donde el cliente está mirando.
Sin banda de tasa para ese plazo se RECHAZA; no se cae a la de originación,
porque diferir al 36 % una compra que el cliente creía a MSI es exactamente el
error que este mecanismo existe para impedir.

🔴 El supuesto de BK-26 no se sostuvo. El plan decía «cada devengo diario entre
la compra y el diferimiento se reversa». Pero el devengo ordinario de charges es
POR CUENTA, no por disposición: un cargo diario de una tarjeta cubre el saldo
completo de la línea, así que reversarlo entero devolvería también el interés de
las compras que NO se difirieron.

Se reversa la parte atribuible: importe × tasa / 360 × días. Con una compra de
$6 000 diez días revolvente al 36 % son $60; sobre el saldo completo de la línea
($20 000) habrían sido $200 — más del triple, y a favor del cliente, lo que lo
vuelve difícil de detectar y caro de sostener.

Entra como cargo nuevo en negativo, no editando los diarios: la bitácora es
append-only y borrar lo que ya se cobró dejaría al mayor sin explicación. Y el
IVA se reversa CON el interés: reversarlo solo deja un impuesto trasladado sobre
un ingreso que ya no existe, y eso se descubre en la declaración.

credit-portfolio 159 · charges 45 · credit-product 68. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
820a3d6  BK-25b/25c + AN-28: un MSI ya se puede configurar, y algo lo fija

El bloqueador era literal: CHECK (nominal_rate > 0 AND nominal_rate < 1). Meses
sin intereses es tasa cero, así que un MSI se rechazaba en la base de datos,
antes de llegar a ninguna regla de negocio.

La nominal se relaja a >= 0. La moratoria se queda en > 0, y no es una omisión:
una promoción puede no cobrar interés ordinario, pero si además no cobrara
moratorio, diferir sería una forma de dejar de pagar sin consecuencia.

`purpose` distingue la tasa de diferir de la de originar. Sin él un producto no
puede colocarse al 36 % y a la vez ofrecer 3 y 6 MSI: las dos competirían por la
misma banda de plazo, y perder esa competencia significa diferir al 36 % una
compra que el cliente creía a meses sin intereses. Se filtra ANTES que las
bandas y no participa en la especificidad: es un filtro, no un desempate. Vacío
significa que el producto no difiere — no se cae a la tasa de originación.

AN-28 había encontrado que `fixedPayment` ya devuelve capital/n con tasa cero,
así que el motor soporta MSI sin cambios. Nada lo fijaba: un refactor del cálculo
de la cuota o un cambio de redondeo podía romperlo en silencio, y el síntoma
sería que un cliente al que se le prometió MSI acaba pagando interés. Cuatro
pruebas lo cierran, incluida que el total del plan es EXACTAMENTE el importe de
la compra aunque no divida exacto.

credit-product 68 · credit-portfolio 150. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
6c44855  BK-23 + BK-24/22/27: la tarjeta nace revolvente y el corte le da algo que vencer

El modelo estaba invertido. Toda disposición nacía con calendario, también la de
tarjeta. Eso es correcto para el distribuidor —el vendedor decide al colocar «a
cuántos meses se lo dejas»— y al revés para una tarjeta: la compra nace
revolvente pura, exigible entera en la siguiente fecha de pago posterior al
corte, y el titular decide diferirla después. Con el modelo anterior una compra
ya nacía parcializada al plazo por defecto del producto: ni lo que el cliente
pidió ni lo que el corte debía exigirle.

Los tres van juntos porque AN-20 lo obliga: quitar el calendario sin dar al corte
algo que exigir deja a la línea sin nada que vencer. El envejecido busca cuotas
vencidas; sin ninguna, la línea sale con cero días de atraso siempre, y ese cero
arrastra a riesgo, a cobranza y al quebranto detrás.

BK-24 · `Disposition.planMode`: REVOLVING (sin plan) | AMORTIZED (con plan).
BK-22 · la fecha exigible de una revolvente sale del corte.
BK-27 · el corte materializa una cuota del ciclo con el exigible.

La resta del exigible sale sola: diferir una compra la convierte en AMORTIZED,
deja de ser REVOLVING y por tanto deja de sumar. No hay que restarla — ya no
está. Y `billed_cycle` evita que el corte siguiente vuelva a exigir las mismas
compras, porque `planMode` sigue siendo REVOLVING después de facturarlas.

La cuota del ciclo lleva SÓLO capital: el ordinario lo devenga charges día a día
y meterlo aquí lo cobraría dos veces. Además mantiene correcta la base del
moratorio, que es capital vencido (BK-19).

BK-23 · los diez campos nuevos van en `OpcionesDePago`, no como diez posiciones
más en `Capabilities`. Un record de diecinueve se construye mal tarde o temprano
y ya pasó: una prueba de contrato cazó dos campos que nunca se poblaron. Un
constructor de nueve argumentos que delega mantiene compilando todo lo existente.

Dos tropiezos que dejo escritos: SMALLINT contra Integer tumbó 27 pruebas por
`ddl-auto: validate`; y varias ITs de cartera caen al Postgres del stack local,
que quedó con el changeset registrado con el tipo viejo — se reparó en sitio.

credit-portfolio 146 · credit-product 63 · closing 63 · charges 40 · banking 39.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
a3aa611  BK-18..BK-21: la mora existe, y se cobra sobre lo que corresponde

El circuito estaba cortado en un punto exacto. Cartera medía el DPD desde las
cuotas vencidas y publicaba `delinquency-status-updated`. Charges consumía
balance-updated, charge-rejected y credit-account-activated — y no la mora.
`activateMoratorium()` tenía CERO llamadores de producción, sólo dos pruebas, y
la cuenta contable 4102 no había recibido un abono en toda la historia.

El orden se respetó: BK-19 ANTES que BK-18. Al revés, la primera corrida del
listener habría encendido el cobro sobre el saldo completo en toda la cartera
vencida.

BK-19 · la base era `principalBalance` — todo el saldo. Sobre un crédito de
        $10 000 con una cuota vencida de $1 000 de capital cobraba sobre los
        $10 000: diez veces. Ahora es el capital vencido, que cartera publica.
        Capital y no importe: cobrar mora sobre el interés de la cuota es
        interés sobre interés, y en una cuota francesa temprana el interés es la
        mayor parte del importe.
BK-18 · el listener que faltaba.
BK-20 · `clearMoratorium()` también existía sin llamador: una vez encendida, la
        mora seguía devengando aunque el cliente se pusiera al corriente. Ahora
        capital vencido cero la apaga en la misma pasada.
BK-21 · el hallazgo fue otro del previsto. Charges y el seed del cierre ya
        decían 3; el DEFAULT de la columna decía 0. Coincidían mientras alguien
        especificara la columna — la primera política dada de alta sin ella
        dejaba esas cuentas cayendo en mora un día después del vencimiento con
        las vecinas teniendo tres.

Una cuota PARTIAL cuenta entera: el modelo no lleva el abono acumulado por
mensualidad y sin esa columna descontar «algo» sería inventarlo. Se sobreestima
y se declara.

Y un error mío que las pruebas cazaron en la misma pasada: un replace sin
contador dejó el devengo ORDINARIO cobrando sólo sobre lo vencido. Tres pruebas
fallaron de inmediato. Queda escrito por qué son bases distintas.

charges 40 · credit-portfolio 139 · closing 63. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
7c23c69  BK-11..BK-17: el dinero sale de verdad — mueren los dos despachadores paralelos

`SpeiDispatchPort` tenía UNA implementación: un stub que devolvía
"SPEI-STUB-XXXXXXXX", marcaba la disposición completada y publicaba
disposition-completed. Contabilidad asentaba 1201 → 1101 —salida de caja— de
dinero que nunca salió: el activo crecía y el banco bajaba contra nada. El de
wallet era peor: un DUPLICADO del camino correcto, que ya existía y funcionaba
(`wallet.withdrawal-completed` → disbursement); se adelantaba al evento.

BK-11/14 · cartera autoriza y publica `disposition-authorized` — el topic que
           disbursement llevaba escuchando sin emisor. Se completa en
           `onDisbursementCompleted`, con evidencia del proveedor.
BK-12 · el puerto de wallet se borra sin sustituto.
BK-13 · el tipo de disposición sale del PRODUCTO, no de la petición. Cuando
        venía en la petición, una solicitud sobre una DISTRIBUTOR_LINE que
        mandara "SELF_USE" —o un valor basura, que caía al mismo default
        silencioso— acreditaba el dinero a la distribuidora en vez de mandarlo
        a la beneficiaria. Sin configuración de producto la disposición se
        DETIENE: adivinar el tipo es adivinar a quién se le manda el dinero.
BK-16 · `disbursement.returned` se publicaba y nadie lo escuchaba. El cliente
        quedaba debiendo un dinero que el banco ya había devuelto. Ahora cartera
        revierte saldo y cupo, y también al fallar el pago.
BK-17 · `process_thirdPartyCredit_dispatchesSpei` dejó de compilar —la señal de
        que la corrección llegó— y afirma ahora lo contrario.

Y lo que las pruebas destaparon al reescribirlas: las de colocación a tercero se
hacían sobre una línea de USO PROPIO mandando "THIRD_PARTY_CREDIT" en el comando.
Es literalmente el agujero. Ahora necesitan una línea cuyo producto disponga a la
beneficiaria, y no hay forma de pedirlo desde fuera.

Contabilidad: DISPOSITION_SELF_USE deja de abonar a 2101 (fondos de clientes) y
abona a 1101 — con el monedero en hold toda disposición sale a una cuenta
bancaria, y ese 1101 sí tiene contraparte conciliable. Se añaden las pólizas de
DISPOSITION_RETURNED y DISPOSITION_FAILED: sin ellas la reversa de cartera no
tenía contrapartida y el activo se quedaba inflado.

410 pruebas verdes en los ocho servicios tocados.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
1a10278  BK-07..BK-10: tesorería decide por dónde sale el dinero

La decisión estaba partida y ninguna mitad la tomaba entera:
`disbursement.routing_rules` elegía el PROVEEDOR por (empresa, rail, monto), y
la CUENTA la resolvía el conector de STP con un `is_default` por empresa, ciego
al saldo, al costo y al horario. Media decisión cada uno, la responsabilidad
entera de ninguno.

BK-09 · `PayoutRoutingService` toma las dos. Mismo desempate que ya usaba el
        ruteo de proveedor —para que migrar no cambie por dónde sale hoy— más
        dos desempates finales: `findAllEnabled()` no garantiza orden, y sin
        ellos dos rutas empatadas harían que el pago saliera un día por una
        cuenta y al siguiente por otra sin que nadie cambiara nada. Y una ruta
        cuya cuenta está SUSPENDED se salta: si no, suspender no significaría
        nada y el pago fallaría en el proveedor, donde ya no se corrige.
BK-08 · `routing_rules` se va entera. Dejarla de respaldo sería peor: dos
        catálogos de por dónde sale el dinero, uno sin lector, esperando a que
        alguien lo edite creyendo que sirve.
BK-07 · la cuenta ordenante viaja en el mensaje hasta el conector (paso 1 de 2).
BK-10 · contrato ACL. Los dos ArchUnit prohíben ahora `com.fintech.banking..`.

🔴 EL HALLAZGO: la configuración del carril NUNCA se sembró. Ni un changeset ni
un script insertó jamás una fila en `routing_rules` ni en `ordering_accounts`.
Con la tabla vacía cada orden se aplazaba con NO_ROUTING_RULE y a los seis
intentos moría FAILED. Que no se notara en ningún ambiente es la prueba más
limpia de que el carril del dinero nunca se ejecutó: el `Noop` de cartera lo
cortocircuitaba antes. Mover la configuración sin sembrarla habría reproducido
el hueco con otro nombre — de ahí `005-seed-demo` y `SeedDelCarrilIT`.

Desviación de BK-10: el contrato es HTTP, no un evento. La petición de ruta es
pregunta-respuesta; como par de eventos exige correlación y deja órdenes en
limbo. Lo que el plan pedía de fondo —sin imports entre módulos— se cumple y
los ArchUnit lo imponen. La consecuencia se resolvió a propósito: se distinguen
TRES desenlaces, no dos. Sin ruta gasta intento (configuración incompleta debe
doler); tesorería caída espera 30 s SIN gastar intento. Confundirlos haría que
una caída de minutos matara órdenes válidas. Hay una prueba de diez pasadas con
tesorería caída que afirma cero intentos consumidos.

Y un defecto latente que salió de paso: el relay releía la cuenta ordenante por
id AL FIRMAR, minutos después de registrar. Cambiar la cuenta de una empresa en
esa ventana alteraba la cadena original de una orden ya registrada. La orden
guarda ahora la fotografía de la cuenta con la que se firma.

banking 39 · disbursement 37 · stp 34 · closing 62 = 172 pruebas, cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
df344c9  BK-03..BK-06: dominio banking (D15) — cuentas propias y las dos puentes

El camino del dinero no tenía dueño: la CLABE de la que salía vivía dentro del
conector de STP (`stp.ordering_accounts`) y se elegía con un `is_default` por
empresa, ciego al saldo y al costo. `disbursement.routing_rules` decidía el
proveedor y nunca la cuenta. Este servicio se queda con las dos decisiones.

BK-03 · módulo Gradle, puerto 8104, schema `banking`, hexagonal, Compose.
BK-04 · las 5 tablas `bank_*` se mudan de `closing`. AN-12 confirmó cero clases
        Java tocándolas: el changeset que las creaba se retira y uno nuevo las
        tira, para que converjan tanto una base nueva como una ya migrada.
BK-05 · `BankAccount` + `Clabe` como VO: dígito verificador comprobado al alta,
        y la CLABE nunca sale completa — ni en respuesta, ni en bitácora, ni en
        el mensaje del error de duplicado.
BK-06 · las cuentas puente del catálogo contable.

Desviación de BK-06: son DOS puentes, no una. El plan pedía «1109 Depósitos por
identificar»; una sola cuenta obliga a que los cargos no aclarados vivan en una
cuenta deudora llamada «depósitos», con saldo del signo contrario al de su
nombre. Un abono sin dueño es PASIVO (2109) y un cargo sin aclarar es ACTIVO
(1109): juntarlos sería compensar activo con pasivo.

Hallazgo del mismo pase: `suspense_entries.status` admitía WRITTEN_OFF sin
contrapartida contable — la partida se cerraba en banking y en el mayor seguía
viva para siempre. La cierran 4105 y 5105.

Y la trampa que evita `BANK_DEPOSIT_IDENTIFIED`: si el asiento de identificación
cargara contra 1201, el flujo de pagos postearía después su PAYMENT_APPLIED
(1101 → 1201) y el banco subiría dos veces por el mismo depósito. Por eso la
reclasificación sale contra 1101 y deja que el flujo real ponga su pata.

Deuda declarada, no escondida (BK-49): el dígito verificador está escrito tres
veces (stp, disbursement, banking). Consolidarlo en `shared` toca el camino
caliente del dinero en dos servicios que la Fase 3 va a reescribir; se hace
después de esa fase.

banking 24 pruebas · closing 62 (la que baja se mudó) · accounting 28. Cero fallos.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
df8aed8  BK-44: pruebas de contrato entre productor y consumidor

En este monorepo cada consumidor re-declara a mano el payload del productor en
su paquete ACL. Es una decisión defendible —no acopla los módulos— con un costo
que se pagó caro: un cambio de contrato entre dos servicios no rompía nada, ni
al compilar ni al probar. Así sobrevivieron tres listeners que escuchan topics
que nadie publica y cuarenta y dos topics que nadie consume.

`ContratoDeEvento` cierra el hueco sin acoplar: serializa el evento del
productor, lo deserializa con el payload que declara el consumidor —usando el
mismo JsonDeserializer que construye el contenedor de Kafka, porque la
tolerancia a campos desconocidos depende de cuál se construya— y afirma que los
campos que el consumidor lee llegaron con valor.

En su primera ejecución encontró un bug introducido en TK-06 por mí:
`paymentFrequency` y `termPeriods` se añadieron al evento de activación y sus
getters nunca se escribieron. Sin getter Jackson no serializa, así que el cierre
habría recibido la cadencia nula y caído a mensual para todo producto — un
calendario de corte quincenal o semanal mal derivado, en silencio. Compilaba y
las trescientas dos pruebas seguían verdes.

Es el mismo defecto que el propio archivo documenta unas líneas más arriba sobre
`originUnitCode`: el campo existe, el constructor lo recibe, y sin getter el
dato nunca viaja. El mismo error, en el mismo archivo, dos veces — que es la
justificación de que esta prueba exista.

243 pruebas ✅ (shared 43, closing 63, cartera 137)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
11a3966  AN-01..AN-28 y BK-43: análisis previo y arnés de integración compartido

Los veintiocho análisis previos ejecutados, veinticinco resueltos y tres a la
espera del stack levantado. Dos resultados reducen el alcance del plan y tres
lo corrigen.

El que más lo reduce: el contrato de STP ya expone abonos recibidos. El puerto
documenta `@param tipoOrden "E" enviadas · "R" recibidas` y la constante que
fija "E" está en el llamador, no en el contrato. La ingesta del estado de cuenta
extiende el poller que ya existe en vez de necesitar un ingestor de archivo con
su formato, validación y reproceso.

Y la cadena de identificadores resultó estar completa: `tracking_key` se
persiste con restricción de unicidad en las órdenes de pago y con índice en las
observaciones de conciliación, que ya son la proyección de lo que el banco
reporta. Falta sólo la tabla que une los eslabones.

Tres correcciones al plan. Los dos calendarios no se unifican: la ventana de
SPEI es intradía y de rail, el de negocio es de fecha contable, y confundirlos
sería un error. La corrección de la base del moratorio va antes de conectar su
listener, porque hacerlo al revés convertiría un cobro diez veces mayor de
latente en activo. Y la revolvente pura no puede entregarse sola: el cálculo de
mora busca cuotas vencidas, así que sin calendario no hay nada que vencer salvo
que el exigible salga del corte.

El arnés compartido sustituye el bloque de doce líneas de Testcontainers que se
repite en unos treinta y seis archivos, y con él el contenedor por clase de
prueba. Los contenedores se levantan una vez por JVM y no se cierran: el
aislamiento viene de truncar el esquema, no de reiniciar.

Al migrar el primer piloto salió el fallo que hacía falta ver: truncar el
esquema entero también borra lo que Liquibase sembró, y el síntoma es
desconcertante — todo falla diciendo que no existe la configuración sin que
nadie la haya tocado. La clase base no puede distinguir catálogo de dato
transaccional, así que lo declara la prueba.

106 pruebas ✅ (shared 43, closing 63)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
0b7375d  TK-07: cierre diario de cartera con el día de negocio recibido

`BusinessDayRunner` corre el día completo —planifica, trabaja y sella cada
fase— avanzando por dependencia y no por hora de reloj. El día se recibe como
parámetro, que es lo que permite reproducir el cierre del 15 el día 20 y
recorrer el ciclo de vida de un crédito sin esperar meses reales.

El cierre no devenga ni calcula mora: publica que la ventana está abierta y el
dueño del cálculo reacciona. Meter aquí la aritmética del interés crearía una
segunda fuente de verdad sobre el mismo número. La única fase donde hace trabajo
propio es el corte, porque el corte es suyo.

Y ahí hay una línea que se dejó deliberadamente sin cruzar: para un producto a
plazo el cierre no inventa el importe de la cuota. Ese número sale del plan de
amortización, que es de cartera, y duplicar la aritmética produciría dos
importes que divergen al primer redondeo. El corte viaja con el saldo y cartera
completa el exigible. Para un revolvente sí calcula el pago mínimo, porque ese
número nace del corte y no existe en ningún otro lado.

Las pruebas destaparon un bug en el sello: `isIntact()` devolvía false para todo
sello leído de la base. La huella se calculaba sobre `toPlainString()` de los
importes y `NUMERIC(19,4)` devuelve siempre escala 4, así que se resumía "20000"
al sellar y "20000.0000" al releer. La detección de manipulación quedaba
inservible justo cuando se usa, al auditar un sello viejo. Se normaliza a la
escala de la columna antes de resumir.

Y una expectativa mal puesta en una prueba, no un fallo del motor: el 10 de mayo
de 2026 es domingo y el corte se corrió al lunes, que es exactamente lo que la
política pide.

63 pruebas en closing-service ✅

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
c73aa20  TK-06: el calendario de corte lo deriva y lo persiste el cierre

Para un producto no revolvente la cadencia del corte coincide con el
vencimiento de la cuota, así que la tentación es leer installments.due_date de
cartera. No se hace, por tres razones: consultar cartera durante la ventana de
cierre rompe el aislamiento y es lo que hoy hace que el barrido compita con la
API del backoffice; el corte es una decisión de política —cortar N días antes,
correrse si cae inhábil— que un plan de pagos de cara al cliente no puede
expresar; y un corte sellado es inmutable, así que una reestructura no puede
reescribirlo hacia atrás.

Para poder derivarlo hacía falta un dato que el evento de activación no traía.
Se enriquece con la cadencia y el plazo, que cartera ya tiene a mano al activar.
Antes de tocarlo se verificó que los once consumidores toleran campos nuevos:
diez lo declaran con @JsonIgnoreProperties y wallet no, así que se escribió una
prueba que ejercita el mismo deserializador que usa el contenedor y demuestra
que tolera igual —Spring Kafka lo construye con un ObjectMapper que ya desactiva
FAIL_ON_UNKNOWN_PROPERTIES—. Queda como guardia del contrato.

La proyección por cuenta se alimenta de los eventos que cartera ya publica y
nunca la consulta. Un balance-updated fuera de orden no retrocede el saldo
recordado: llegaría después uno más nuevo y la proyección quedaría oscilando.

Dos pruebas fijan la raíz del problema de conciliación: dos tarjetas activadas
en días distintos cortan en días distintos, y un corte sellado no se reabre. De
ahí sale que el cuadre vaya por flujo sobre la fecha de negocio y no por corte.

302 pruebas ✅ (shared 43, closing 53, cartera 133, charges 33, wallet 40)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
bfdcb70  TK-05: motor de corridas con reparto por candado, sin locks de base de datos

El reparto es: leer candidatas con un SELECT normal, ganar el candado de Redis
de la unidad y confirmar con un UPDATE condicional. Si el CAS devuelve cero
filas, otro pod se adelantó y se sigue con la siguiente — nadie espera a nadie.
No hay un solo FOR UPDATE en el servicio.

Tres trampas costaron una corrección cada una, y las tres eran invisibles al
compilar:

`@Transactional` sobre un método llamado desde la misma clase no pasa por el
proxy y no hace nada. Sería un REQUIRES_NEW decorativo, y el primer fallo de una
unidad marcaría la transacción del lote como rollback-only, revirtiendo el
trabajo ya hecho de las anteriores. Se usa TransactionTemplate, que es lo que
OutboxRelayService ya documenta en este monorepo por la misma razón.

La marca de FAILED no puede ir en la misma transacción que el trabajo: el
rollback se la lleva y la unidad reaparece como CLAIMED sin causa. Son dos
transacciones, una que hace y deshace y otra que graba por qué.

Y `@ConditionalOnBean` depende del orden de evaluación. La autoconfiguración del
candado se procesaba antes que la de Redisson, el cliente todavía no existía, la
condición fallaba en silencio y el bean simplemente no aparecía. Se arregla con
`@AutoConfiguration(afterName = …)` y registrando cada configuración por
separado.

El servicio no arranca sin candado, y eso se conserva: las pruebas que no van
sobre el reparto usan un doble en memoria en vez de relajar la regla.

Las diez pruebas van contra Postgres y Redis reales, porque las tres cosas que
importan —tres pods repartiéndose sin solaparse, el pod que muere sin llevarse
su lote en silencio, y el titular de un candado vencido que no puede escribir—
no se ven con mocks.

83 pruebas ✅ (shared 43, closing-service 40)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
c3bfa66  TK-04: calendario de negocio y política de cierre por producto

La fecha de un cierre era el reloj del pod que lo corría. Eso impide saber si
un día es hábil, correr un corte que cae en domingo y —lo que más duele—
reproducir el cierre del día 15 el día 20, que es lo que exige cualquier
reproceso. `BusinessCalendarService` la sustituye. La tabla guarda sólo
excepciones y el resto lo resuelve la regla general, así que un calendario
nuevo funciona sin sembrarle el año entero antes de poder cerrar nada.

`AccrualBasis` declara por fin la convención de devengo, con los tres números
fijados en prueba sobre el crédito del análisis: actual/360 devenga 551.11 en
marzo y 497.78 en febrero contra los 533.33 que reparte el plan, y 30/360
coincide al centavo en cualquier mes. Se siembra actual/360 porque es lo que el
sistema hace hoy: la política preserva el comportamiento actual y lo deja
declarado, en vez de cambiarlo por la puerta de atrás. Cambiar a 30/360 es
editar una fila.

Una prueba fija además que la suma de los cargos diarios reproduce el interés
del tramo dentro del redondeo. Si divergieran, el devengo diario y el mensual
contarían historias distintas y ninguna de las dos sería falsa.

`ClosePhase` expresa el orden como dependencia entre fases y no como hora de
reloj, que es como está hoy: 23:00, 23:30, 23:59, 01:00. Si el devengo se
alarga, la mora espera en vez de leer datos viejos.

`ClosePolicyResolver` resuelve producto sobre tipo sobre global, con la regla de
precedencia en el dominio y no en un ORDER BY, y siempre con la política vigente
el día del cierre. Sin política, falla explícito: inventarle un default a un
producto que no la declaró es cómo se acaba devengando con una convención que
nadie eligió.

30 pruebas en closing-service ✅ (23 nuevas)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
179062a  TK-02: claves naturales de idempotencia en el devengo y la liquidación

El candado distribuido evita el trabajo duplicado; esto evita el dato
duplicado. Hacen falta los dos: si el candado falla, aquí rebota.

El plan proponía una restricción única sobre (cuenta, tipo, fecha) para
`charge_records`. Habría roto el devengo moratorio de toda cuenta en mora: un
mismo día genera dos cargos de IVA, el del interés ordinario y el del moratorio,
y la restricción total los hace chocar. La clave natural real es distinta por
tipo — el interés va por cuenta y día, el IVA por el cargo padre del que se
deriva, y la comisión de apertura por cuenta una sola vez.

El devengo moratorio no marcaba la fecha: repetir la corrida del día duplicaba
el cargo con una sola réplica. Se le da su propio reloj en vez de compartir
`last_accrual_date` con el ordinario, porque compartirlo tiene un modo de falla
peor: si el job moratorio corriera primero y marcara el día, `needsAccrual`
daría falso y el interés ordinario de ese día no se devengaría nunca. Se
cambiaría un cargo duplicado por uno omitido.

`liquidation_batches` tenía un índice donde hacía falta una restricción. Un
índice acelera la consulta y no impide nada: dos corridas del mismo período
creaban dos lotes, y el doble pago a un distribuidor no se corrige con un
rollback.

La prueba que justifica el orden del plan es la última: ocho hilos devengando la
misma cuenta a la vez, con el candado fuera de juego, dejan un solo cargo.

66 pruebas ✅ (charges 33, commission 33) — 6 nuevas de integración

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
9496dfd  Modelo de conciliación con calendarios desalineados, capacidad y E2E

La pregunta de fondo era cómo cuadrar cuando la cartera no cierra los mismos
días y los pagos no entran los mismos días. La respuesta es que no se concilian
los cortes entre sí —son agregaciones de universos distintos y no pueden
cuadrar por construcción— sino el flujo sobre el eje de la fecha de negocio,
sostenido por la identidad de arrastre: saldo de ayer más los movimientos de
hoy es el saldo de hoy. Con esa identidad cerrando a diario, el corte deja de
ser un problema de conciliación y pasa a ser una agregación que cuadra sola.

Quedan definidos cuatro cuadres diarios —cartera consigo misma, cartera contra
mayor, cartera contra banco, mayor contra banco— y un quinto de consistencia
entre el corte y sus movimientos. El corte de cuenta no participa en ninguno de
los cuatro primeros, que es justamente lo que lo desacopla.

Las diferencias de tiempo no son descuadres: un pago de las 23:50 entra al banco
hoy y a cartera mañana. La regla es que toda diferencia esté explicada por
partidas identificadas con importe y referencia, y no que quepa dentro de una
tolerancia. La tolerancia es para el redondeo; un descuadre de cuatro mil pesos
que cabe en una tolerancia de cinco mil es un cuadre falso, que es el mismo modo
de falla que este análisis ya encontró tres veces.

Y hay un bloqueador concreto: `balance_events` guarda `applied_at DEFAULT NOW()`
—cuándo se procesó— y no la fecha del hecho. El `effectiveDate` llega al
servicio, viaja en el evento y contabilidad lo usa, pero cartera no lo persiste.
Sin esa columna, cartera puede contestar qué se procesó a las 03:14 pero no qué
se movió el día 22, que es la pregunta de dos de los cuatro cuadres.

Se añade el modelo de capacidad: la fórmula con su parte serial y su parte
paralela, el costo por unidad en operaciones, y sobre todo hacia dónde se mueve
el cuello al escalar —CPU, pool, max_connections, Redis, particiones— porque
escalar no da rendimiento indefinido, traslada el cuello. Con las restricciones
duras que hoy no están dimensionadas: no hay concurrency en ningún listener ni
particiones declaradas en ningún topic.

Y el E2E: ocho productos por diecisiete escenarios, con el reloj de negocio
corrido día a día y la regla de que ningún saldo se escribe a mano — todo
movimiento tiene que haber entrado por una corrida de cierre.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
0eb7e9c  TK-03: closing-service — el servicio de cierres, con bancos dentro

Los cierres no tenían servicio: los catorce jobs viven dentro de los servicios
de dominio, y el barrido de todas las cuentas comparte JVM y pool con la API que
atiende al backoffice. Este servicio existe para sacarlo de ahí.

Y no había servicio de bancos ni de tesorería en toda la plataforma. La
conciliación bancaria queda aquí como paquete propio: su ciclo de vida es el del
cierre —"el saldo del banco al cierre del día D"— y separarla obligaría a
coordinar dos servicios para responder una sola pregunta. La frontera queda
trazada por si algún día crece hasta merecer su propio despliegue.

Catorce tablas: calendario de negocio, política de cierre versionada por
producto, proyección por cuenta, calendario de corte, motor de corridas y
unidades, sellos, hallazgos de conciliación y el bloque de bancos.

El calendario de corte es de este servicio y no de cartera, aunque para un
producto no revolvente la cadencia coincida con el vencimiento de la cuota.
Leerlo de `installments.due_date` rompería el aislamiento, dejaría el corte sin
política propia —cortar N días antes del vencimiento, o correrse si cae
inhábil, no cabe en un plan de pagos— y haría que una reestructura reescribiera
un corte ya sellado. El cierre lo deriva de la política y de lo que aprende por
evento, y lo persiste.

La política declara `accrual_basis` por producto. Era el hueco que dejó el
análisis: el plan reparte con 30/360 y el devengo corre actual/360, y nada lo
decía.

`close_units` lleva `fencing_token`: el candado de Redis puede vencer con el
trabajo todavía vivo, y el número monótono es lo que permite rechazar al titular
anterior.

7 pruebas de integración contra Postgres ✅

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
b181d25  Análisis previo a los cierres de cartera y cobranza

Base medida antes de tocar nada: 160 pruebas de integración en verde
(credit-portfolio 133, charges 27). Sobre esa base, tres hallazgos que
condicionan el diseño del motor.

El plan de pagos y el devengo corren con convenciones distintas y nadie las
cruza. `AmortizationEngine` reparte el interés con tasa/periodosPorAño —30/360
implícito— y `InterestAccrualService` devenga tasa/360 por día natural. Sobre
un crédito de $20,000 al 32%, la cuota del plan trae $533.33 de interés y el
devengo del mismo período da $551.18 en marzo y $497.84 en febrero. Ninguna de
las dos convenciones está mal; lo que está mal es que no esté declarada ni
probada, y que el desvío no rompa nada porque cada pieza cuadra consigo misma.
Pasa a ser `accrualBasis` por producto, y el E2E lo fija.

La cobranza no sabe de productos: `DunningService.runDailyCycle` no menciona
`productType` en ninguna línea. Es un ciclo plano de cinco días por días de
mora, idéntico para una tarjeta, una nómina y una línea de distribuidor. No hay
fecha de corte configurada ni corrida que pregunte a qué cuentas les toca corte
hoy.

Para un no revolvente no hace falta inventar el ciclo: ya está en
`installments.due_date`, generado según la `paymentFrequency` del producto. El
corte es la fecha de la cuota, y el cierre de cobranza evalúa contra la gracia
si se pagó.

Queda diseñado el E2E de ciclo de vida —diez pasos, del alta al sello, con
matriz por cadencia y amortización— y anotado que los pasos que no dependen del
motor se pueden fijar ya, como red mientras se construye.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
e6d5f6f  TK-01: candado distribuido en Redis para el reparto del cierre

Reemplaza la coordinación por `FOR UPDATE SKIP LOCKED` que proponía el plan:
ningún candado vive ya en la base de datos. El mecanismo es genérico —una
llave de unicidad compuesta, `fintech:lock:<dominio>:<propósito>:<disc>`— y
sirve a cualquier servicio, no sólo al cierre.

Dos implementaciones detrás del mismo puerto, elegibles por
`fintech.lock.provider`: Redisson (predeterminada, con watchdog) y una sobre
`StringRedisTemplate` sin dependencias nuevas. La segunda usa
`WATCH`/`MULTI`/`EXEC` vía `SessionCallback` en vez de scripts: Lua queda
acotado al gateway.

El fencing token no es opcional aquí. Un candado con expiración puede vencer
con el trabajo todavía vivo —una pausa de GC basta— y entonces dos procesos se
creen dueños. El contador monótono por llave permite rechazar al titular viejo.
Vive en su propio espacio de nombres para que el borrado de la liberación no lo
reinicie.

Dos hallazgos que las pruebas con mocks no ven y la batería contra Redis real
sí:

- `RLock` es reentrante. El hilo que ya lo posee vuelve a entrar, así que una
  segunda petición servida por el mismo hilo del pool pasaría de largo — lo
  contrario de lo que un candado de idempotencia promete.
- Deshacer esa re-entrada con `unlock()` reestablece el arrendamiento al valor
  por defecto de Redisson en vez de al TTL pedido: una llave de 800 ms se
  convertía en una de 30 s. Por eso la re-entrada se detecta antes de tomar el
  candado, no se deshace después.

La batería de integración corre sobre los dos proveedores. Que ambos pasen es
lo que sostiene la promesa del puerto: cambiar de implementación es una
propiedad, no una migración.

43 pruebas ✅ (29 unitarias + 14 de integración contra Redis real)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

────────────────────────────────────────────────────────
