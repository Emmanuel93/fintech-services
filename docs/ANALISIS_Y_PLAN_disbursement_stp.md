# Integración de STP en `fintech-services`
## Análisis del legado, arquitectura objetivo y plan de ejecución

**Autor:** análisis asistido · **Fecha:** 2026-08-11
**Repos analizados:** `Downloads/stp-service` (legado, 151 clases Java) · `Documents/projects/fintech-services` (monorepo, 20 servicios + `shared`)

**Decisiones tomadas antes de escribir este documento:**

| Decisión | Valor |
|---|---|
| Nombres | **`disbursement-service`** (dominio de payouts) + **`stp-service`** (conector de proveedor) |
| **Exposición a internet** | **Ninguna.** Ambos son servicios **internos**. STP no llama a la plataforma |
| **Confirmación de liquidación** | **Consulta activa a STP** (`V2/conciliacion`), no webhooks (§10.4). Entra en la Fase 1 |
| Comunicación con `credit-portfolio` | **Reacción a hechos por Kafka**, no REST ni topic de comando (§8.1, §9.2). `credit-account-activated` —enriquecido— dispara el desembolso, igual que ya dispara la notificación de bienvenida. Las consultas de operación sí por REST vía el BFF de backoffice |
| Alcance de Fase 1 | Salida SPEI + confirmación por polling. Abonos entrantes, cierre de día, saldos y extracto → Fase 4 |
| Custodia de llaves | **Envelope encryption en Postgres** (material cifrado en BD, KEK fuera de la BD) |
| Multi-empresa | **Real desde el día 1** — `companyId` obligatorio end-to-end |
| Comercialización | Ambos **vendibles por separado**, con prueba ejecutable: núcleo agnóstico de crédito, test de arquitectura y simulacro de extracción (§7.2, §7.5) |
| Ambientes bajos | **Stub de STP que firma de verdad** y devuelve el eco de lo enviado, con 11 escenarios deterministas (§10.6). Nada de `return true` |
| De-branding | El diseño objetivo es agnóstico de tenant. El nombre del tenant legado no aparece en ningún artefacto nuevo |

---

# Índice

**Parte I — Análisis**

1. [Resumen ejecutivo](#1-resumen-ejecutivo)
2. [Radiografía del legado `stp-service`](#2-radiografía-del-legado-stp-service)
3. [El sello: anatomía exacta del contrato con STP](#3-el-sello-anatomía-exacta-del-contrato-con-stp)
4. [Inventario de acoplamiento mono-tenant](#4-inventario-de-acoplamiento-mono-tenant)
5. [Bugs y deuda que NO se deben migrar](#5-bugs-y-deuda-que-no-se-deben-migrar)
6. [El hueco actual en fintech-services](#6-el-hueco-actual-en-fintech-services) — **incluye §6.4, el hallazgo principal**

**Parte II — Arquitectura objetivo**

7. [El split: dos bounded contexts, dos productos](#7-el-split-dos-bounded-contexts-dos-productos) — **§7.5: doble check del desacople**
8. [Contrato de eventos Kafka](#8-contrato-de-eventos-kafka) — **§8.1: hechos, no comandos**
9. [`disbursement-service` — diseño](#9-disbursement-service--diseño) — **§9.2: evento vs REST · §9.3: el cambio en credit-portfolio**
10. [`stp-service` — diseño](#10-stp-service--diseño) — **§10.4: confirmación sin webhooks · §10.6: stub de STP**
11. [Multi-empresa y custodia de llaves](#11-multi-empresa-y-custodia-de-llaves)
12. [El módulo de firma — especificación ejecutable](#12-el-módulo-de-firma--especificación-ejecutable)
13. [Idempotencia, outbox, reintentos y DLT](#13-idempotencia-outbox-reintentos-y-dlt)
14. [Esquemas de base de datos](#14-esquemas-de-base-de-datos)
15. [Seguridad — dos servicios internos, cero superficie externa](#15-seguridad--dos-servicios-internos-cero-superficie-externa)

**Parte III — Plan de ejecución**

16. [Plan por fases](#16-plan-por-fases)
17. [Checklist de conformidad con `base-service.md`](#17-checklist-de-conformidad-con-base-servicemd)
18. [Riesgos y cuestiones abiertas](#18-riesgos-y-cuestiones-abiertas)

**Apéndices**

- [A — Mapa de migración clase por clase](#apéndice-a--mapa-de-migración-clase-por-clase)
- [B — Lógica de negocio a preservar](#apéndice-b--lógica-de-negocio-a-preservar-lista-de-verificación)
- [C — Afirmaciones verificadas contra el código](#apéndice-c--afirmaciones-verificadas-contra-el-código)
- [D — Hallazgos en `identity-service` y `gateway-service`](#apéndice-d--hallazgos-en-identity-service-y-gateway-service)

---

# Parte I — Análisis

## 1. Resumen ejecutivo

`stp-service` **no es un adaptador**. Es un monolito de 151 clases sobre Spring Boot 2.6.2 que mezcla, en el mismo proceso y la misma base de datos, **siete responsabilidades distintas**:

1. Conector con STP (firma + HTTP + catálogo de errores Banxico)
2. Motor de dispersión / desembolso
3. Recepción de abonos entrantes (pagos SPEI recibidos)
4. Alta y administración de CLABEs de clientes
5. Conciliación diaria contra STP con paginación de 1000 registros
6. Contabilidad de cierre de día (saldo inicial/final, día Banxico, días festivos)
7. Exportador de extracto bancario a **Oracle Cloud ERP** (5 CSV en un ZIP)

Y encima está atado a **un solo tenant, por nombre, en el código**: la empresa es una constante literal en `StringService`, la cuenta ordenante es `findById(1)`, el prefijo de la clave de rastreo son dos letras fijas, la única autenticación saliente es un **token estático hardcodeado en el código fuente**, y los proveedores de token se instancian con `Class.forName()` sobre un FQCN guardado en una **columna de la base de datos**.

> **Nota de este documento:** el nombre comercial del tenant legado no se repite aquí. Donde el valor literal importa técnicamente (porque viaja dentro de la cadena firmada) se referencia como `<EMPRESA_LEGADO>` y se apunta al archivo y línea donde vive. Todo el diseño objetivo es agnóstico de tenant por construcción — ver §11 y el Apéndice A.

Lo que sí vale la pena rescatar es un núcleo de dominio pequeño y muy valioso:

- La **construcción de la cadena original y el sello** — es el contrato con Banxico y no se negocia.
- El catálogo de **32 códigos de error de Banxico**.
- El concepto de **día Banxico** y la **ventana operativa** (los listeners de dispersión se apagan a las 16:50 y se encienden a las 17:10).
- La **comparación normalizada de nombres** contra el acuse CEP.
- El **algoritmo de dígito verificador de CLABE**.
- La **conciliación con auto-descubrimiento de pagos perdidos**.

La propuesta es partirlo en dos servicios que se vean exactamente como los demás de `fintech-services`:

```
credit-portfolio / wallet / payments
        │  (eventos que ya existen o casi)
        ▼
┌─────────────────────────────┐     disbursement.stp-requested     ┌──────────────────────────┐
│   disbursement-service      │ ────────────────────────────────►  │      stp-service         │
│   :8100 · schema disbursement│                                    │  :8101 · schema stp      │
│                             │ ◄──────────────────────────────── │                          │
│  QUÉ desembolsar y a quién  │   stp.order-accepted / -rejected   │  CÓMO hablar con STP     │
│  Agnóstico de proveedor     │   stp.order-settled  / -returned   │  Sello · multi-empresa   │
└─────────────────────────────┘                                    └──────────────────────────┘
```

`disbursement-service` **no sabe qué es un sello**. `stp-service` **no sabe qué es un crédito**.

---

## 2. Radiografía del legado `stp-service`

### 2.1 Stack

| Ítem | Valor | Observación |
|---|---|---|
| Spring Boot | **2.6.2** (dic-2021) | Fuera de soporte OSS |
| Java | 17 | |
| Build | Maven, contra un repositorio de artefactos privado | |
| Broker | **Azure Service Bus** (JMS/AMQP, Qpid) | 6 topics |
| HTTP client | **Unirest 1.4.9** | Abandonada desde 2017 |
| Tracing | Spring Cloud Sleuth 3.1.11 | Deprecado (→ Micrometer Tracing) |
| Persistencia | Hibernate 5.6 + enums nativos de Postgres vía `PostgreSQLEnumType extends org.hibernate.type.EnumType` | **API removida en Hibernate 6 — bloquea Boot 3** |
| Server | Undertow, `worker-threads=4` | Contra `concurrency=200` en los listeners JMS |
| Arranque | Dual: sin args → web + listeners; con args → CLI Picocli | El mismo JAR sirve para los CronJobs |

### 2.2 Superficie HTTP actual

| Método | Ruta | Origen | Qué hace |
|---|---|---|---|
| `POST` | `/api/v1/conector-stp/dispersar` | core legado | Dispara una dispersión |
| `POST` | `/api/v1/conector-stp/validar` | core legado | Micro-dispersión de $0.01 para validar titularidad |
| `PUT` | `/api/v1/conector-stp/dispersar` | **STP** | Actualiza estado de la orden |
| `POST` | `/api/v1/conector-stp/acuse-cep` | **STP** | Acuse CEP con `sello` |
| `POST` | `/api/v1/conector-stp/pago` | **STP** | Abono entrante |
| `POST/DELETE/GET` | `/api/v1/conector-stp/cuenta` | core legado | Alta/baja de CLABE |
| `GET` | `/api/v1/conector-stp/saldoCuenta`, `/conciliacion` | Ops | Proxy a STP |
| `POST` | `/api/v1/conector-stp/conciliar` | Ops | Conciliación manual |
| `POST/GET` | `/api/v1/tesoreria` | Ops | Extracto bancario |

**Ninguno tiene autenticación.** Y en el diseño objetivo **ninguno sobrevive**: los tres que STP invoca (`PUT /dispersar`, `POST /acuse-cep`, `POST /pago`) desaparecen, porque la plataforma nueva no expone nada a internet — se sustituyen por consulta activa a STP (§10.4). No hay Spring Security en el proyecto (grep de `WebSecurity|SecurityFilter` → 0 resultados). La dependencia `jjwt:0.9.1` está declarada pero sin usar. Los tres endpoints que STP invoca están abiertos: cualquiera con acceso de red puede marcar transacciones como `LIQUIDADA` o inyectar abonos falsos hacia el core legado.

### 2.3 Flujo de dispersión — cómo funciona hoy

```
POST /dispersar (DispersionDTO)
  │
  ├─ 1. Decodifica el número de tarjeta (llega en Base64)
  ├─ 2. Resuelve TipoTransaccion → valida monto máximo (comparación en double, no BigDecimal)
  ├─ 3. Resuelve CuentaStp vía tipoTransaccion.idCuentaStp   ← única indirección real que existe
  ├─ 4. PERSISTE TransaccionStp con status=ESPERA, idCorrelacion=UUID
  ├─ 5. claveRastreo = "SV" + yyyyMMdd + zeroPad(14, transaccionStp.getId())   ← acopla la clave
  │                                                                             de negocio al PK
  ├─ 6. Trunca nombreBeneficiario a 40 chars (después del save → la BD guarda 150)
  ├─ 7. Firma: BeanUtils.copyProperties(OrdenPagoFirma → RegistraOrdenPagoDTO) + firma
  │      ⚠ la cadena firmada tiene 34 campos; el JSON enviado sólo 24
  └─ 8. Encola en dispersion-topic  (si falla → sólo log.error, la transacción queda huérfana)
        │
        ▼ DispersionTopicListener
        ├─ elimina idCorrelacion/idTransaccion del JSON (muta el payload)
        ├─ PUT https://demo.stpmex.com:7024/speiws/rest/ordenPago/registra
        │    sin cabecera de auth — la única autenticación es el campo `firma` del body
        ├─ detecta error por `Integer.toString(id).length() <= 3`   ← BUG con -200
        ├─ marca EXITOSA + bitácora
        └─ PUT al legado (webhook) con token estático X-Auth-Token
```

**Puntos estructurales a notar:**

- El mensaje de la cola **es** el request HTTP saliente con campos de control incrustados. No hay separación entre evento de dominio y payload de integración.
- Todos los mensajes se publican con **retardo programado** (`x-opt-scheduled-enqueue-time` vía API interna de Qpid, `getFacade().setTracingAnnotation`). Ese retardo existe para tapar la falta de outbox: da tiempo a que el commit de la BD llegue antes de que el consumidor lea. **No es portable a Kafka** y no hace falta si se implementa outbox bien.
- El `nAck` hace `Thread.sleep(300000)` — **5 minutos bloqueando el hilo consumidor**. Con `lock-duration=PT1M`, el lock del mensaje expira antes del nAck → redelivery duplicada garantizada en cada fallo.
- La idempotencia a nivel de mensajería es un stub: `AbstractMessageListener.isDuplicate()` tiene el cuerpo `// ToDo: Leer la tabla` y `return false;`.
- La detección de pagos duplicados se hace por **string matching en español** sobre la respuesta del legado: `response.contains("Ya existe un pago idéntico, favor de verificarlo")`.

### 2.4 Modelo de datos (14 entidades)

| Tabla | Rol | ¿Se migra a Fase 1? |
|---|---|---|
| `mae_transaccionesstp` | Orden de pago STP | **Sí** → `stp.payment_orders` |
| `his_bitacoratransaccionesstp` | Bitácora inmutable | **Sí** → `stp.payment_order_events` |
| `cat_cuentasstp` | Cuenta ordenante | **Sí** → `stp.ordering_accounts` (+ `company_id`) |
| `cat_tipotransaccionesstp` | Tipo de transacción + monto máx + cuenta | **Sí**, reformulado como reglas de routing |
| `mae_acusecep` | Acuse CEP | **Sí** → `stp.cep_receipts` |
| `cat_configuraciones` | Config de negocio en BD | Parcial → `@ConfigurationProperties` + T5 |
| `cat_users` | Endpoints del legado + FQCN del token provider | **No** — desaparece |
| `mae_cuentas_bancarias` | CLABEs de clientes | Fase 4 |
| `mae_cierre_diario` | Día Banxico | Fase 4 |
| `mae_transacciones_conciliacion` | Conciliación (33 columnas) | Fase 4 |
| `his_saldo_cuenta` / `_detalle` | Saldos | Fase 4 |
| `cat_dias_festivos` | Calendario Banxico | Fase 4 (o T5 configuration) |
| `cat_instituciones` | Catálogo | **⚠ La tabla no existe en el dump `stp-schema-base.sql`** — sólo en el `@Table` de la entidad |
| `mae_transaction_users` | Join table | **Rota**: `joinColumns` apunta a `idu_tipotransaccionstp` pero guarda el id de transacción |

---

## 3. El sello: anatomía exacta del contrato con STP

Esto es lo único del legado que **no se puede reinterpretar**. Cualquier desviación de un carácter rompe la firma y STP rechaza la orden.

### 3.1 Cómo se construye hoy

La cadena original **no se construye con un `StringBuilder`**: se genera por **reflexión** sobre los campos del bean, usando `commons-lang3 ReflectionToStringBuilder` con un estilo custom.

```java
// StyleString.java:13-21
this.setUseFieldNames(false);
this.setFieldSeparator("|");
this.setContentStart("||");
this.setContentEnd("||");
this.setNullText("");
this.setUseClassName(false);
this.setUseIdentityHashCode(false);
```

```java
// StyleString.appendDetail:23-37
if (value instanceof LocalDate)  value = ((LocalDate) value).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
if (value instanceof BigDecimal) value = new DecimalFormat("0.00").format(value);
if (value instanceof Date)       value = new SimpleDateFormat("yyyyMMdd").format(value);
buffer.append(value);
```

Y `MyReflectionToStringBuilder.appendFieldsIn` sobreescribe al padre **sólo para quitar el orden alfabético** y respetar el orden de declaración, usando `clazz.getDeclaredFields()`.

> ⚠️ **Riesgo crítico:** `getDeclaredFields()` **no garantiza orden por especificación de la JVM**. Funciona en HotSpot, pero es un contrato implícito. Un cambio de compilador, un agente de instrumentación o una JVM distinta pueden reordenarlo y romper todas las firmas en silencio.
>
> ⚠️ **Riesgo secundario:** `new DecimalFormat("0.00")` usa el **`Locale` por defecto de la JVM**. En un locale con coma decimal (`es_MX` con ciertas configuraciones, `de_DE`, etc.) `100.00` se formatea `100,00` y la firma cambia. El `Dockerfile` fija `TZ=America/Mazatlan` pero **no fija `LANG`/`LC_ALL`**.

### 3.2 Reglas exactas (especificación normativa)

| Regla | Valor |
|---|---|
| Prefijo | `\|\|` |
| Separador | `\|` |
| Sufijo | `\|\|` |
| Nombres de campo | **No** — sólo valores posicionales |
| `null` | Cadena vacía (produce pipes consecutivos) |
| `LocalDate` | `yyyyMMdd` |
| `BigDecimal` | `DecimalFormat("0.00")` → 2 decimales, **redondeo HALF_EVEN**, **separador decimal `.`** |
| `Integer` / `String` | `toString()` directo, sin padding ni truncado |
| Encoding para firmar | **UTF-8** |
| Algoritmo | **`SHA256withRSA`** (PKCS#1 v1.5) |
| Salida | **Base64 estándar, no chunked** (`commons-codec Base64.encodeBase64String`) |

### 3.3 Los tres beans firmables y su orden de campos

**`OrdenPagoFirma` — 34 campos, en este orden exacto:**

```
 1 institucionContraparte   2 empresa                  3 fechaOperacion          4 folioOrigen
 5 claveRastreo             6 institucionOperante      7 monto                   8 tipoPago
 9 tipoCuentaOrdenante     10 nombreOrdenante         11 cuentaOrdenante        12 rfcCurpOrdenante
13 tipoCuentaBeneficiario  14 nombreBeneficiario      15 cuentaBeneficiario     16 rfcCurpBeneficiario
17 emailBeneficiario       18 tipoCuentaBeneficiario2 19 nombreBeneficiario2    20 cuentaBeneficiario2
21 rfcCurpBeneficiario2    22 conceptoPago            23 conceptoPago2          24 cveCatalogoUsuario
25 cveCatalogoUsuario2     26 cvePago                 27 referenciaCobranza     28 referenciaNumerica
29 tipoOperacion           30 topologia               31 usuario                32 medioEntrega
33 prioridad               34 iva
```

**`SaldoCuentaFirma` — 3 campos:** `empresa, cuentaOrdenante, fecha`
**`ConciliacionFirma` — 3 campos:** `empresa, tipoOrden, fechaOperacion`

### 3.4 Vector de regresión de oro

Este es el test que existe hoy (`StringServiceTest.generarCadenaOriginalOrdenPago`) y que **debe pasar byte a byte en la nueva implementación**:

```java
String expected =
    "||846|empresa|20230516|folioOrigen|claveRastreo|90646|100.00|1|" +
    "3|nombreOrdenante|cuentaOrdenante|rfcCurpOrdenante|3|" +
    "nombreBeneficiario|cuentaBeneficiario|rfcCurpBeneficiario|emailBeneficiario|3|" +
    "nombreBeneficiario2|cuentaBeneficiario2|rfcCurpBeneficiario2|conceptoPago|conceptoPago2|" +
    "claveCatalogoUsuario|claveCatalogoUsuario2|clavePago|referenciaCobranza|12345|" +
    "tipoOperacion|topologia|usuario|medioEntrega|prioridad|0.16||";
// (iva de entrada = 0.162 → "0.16")
```

Y el de saldo, que fija el comportamiento de nulos:

```java
String expected = "||empresa|cuenta_prueba|||";   // fecha == null → cadena vacía
```

**Este par de vectores es la especificación ejecutable de la Fase 1.** El primer commit del `stp-service` nuevo debe ser el test, y el segundo la implementación que lo hace pasar.

### 3.5 Carga de la llave privada — hoy

```java
// EncryptService.getCertified:112-131
String base64EncodedKeystore = new String(Files.readAllBytes(Paths.get(keystoreFilename)));
byte[] decodedKeystoreBytes  = Base64.decodeBase64(base64EncodedKeystore);
KeyStore keyStore = KeyStore.getInstance("JKS");
keyStore.load(new ByteArrayInputStream(decodedKeystoreBytes), password.toCharArray());
privateKey = (RSAPrivateKey) keyStore.getKey(alias, password.toCharArray());
```

- Formato: **JKS codificado en Base64** (doble capa), **no PKCS#12, no PEM**.
- La **misma password** se usa para el keystore y para la entrada de clave.
- Alias del keystore hardcodeado en `private.key.alias`.
- Se **recarga del disco en cada firma** — I/O + parse de keystore por transacción, sin caché.
- En Kubernetes se monta el Secret `stp-secret` **entero** en `${PRIVATE_KEY_PATH}`, así que **la password de la llave queda como archivo en el mismo directorio que la llave**.
- Existe una segunda ruta muerta (`getCertified()` sin argumentos) que reconstruye un PEM PKCS#8 con BouncyCastle desde la propiedad `private.key`. **Cero callers.** Las dependencias `bcprov`/`bcpkix` existen sólo por ella.

### 3.6 Verificación de firma entrante

**No existe.** Búsqueda exhaustiva: cero llamadas a `Signature.verify()`, cero cargas de llave pública. El `sello` del `AcuseCEPDTO` se persiste tal cual en `mae_acusecep.sello` sin validar. El campo `empresa` que manda STP tampoco se compara nunca — y de hecho el mock lo envía con un espacio de más respecto a la constante del código, lo que confirma que nadie los ha cotejado jamás.

---

## 4. Inventario de acoplamiento mono-tenant

### 4.1 Constantes de negocio embebidas en el código

| Ítem | Valor | Ubicación exacta |
|---|---|---|
| `EMPRESA_DESCRIPCION` | `<EMPRESA_LEGADO>` — **va dentro de la cadena firmada, posición 2** | `StringService.java:28` |
| `EMPRESA_DESCRIPCION_SV` | `"SV"` — prefijo de clave de rastreo | `StringService.java:29` |
| `STP_PAGOS` | `"stp-pagos"` | `StringService.java:30` y duplicado en `PagosApplication.java:39` |
| `<TENANT>_STP_ACCOUNT_ID` | `1` | `StringService.java:31` |
| `cuentaStpRepository.findById(1)` | literal | `ExtractoBancarioApplication:90,156`, `SaldoCuentaCommand:136` |
| `NUM_CUENTA_STP` | `"646180378000000003"` | `ConciliacionApplication:82`, `SaldoFinalDiaCommand:177`, `SaldoInicialDiaCommand:212` |
| `INSTITUCION_OPERANTE` | `90646` | `TransferenciaApplication:92` |
| Prefijo CLABE | `646` + `180` + `3780` | `application.properties:57-59` |
| Paquete raíz con el nombre del tenant legado (151 clases) | — | todo el repo |
| Owner de todas las tablas = nombre del tenant legado | — | `stp-schema-base.sql` (`ALTER TABLE … OWNER TO …`) |

`empresa` se fija a esa constante en **4 puntos distintos**: `TransferenciaApplication:236` y `:368`, `ConsultasApplication:71` y `:141`.

### 4.2 El peor acoplamiento: el `UserTokenProvider` del core legado

`service/TokenProvider/<Legado>Token.java` — archivo completo, 11 líneas:

```java
public class <Legado>Token implements UserTokenProvider {
    @Override public String getAuthheader() { return "X-Auth-Token"; }
    @Override public String getToken()      { return "<TOKEN_ESTÁTICO_DE_20_CHARS>"; }
}
```

Un **token estático hardcodeado en el código fuente**, en un repositorio Git. Y peor: se instancia por reflexión desde un FQCN guardado en la columna `cat_users.token_provider`:

```java
tokenProvider = (UserTokenProvider) Class.forName(user.getTokenProvider()).newInstance();
```

Vector de ejecución arbitraria si la BD se compromete, y acoplamiento duro: los proveedores tienen que existir compilados dentro del artefacto.

### 4.3 Secretos en claro en el repositorio

⚠️ **Estos valores están versionados en Git y hay que rotarlos, independientemente de este proyecto:**

- `spring.datasource.password=<REDACTADO>` + host de BD con **IP pública** en el `application.properties`
- `spring.jms.servicebus.connection-string=…SharedAccessKey=<REDACTADO>`
- `private.key=<REDACTADO>` — **la llave privada RSA completa, ~1600 caracteres, en claro**
- `private.key.password=<REDACTADO — 6 dígitos>`
- El token estático del `UserTokenProvider` (20 caracteres, en el `.java`)
- `private.key.path=/Users/<desarrollador>/Documents/Proyectos/stp-service` — la ruta de la laptop de un desarrollador, empaquetada en el JAR de producción

Además, varias variables **no están en ningún ConfigMap** de Kubernetes (`TESTING`, `EVIDENCE_PATH`, `STP_NUMBER`, `STP_COUNTRY`, `STP_CLIENT_PREFIX`, los dos crons), así que en producción toman el valor del `application.properties` empaquetado — **incluida la llave privada de desarrollo**.

### 4.4 Multi-empresa: no existe

| Pregunta | Respuesta |
|---|---|
| ¿`CuentaStp` tiene empresa? | **No.** Cero columnas de tenant en `cat_cuentasstp` |
| ¿`Configuracion` es por empresa? | **No.** `nom_clave` es global; dos empresas compartirían `arePaymentsActive`, `horaMaxPermitidaPago`… |
| ¿Alguna tabla tiene tenant? | Sólo `mae_transacciones_conciliacion.nom_empresa` y `mae_acusecep.empresa`, pero son eco de STP: **cero queries filtran por empresa** |
| ¿`empresa` es dinámico? | **No.** Constante en 4 puntos. `DispersionDTO` ni siquiera tiene el campo |
| ¿Cómo se resuelve la llave? | **Una sola llave global por instancia.** `EncryptService.sign` no recibe ningún parámetro de contexto |

**El dato bueno:** `empresa` ya ocupa la **posición 2** de la cadena original. El protocolo de STP soporta multi-empresa perfectamente. Es el resto del código el que no.

---

## 5. Bugs y deuda que NO se deben migrar

Los encontré leyendo el código; los listo porque varios afectan dinero real y porque el plan debe cerrarlos explícitamente, no arrastrarlos.

| # | Bug | Impacto |
|---|---|---|
| **B1** | `responseBaxicoContainError()` detecta error por `Integer.toString(id).length() <= 3`. El código **`-200` (`RECHAZO_POR_PLD`) tiene 4 caracteres** → no se detecta como error | Una orden **rechazada por PLD** se marca `EXITOSA` y se notifica al legado un tracking code `-200`. **Dinero reportado como dispersado que nunca salió.** |
| **B2** | `TransferenciaService.actualizarTransaccion` línea 148 hace `setDescripcionError(respuestaBanxico.getDescripcionError())` y línea 151 lo sobreescribe con `setDescripcionError(error)` | La descripción real de STP se pierde siempre |
| **B3** | Si el webhook al legado falla, se lanza `Exception` **después** de haber guardado `EXITOSA` → nAck → reprocesamiento completo, **incluyendo un segundo PUT a STP** | **Riesgo de doble dispersión** |
| **B4** | `AbstractMessageListener.isDuplicate()` → `return false;` con un `// ToDo: Leer la tabla` | Idempotencia de mensajería inexistente |
| **B5** | `nAck()` hace `Thread.sleep(300000)` con `lock-duration=PT1M` | El lock expira antes del nAck → redelivery duplicada garantizada |
| **B6** | `PagosController` mantiene `respuestaPagoDTO`, `responseId` y `statusCode` como **atributos de instancia de un singleton**, mutados por request | Race condition con 4 réplicas. Si `createNewPayment` lanza, el catch devuelve la respuesta **de la petición anterior** |
| **B7** | `nombreBeneficiario` se trunca a 40 chars antes de firmar pero se guarda completo (150) | Nombres largos → `isEqualsNames = false` siempre en el acuse CEP |
| **B8** | Dos fuentes de verdad para el mapeo de estado STP: `obtenerStatusDispersion()` (por nombre) y `getStatusDispersion()` (por código, con `default -> null`) | Transacciones sin estado en silencio |
| **B9** | `StringService.generarCadenaOriginal(RetornaOrdenDTO)` llama `setExcludeFieldNames` dos veces; el método **reemplaza** el array, no acumula → `empresa` sí entra en la cadena. Además `EncryptService` **loguea la cadena original completa en nivel SEVERE** | Ruta muerta hoy, pero es una fuga de datos esperando a ser activada |
| **B10** | `esPagoDuplicado()` hace string matching en español sobre la respuesta del legado | Un cambio de copy en el core legado rompe la deduplicación de pagos |
| **B11** | Fallo de encolado → sólo `log.error`, el método retorna éxito | Transacción huérfana en `ESPERA` para siempre |
| **B12** | `@Scheduled` de `QueueAdminApplication` con `replicas: 4` | La ventana operativa 16:50–17:10 se aplica **por réplica en memoria**; un pod que arranca a las 17:00 tiene los listeners encendidos |
| **B13** | Los CronJobs arrancan el contexto completo → **también levantan los 6 `@JmsListener`** | Cada ejecución de cron consume mensajes de producción |
| **B14** | `spring.datasource.hikari.max-lifetime=1000` (**1 segundo**) | Reciclado constante de conexiones |
| **B15** | `.get()` sin `isPresent()` en `PagosService:240` y `TransferenciaService:115` | `NoSuchElementException` en runtime |
| **B16** | `@RepositoryRestResource` en `BitacoraTransaccionStpRepository` + `spring-boot-starter-data-rest` | Endpoint REST no intencional que expone la bitácora |
| **B17** | `encript()` es SHA-256 **sin salt** sobre CLABEs | Vulnerable a ataque de diccionario (el espacio de CLABEs es enumerable) |

**Código muerto a no migrar:** `MessageSender`, `QueueServiceInterface`, `TopicListenerInterface`, `QueueMessageDTO`, `jmsConfigStartUpOff`, `EncryptService.getCertified()` sin args, `VentaDTO`, `ClienteDTO`, `AuthResponse`, `BooleanResponse`, `IntermitenciaDTO`, `TiposDePago`, `RetornaOrdenDTO`, `initDatabaseStp.sql`, `pipeline-deprecated/`, y las propiedades `security.validate.url`, `security.refresh.url`, `stp.minimum.balance.alert`, `event.bus.retry-time.in-sec`.

---

## 6. El hueco actual en `fintech-services`

### 6.1 Lo que ya existe

El monorepo **ya tiene un flujo de desembolso completo, pero con el rail simulado**:

```
Cliente → POST /api/v1/wallet/{creditAccountId}/dispositions
  ▼ DispositionOrchestrationService (DO-02 fail-fast, DO-03, DO-05)
  ▼ topic wallet.disposition-requested                    key=creditAccountId
credit-portfolio · DispositionRequestedListener → CreditAccountService.process()
  │ idempotencia: balanceEventRepository.existsBySourceEventId(dispositionRequestId)
  │ Disposition.create(...).markProcessing()
  │ externalRef = SELF_USE ? "WALLET-CREDIT"
  │                        : speiDispatch.dispatch(dispositionId, amount, payeeAccount)   ◄── AQUÍ
  │ disposition.complete(externalRef)
  │ account.applyDisposition(amount) ; BalanceEvent.record("DISPOSITION_<type>")
  ▼ publica credit-portfolio.balance-updated + credit-portfolio.disposition-completed
wallet · DispositionCompletedListener → WalletProjectionService (DO-06/DO-07)
```

Y el adaptador de ese `speiDispatch` es:

```java
/** Stub — immediately confirms disbursement. TODO: integrate real SPEI/BANXICO adapter. */
@Component
public class NoopSpeiDispatchAdapter implements SpeiDispatchPort {
    @Override
    public String dispatch(UUID dispositionId, BigDecimal amount, String clabeAccount) {
        return "SPEI-STUB-" + dispositionId.toString().substring(0, 8).toUpperCase();
    }
}
```

Hay un gemelo idéntico en `wallet-service`: `NoopWalletDispatchAdapter` → `"WALLET-WD-STUB-…"`.

### 6.2 Por qué NO basta con "rellenar el stub"

La tentación obvia es implementar `SpeiDispatchPort` con una llamada real a STP. **Sería un error grave**, por cinco razones:

1. **La llamada está dentro de `@Transactional` de `CreditAccountService`.** Un HTTP a un tercero dentro de la transacción que ajusta saldos: la transacción de saldos queda abierta durante el round-trip a STP, y un timeout deja el saldo aplicado sin saber si el dinero salió.
2. **`credit-portfolio` es el límite de consistencia del sistema** — el propio README lo declara: *"los saldos exigen consistencia fuerte; partir el límite de consistencia es un anti-patrón"*. Meterle un proveedor de pagos dentro lo contamina.
3. **La firma es síncrona y devuelve `String`.** STP no confirma la liquidación en la respuesta del `ordenPago/registra`: devuelve un `id`. La liquidación real llega **después**, por webhook (`PUT /dispersar` con estado, `POST /acuse-cep`). El puerto actual no tiene dónde poner eso.
4. **No hay lugar para el `companyId`.** La firma `dispatch(UUID, BigDecimal, String)` no lleva tenant, y `credit-portfolio` no debería conocerlo.
5. **Un solo proveedor queda cableado para siempre.** Añadir un segundo banco implicaría un `if` dentro del core de saldos.

### 6.3 Huecos ya identificados en el propio repo

| Hueco | Evidencia |
|---|---|
| `credit-portfolio.disposition-rejected` se publica y **nadie lo consume** | `KafkaCreditPortfolioPublisher` publica `DispositionRejectedEvent`; grep de consumidores → 0 |
| `wallet.payment-instruction-created` sin consumidor | `docs/dominios/07_wallet_domain.md`: *"Payments (no implementado del lado Payments todavía)"* |
| `wallet.snapshot-updated` sin consumidor | destinatario previsto: Channels/Notifications |
| Sin reversión de retiro fallido | `WD-02` documentado: *"no hay reversión automática si el rail real falla más tarde"*. `WalletWithdrawal.markFailed()` existe y **nadie lo llama** |
| Conciliación | `ReconciliationBatch`, SIC-40 de BANXICO: **especificados en `06_payments_domain.md`, sin código** |
| Integración SPEI real | `IMPLEMENTATION_TRACKER.md:951`: *"falta integración real SPEI/BANXICO"* |

**Este proyecto cierra el hueco #1, #4 y #6, y deja el terreno preparado para #5.**

### 6.4 El hallazgo principal: al activar el crédito **no se publica ningún evento de desembolso**

Esta es la pregunta que dio origen a esta revisión, y la respuesta es que **no, no está configurado**.

`CreditAccountService.activate(...)` (líneas 85–169) hace esto:

```java
// 3-4. Initial disposition — skipped for revolving (PL-01 …)
BigDecimal disbursementAmount = resolveAmount(cmd, caps);
if (disbursementAmount.signum() > 0) {
    Disposition disposition = Disposition.create(
            account.getCreditAccountId(), resolveDispositionType(caps), disbursementAmount, null);
    disposition.markProcessing();
    dispositionRepository.save(disposition);

    String externalRef = speiDispatch.dispatch(                    // ← stub síncrono
            disposition.getDispositionId(), disbursementAmount, cmd.clabeAccount());
    disposition.complete(externalRef);                             // ← COMPLETED al instante
    dispositionRepository.save(disposition);
}

// 5. Activate
account.activate(disbursementAmount);
accountRepository.save(account);

// 6. Publish CreditAccountActivated
eventPublisher.publishCreditAccountActivated(new CreditAccountActivatedEvent(…));
```

Tres cosas, todas verificadas por grep:

1. **`publishDispositionCompleted` NO se llama aquí.** Su **único** caller es `CreditAccountService:253`, dentro de `process(...)` — el camino **iniciado por wallet**. (`publishDispositionRejected`, en `:224`, está en el mismo método.) El desembolso de originación — el principal para todo producto no revolvente — **no genera ni un solo mensaje en Kafka**.

2. **`CreditAccountActivatedEvent` no sirve para desembolsar.** Sus 15 campos son `creditAccountId, contractId, obligorPartyId, productType, productBehavior, nominalRate, moratoriumRate, openingFeeRate, principalBalance, creditLimit, riskTier, activatedAt, promoterCode` (+ `eventId`, `occurredOn`). **No lleva la CLABE del beneficiario, ni el monto a desembolsar como tal, ni el nombre ni el RFC.** Un consumidor de este evento no puede construir una orden de pago.

3. **La CLABE sí existe, pero se queda dentro.** `cmd.clabeAccount()` llega desde origination en `credit-product-creation-requested` y se congela en la entidad vía `CreditAccount.fromSnapshot(...)`. Está ahí; simplemente nadie la publica.

**Consecuencia directa sobre el plan anterior:** la Fase 2 que había diseñado hacía que `disbursement-service` consumiera `credit-portfolio.disposition-completed`. Eso **sólo habría cubierto los desembolsos iniciados desde wallet** — el desembolso de originación, que es el caso mayoritario, se habría quedado fuera en silencio. Es el tipo de error que no revienta en pruebas: simplemente no desembolsa y nadie se entera hasta que un cliente reclama.

La corrección está en §8.1–§8.2 (se enriquece el hecho que ya existe, no se inventa un topic), §9.3 (el código, antes y después) y la **Fase 2-B** del plan (§16), que es trabajo dentro de `credit-portfolio` y no existía en la versión anterior de este documento.

### 6.5 Estado real del despacho SPEI en el repo

| Punto | Qué hay | Dónde |
|---|---|---|
| `SpeiDispatchPort.dispatch(UUID, BigDecimal, String)` | Interfaz síncrona que devuelve `String externalRef` | `credit-portfolio/application/port/out/` |
| `NoopSpeiDispatchAdapter` | Devuelve `"SPEI-STUB-" + id.substring(0,8)` | `credit-portfolio/infrastructure/adapter/out/spei/` |
| Llamadas | 2: `CreditAccountService:139` (originación) y `:237` (wallet, sólo si no es `SELF_USE`) | |
| `WalletDispatchPort` + `NoopWalletDispatchAdapter` | Gemelo en `wallet-service` para retiros | `wallet/…/out/dispatch/` |
| Transaccionalidad | `CreditAccountService` lleva `@Transactional` **a nivel de clase** (línea 45) → ambas llamadas ocurren dentro de la transacción de saldos | |

**Los tres se eliminan.** `SpeiDispatchPort`, `NoopSpeiDispatchAdapter` y `WalletDispatchPort`/`NoopWalletDispatchAdapter` desaparecen: su responsabilidad pasa a `disbursement-service`, y el acoplamiento se rompe publicando un evento en vez de llamando a un puerto.

---
---

# Parte II — Arquitectura objetivo

## 7. El split: dos bounded contexts, dos productos

### 7.1 La línea de corte

| | `disbursement-service` | `stp-service` |
|---|---|---|
| **Pregunta que responde** | *¿Qué hay que pagar, a quién, por qué rail y con qué prioridad?* | *¿Cómo se le habla a STP en nombre de la empresa X?* |
| **Naturaleza** | Dominio de negocio (payout orchestration) | Anti-corruption layer / conector de proveedor |
| **Agregado raíz** | `DisbursementOrder` | `StpPaymentOrder` |
| **Conoce** | empresa, beneficiario, monto, rail, proveedor, reglas de routing, ventana operativa | empresa STP, cuenta ordenante, cadena original, sello, códigos Banxico, conciliación |
| **NO conoce** | qué es un sello, qué es una clave de rastreo, **qué es un crédito** | qué es un desembolso, qué es una cuenta de crédito, qué es un wallet |
| **Puerto** | `8100` | `8101` |
| **Schema** | `disbursement` | `stp` |
| **Paquete** | `com.fintech.disbursement` | `com.fintech.stp` |
| **Exposición** | **Interna.** Kafka + REST vía BFF de backoffice | **Interna.** Kafka + REST vía BFF de backoffice. **Egress** hacia STP |
| **Vendible aparte** | ✅ Es un producto de payouts multi-rail multi-empresa | ✅ Es un conector STP multi-empresa |

### 7.2 Vendible por separado — qué implica de verdad

Que los dos servicios se puedan comercializar aparte no es una aspiración de marketing: es una **restricción de diseño verificable**. Concretamente:

| Restricción | Cómo se cumple |
|---|---|
| **El núcleo de `disbursement` no compila contra nada de crédito** | `DisbursementOrder` no tiene `creditAccountId` ni `obligorPartyId` como campos. Lleva `sourceSystem`, `sourceType`, `sourceReference` (string opaco) y `sourceMetadata` (JSONB). Los IDs de crédito viajan ahí y se devuelven en eco |
| **Todo lo que sabe de crédito vive en un adaptador de entrada** | Tres listeners ACL (`CreditAccountActivatedListener`, `DispositionAuthorizedListener`, `WalletWithdrawalListener`) son los únicos archivos que mencionan crédito. Traducen hechos ajenos a un `RequestDisbursementCommand` neutro. Bórralos y el servicio sigue funcionando (§7.5) |
| **Hay una puerta de entrada que no es Kafka** | `POST /api/v1/disbursements` con `Idempotency-Key`, para un comprador que no tiene tu Kafka. Internamente **no se usa** — credit-portfolio entra por evento |
| **El proveedor es intercambiable** | `ProviderDispatchPort` + `routing_rules`. Añadir un banco es una fila en tabla y un servicio conector nuevo |
| **`stp-service` no menciona `disbursement`** | Consume un topic cuyo nombre es **configurable** (`fintech.stp.inbound-topic`, default `disbursement.stp-requested`) y cuyo payload es una orden de pago SPEI genérica: sin `creditAccountId`, sin `disbursementId` semántico — sólo `paymentRequestId` |
| **Ninguno depende del otro para arrancar** | Se comunican sólo por Kafka. `disbursement` sin `stp` acumula órdenes en `REQUESTED`; `stp` sin `disbursement` simplemente no recibe nada |

> **Consecuencia práctica:** el vocabulario del núcleo de `disbursement` es *payer / beneficiary / amount / rail / provider / order*, no *cuenta de crédito / obligado / disposición*. Si en algún punto del diseño aparece un concepto de crédito fuera del ACL, es un bug de arquitectura.

### 7.3 Vista end-to-end objetivo

```
┌── ORIGEN: HECHOS del dominio de crédito + una puerta de producto ────────────────┐
│                                                                                   │
│  credit-portfolio.credit-account-activated ← EXISTENTE, enriquecido con el bloque │
│                                              `disbursement` (§8.2). Hoy lo oyen 9 │
│                                              servicios; disbursement es el 10.º   │
│  credit-portfolio.disposition-authorized   ← HECHO NUEVO: disposición desde wallet │
│  wallet.withdrawal-completed               ← retiro del wallet balance            │
│  payments.surplus-return-initiated         ← devolución de sobrepago (sin código) │
│  POST /api/v1/disbursements                ← API de producto, para un comprador   │
└──────────────────────────────────┬────────────────────────────────────────────────┘
                                   │  (ACL: traduce a RequestDisbursementCommand)
                                   ▼
┌── disbursement-service :8100 · INTERNO ──────────────────────────────────────────┐
│  DisbursementOrder (agregado, agnóstico de dominio origen)                        │
│    · idempotencia por (sourceSystem, sourceType, sourceEventId) UNIQUE             │
│    · resuelve companyId                                                            │
│    · valida beneficiario (CLABE + dígito verificador), monto, ventana operativa    │
│    · RoutingService → rail + provider + topic destino                              │
│    · REQUESTED → DISPATCHED → ACCEPTED → SETTLED  ↘ REJECTED/RETURNED/FAILED       │
└──────────────────────────────────┬────────────────────────────────────────────────┘
                                   │  disbursement.stp-requested      key=paymentRequestId
                                   ▼
┌── stp-service :8101 · INTERNO ───────────────────────────────────────────────────┐
│  StpPaymentOrder (agregado)                                                       │
│    · Company → empresa · institucionOperante · trackingPrefix                     │
│    · OrderingAccount por companyId                                                │
│    · claveRastreo por secuencia (companyId, businessDate)                          │
│    · OrdenPagoFirma → CadenaOriginalBuilder → SigningService (llave por empresa)  │
│    · outbox → PUT {stp}/ordenPago/registra          ── EGRESS, TLS saliente ──►   │
│    · BanxicoResponseCode (32 códigos)                                              │
│                                                                                    │
│  StpSettlementPollingJob  ── EGRESS ──►  POST {stp}/V2/conciliacion                │
│    · consulta activa cada N min mientras haya órdenes en vuelo (§10.4)            │
│    · registra SettlementObservation (evidencia append-only, idempotente)           │
└──────────────────────────────────┬────────────────────────────────────────────────┘
                                   │  stp.order-accepted | stp.order-rejected
                                   │  stp.order-settled  | stp.order-returned
                                   ▼
┌── disbursement-service (cierra el ciclo) ────────────────────────────────────────┐
│  disbursement.completed | disbursement.failed | disbursement.returned             │
└──────────────────────────────────┬────────────────────────────────────────────────┘
                                   ▼
       credit-portfolio (cierra la Disposition)  ·  wallet  ·  accounting  ·  audit
```

**Ningún componente de este diagrama recibe tráfico de internet.** La única dirección hacia afuera es **egress** desde `stp-service` a los endpoints de STP. La flecha de vuelta no existe: la información llega porque nosotros la vamos a buscar.

### 7.4 Por qué un topic por proveedor y no uno genérico

El topic es `disbursement.stp-requested`, **no** `disbursement.dispatch-requested` con un campo `provider`.

- Con un topic genérico, **cada conector lee todos los mensajes** y descarta los que no son suyos. Desperdicio y acoplamiento por convención.
- Con topic por proveedor, añadir `banorte-connector-service` es: fila nueva en `routing_rules`, topic `disbursement.banorte-requested`, servicio nuevo. **Cero cambios en `stp-service`.**
- El routing queda como decisión **explícita y auditable** del dominio, no como un `if` distribuido.
- Y para comercializar `stp-service`: el comprador apunta `fintech.stp.inbound-topic` a lo suyo y no tiene que adoptar tu nomenclatura.

### 7.5 Doble check del desacople — la prueba, no la promesa

"Están desacoplados" es una afirmación verificable. Estos son los ocho criterios y cómo se comprueba cada uno. Si alguno falla, el desacople no existe por mucho que lo diga el diagrama.

| # | Criterio | Cómo se verifica | Estado del diseño |
|---|---|---|---|
| **DC-1** | `disbursement` no compila contra ningún tipo del dominio de crédito | Test de arquitectura (ArchUnit) sobre `domain/` y `application/` | ✅ Por construcción: los IDs de crédito viajan en `sourceReference` (String) y `sourceMetadata` (JSONB) |
| **DC-2** | `stp` no compila contra ningún tipo de `disbursement` | Idem | ✅ El payload de entrada es una orden de pago SPEI; el topic es configurable |
| **DC-3** | `credit-portfolio` no nombra tipos de `disbursement` | grep de `disbursement` en `services/credit-portfolio-service/src/main` | ⚠️ **Aparece tres veces**, y sólo como literales de topic en `@KafkaListener`: `disbursement.completed`, `.failed`, `.returned`. Es inevitable y aceptable: el nombre de un topic no es una dependencia de compilación |
| **DC-4** | Cada servicio tiene su propio schema y nadie lee el del otro | grep de `schema =` en las entidades | ✅ `disbursement`, `stp`, `credit_portfolio` — sin FK cross-schema, sin joins |
| **DC-5** | Ninguno necesita al otro para arrancar | Levantar cada uno solo con Postgres + Kafka | ✅ Sin `depends_on` entre ellos en el compose, sin cliente HTTP entre ellos |
| **DC-6** | Hay una puerta de entrada que no es Kafka | `POST /api/v1/disbursements` con `Idempotency-Key` | ✅ Un comprador sin nuestro Kafka puede usar el servicio |
| **DC-7** | El proveedor es sustituible sin tocar el dominio | Añadir una fila en `routing_rules` + un servicio conector | ✅ `ProviderDispatchPort` + topic por proveedor (§7.4) |
| **DC-8** | Extraer el servicio a otro repo es mecánico | Simulacro documentado (abajo) | ✅ 3 listeners a borrar en `disbursement`, 0 en `stp` |

#### El test de arquitectura, escrito

Es el entregable 2.13 y es lo que convierte el desacople en algo que no se puede romper por descuido:

**Dos clases**, una por módulo: `@AnalyzeClasses` sólo importa el paquete que se le indica, así que una regla sobre `com.fintech.stp` escrita dentro del test de `disbursement` evaluaría sobre un conjunto vacío — y desde ArchUnit 0.23 `failOnEmptyShould` está activo por defecto, con lo que fallaría por la razón equivocada.

```java
// services/disbursement-service/src/test/java/.../DisbursementDecouplingTest.java
@AnalyzeClasses(packages = "com.fintech.disbursement",
                importOptions = ImportOption.DoNotIncludeTests.class)
class DisbursementDecouplingTest {

    /** DC-1 — el núcleo no sabe qué es un crédito. */
    @ArchTest
    static final ArchRule nucleo_agnostico_de_credito =
        noClasses().that().resideInAnyPackage("..disbursement.domain..", "..disbursement.application..")
            .should().haveSimpleNameContaining("Credit")
            .orShould().haveSimpleNameContaining("Disposition")
            .orShould().haveSimpleNameContaining("Wallet")
            .orShould().haveSimpleNameContaining("Obligor")
            .because("el núcleo de payouts debe poder venderse sin el core de crédito (§7.2)");

    /** DC-1 — y tampoco importa nada de otro servicio. */
    @ArchTest
    static final ArchRule sin_imports_de_otros_servicios =
        noClasses().that().resideInAPackage("com.fintech.disbursement..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.fintech.creditportfolio..", "com.fintech.wallet..",
                    "com.fintech.payments..", "com.fintech.stp..");

    /** El vocabulario de crédito sólo puede aparecer en los adaptadores de entrada (ACL). */
    @ArchTest
    static final ArchRule credito_solo_en_el_acl =
        classes().that().haveSimpleNameContaining("CreditAccount")
            .should().resideInAPackage("..infrastructure.adapter.in.messaging..")
            .allowEmptyShould(true);   // vale mientras el ACL aún no exista (Fase 2 antes de 2-B)
}

// services/stp-service/src/test/java/.../StpDecouplingTest.java
@AnalyzeClasses(packages = "com.fintech.stp",
                importOptions = ImportOption.DoNotIncludeTests.class)
class StpDecouplingTest {

    /** DC-2 — el conector no sabe qué es un desembolso ni una cuenta de crédito. */
    @ArchTest
    static final ArchRule agnostico_de_desembolso_y_credito =
        noClasses().that().resideInAPackage("com.fintech.stp..")
            .should().haveSimpleNameContaining("Disbursement")
            .orShould().haveSimpleNameContaining("CreditAccount");

    @ArchTest
    static final ArchRule sin_imports_de_otros_servicios =
        noClasses().that().resideInAPackage("com.fintech.stp..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.fintech.disbursement..", "com.fintech.creditportfolio..",
                    "com.fintech.wallet..", "com.fintech.payments..");
}
```

#### El simulacro de extracción

La prueba definitiva de "vendible aparte" es poder sacarlo. Esto es lo que costaría, archivo por archivo:

**`disbursement-service` → repo propio**

| Paso | Qué |
|---|---|
| 1 | Copiar el módulo completo |
| 2 | **Borrar 3 listeners ACL** y sus payloads: `CreditAccountActivatedListener`, `DispositionAuthorizedListener`, `WalletWithdrawalListener` |
| 3 | Quitar sus `@Bean ConcurrentKafkaListenerContainerFactory` de `KafkaConfig` |
| 4 | Sustituir `implementation(project(":shared"))` por copiar `DomainException` (2 clases, 40 líneas) |
| 5 | Reemplazar `JwtAuthenticationFilter` por el mecanismo de auth del comprador |
| **Resultado** | Un servicio de payouts multi-rail multi-empresa con API REST, ~5 archivos tocados. **Cero cambios en `domain/` y `application/`** |

**`stp-service` → repo propio**

| Paso | Qué |
|---|---|
| 1 | Copiar el módulo completo |
| 2 | Apuntar `fintech.stp.inbound-topic` al topic del comprador |
| 3 | Pasos 4 y 5 de arriba |
| **Resultado** | Un conector STP multi-empresa con firma, poller de conciliación y custodia de llaves. **Cero archivos borrados** |

#### Lo que sí queda acoplado, y por qué está bien

Ser honesto sobre esto importa más que la lista de verdes:

| Acoplamiento real | Por qué se acepta |
|---|---|
| `disbursement` conoce el **formato del payload** de `credit-account-activated` | Es la definición de un ACL. Vive en un archivo, aislado del núcleo |
| `credit-portfolio` conoce los **nombres de tres topics** de `disbursement` | Nombres de topic, no tipos. Configurables si hiciera falta |
| Ambos usan `shared` (`DomainException`, `GlobalExceptionHandler`) | 2 clases, 40 líneas, sin lógica de negocio. Copiarlas al extraer es trivial |
| Ambos siguen las convenciones del monorepo (header-trust, Liquibase, OTel) | Es lo que los hace mantenibles **aquí**. Sustituirlas al extraer es el trabajo normal de empaquetar un producto |

---

## 8. Contrato de eventos Kafka

Convención real del repo: `<servicio-productor>.<evento-en-kebab-case-participio>`, JSON puro con `JsonSerializer`/`JsonDeserializer`, records `*Payload` propios (**no** `DomainEvent` de `shared` — está muerto en todo el repo), consumidor con `USE_TYPE_INFO_HEADERS=false` + `VALUE_DEFAULT_TYPE`.

### 8.1 La decisión de fondo: **hechos, no comandos**

Una versión anterior de este documento proponía un topic nuevo, `credit-portfolio.disbursement-requested`. **Eso era un comando disfrazado de evento**, y el repo no trabaja así. Su propia decisión de arquitectura dice:

> *"Coreografía, no orquestación central — El flujo fluye por eventos encadenados; no hay un orquestador dios."*
> *"Event-carried state transfer — El evento lleva los datos que el consumidor necesita."*

Aplicando eso literalmente, **el desembolso no necesita un topic propio**. El dinero sale en dos momentos, y cada momento ya es —o debe ser— un hecho del dominio de crédito:

| Momento | Hecho | Estado |
|---|---|---|
| Se activa el crédito | **`credit-portfolio.credit-account-activated`** | ✅ **Ya existe**, con 9 consumidores. Sólo hay que **enriquecerlo** |
| Se autoriza una disposición desde wallet | **`credit-portfolio.disposition-authorized`** | 🆕 Hecho nuevo — hoy no hay ningún evento en ese punto |
| El dinero llegó al beneficiario | **`credit-portfolio.disposition-completed`** | ✅ Ya existe. Hoy **miente** (se publica con un `externalRef` de stub); pasa a ser verdad |

Y quien reacciona a cada hecho decide por sí mismo:

```
credit-portfolio.credit-account-activated          ← UN hecho
   ├─► notifications   → notificación #2 WELCOME_ACTIVATED     (ya implementado)
   ├─► charges         → arranca el devengamiento              (ya implementado)
   ├─► payments        → crea el AccountBalanceSnapshot        (ya implementado)
   ├─► wallet          → crea la WalletView                    (ya implementado)
   ├─► commission      → asigna promotor                       (ya implementado)
   ├─► collections · risk · origination · audit                (ya implementado)
   └─► disbursement    → crea la DisbursementOrder             ◄── LO ÚNICO NUEVO
```

**Ese es el patrón que ya usan los otros ocho consumidores.** `disbursement-service` se suma a la fila; no inventa un carril paralelo.

### 8.2 Enriquecer `credit-account-activated` — y por qué es seguro

`CreditAccountActivatedEvent` tiene hoy 15 campos y **ninguno sirve para pagar**: no lleva CLABE, ni nombre del beneficiario, ni RFC, ni el monto desembolsado como tal (§6.4). Se le añade un bloque anidado, presente **sólo cuando hay dinero que mover**:

```java
public class CreditAccountActivatedEvent {
    // ── los 15 campos actuales, sin tocar ────────────────────────────────
    private final String eventId;            private final Instant occurredOn;
    private final UUID creditAccountId;      private final UUID contractId;
    private final UUID obligorPartyId;       private final String productType;
    private final String productBehavior;    private final BigDecimal nominalRate;
    private final BigDecimal moratoriumRate; private final BigDecimal openingFeeRate;
    private final BigDecimal principalBalance; private final BigDecimal creditLimit;
    private final String riskTier;           private final Instant activatedAt;
    private final String promoterCode;

    // ── NUEVO: null cuando no hay desembolso (revolvente abre en cero) ───
    private final DisbursementInstruction disbursement;
}

/** Todo lo que hace falta para pagar, sin que nadie tenga que preguntar nada. */
public record DisbursementInstruction(
        UUID    dispositionId,           // clave de idempotencia aguas abajo
        UUID    companyId,               // tenant — nullable; disbursement lo resuelve si falta
        String  dispositionType,         // SELF_USE | THIRD_PARTY_CREDIT | PAYROLL
        BigDecimal amount,
        String  currency,                // "MXN"
        String  beneficiaryName,
        String  beneficiaryAccount,      // la CLABE que hoy se queda dentro de CreditAccount
        String  beneficiaryAccountType,  // "40" CLABE | "3" tarjeta | "10" celular
        String  beneficiaryTaxId,        // RFC/CURP, nullable
        Integer beneficiaryInstitution,  // nullable — derivable de la CLABE
        UUID    beneficiaryPartyId,      // null si el beneficiario es el propio obligado
        String  concept
) {}
```

**Retrocompatibilidad — verificada en los 9 consumidores, no supuesta:**

| Consumidor | Cómo deserializa | ¿Sobrevive a campos nuevos? |
|---|---|---|
| charges, collections, commission, notifications, payments, risk, origination | `record` con `@JsonIgnoreProperties(ignoreUnknown = true)` | ✅ Explícitamente |
| **wallet** | `record` **sin** la anotación | ✅ Pero **por accidente**: `JsonDeserializer` de spring-kafka usa `JacksonUtils.enhancedObjectMapper()`, que desactiva `FAIL_ON_UNKNOWN_PROPERTIES`. Ningún servicio inyecta un `ObjectMapper` propio (verificado por grep) |
| audit | `onAccountActivated(String payload)` → `Map<String,Object>` | ✅ Totalmente tolerante |

> **Acción barata que quita una mina:** añadir `@JsonIgnoreProperties(ignoreUnknown = true)` a `services/wallet-service/.../CreditAccountActivatedPayload`. Hoy funciona por el default del mapper de spring-kafka; el día que alguien configure un `ObjectMapper` estricto, wallet deja de arrancar. Entra como entregable 2B.5.

**Regla de emisión (`CP-D1`)** — cuándo `disbursement` viene poblado:

| Caso | ¿Bloque `disbursement`? | Evidencia en código |
|---|---|---|
| Activación de producto **no revolvente** | ✅ con `amount = approvedAmount` | `resolveAmount(...)` devuelve `cmd.approvedAmount()` |
| Activación de producto **revolvente** | ❌ `null` | `resolveAmount(...)` devuelve `ZERO` si `caps.hasCreditLimit()` (líneas 186-191) |

**Y el consumidor filtra por su cuenta** — así es como funcionan los otros ocho:

```java
// disbursement-service · CreditAccountActivatedListener  [ACL]
if (event.disbursement() == null) return;                      // revolvente: no hay nada que pagar
if ("SELF_USE".equals(event.disbursement().dispositionType())) return;  // DO-06: el dinero se queda dentro
requestDisbursementUseCase.request(toCommand(event));
```

### 8.3 El hecho que falta: `credit-portfolio.disposition-authorized`

El camino de wallet no tiene ningún evento en el momento de autorizar. Hoy `process(...)` valida, despacha al stub y publica `disposition-completed` de golpe. Al separar autorización de liquidación, aparece un hecho que antes estaba implícito:

```java
public record DispositionAuthorizedPayload(
        String  eventId,
        UUID    dispositionId,        // clave de idempotencia
        UUID    creditAccountId,
        UUID    obligorPartyId,
        UUID    companyId,
        String  dispositionType,      // THIRD_PARTY_CREDIT | PAYROLL  (SELF_USE no se publica)
        BigDecimal amount,
        String  currency,
        String  beneficiaryName,
        String  beneficiaryAccount,
        String  beneficiaryAccountType,
        String  beneficiaryTaxId,
        Integer beneficiaryInstitution,
        UUID    beneficiaryPartyId,
        String  concept,
        String  sourceEventId,        // el dispositionRequestId de wallet — traza end-to-end
        Instant occurredOn
) {}
```

`SELF_USE` **no se publica**: el dinero no sale de la plataforma (`DO-06`), wallet acredita el `walletBalance` desde `disposition-completed` como ya hace.

### 8.4 Topics — el mapa completo

**Nuevos (9):**

| Topic | Productor | Consumidores | Key |
|---|---|---|---|
| **`credit-portfolio.disposition-authorized`** | credit-portfolio | **disbursement**, audit | `creditAccountId` |
| `disbursement.stp-requested` | disbursement | **stp** | `paymentRequestId` |
| `disbursement.completed` | disbursement | credit-portfolio, accounting, audit | `sourceReference` |
| `disbursement.failed` | disbursement | credit-portfolio, wallet, notifications, audit | `sourceReference` |
| `disbursement.returned` | disbursement | credit-portfolio, accounting, audit | `sourceReference` |
| `stp.order-accepted` | stp | disbursement | `paymentRequestId` |
| `stp.order-rejected` | stp | disbursement | `paymentRequestId` |
| `stp.order-settled` | stp | disbursement | `paymentRequestId` |
| `stp.order-returned` | stp | disbursement | `paymentRequestId` |

**Modificados (1):**

| Topic | Cambio | Riesgo |
|---|---|---|
| `credit-portfolio.credit-account-activated` | **+1 campo anidado** `disbursement` | Ninguno — §8.2 |

**Que pasan a ser verdad (1):**

| Topic | Antes | Después |
|---|---|---|
| `credit-portfolio.disposition-completed` | Se publica con `externalRef = "SPEI-STUB-…"` antes de que salga un peso | Se publica cuando `disbursement.completed` confirma la liquidación, con el `externalRef` real |

**Existentes que se consumen sin cambios:**

| Topic | Consumidor nuevo |
|---|---|
| `credit-portfolio.credit-account-activated` | disbursement (el 10.º consumidor) |
| `wallet.withdrawal-completed` | disbursement |
| `credit-portfolio.disposition-rejected` | **wallet** — revierte su reserva optimista (hoy se publica y nadie lo consume) |
| `configuration.configuration-updated` | ambos — invalidación de caché |
| `payments.surplus-return-initiated` | ⬜ No existe todavía (`PA-05` especificado sin código). Fuera del alcance |

### 8.5 El efecto colateral bueno: las notificaciones se vuelven honestas

`notifications-service` ya tiene las notificaciones cableadas al hecho correcto, y **no hay que tocarlas**:

| # | Notificación | Se dispara con | Qué pasa hoy |
|---|---|---|---|
| **#2** | `WELCOME_ACTIVATED` | `credit-portfolio.credit-account-activated` | ✅ Correcta |
| **#3** | `DISBURSEMENT_COMPLETED` | `credit-portfolio.disposition-completed` | ⚠️ Ver abajo |

**El estado real de la notificación #3 es peor de lo que parece, y es distinto en cada camino:**

| Camino | Qué recibe el cliente hoy | Por qué |
|---|---|---|
| **Originación** (producto no revolvente) | **Nada. Nunca.** | `activate()` no publica `disposition-completed` (§6.4). El aviso de desembolso del crédito principal **no existe** |
| **Disposición desde wallet** | Un aviso *"tu desembolso se completó"* emitido con un `externalRef = "SPEI-STUB-…"`, antes de que salga un peso | `process()` sí publica, pero con el resultado del stub |

Después del cambio, **ambos** caminos publican `disposition-completed` cuando `disbursement.completed` confirma la liquidación. Es decir:

- El desembolso de originación **empieza a notificarse**, cosa que hoy no ocurre.
- El de wallet **deja de notificarse antes de tiempo**.

Y todo eso **sin modificar una línea de `notifications-service`**. Eso es lo que da la arquitectura orientada a eventos cuando los hechos son honestos: se arregla el emisor y el resto de la cadena se corrige sola.

**Lo único que falta añadir ahí** es la contraparte negativa, que hoy no existe en ningún camino:

| # | Notificación nueva | Se dispara con |
|---|---|---|
| **#3b** | `DISBURSEMENT_FAILED` | `disbursement.failed` |

Un `EventType` nuevo, un listener nuevo, una plantilla nueva. Entra como entregable 2B.11.

### 8.6 Payloads de ejecución

**`disbursement.stp-requested`** — sin una sola palabra del dominio de crédito:

```java
public record StpPaymentRequestedPayload(
        UUID    paymentRequestId,      // idempotencia en stp-service
        UUID    companyId,
        BigDecimal amount,             String currency,
        String  beneficiaryName,       String beneficiaryAccount,
        String  beneficiaryAccountType, String beneficiaryTaxId,
        Integer beneficiaryInstitution,
        String  concept,               Long numericReference,
        String  paymentType,           String correlationId,
        Instant occurredOn) {}
```

**`stp.order-accepted`** — STP aceptó el registro. **No significa liquidado.**

```java
public record StpOrderAcceptedPayload(
        UUID paymentRequestId, UUID companyId,
        String stpOrderId,        // idRespuestaStp
        String trackingKey,       // claveRastreo — identificador ante Banxico
        Instant occurredOn) {}
```

**`stp.order-rejected`**

```java
public record StpOrderRejectedPayload(
        UUID paymentRequestId, UUID companyId,
        Integer banxicoCode,      String banxicoReason,
        String detail, boolean retryable, Instant occurredOn) {}
```

**`stp.order-settled`** — lo emite el poller (§10.4). **Aquí sí salió el dinero.**

```java
public record StpOrderSettledPayload(
        UUID paymentRequestId, UUID companyId,
        String trackingKey, String cepUrl, String cepBeneficiaryName,
        boolean beneficiaryNameMatches,   // compareNames normalizado
        Instant settledAt,
        String observedVia,               // POLL_RECONCILIATION | POLL_ORDER | EOD_BATCH
        Instant occurredOn) {}
```

**`stp.order-returned`**

```java
public record StpOrderReturnedPayload(
        UUID paymentRequestId, UUID companyId, String trackingKey,
        String status,            // DEVUELTA | CANCELADA
        String returnCauseCode, String observedVia, Instant occurredOn) {}
```

**`disbursement.failed` / `disbursement.returned`** — misma forma que `completed`, con el motivo en vez del resultado:

```java
public record DisbursementFailedPayload(
        UUID disbursementId, UUID companyId,
        String sourceSystem, String sourceType, String sourceReference, String sourceEventId,
        Map<String,String> sourceMetadata,
        BigDecimal amount, String currency,
        String rail, String provider,
        String failureCode,        // INVALID_BENEFICIARY_ACCOUNT | NO_ROUTING_RULE |
                                   // UNRESOLVED_COMPANY | PROVIDER_REJECTED | …
        String failureReason,
        Integer providerCode,      // banxicoCode traducido, nullable
        Instant occurredOn) {}

public record DisbursementReturnedPayload(
        UUID disbursementId, UUID companyId,
        String sourceSystem, String sourceType, String sourceReference, String sourceEventId,
        Map<String,String> sourceMetadata,
        BigDecimal amount, String currency,
        String rail, String provider, String externalRef,
        String returnCauseCode, Instant returnedAt, Instant occurredOn) {}
```

**`disbursement.completed`** — devuelve el eco del origen para que el emisor correlacione sin guardar estado:

```java
public record DisbursementCompletedPayload(
        UUID disbursementId, UUID companyId,
        String sourceSystem,          // "credit-portfolio"
        String sourceType,            // DISPOSITION | WITHDRAWAL | …
        String sourceReference,       // el creditAccountId, como string opaco
        String sourceEventId,         // el dispositionId original
        Map<String,String> sourceMetadata,   // eco literal
        BigDecimal amount, String currency,
        String rail, String provider, String externalRef, String cepUrl,
        Instant settledAt, Instant occurredOn) {}
```

### 8.7 Regla de oro del contrato

> **`stp-service` es el único servicio del monorepo que conoce el vocabulario de STP.** Ni `claveRastreo`, ni `firma`, ni `institucionOperante`, ni `empresa`, ni `RespuestaBanxicoDTO` cruzan hacia `disbursement.*`. Lo que sale es `externalRef`, `banxicoCode` y `banxicoReason`: traducido, estable y versionable.
>
> **`disbursement-service` es el único que conoce el vocabulario de rails de pago.** Ni `rail`, ni `provider`, ni `trackingKey` cruzan hacia `credit-portfolio`. Lo que vuelve es `externalRef` y `settledAt`.
>
> **`credit-portfolio` no conoce a ninguno de los dos.** Publica hechos de su propio dominio y consume el resultado. Si mañana desaparece `disbursement-service`, `credit-portfolio` compila y arranca igual: simplemente nadie recoge sus hechos.

---

## 9. `disbursement-service` — diseño

### 9.1 Estructura de paquetes

Estilo **wallet** para la capa de aplicación (ports `in` + records `*Command` + `*Service implements *UseCase`); estilo **payments** para persistencia y Liquibase.

```
com/fintech/disbursement/
├── package-info.java                        @ApplicationModule(allowedDependencies = {"shared"})
├── DisbursementApplication.java
├── application/
│   ├── DisbursementProperties.java          @ConfigurationProperties("fintech.disbursement") @Validated
│   ├── RequestDisbursementCommand.java       record — NEUTRO, sin conceptos de crédito
│   ├── SettleDisbursementCommand.java
│   ├── FailDisbursementCommand.java
│   ├── port/in/
│   │   ├── RequestDisbursementUseCase.java
│   │   ├── SettleDisbursementUseCase.java
│   │   ├── FailDisbursementUseCase.java
│   │   └── GetDisbursementUseCase.java
│   ├── port/out/
│   │   ├── DisbursementOrderRepository.java
│   │   ├── DisbursementEventRepository.java
│   │   ├── RoutingRuleRepository.java
│   │   ├── CompanyResolutionRepository.java
│   │   ├── DisbursementEventPublisher.java
│   │   └── ProviderDispatchPort.java         abstracción del rail
│   └── service/
│       ├── DisbursementOrchestrationService.java
│       ├── DisbursementSettlementService.java
│       ├── RoutingService.java
│       └── CompanyResolutionService.java
├── domain/                                   ← CERO menciones a crédito
│   ├── DisbursementOrder.java                @Entity — aggregate root
│   ├── DisbursementStatus.java
│   ├── DisbursementSource.java               enum DISPOSITION | WITHDRAWAL | SURPLUS_RETURN
│   │                                              | ACCOUNT_VERIFICATION | API | MANUAL
│   ├── Beneficiary.java                      @Embeddable — nombre, cuenta, tipo, taxId, institución
│   ├── Rail.java                             enum SPEI | INTERNAL | CODI
│   ├── Provider.java                         enum STP
│   ├── RoutingRule.java                      @Entity
│   ├── CompanyMapping.java                   @Entity
│   ├── DisbursementEvent.java                @Entity — bitácora append-only
│   ├── ClabeValidator.java                   value object — dígito verificador
│   ├── OperatingWindow.java                  value object — ventana envolvente
│   ├── DisbursementNotFoundException.java
│   ├── InvalidDisbursementStateException.java
│   ├── InvalidBeneficiaryAccountException.java
│   ├── NoRoutingRuleException.java
│   └── UnresolvedCompanyException.java
└── infrastructure/
    ├── adapter/in/api/
    │   ├── DisbursementController.java        consulta + retry + cancel (interno)
    │   ├── DisbursementIngestController.java  POST /api/v1/disbursements — API de PRODUCTO
    │   ├── DisbursementExceptionHandler.java
    │   ├── JwtAuthenticationFilter.java       copia literal de payments
    │   └── dto/
    ├── adapter/in/messaging/                  ← TODO el conocimiento de crédito vive aquí
    │   ├── CreditAccountActivatedListener.java       + payload   [ACL] activación
    │   ├── DispositionAuthorizedListener.java        + payload   [ACL] disposición wallet
    │   ├── WalletWithdrawalListener.java             + payload   [ACL] retiro
    │   ├── StpOrderAcceptedListener.java             + payload
    │   ├── StpOrderRejectedListener.java             + payload
    │   ├── StpOrderSettledListener.java              + payload
    │   └── StpOrderReturnedListener.java             + payload
    ├── adapter/out/messaging/
    │   ├── KafkaDisbursementEventPublisher.java
    │   ├── KafkaStpDispatchAdapter.java       implements ProviderDispatchPort
    │   └── *Payload.java
    ├── adapter/out/persistence/
    │   ├── JpaDisbursementOrderRepository.java
    │   ├── JpaDisbursementEventRepository.java
    │   ├── JpaRoutingRuleRepository.java
    │   └── JpaCompanyMappingRepository.java
    ├── job/
    │   └── DisbursementDispatchJob.java       despacha lo pendiente al abrir la ventana
    └── config/
        ├── KafkaConfig.java · SecurityConfig.java · OpenApiConfig.java
        └── DisbursementModuleConfig.java
```

> **Prueba de que el servicio es vendible aparte:** borra los tres listeners ACL y sus payloads. El resto compila y funciona con la API REST de ingesta. Esa es la definición operativa de "desacoplado del dominio de crédito", y en §7.5 está escrita como test de arquitectura, no como promesa.

### 9.2 Por qué evento y no REST, y por qué un hecho existente y no un topic nuevo

Son dos decisiones distintas y conviene separarlas.

#### (a) Evento, no REST

**Lo que ya hace el repo** — no es dogma, es el patrón vigente y hay una línea clara:

| Uso | Mecanismo | Ejemplos reales |
|---|---|---|
| **Comandos / cambios de estado entre dominios** | **Kafka** | `wallet.disposition-requested` → credit-portfolio; `origination.credit-product-creation-requested` → credit-portfolio; `origination.score-requested` → scoring |
| **Lecturas / consultas de catálogo** | REST | origination → credit-product (`RestClientProductCatalogAdapter`), origination → party (`RestClientPartyAdapter`), channels → party (`PartyServiceClient`) — los tres `.get()` |

El README lo enuncia como principio (*"Kafka es la columna vertebral… única excepción síncrona: validar token contra identity"*) y el código lo matiza: **REST para leer, Kafka para lo demás**.

Los seis argumentos concretos:

1. **La llamada está dentro de `@Transactional`.** `CreditAccountService` lleva la anotación a nivel de clase (línea 45). Un `RestClient` ahí tiene el mismo defecto que el `speiDispatch` actual: la transacción que ajusta saldos queda abierta durante un round-trip a otro servicio, y un timeout deja el saldo aplicado sin saber si la orden se creó.

2. **Acoplamiento temporal, y esto es lo decisivo para comercializar.** Con REST, si `disbursement-service` está caído **no se puede activar un crédito**. El core de crédito quedaría rehén de la disponibilidad del módulo de pagos. Con evento, el crédito se activa y el desembolso se procesa cuando el servicio vuelva.

3. **El resultado es intrínsecamente asíncrono.** STP acepta el registro en segundos, pero la liquidación se confirma minutos u horas después, por consulta (§10.4). Un REST devolvería `202 Accepted` y aun así harían falta eventos de vuelta: acabarías manteniendo **dos** mecanismos donde uno basta.

4. **Hay nueve interesados, no uno.** El hecho "se activó el crédito" ya lo consumen notifications, charges, payments, wallet, commission, collections, risk, origination y audit. Un REST sería un décimo canal punto a punto para el mismo hecho.

5. **Reintentos y DLT gratis.** Un evento que falla se reintenta con backoff y acaba en DLT con evidencia. Con REST hay que construir cola de reintentos, backoff y almacén de fallos **dentro de `credit-portfolio`** — reimplementar Kafka a mano en el servicio más sensible del sistema.

6. **Precedente idéntico ya en producción.** `wallet.disposition-requested` es exactamente esto. Usar REST aquí crearía dos patrones para el mismo problema en el mismo servicio.

**La única objeción seria** es la pérdida del rechazo inmediato: con REST, una CLABE con dígito verificador inválido devuelve `400` en el acto. Se mitiga de dos formas, ambas en el diseño: (a) `credit-portfolio` valida la CLABE **antes** de publicar — es una validación local, no necesita a nadie; (b) `disbursement.failed` con `failureCode=INVALID_BENEFICIARY_ACCOUNT` llega y dispara la notificación #3b.

#### (b) Hecho existente, no topic nuevo

Descartada la vía REST, quedaba elegir **qué** evento. La versión anterior de este documento proponía `credit-portfolio.disbursement-requested`. Estaba mal, y vale la pena decir por qué:

| | `disbursement-requested` (descartado) | `credit-account-activated` enriquecido |
|---|---|---|
| Qué es | Un **comando** con forma de evento: *"desembolsa esto"* | Un **hecho**: *"el crédito se activó"* |
| Quién decide qué hacer | El emisor, al nombrar la acción en el topic | Cada consumidor, por su cuenta |
| Encaja con *"coreografía, no orquestación"* | ❌ | ✅ |
| Topics para el mismo instante | **2** (`activated` + `disbursement-requested`) publicados en el mismo commit | **1** |
| Acoplamiento | `credit-portfolio` sabe que existe un servicio de desembolso | No sabe que existe |
| Añadir un consumidor nuevo | Hay que decidir a cuál de los dos topics engancharlo | Se engancha al hecho, como los otros nueve |

Dos eventos publicados en el mismo instante describiendo el mismo suceso es el olor clásico de un comando infiltrado. Y hay un argumento práctico además del estético: con el topic-comando, `credit-portfolio` **nombra** al servicio de desembolso en su propio código. Con el hecho, no. La dirección de la dependencia se invierte, que es justo lo que hace falta para que los dos servicios sean vendibles por separado (§7.2).

#### (c) La consecuencia que hay que aceptar: qué significa `ACTIVE`

Si `credit-account-activated` es el disparador del desembolso, **la activación no puede esperar al desembolso** — sería una dependencia circular. Eso decide una cuestión que la versión anterior dejaba abierta (`Q2`):

```
ACTIVE  =  "el crédito existe, la deuda existe, el devengamiento corrió,
            y el desembolso va en camino"

           NO significa "el dinero ya está en la cuenta del cliente"
```

**Y está bien que sea así**, por tres razones que se sostienen en el código:

1. **La deuda nace en la activación, no en la liquidación.** `account.activate(disbursementAmount)` fija el `principalBalance`, y `charges-service` arranca el devengamiento con `credit-account-activated`. El obligado ya debe. Retrasar eso hasta que STP confirme sería contablemente incorrecto.
2. **El hecho "el dinero llegó" ya tiene su propio evento**: `disposition-completed`. Lo que hay que arreglar no es *cuándo* se activa la cuenta, sino que ese segundo evento **hoy miente**. Con este diseño pasa a ser verdad.
3. **La notificación al cliente ya distingue los dos momentos:** `#2 WELCOME_ACTIVATED` con la activación y `#3 DISBURSEMENT_COMPLETED` con la liquidación. El producto ya estaba modelado bien; era la implementación la que no lo respetaba.

> **Efecto sobre el plan:** la antigua Fase 5 (*"ACTIVE = el dinero salió"*) **desaparece**. En su lugar queda documentar la semántica en `04b_credit_portfolio_domain.md` y añadir el estado intermedio de la `Disposition`, que es donde vive de verdad la información de si el dinero salió o no.

### 9.3 Qué cambia exactamente en `credit-portfolio` (Fase 2-B)

**Camino de activación** — `CreditAccountService.activate(...)`:

```java
// ── ANTES (líneas 132-145) ────────────────────────────────────────────────
BigDecimal disbursementAmount = resolveAmount(cmd, caps);
if (disbursementAmount.signum() > 0) {
    Disposition disposition = Disposition.create(
            account.getCreditAccountId(), resolveDispositionType(caps), disbursementAmount, null);
    disposition.markProcessing();
    dispositionRepository.save(disposition);
    String externalRef = speiDispatch.dispatch(              // ← HTTP dentro de @Transactional
            disposition.getDispositionId(), disbursementAmount, cmd.clabeAccount());
    disposition.complete(externalRef);                       // ← COMPLETED sin que salga dinero
    dispositionRepository.save(disposition);
}
account.activate(disbursementAmount);
accountRepository.save(account);
eventPublisher.publishCreditAccountActivated(new CreditAccountActivatedEvent(/* 15 campos */));

// ── DESPUÉS ───────────────────────────────────────────────────────────────
BigDecimal disbursementAmount = resolveAmount(cmd, caps);
DisbursementInstruction instruction = null;

if (disbursementAmount.signum() > 0) {
    Disposition disposition = Disposition.create(
            account.getCreditAccountId(), resolveDispositionType(caps), disbursementAmount, null);
    disposition.markProcessing();                            // ← se QUEDA en PROCESSING
    dispositionRepository.save(disposition);

    instruction = new DisbursementInstruction(               // ← el bloque del §8.2
            disposition.getDispositionId(), resolveCompanyId(account),
            disposition.getDispositionType().name(), disbursementAmount, "MXN",
            account.getBeneficiaryName(), account.getClabeAccount(), "40",
            account.getBeneficiaryTaxId(), null, null, "DISPOSICION DE CREDITO");
}

account.activate(disbursementAmount);                        // ← sin cambios: la deuda nace aquí
accountRepository.save(account);

eventPublisher.publishCreditAccountActivated(
        new CreditAccountActivatedEvent(/* los 15 campos */, instruction));   // ← +1 campo
```

**Camino de wallet** — `CreditAccountService.process(...)`:

```java
// ANTES:  externalRef = SELF_USE ? "WALLET-CREDIT" : speiDispatch.dispatch(...)
//         disposition.complete(externalRef)
//         → publishBalanceUpdated + publishDispositionCompleted

// DESPUÉS:
if (type == DispositionType.SELF_USE) {
    disposition.complete("WALLET-CREDIT");                   // el dinero no sale: se completa ya
} else {
    disposition.markProcessing();                            // queda en vuelo
}
dispositionRepository.save(disposition);
account.applyDisposition(cmd.amount());
accountRepository.save(account);
balanceEventRepository.save(BalanceEvent.record(...));
eventPublisher.publishBalanceUpdated(...);                   // sin cambios

if (type == DispositionType.SELF_USE) {
    eventPublisher.publishDispositionCompleted(...);          // igual que hoy — wallet acredita (DO-06)
} else {
    eventPublisher.publishDispositionAuthorized(...);         // ← hecho nuevo (§8.3)
}
```

**Cierre del ciclo** — dos listeners nuevos:

```java
@KafkaListener(topics = "disbursement.completed", …)
public void onDisbursementCompleted(DisbursementCompletedPayload event) {
    // sourceMetadata.dispositionId → disposition.complete(event.externalRef())
    // → publishDispositionCompleted  ← el evento que wallet y notifications YA consumen,
    //                                   ahora con el externalRef real
}

@KafkaListener(topics = "disbursement.failed", …)
public void onDisbursementFailed(DisbursementFailedPayload event) {
    // disposition.fail() + BalanceEvent compensatorio que revierte applyDisposition
    // → publishBalanceUpdated con el saldo corregido
}
```

**Se eliminan:** `SpeiDispatchPort`, `NoopSpeiDispatchAdapter`, y el `@Mock SpeiDispatchPort` de `CreditAccountServiceTest`.

#### Precondición real del diseño: falta la identidad del beneficiario

Verificado en el código: **`CreditAccount` guarda `clabeAccount` pero no guarda el nombre ni el RFC/CURP del beneficiario.** Tampoco los lleva `CreditProductCreationRequestedPayload`, ni `CreateCreditAccountCommand`, ni `RequestDispositionCommand` de wallet (que tiene `beneficiaryPartyId` y `payeeAccount`, pero ningún nombre).

Y STP los exige: `nombreBeneficiario` ocupa la posición 14 de la cadena firmada y `rfcCurpBeneficiario` la 16. **Sin ellos no se puede construir una orden de pago.**

Tres formas de cerrarlo:

| Opción | Cómo | Veredicto |
|---|---|---|
| **A — origination los propaga** | Añadir `obligorName` + `obligorTaxId` a `credit-product-creation-requested` y a `CreditAccount`. Origination ya los tiene del `Prospect` | ✅ **Recomendada.** Es *event-carried state transfer* y encaja con el principio de **snapshot inmutable al originar**: el nombre con el que se desembolsa queda congelado, igual que los términos |
| B — `disbursement` consulta a `party-service` | Es una lectura, permitida por el patrón REST del repo | ❌ **Mata la comercialización.** `disbursement-service` pasaría a depender de un servicio del dominio de crédito, rompiendo §7.2 |
| C — `stp-service` lo resuelve | — | ❌ Peor: metería el dominio de personas en el conector |

Para el caso `THIRD_PARTY_CREDIT` el beneficiario es **otra** parte, así que `wallet` también tiene que llevar su nombre y RFC en `RequestDispositionCommand` y en el evento. Es el mismo trabajo, en el otro extremo.

**Consecuencia para el plan:** esto añade un entregable en `origination-service` y otro en `wallet-service` que no estaban contemplados. Van como **2B.1** y **2B.2**, y son **bloqueantes**: sin ellos no se puede firmar una orden.

### 9.4 Máquina de estados de `DisbursementOrder`

```
  REQUESTED ──► DISPATCHED ──► ACCEPTED ──► SETTLED    (terminal, éxito)
      │              │             │
      │              │             └──────► RETURNED   (terminal — devuelto por el banco receptor)
      │              └────────────────────► REJECTED   (terminal — el proveedor rechazó el registro)
      └───────────────────────────────────► FAILED     (terminal — validación local o sin routing)

  CANCELLED  (sólo desde REQUESTED, por operación manual y con motivo)
```

| ID | Invariante |
|---|---|
| **DB-01** | Estado terminal inmutable. Cualquier transición desde `SETTLED`/`REJECTED`/`RETURNED`/`FAILED`/`CANCELLED` → `InvalidDisbursementStateException` (422) |
| **DB-02** | `(sourceSystem, sourceType, sourceEventId)` es **UNIQUE**. Evento repetido → log `"Duplicate … — skipping (idempotent)"` y `return` |
| **DB-03** | `SETTLED` sólo con evidencia del proveedor. **`ACCEPTED` no es dinero entregado** y no se reporta como tal |
| **DB-04** | La cuenta del beneficiario se valida (dígito verificador CLABE) **antes** de despachar. Falla → `FAILED` con `INVALID_BENEFICIARY_ACCOUNT`, sin tocar al proveedor |
| **DB-05** | Fuera de la ventana operativa la orden queda `REQUESTED` con `scheduled_for`. **No se rechaza** |
| **DB-06** | Toda transición escribe en `disbursement_events` (append-only). Nunca se borra ni se actualiza |
| **DB-07** | `companyId` obligatorio, resuelto **antes** de crear la orden. Sin empresa → `FAILED` con `UNRESOLVED_COMPANY` |
| **DB-08** | Un `banxicoCode` clasificado `RETRYABLE` (p.ej. `-30`) **no** pasa a `REJECTED`: vuelve a `REQUESTED` con backoff y contador acotado |
| **DB-09** | El núcleo no lee `sourceMetadata` para decidir nada. Es carga opaca que se devuelve en eco. *(Invariante de comercialización — §7.2)* |

### 9.5 `RoutingService`

Sustituye al `cat_tipotransaccionesstp` del legado, que hacía de proxy accidental de "cuenta ordenante".

```java
RoutingDecision decide(UUID companyId, Rail preferredRail, BigDecimal amount, String beneficiaryAccountType)
```

Selecciona la regla activa de mayor prioridad que satisfaga `companyId` + `rail` + `amount ∈ [minAmount, maxAmount]`; devuelve `(rail, provider, targetTopic)`. Sin regla → `NoRoutingRuleException` → orden `FAILED` con `NO_ROUTING_RULE`.

Permite sin tocar código: subir un tope por empresa, apagar STP para una empresa concreta, o enrutar montos grandes a otro proveedor.

### 9.6 API REST

```
# Interna — operación vía channel-backoffice-service
GET  /api/v1/disbursements/{disbursementId}
GET  /api/v1/disbursements?companyId=&status=&from=&to=
POST /api/v1/disbursements/{disbursementId}/retry     rol OPS_SUPERVISOR / ADMIN
POST /api/v1/disbursements/{disbursementId}/cancel    rol OPS_SUPERVISOR / ADMIN

# De producto — para un comprador que no tiene nuestro Kafka
POST /api/v1/disbursements     Idempotency-Key: <clave del cliente>     autenticado (header-trust)
```

`credit-portfolio` **nunca** usa `POST /api/v1/disbursements`: entra por evento. La API existe para que el servicio sea un producto, y su clave de idempotencia (`Idempotency-Key`) se mapea a `sourceEventId` con `sourceSystem = "api"` y `sourceType = "API"`, reusando exactamente el mismo camino y el mismo `UNIQUE` (**DB-02**).

---

## 10. `stp-service` — diseño

### 10.1 Estructura de paquetes

```
com/fintech/stp/
├── package-info.java · StpApplication.java
├── application/
│   ├── StpProperties.java                    @ConfigurationProperties("fintech.stp") @Validated
│   ├── RegisterPaymentOrderCommand.java · ApplySettlementCommand.java
│   ├── port/in/
│   │   ├── RegisterPaymentOrderUseCase.java
│   │   ├── PollSettlementsUseCase.java
│   │   └── ApplySettlementUseCase.java
│   ├── port/out/
│   │   ├── StpPaymentOrderRepository.java · StpPaymentOrderEventRepository.java
│   │   ├── SettlementObservationRepository.java
│   │   ├── StpCompanyRepository.java · OrderingAccountRepository.java
│   │   ├── TrackingKeySequencePort.java
│   │   ├── SigningKeyProvider.java           resuelve PrivateKey/PublicKey por companyId
│   │   ├── StpGatewayPort.java               ACL de salida hacia STP
│   │   └── StpEventPublisher.java
│   └── service/
│       ├── PaymentOrderRegistrationService.java
│       ├── SettlementPollingService.java     ← sustituye a los webhooks
│       └── SettlementApplicationService.java
├── domain/
│   ├── StpPaymentOrder.java · StpPaymentOrderStatus.java · StpPaymentOrderEvent.java
│   ├── SettlementObservation.java            @Entity — evidencia cruda, append-only
│   ├── StpCompany.java · StpCompanyKey.java · KeyPurpose.java · OrderingAccount.java
│   ├── BanxicoResponseCode.java              32 códigos + clasificación retryable
│   ├── StpOrderStatusCode.java               LQ/TLQ/CCO/CXO/CCE · D/TD/RE · CL/TCL
│   ├── signing/
│   │   ├── OrdenPagoFirma.java · SaldoCuentaFirma.java · ConciliacionFirma.java
│   │   ├── CadenaOriginalBuilder.java        el corazón, explícito y determinista
│   │   └── SignatureAlgorithm.java
│   ├── TrackingKey.java · BeneficiaryNameMatcher.java
│   └── <excepciones de dominio>
└── infrastructure/
    ├── adapter/in/api/
    │   ├── StpAdminController.java           empresas / cuentas / llaves (rol ADMIN)
    │   ├── StpOperationsController.java      consulta (ADMIN/OPS_SUPERVISOR/AUDITOR)
    │   ├── StpExceptionHandler.java · JwtAuthenticationFilter.java
    │   └── dto/
    ├── adapter/in/messaging/
    │   └── StpPaymentRequestedListener.java  topic configurable + payload
    ├── adapter/out/http/
    │   ├── RestClientStpGateway.java         implements StpGatewayPort
    │   └── dto/  RegistraOrdenPagoRequest · ConciliacionRequest · RespuestaBanxico …
    ├── adapter/out/crypto/
    │   ├── EnvelopeEncryptedKeyProvider.java · KeyEncryptionKeyResolver.java · SigningKeyCache.java
    ├── adapter/out/persistence/  (Jpa*Repository + PostgresTrackingKeySequenceAdapter)
    ├── adapter/out/messaging/    KafkaStpEventPublisher + payloads
    ├── job/
    │   ├── StpSettlementPollingJob.java      consulta activa de órdenes en vuelo
    │   ├── StpOutboxRelayJob.java            relay del outbox → registro de órdenes
    │   └── StpEndOfDayReconciliationJob.java barrido de cierre (Fase 4)
    └── config/  KafkaConfig · SecurityConfig · OpenApiConfig · StpModuleConfig · RestClientConfig
```

**No hay `StpWebhookController`.** No hay ninguna ruta que STP pueda invocar.

### 10.2 Flujo de registro de orden

```
StpPaymentRequestedListener  (topic de fintech.stp.inbound-topic)
  ▼ PaymentOrderRegistrationService.register(cmd)          @Transactional
  │
  ├─ IDEMPOTENCIA:  if (repo.existsByPaymentRequestId(...)) { log "…skipping (idempotent)"; return; }
  ├─ StpCompany company   = companyRepo.findActiveById(cmd.companyId())
  ├─ OrderingAccount ord  = orderingRepo.findDefaultByCompany(company.getId())
  ├─ TrackingKey key      = TrackingKey.generate(company.getTrackingPrefix(), businessDate,
  │                                              trackingKeySequence.next(companyId, businessDate))
  ├─ StpPaymentOrder order = StpPaymentOrder.create(...)   status = PENDING
  ├─ orderRepo.save(order) + payment_order_events(PENDING)
  └─ outbox_messages(REGISTER_ORDER)                        ← COMMIT. Nada de red aquí
        │
        ▼ StpOutboxRelayJob  (SELECT … FOR UPDATE SKIP LOCKED)
        ├─ OrdenPagoFirma → CadenaOriginalBuilder → SigningService(companyId)
        ├─ PUT {stp.base-url}ordenPago/registra          ── EGRESS ──►
        ├─ id > 0            → order.accept(stpOrderId)  → stp.order-accepted
        └─ id ≤ 0            → BanxicoResponseCode.of(id)
                                ├─ CLAVE_RASTREO_DUPLICADA (-1) → §13.2 nivel 3: ÉXITO idempotente
                                ├─ RETRYABLE                    → order.retry() con backoff
                                └─ TERMINAL                     → order.reject() → stp.order-rejected
```

### 10.3 Cambios sustantivos respecto al legado

| Legado | Nuevo |
|---|---|
| `claveRastreo` = prefijo fijo + fecha + `zeroPad(14, PK)` | Secuencia por `(companyId, businessDate)` — no acopla el identificador ante Banxico al PK de Postgres |
| Detección de error por longitud de string (**B1**) | `BanxicoResponseCode.of(int)` — mapeo explícito de 32 códigos + `UNKNOWN` que **falla cerrado** |
| Truncado de nombre a 40 después del save (**B7**) | Se trunca **antes** de persistir y se guardan **ambos**: `beneficiary_name` (completo, auditoría) y `beneficiary_name_sent` (40, lo que se firmó) |
| `BeanUtils.copyProperties` con pérdida silenciosa de 10 campos | Mapeo explícito y probado |
| Llave recargada del disco en cada firma | `SigningKeyProvider` con caché e invalidación por rotación |
| **STP nos avisa por 3 endpoints abiertos** | **Nosotros consultamos a STP.** Cero superficie de entrada (§10.4) |
| `sello` del acuse persistido sin validar | Se verifica contra la llave pública de STP de la empresa (**SO-04**) |
| `Class.forName(BD)` para el token saliente | Eliminado — no hay salida hacia el core legado |
| Unirest 1.4.9 con `setTimeouts` estático global | `RestClient` de Spring 6.1, timeouts por request, circuit breaker |

### 10.4 Confirmación de liquidación **sin webhooks**

Este es el cambio estructural que impone "sin exposición a internet". El legado se enteraba porque STP le pegaba a tres endpoints abiertos. Ahora el flujo se invierte: **la plataforma consulta**.

**Lo que STP ofrece para consultar** (verificado en el legado):

| Endpoint | Base | Payload | Firma | Devuelve |
|---|---|---|---|---|
| `V2/conciliacion` | `stp.consulta.base-url` | `{empresa, tipoOrden, fechaOperacion, page, firma}` | `ConciliacionFirma{empresa, tipoOrden, fechaOperacion}` — 3 campos | `{estado, mensaje, total, datos[]}` paginado de 1000, con `claveRastreo`, `estado`, `causaDevolucion`, `tsLiquidacion`, `sello`, `urlCEP`, `nombreCep` |
| `consultaSaldoCuenta` | `stp.consulta.base-url` | `{empresa, cuenta, fecha, firma}` | `SaldoCuentaFirma` — 3 campos | saldo y detalle |
| `api/dispersion` (consulta por rastreo) | `stp.base-url` | `ConsultaOrdenDTO{claveRastreo, fechaOperacion, institucionOperante, empresa, firma}` | por reflexión, excluyendo `firma` | `RespuestaCEPDTO` |

> ⚠️ El tercero es **dudoso**: en el legado el método se llama `consultarOrdenEnviadaPorRastreo` pero apunta a `api/dispersion` (la base de dispersión, no la de consultas) y no tiene ningún caller vivo. **Hay que validarlo con STP antes de depender de él** — es la **Q14**.

**El diseño, a dos velocidades:**

```
┌── Poller de órdenes en vuelo ──────────────────────────────────────────────┐
│  StpSettlementPollingJob — cada fintech.stp.polling.interval (default 3 min)│
│                                                                              │
│  1. ¿Hay órdenes en SENT o ACCEPTED con sent_at < now - grace? Si no → termina│
│  2. Agrupa por (companyId, businessDate)                                     │
│  3. Por grupo: POST V2/conciliacion {tipoOrden: "E", fechaOperacion, page}   │
│     paginando hasta cubrir `total`                                           │
│  4. Cruza por claveRastreo contra las órdenes en vuelo                        │
│  5. Por coincidencia → SettlementObservation (append-only, idempotente)      │
│  6. Aplica la transición y publica stp.order-settled | stp.order-returned    │
└──────────────────────────────────────────────────────────────────────────────┘

┌── Barrido de cierre de día (Fase 4) ───────────────────────────────────────┐
│  StpEndOfDayReconciliationJob — la conciliación completa del legado:         │
│  totales, saldo final calculado vs consultado, auto-descubrimiento de pagos │
│  perdidos. Es la fuente de verdad; el poller es la vía rápida.              │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Reglas de la observación de liquidación** (sustituyen a las `WH-*` de la versión anterior):

| ID | Regla |
|---|---|
| **SO-01** | Toda respuesta de STP se persiste en `stp.settlement_observations` **antes** de aplicarse. El payload crudo es evidencia regulatoria |
| **SO-02** | `UNIQUE (company_id, tracking_key, observed_status, observed_at_source)` → una observación repetida se marca `DUPLICATE` y no reaplica nada |
| **SO-03** | Una observación cuya `claveRastreo` no case con ninguna orden → `UNMATCHED` + alerta. **No se descarta**: puede ser una orden creada por el legado durante el corte, o una que perdimos |
| **SO-04** | Si la observación trae `sello`, se **verifica** contra la llave pública de STP de la empresa. Inválido → `SIGNATURE_INVALID` + alerta, y **no se aplica** |
| **SO-05** | El poller **nunca escribe hacia STP**. Sólo lee, persiste y publica en Kafka |
| **SO-06** | Una orden en `SENT` o `ACCEPTED` sin observación durante más de `fintech.stp.settlement-timeout` (default 24 h) genera alerta y queda para el barrido de cierre. **Nunca se marca `SETTLED` por timeout** |

**Comparación honesta con webhooks:**

| | Webhooks (descartado) | Polling (elegido) |
|---|---|---|
| Superficie de ataque | Endpoint público, autenticación, verificación de firma, rate limit, DDoS | **Cero.** Sólo egress TLS iniciado por nosotros |
| Latencia de confirmación | Segundos | Hasta el intervalo de poll (3 min por defecto, configurable) |
| Confianza en el canal | Hay que probar que el que llama es STP | La conexión la abrimos nosotros contra el host de STP: **la autenticación del canal es el propio TLS** |
| Pérdida de eventos | Si el endpoint está caído, depende del reintento de STP | Imposible: el estado se relee hasta que coincide |
| Costo | Bajo por evento | N llamadas por intervalo; acotado porque sólo corre si hay órdenes en vuelo |
| Infra necesaria | Server block en el gateway, un BFF para el socio, registro de cliente en identity, whitelist de IPs, rate limits | **Ninguna** |

El intercambio real es **latencia por superficie de ataque**, y para un flujo donde la confirmación de SPEI ya es de naturaleza batch, 3 minutos es irrelevante. La ganancia de seguridad y de infraestructura no lo es.

> **Nota:** si más adelante el negocio exige confirmación en segundos, la evolución natural **no** es abrir un endpoint: es bajar el intervalo del poller y, si hace falta, usar el endpoint de consulta por clave de rastreo (**Q14**) para las órdenes recién enviadas. La arquitectura no cambia.

### 10.5 API REST — toda interna

```
# Administración (rol ADMIN, vía channel-backoffice-service)
POST   /api/v1/stp/companies
GET    /api/v1/stp/companies
POST   /api/v1/stp/companies/{id}/ordering-accounts
POST   /api/v1/stp/companies/{id}/keys              alta / rotación
GET    /api/v1/stp/companies/{id}/keys              metadatos: huella, vigencia, estado
DELETE /api/v1/stp/companies/{id}/keys/{keyId}      revocación

# Operación (ADMIN / OPS_SUPERVISOR / AUDITOR)
GET    /api/v1/stp/payment-orders/{paymentRequestId}
GET    /api/v1/stp/payment-orders?companyId=&status=&businessDate=
GET    /api/v1/stp/settlement-observations?trackingKey=
POST   /api/v1/stp/poll                             disparo manual del poller (ADMIN/OPS_SUPERVISOR)
```

> **Regla:** ningún endpoint devuelve jamás material criptográfico. `GET /keys` devuelve `alias`, `fingerprintSha256`, `validFrom`, `validTo`, `status`, `algorithm`, `keySize`, `purpose`. Nada más.

### 10.6 Stub de STP para ambientes bajos

En local, CI y ambientes bajos no se habla con STP. Pero el sustituto **no puede ser un `return true`**: si el stub no ejercita la firma, la conciliación y la verificación del sello, esos tres caminos sólo se prueban en producción — que es exactamente lo que pasa hoy con `NoopSpeiDispatchAdapter`.

**Principio de diseño: el stub devuelve lo que se le envió, más lo único que sólo el banco puede saber.**

#### Dónde vive

El repo ya tiene el patrón: `NoopSpeiDispatchAdapter`, `NoopWalletDispatchAdapter`, `NoopPushAdapter`, `NoopPacAdapter` — todos `@Component` en `src/main`, activados por configuración. Se sigue igual:

```
com/fintech/stp/infrastructure/adapter/out/http/
├── RestClientStpGateway.java     @ConditionalOnProperty(… havingValue = "real",  matchIfMissing = true)
└── stub/
    ├── StubStpGateway.java       @ConditionalOnProperty(… havingValue = "stub")
    ├── StubOrderStore.java       persistencia en stp.stub_orders (sobrevive reinicios)
    ├── StubScenario.java         enum de escenarios deterministas
    └── StubSigner.java           firma de verdad con la llave del stub
```

```yaml
fintech:
  stp:
    gateway:
      mode: ${STP_GATEWAY_MODE:stub}     # stub | real   — 'real' sólo en prod
      stub:
        settle-after: PT30S              # cuánto tarda en aparecer LQ en la conciliación
        latency: PT0.2S                  # latencia simulada por llamada
```

> **Guarda de arranque obligatoria:** si `mode = stub` y el perfil activo es `prod`, el contexto **no arranca**. Un `@PostConstruct` que lanza `IllegalStateException`. Un stub de pagos silenciosamente activo en producción es la peor clase de incidente: todo se ve verde y no sale un peso.

#### Qué hace exactamente

**1. `registerPaymentOrder(...)` — persiste el eco completo**

Guarda **todo** lo que llegó, sin interpretar: `claveRastreo`, `empresa`, `monto`, `cuentaOrdenante`, `cuentaBeneficiario`, `nombreBeneficiario`, `rfcCurpBeneficiario`, `institucionContraparte`, `institucionOperante`, `conceptoPago`, `referenciaNumerica`, `tipoPago`, `tipoCuenta*`, y **la firma recibida**. Devuelve la misma forma que STP:

```json
{ "resultado": { "id": 20250811000123, "descripcionError": null } }
```

**2. `queryReconciliation(empresa, tipoOrden, fecha, page)` — devuelve lo mismo que se envió**

Reconstruye cada `ConciliacionDetalleResponseDTO` con **los valores originales**, y añade sólo los campos que del lado del banco no existían al enviar:

| Campo | De dónde sale |
|---|---|
| `claveRastreo`, `monto`, `cuentaBeneficiario`, `cuentaOrdenante`, `empresa`, `nombreBeneficiario`, `nombreOrdenante`, `rfcCurpBeneficiario`, `rfcCurpOrdenante`, `institucionContraparte`, `institucionOperante`, `conceptoPago`, `referenciaNumerica`, `tipoPago`, `tipoCuenta*`, `fechaOperacion` | **Eco literal de lo enviado** |
| `estado` | Lo dicta el escenario (default `LQ`) |
| `tsCaptura` / `tsLiquidacion` | Epoch ms: recepción y recepción + `settle-after` |
| `urlCEP` | `https://stub.local/cep/{claveRastreo}` |
| **`nombreCep`** | **El mismo `nombreBeneficiario` que se envió** — así `compareNames` da `true` en el camino feliz y el escenario `.07` lo rompe a propósito |
| `rfcCep` | El `rfcCurpBeneficiario` enviado |
| **`sello`** | **Firma real** sobre la cadena de conciliación, con la llave privada del stub |
| `causaDevolucion` | Sólo en escenarios de devolución |
| `idEF`, `medioEntrega`, `prioridad`, `topologia` | Valores fijos plausibles |

Pagina de 1000 y devuelve `total`, igual que STP.

**3. El detalle que hace que el stub valga: firma de verdad**

El stub tiene su **propio par de llaves RSA**. La pública se siembra en `stp.company_keys` con `purpose = 'VERIFICATION'` para la empresa de pruebas. Así, en local:

- La orden saliente se firma con la llave de firma de la empresa → se ejercita `CadenaOriginalBuilder` completo.
- La observación entrante se verifica contra la pública del stub → **se ejercita `SO-04` de verdad**.

Si mañana alguien rompe la verificación de sello, revienta en el `FlowIT` de local, no en producción. Ese es el punto: **el stub ejercita la criptografía, no la esquiva.**

#### Escenarios deterministas — por los centavos del monto

Técnica clásica de sandbox de pagos, y aquí es especialmente útil porque permite escribir pruebas de aceptación sin mocks:

| Centavos | Escenario | Qué ejercita |
|---|---|---|
| `.00` | Registro OK → conciliación `LQ` | Camino feliz completo |
| `.01` | Registro rechazado `id = -1`, **pero el stub sí persiste la orden** y la liquida | `CLAVE_RASTREO_DUPLICADA` → **§13.2 nivel 3: éxito idempotente**. Persistirla es lo que hace el caso realista: la orden existía de un intento anterior, así que aparece en la conciliación y termina en `SETTLED`, no en `SO-06` |
| `.02` | Registro rechazado `id = -200` | `RECHAZO_POR_PLD` — **el bug B1**. Debe terminar en `REJECTED`, nunca en éxito |
| `.03` | Registro rechazado `id = -30` | `ENLACE_FINANCIERO_MODO_CONSULTAS` → **DB-08**: reintento, no rechazo terminal |
| `.04` | Aceptada, luego `estado = D` + `causaDevolucion` | Camino de devolución → `stp.order-returned` |
| `.05` | Aceptada, luego `estado = CL` | Cancelación |
| `.06` | Aceptada y **nunca aparece** en la conciliación | **SO-06**: alerta a las 24 h, y **nunca** `SETTLED` por timeout |
| `.07` | `nombreCep` distinto del enviado | `beneficiaryNameMatches = false` |
| `.08` | Sello alterado | **SO-04**: `SIGNATURE_INVALID`, no se aplica el cambio de estado |
| `.09` | Aparece una `claveRastreo` que nunca enviamos | **SO-03**: `UNMATCHED` + alerta |
| `.10` | Registro con timeout HTTP | Circuit breaker (**EG-04**) y reintento desde el outbox |

Con esa tabla, `StpAcceptanceTest` cubre las once ramas sin un solo mock de Mockito: se levanta el servicio con `mode: stub` y se mandan once órdenes.

#### Qué NO hace el stub

- **No inventa datos.** Si un campo no venía en la orden, no aparece en la conciliación. Si el nombre viene truncado a 40, vuelve truncado a 40 — que es justamente como se detecta el problema **B7**.
- **No acepta órdenes mal firmadas.** Verifica la firma entrante contra la **pública de la llave de firma de la empresa**. Para eso, `EnvelopeEncryptedKeyProvider` deriva el SPKI público al dar de alta una llave `SIGNING` y lo persiste en la columna `public_key_spki` de esa misma fila (§14.2: la columna es nullable y el `CHECK` sólo la exige para `VERIFICATION`, así que no hace falta ni un `purpose` nuevo ni tocar el índice). Sirve además para calcular el `fingerprint_sha256`. Si `CadenaOriginalBuilder` cambia y rompe el contrato, el stub rechaza la orden — igual que haría STP.
- **No corre en producción.** Guarda de arranque.

#### Sobre el Mockoon del legado

El legado trae `ci-cd/config.d/low/shared/stpmex_com.json`, un Mockoon con las respuestas de STP. **No se migra**: es un archivo JSON estático fuera del ciclo de build, sin estado, incapaz de devolver el eco de lo enviado, y contiene un Bearer JWT que hay que rotar (§4.3). Sí conviene **leerlo antes de escribir el stub**, para copiar las formas de respuesta reales que documenta.

Si más adelante hace falta un contrato verificable frente a STP (contract testing), el complemento natural es un **WireMock** con los mismos escenarios en un contenedor aparte — pero eso es para pruebas de contrato, no para el desarrollo diario.

---

## 11. Multi-empresa y custodia de llaves

### 11.1 El modelo de tenant

`companyId` (UUID) es el tenant y viaja **en el evento**, no en un header ni en un `ThreadLocal`.

```
credit-account-activated.disbursement.companyId  ·  disposition-authorized.companyId
   └► disbursement.disbursement_orders.company_id
        └► disbursement.stp-requested.companyId
             └► stp.companies.company_id
                  ├► stp.ordering_accounts   (cuenta ordenante)
                  ├► stp.company_keys        (llave de firma + llave de verificación)
                  └► stp_empresa             (string que va en la cadena original, posición 2)
```

**Resolución en `disbursement-service`**, por orden de precedencia:

1. `companyId` explícito en el evento origen.
2. Mapeo en `disbursement.company_mappings` (por `productType` / `sourceType`).
3. `fintech.disbursement.default-company-id`.
4. Sin resolución → orden `FAILED` con `UNRESOLVED_COMPANY` (**DB-07**). Nunca se adivina.

> Tanto `DisbursementInstruction` (§8.2) como `DispositionAuthorizedPayload` (§8.3) **incluyen `companyId`** como campo nullable — no cuesta nada dejarlo listo. Mientras `credit-portfolio` no lo pueble, se usa la ruta (2)/(3). **Q1** decide cuándo lo puebla.

### 11.2 Custodia de llaves — envelope encryption

**El material cifrado vive en Postgres; la KEK vive fuera.**

```
┌─────────────────────────────────────────────────────────────────────┐
│  stp.company_keys                                                    │
│    key_id, company_id, alias, purpose (SIGNING | VERIFICATION),      │
│    algorithm, key_size,                                              │
│    wrapped_dek        BYTEA   ← DEK cifrada con la KEK   (SIGNING)   │
│    encrypted_material BYTEA   ← PKCS#8 cifrado (AES-GCM)  (SIGNING)  │
│    iv, auth_tag       BYTEA                               (SIGNING)  │
│    public_key_spki    BYTEA   ← SPKI en claro         (VERIFICATION) │
│    kek_id             VARCHAR ← qué KEK la envolvió (rotación)       │
│    fingerprint_sha256 VARCHAR ← huella de la llave PÚBLICA           │
│    valid_from, valid_to, status, created_at, created_by              │
└─────────────────────────────────────────────────────────────────────┘
                        │  la KEK NUNCA está en la BD
                        ▼
      STP_KEK_<kekId>   (variable de entorno / Secret de K8s / KMS)
```

**Propiedades:**

- Un dump de la base **no compromete ninguna llave privada** — sin la KEK, `encrypted_material` es ruido.
- Dos llaves por empresa y propósito pueden convivir (`ACTIVE` / `ROTATING` / `RETIRED`) → rotación sin ventana de indisponibilidad.
- `kek_id` permite **rotar la KEK** re-envolviendo sólo las DEKs, sin descifrar material RSA.
- Igual en Docker Compose, K8s y CI. Cero infraestructura nueva.
- Migrar a Vault/KeyVault/HSM = cambiar **una** implementación de `SigningKeyProvider`.

**El puerto** — simétrico, porque firmar lo que sale y verificar lo que entra son la misma responsabilidad:

```java
public interface SigningKeyProvider {
    /** Llave privada de firma de la empresa. Cacheada; se invalida al rotar. */
    PrivateKey activeSigningKey(UUID companyId);

    /** Llave pública de STP para verificar el `sello` de las observaciones (SO-04). */
    PublicKey activeVerificationKey(UUID companyId);

    /** Metadatos sin material — para la API de administración y el log de auditoría. */
    SigningKeyMetadata activeKeyMetadata(UUID companyId, KeyPurpose purpose);
}
```

**Reglas de operación, no negociables:**

| ID | Regla |
|---|---|
| **KY-01** | El material privado **nunca** sale del servicio: ni por API, ni en logs, ni en trazas, ni en mensajes de excepción |
| **KY-02** | La KEK **nunca** se persiste en Postgres ni se versiona en Git |
| **KY-03** | Toda firma escribe auditoría con `companyId`, `keyId`, `fingerprint` y `paymentRequestId` — **nunca la cadena original completa** (cierra **B9**) |
| **KY-04** | El alta por API recibe el PKCS#8 en Base64, lo cifra **en el mismo request** y jamás lo escribe a disco ni a log |
| **KY-05** | Caché de `PrivateKey` en memoria con TTL e invalidación explícita al rotar. Nunca se serializa |
| **KY-06** | `valid_to` vencido → `SigningKeyNotAvailableException` → la orden va a `FAILED`. **No se firma con una llave expirada** |
| **KY-07** | Alerta cuando falten menos de 30 días para `valid_to` de cualquier llave activa |

### 11.3 Migración de la llave actual

La llave del tenant legado es un **JKS codificado en Base64**, y su password vive junto a ella en el mismo Secret. Procedimiento de una sola vez, ejecutado por un operador, **fuera del repositorio**:

```
1. Descargar el Secret de K8s
2. base64 -d llavePrivada64.jks > llavePrivada.jks
3. keytool -importkeystore -srckeystore llavePrivada.jks -srcstoretype JKS \
           -destkeystore llave.p12 -deststoretype PKCS12 -srcalias '<alias del keystore>'
4. openssl pkcs12 -in llave.p12 -nocerts -nodes | openssl pkcs8 -topk8 -nocrypt -outform DER > llave.pk8
5. POST /api/v1/stp/companies/{id}/keys  { alias, purpose: SIGNING, materialBase64, validFrom, validTo }
6. Verificar: la huella devuelta coincide con `openssl rsa -pubout | sha256sum`
7. Firmar el vector de regresión con la llave nueva y comparar contra una firma conocida
8. Cargar la llave PÚBLICA de STP:  POST /keys { purpose: VERIFICATION, publicKeySpkiBase64 }
9. Destruir los artefactos intermedios (pasos 2-4) del disco del operador
10. ROTAR la llave de firma: la actual lleva tiempo en Git en claro
```

> El paso 10 no es opcional. `private.key` está en el `application.properties` versionado. Cualquiera con acceso histórico al repo tiene la llave privada de producción.

---

---

## 12. El módulo de firma — especificación ejecutable

### 12.1 `CadenaOriginalBuilder` — explícito, no reflexivo

El cambio más importante de todo el proyecto: **la cadena original deja de depender de `getDeclaredFields()`**.

```java
public final class CadenaOriginalBuilder {

    private static final String PREFIX    = "||";
    private static final String SEPARATOR = "|";
    private static final String SUFFIX    = "||";

    // Locale.ROOT es obligatorio: DecimalFormat toma el locale por defecto de la JVM
    // y en un locale con coma decimal la firma cambia y STP rechaza la orden.
    private static final DecimalFormat AMOUNT = amountFormat();

    private static DecimalFormat amountFormat() {
        DecimalFormat df = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        df.setRoundingMode(RoundingMode.HALF_EVEN);   // explícito: es el default de DecimalFormat
        return df;
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    /** Orden posicional CONGELADO. Cambiar este array cambia la firma. */
    private static final List<Function<OrdenPagoFirma, Object>> ORDEN_PAGO_FIELDS = List.of(
            OrdenPagoFirma::institucionContraparte,   //  1
            OrdenPagoFirma::empresa,                  //  2
            OrdenPagoFirma::fechaOperacion,           //  3
            OrdenPagoFirma::folioOrigen,              //  4
            OrdenPagoFirma::claveRastreo,             //  5
            OrdenPagoFirma::institucionOperante,      //  6
            OrdenPagoFirma::monto,                    //  7
            OrdenPagoFirma::tipoPago,                 //  8
            OrdenPagoFirma::tipoCuentaOrdenante,      //  9
            OrdenPagoFirma::nombreOrdenante,          // 10
            OrdenPagoFirma::cuentaOrdenante,          // 11
            OrdenPagoFirma::rfcCurpOrdenante,         // 12
            OrdenPagoFirma::tipoCuentaBeneficiario,   // 13
            OrdenPagoFirma::nombreBeneficiario,       // 14
            OrdenPagoFirma::cuentaBeneficiario,       // 15
            OrdenPagoFirma::rfcCurpBeneficiario,      // 16
            OrdenPagoFirma::emailBeneficiario,        // 17
            OrdenPagoFirma::tipoCuentaBeneficiario2,  // 18
            OrdenPagoFirma::nombreBeneficiario2,      // 19
            OrdenPagoFirma::cuentaBeneficiario2,      // 20
            OrdenPagoFirma::rfcCurpBeneficiario2,     // 21
            OrdenPagoFirma::conceptoPago,             // 22
            OrdenPagoFirma::conceptoPago2,            // 23
            OrdenPagoFirma::cveCatalogoUsuario,       // 24
            OrdenPagoFirma::cveCatalogoUsuario2,      // 25
            OrdenPagoFirma::cvePago,                  // 26
            OrdenPagoFirma::referenciaCobranza,       // 27
            OrdenPagoFirma::referenciaNumerica,       // 28
            OrdenPagoFirma::tipoOperacion,            // 29
            OrdenPagoFirma::topologia,                // 30
            OrdenPagoFirma::usuario,                  // 31
            OrdenPagoFirma::medioEntrega,             // 32
            OrdenPagoFirma::prioridad,                // 33
            OrdenPagoFirma::iva);                     // 34

    public static String build(OrdenPagoFirma f) { return join(ORDEN_PAGO_FIELDS, f); }

    private static <T> String join(List<Function<T, Object>> extractors, T source) {
        return extractors.stream()
                .map(e -> render(e.apply(source)))
                .collect(Collectors.joining(SEPARATOR, PREFIX, SUFFIX));
    }

    /** Reglas de renderizado — replican StyleString.appendDetail del legado, byte a byte. */
    private static String render(Object value) {
        if (value == null)                  return "";
        if (value instanceof LocalDate d)   return d.format(DATE);
        if (value instanceof BigDecimal b)  return AMOUNT.format(b);
        return value.toString();
    }
}
```

### 12.2 Tests obligatorios antes de escribir la implementación

| ID | Test | Qué fija |
|---|---|---|
| **SG-T01** | Vector de oro `OrdenPagoFirma` (§3.4) — comparación de string exacta | El contrato completo de 34 campos |
| **SG-T02** | Vector de oro `SaldoCuentaFirma`: `"\|\|empresa\|cuenta_prueba\|\|\|"` | Nulos → cadena vacía |
| **SG-T03** | `ConciliacionFirma` → `"\|\|EMPRESA\|E\|20250611\|\|"` | Tercer bean |
| **SG-T04** | El mismo vector con `Locale.setDefault(Locale.GERMANY)` produce **idéntico** resultado | Blinda contra el bug de locale |
| **SG-T05** | `monto = 100` → `"100.00"`; `= 100.5` → `"100.50"`; `= 0.162` → `"0.16"`; `= 0.165` → `"0.16"` (HALF_EVEN) | Formato y redondeo |
| **SG-T06** | `institucionContraparte = null` → dos pipes consecutivos en la posición 1 | Nulo en el primer campo |
| **SG-T07** | Firmar la cadena de oro con una llave de test fija produce una firma Base64 conocida y estable | El algoritmo completo end-to-end |
| **SG-T08** | La firma verifica contra la llave pública correspondiente (`Signature.verify`) | Correctitud criptográfica |
| **SG-T09** | Dos empresas distintas producen firmas distintas para la misma cadena | Aislamiento multi-tenant |
| **SG-T10** | Llave con `valid_to` en el pasado → `SigningKeyNotAvailableException`, no firma | KY-06 |
| **SG-T11** | El log de la firma **no** contiene la cadena original ni el material de la llave | KY-03 |
| **SG-T12** | Property test: 1000 `OrdenPagoFirma` aleatorios → la implementación nueva coincide con un oráculo que reproduce `ReflectionToStringBuilder` | Equivalencia con el legado |
| **SG-T13** | `ConciliacionFirma` con `tipoOrden="E"` produce la cadena que acepta `V2/conciliacion` | **Sin esto no funciona el poller (§10.4)** — la conciliación es ahora camino crítico, no un batch nocturno |
| **SG-T14** | Verificar un `sello` conocido de STP con la llave pública cargada → `true`; alterar un byte del payload → `false` | **SO-04** |

> **SG-T12 es el que da confianza real para el corte.** Se implementa como test temporal con `commons-lang3` en `testImplementation` y se borra tras la Fase 3, cuando el legado ya no exista.

### 12.3 Reconstrucción del catálogo de errores

`BanxicoResponseCode` reemplaza a `ResponseErrorBanxico` con dos mejoras:

```java
public enum BanxicoResponseCode {
    OTROS(0, "Otros", Terminality.TERMINAL),
    DATOS_OBLIGATORIOS(1, "Dato obligatorio", Terminality.TERMINAL),
    // … los 32 códigos del legado …
    ENLACE_FINANCIERO_MODO_CONSULTAS(-30, "Enlace Financiero en modo consultas", Terminality.RETRYABLE),
    RECHAZO_POR_PLD(-200, "Se rechaza por PLD", Terminality.TERMINAL);   // ← ya no se pierde (B1)

    /** Código no catalogado → falla CERRADO: terminal y alerta. Nunca se trata como éxito. */
    public static BanxicoResponseCode of(int code) { … }
}
```

Los códigos ausentes del catálogo STP (`-4`, `-8`, `-15`, `-19`, `-24`, `-25`, `-27..-29`, `-31..-33`) se mapean a `UNKNOWN(TERMINAL)` **con métrica y alerta**, no a una excepción genérica.

---

## 13. Idempotencia, outbox, reintentos y DLT

Este es el capítulo donde el diseño nuevo se separa más del legado, y donde se justifica la mayor parte del esfuerzo.

### 13.1 El problema del legado

- Idempotencia: stub (`return false`) — **B4**
- Reintentos: `Thread.sleep(5 min)` bloqueando el consumidor — **B5**
- Publicación: `save()` + HTTP en la misma transacción, y si el encolado falla sólo se loguea — **B11**
- El retardo programado de 2 minutos en cada mensaje existe **precisamente** para tapar la carrera entre el commit y el consumo

### 13.2 Idempotencia — cuatro niveles

| Nivel | Mecanismo | Dónde |
|---|---|---|
| **Consumo de eventos** | Guard `existsBy<clave>` al inicio del handler + `log.info("Duplicate … — skipping (idempotent)")` + `return` | Ambos servicios. **Es el patrón que ya usa el repo** (`payments` por `externalRef`, `credit-portfolio` por `sourceEventId`) |
| **Creación de agregado** | Constraint `UNIQUE` en BD sobre la clave natural (`(source_system, source_type, source_event_id)` en disbursement, `payment_request_id` en stp) | La BD es el árbitro final ante carreras entre réplicas |
| **Llamada al proveedor** | `claveRastreo` es única por `(empresa, fecha, secuencia)`. STP responde `-1 CLAVE_RASTREO_DUPLICADA`, que se trata como **éxito idempotente**, no como error | `PaymentOrderRegistrationService` |
| **Observación de liquidación** | `UNIQUE (company_id, tracking_key, observed_status, observed_at_source)` — el poller relee lo mismo cada 3 min por diseño; la segunda vez no reaplica nada (**SO-02**) | `SettlementPollingService` |

> El tercer nivel es sutil y muy importante: si STP devuelve `CLAVE_RASTREO_DUPLICADA` significa que la orden **ya se registró** en un intento anterior que no alcanzamos a persistir. Tratarlo como error genera una segunda orden con clave nueva → **doble dispersión**. Se trata consultando el estado de la orden original.

### 13.3 Outbox transaccional

`stp-service` **no llama a STP dentro de la transacción que persiste la orden**. El patrón:

```
T1 (transacción de BD)
  ├─ INSERT stp.payment_orders (status = PENDING)
  ├─ INSERT stp.payment_order_events (PENDING)
  └─ INSERT stp.outbox_messages (type = REGISTER_ORDER, payload, next_attempt_at = now)
  COMMIT

Relay (job en infrastructure/job/, ver §13.5 — sin ShedLock)
  ├─ SELECT ... FROM outbox_messages WHERE next_attempt_at <= now FOR UPDATE SKIP LOCKED LIMIT n
  ├─ PUT ordenPago/registra
  └─ T2: actualiza la orden + escribe el evento de Kafka + marca outbox como enviado
```

Beneficios directos: elimina la necesidad del retardo artificial de 2 minutos, hace el reintento seguro, y ninguna transacción de BD queda abierta durante un round-trip HTTP.

> **Nota:** el repo tiene `spring-modulith-starter-jpa` y la tabla `event_publication` en **todos** los servicios, pero **nadie la usa** (cero `@ApplicationModuleListener`, cero `@Externalized`). Aquí hay dos caminos: (a) usar `event_publication` de Modulith como outbox real — sería el primer servicio del repo en hacerlo; (b) tabla `outbox_messages` propia. **Recomiendo (b)** para Fase 1, porque el relay necesita control de `next_attempt_at` y backoff que Modulith no da de fábrica, y porque no quiero que el primer uso de Modulith-events del monorepo sea en el camino del dinero. Documentar (a) como evolución.

### 13.4 Reintentos y DLT

El repo **no tiene ningún `@RetryableTopic`, `DefaultErrorHandler` ni DLT** hoy. Estos dos servicios serán los primeros — y es correcto que lo sean, porque son los que mueven dinero.

```java
@Bean
DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template,
            (record, ex) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
    var backoff = new ExponentialBackOffWithMaxRetries(4);   // 1s → 2s → 4s → 8s
    backoff.setInitialInterval(1000L);
    backoff.setMultiplier(2.0);
    var handler = new DefaultErrorHandler(recoverer, backoff);
    // No reintentar lo que nunca va a funcionar
    handler.addNotRetryableExceptions(
            CompanyNotFoundException.class,
            OrderingAccountNotFoundException.class,
            InvalidBeneficiaryAccountException.class,
            SigningKeyNotAvailableException.class,
            UnresolvedCompanyException.class);
    return handler;
}
```

- Backoff **no bloqueante** (cierra **B5**).
- Excepciones de negocio deterministas van directo a DLT, sin gastar reintentos.
- Topics DLT: `<topic>.dlt`, con alerta de Prometheus sobre `kafka_consumer_records_consumed_total` del grupo DLT.
- **Runbook obligatorio** para el DLT de `disbursement.stp-requested`, `credit-portfolio.credit-account-activated` y `credit-portfolio.disposition-authorized`: cada mensaje ahí es un desembolso que no salió.

### 13.5 Ventana operativa

El legado la implementa apagando listeners con `@Scheduled` en 4 réplicas simultáneas (**B12**). El diseño nuevo la mueve al dominio:

- `fintech.disbursement.operating-window` = `{ start: "17:10", end: "16:50", zone: "America/Mazatlan", days: MON-FRI }`
  — **es una ventana envolvente**: `start > end` significa que abre a las 17:10 de un día y cierra a las 16:50 del siguiente. La ventana *cerrada* es la franja corta 16:50–17:10, que es lo que el legado apagaba con sus dos crons
- Fuera de ventana → la orden queda `REQUESTED` con `scheduled_for` (**DB-05**)
- Un job en `infrastructure/job/DisbursementDispatchJob` (la convención del repo — hay 11 jobs así en `credit-portfolio`, `charges`, `collections`, `commission`, `risk`, `accounting`, `origination`) despacha las órdenes pendientes al abrir la ventana
- Los días festivos vienen de `configuration-service` (T5) o de la tabla local `stp.business_holidays` en Fase 4

Ventaja: reiniciar un pod no cambia el comportamiento, porque el estado está en la BD, no en la memoria de una réplica.

> **Verificado en el repo: ShedLock NO existe** — los 11 `@Scheduled` actuales corren sin coordinación entre réplicas. Como aquí el estado vive en la BD (no en memoria como en el legado), el riesgo se reduce a doble despacho, no a "la ventana quedó abierta en un pod". Dos opciones:
> **(a)** seguir la convención del repo (`@Scheduled` pelado) y blindar con `SELECT … FOR UPDATE SKIP LOCKED` sobre las órdenes pendientes — **suficiente y sin dependencia nueva**;
> **(b)** introducir ShedLock como primera dependencia de este tipo en el monorepo.
> **Recomiendo (a)** para Fase 2: el `SKIP LOCKED` que ya necesita el relay del outbox resuelve el mismo problema, y no rompe el estilo del repo. Si el equipo quiere ShedLock, es un cambio transversal que merece su propio ADR.

---

---

## 14. Esquemas de base de datos

Convenciones del repo (estilo `payments`): schema = nombre del módulo, tablas `snake_case` plural, PK `UUID`, `NUMERIC(19,2)` para dinero, `TIMESTAMPTZ` + `Instant`, sin FK cross-schema, `<tabla>_pk` / `<tabla>_<campo>_uq` / `<tabla>_<campo>_chk` / `<tabla>_<cols>_idx`, `--liquibase formatted sql`, XSD `dbchangelog-4.27.xsd`, y **`<schema>.event_publication` obligatoria** (si falta, `ddl-auto: validate` no arranca).

### 14.1 Schema `disbursement`

Nótese que **no hay ni una columna con nombre de dominio de crédito**. Esa es la comercializabilidad hecha DDL.

```sql
--changeset disbursement-service:002-create-disbursement-orders
CREATE TABLE disbursement.disbursement_orders (
    disbursement_id       UUID          NOT NULL,
    company_id            UUID          NOT NULL,

    -- Procedencia: opaca para el núcleo (DB-09)
    source_system         VARCHAR(40)   NOT NULL,   -- 'credit-portfolio' | 'wallet' | 'api'
    source_type           VARCHAR(30)   NOT NULL
        CONSTRAINT disbursement_orders_source_type_chk
            CHECK (source_type IN ('DISPOSITION','WITHDRAWAL','SURPLUS_RETURN',
                                   'ACCOUNT_VERIFICATION','API','MANUAL')),
    source_reference      VARCHAR(120),              -- p.ej. el creditAccountId, como string
    source_event_id       VARCHAR(120)  NOT NULL,    -- clave de idempotencia
    source_metadata       JSONB         NOT NULL DEFAULT '{}'::jsonb,  -- eco literal

    -- Beneficiario
    beneficiary_name      VARCHAR(150)  NOT NULL,
    beneficiary_account   VARCHAR(20)   NOT NULL,
    beneficiary_account_type VARCHAR(4) NOT NULL,
    beneficiary_tax_id    VARCHAR(18),
    beneficiary_institution INTEGER,

    -- Instrucción
    amount                NUMERIC(19,2) NOT NULL
        CONSTRAINT disbursement_orders_amount_pos_chk CHECK (amount > 0),
    currency              CHAR(3)       NOT NULL DEFAULT 'MXN',
    concept               VARCHAR(40),
    numeric_reference     BIGINT,

    -- Ejecución
    rail                  VARCHAR(20)   NOT NULL,
    provider              VARCHAR(20),
    status                VARCHAR(20)   NOT NULL
        CONSTRAINT disbursement_orders_status_chk
            CHECK (status IN ('REQUESTED','DISPATCHED','ACCEPTED','SETTLED',
                              'REJECTED','RETURNED','FAILED','CANCELLED')),
    external_ref          VARCHAR(60),
    cep_url               VARCHAR(500),
    failure_code          VARCHAR(60),
    failure_reason        VARCHAR(500),
    attempt_count         INTEGER       NOT NULL DEFAULT 0,
    scheduled_for         TIMESTAMPTZ,
    correlation_id        VARCHAR(64),

    created_at            TIMESTAMPTZ   NOT NULL,
    dispatched_at         TIMESTAMPTZ,
    settled_at            TIMESTAMPTZ,
    terminated_at         TIMESTAMPTZ,

    CONSTRAINT disbursement_orders_pk PRIMARY KEY (disbursement_id),
    CONSTRAINT disbursement_orders_source_uq                     -- DB-02
        UNIQUE (source_system, source_type, source_event_id)
);

CREATE INDEX disbursement_orders_ref_idx
    ON disbursement.disbursement_orders (source_reference, created_at DESC);
CREATE INDEX disbursement_orders_pending_idx
    ON disbursement.disbursement_orders (status, scheduled_for)
    WHERE status IN ('REQUESTED','DISPATCHED');
CREATE INDEX disbursement_orders_company_idx
    ON disbursement.disbursement_orders (company_id, created_at DESC);
```

```sql
--changeset disbursement-service:003-create-disbursement-events   (append-only, DB-06)
CREATE TABLE disbursement.disbursement_events (
    event_id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    disbursement_id UUID         NOT NULL,
    from_status     VARCHAR(20),
    to_status       VARCHAR(20)  NOT NULL,
    reason_code     VARCHAR(60),
    detail          VARCHAR(1000),
    actor           VARCHAR(60)  NOT NULL,     -- SYSTEM | <userId> | PROVIDER
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT disbursement_events_pk PRIMARY KEY (event_id)
);
CREATE INDEX disbursement_events_order_idx
    ON disbursement.disbursement_events (disbursement_id, occurred_at);

--changeset disbursement-service:004-create-routing-rules
CREATE TABLE disbursement.routing_rules (
    rule_id      UUID          NOT NULL DEFAULT gen_random_uuid(),
    company_id   UUID          NOT NULL,
    rail         VARCHAR(20)   NOT NULL,
    provider     VARCHAR(20)   NOT NULL,
    target_topic VARCHAR(120)  NOT NULL,
    min_amount   NUMERIC(19,2) NOT NULL DEFAULT 0,
    max_amount   NUMERIC(19,2),
    priority     INTEGER       NOT NULL DEFAULT 100,
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT routing_rules_pk PRIMARY KEY (rule_id)
);
CREATE INDEX routing_rules_lookup_idx
    ON disbursement.routing_rules (company_id, rail, priority) WHERE active;

--changeset disbursement-service:005-create-company-mappings
CREATE TABLE disbursement.company_mappings (
    mapping_id   UUID        NOT NULL DEFAULT gen_random_uuid(),
    match_key    VARCHAR(40) NOT NULL,   -- 'productType' | 'sourceType' | 'sourceSystem'
    match_value  VARCHAR(80) NOT NULL,
    company_id   UUID        NOT NULL,
    priority     INTEGER     NOT NULL DEFAULT 100,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT company_mappings_pk PRIMARY KEY (mapping_id)
);

--changeset disbursement-service:006-create-event-publication
CREATE TABLE IF NOT EXISTS disbursement.event_publication ( … );   -- obligatoria
```

### 14.2 Schema `stp`

```sql
--changeset stp-service:002-create-companies
CREATE TABLE stp.companies (
    company_id           UUID         NOT NULL,
    code                 VARCHAR(40)  NOT NULL,
    stp_empresa          VARCHAR(40)  NOT NULL,   -- string EXACTO de la cadena original
    institucion_operante INTEGER      NOT NULL,
    tracking_prefix      VARCHAR(4)   NOT NULL,   -- por empresa, ya no constante
    clabe_bank_code      CHAR(3)      NOT NULL DEFAULT '646',
    clabe_plaza_code     CHAR(3)      NOT NULL DEFAULT '180',
    clabe_client_prefix  VARCHAR(6),
    status               VARCHAR(20)  NOT NULL
        CONSTRAINT companies_status_chk CHECK (status IN ('ACTIVE','SUSPENDED','RETIRED')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT companies_pk PRIMARY KEY (company_id),
    CONSTRAINT companies_code_uq UNIQUE (code),
    CONSTRAINT companies_empresa_uq UNIQUE (stp_empresa)
);

--changeset stp-service:003-create-company-keys
CREATE TABLE stp.company_keys (
    key_id             UUID         NOT NULL,
    company_id         UUID         NOT NULL,
    alias              VARCHAR(80)  NOT NULL,
    purpose            VARCHAR(20)  NOT NULL DEFAULT 'SIGNING'
        CONSTRAINT company_keys_purpose_chk CHECK (purpose IN ('SIGNING','VERIFICATION')),
    algorithm          VARCHAR(30)  NOT NULL DEFAULT 'SHA256withRSA',
    key_size           INTEGER      NOT NULL DEFAULT 2048,
    -- Sólo SIGNING: material privado envuelto
    wrapped_dek        BYTEA,
    encrypted_material BYTEA,
    iv                 BYTEA,
    auth_tag           BYTEA,
    kek_id             VARCHAR(60),
    -- VERIFICATION: la pública de STP. SIGNING: la pública derivada de nuestra propia
    -- llave, para el fingerprint y para que el stub de §10.6 pueda verificar lo que firmamos.
    public_key_spki    BYTEA,
    fingerprint_sha256 VARCHAR(64)  NOT NULL,
    valid_from         TIMESTAMPTZ  NOT NULL,
    valid_to           TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(20)  NOT NULL
        CONSTRAINT company_keys_status_chk CHECK (status IN ('ACTIVE','ROTATING','RETIRED','REVOKED')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(60)  NOT NULL,
    CONSTRAINT company_keys_pk PRIMARY KEY (key_id),
    CONSTRAINT company_keys_material_chk CHECK (
        (purpose = 'SIGNING'      AND encrypted_material IS NOT NULL AND wrapped_dek IS NOT NULL
                                  AND iv IS NOT NULL AND auth_tag IS NOT NULL AND kek_id IS NOT NULL)
     OR (purpose = 'VERIFICATION' AND public_key_spki IS NOT NULL))
);
-- Una sola llave ACTIVE por empresa y propósito
CREATE UNIQUE INDEX company_keys_active_uq
    ON stp.company_keys (company_id, purpose) WHERE status = 'ACTIVE';

--changeset stp-service:004-create-ordering-accounts
CREATE TABLE stp.ordering_accounts (
    ordering_account_id UUID         NOT NULL,
    company_id          UUID         NOT NULL,
    clabe               VARCHAR(18)  NOT NULL,
    holder_name         VARCHAR(150) NOT NULL,
    tax_id              VARCHAR(18),
    account_type        VARCHAR(4)   NOT NULL DEFAULT '40',
    currency            CHAR(3)      NOT NULL DEFAULT 'MXN',
    stp_client_number   VARCHAR(20),
    is_default          BOOLEAN      NOT NULL DEFAULT FALSE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ordering_accounts_pk PRIMARY KEY (ordering_account_id),
    CONSTRAINT ordering_accounts_clabe_uq UNIQUE (clabe)
);
CREATE UNIQUE INDEX ordering_accounts_default_uq
    ON stp.ordering_accounts (company_id) WHERE is_default AND active;

--changeset stp-service:005-create-payment-orders
CREATE TABLE stp.payment_orders (
    stp_payment_order_id UUID          NOT NULL,
    payment_request_id   UUID          NOT NULL,   -- clave de idempotencia de entrada
    company_id           UUID          NOT NULL,
    ordering_account_id  UUID          NOT NULL,
    tracking_key         VARCHAR(30)   NOT NULL,   -- claveRastreo
    business_date        DATE          NOT NULL,   -- día Banxico
    amount               NUMERIC(19,2) NOT NULL,
    beneficiary_name      VARCHAR(150) NOT NULL,   -- completo, auditoría
    beneficiary_name_sent VARCHAR(40)  NOT NULL,   -- truncado, lo que se firmó (cierra B7)
    beneficiary_account   VARCHAR(20)  NOT NULL,
    beneficiary_account_hash VARCHAR(64) NOT NULL, -- HMAC-SHA256 con salt (cierra B17)
    beneficiary_account_type VARCHAR(4) NOT NULL,
    beneficiary_tax_id    VARCHAR(18),
    beneficiary_institution INTEGER    NOT NULL,
    concept              VARCHAR(40),
    numeric_reference    BIGINT,
    payment_type         VARCHAR(4),
    signature            TEXT,                      -- el sello enviado
    signing_key_id       UUID,                      -- con qué llave se firmó
    status               VARCHAR(20)   NOT NULL
        CONSTRAINT payment_orders_status_chk
            CHECK (status IN ('PENDING','SENT','ACCEPTED','SETTLED',
                              'REJECTED','RETURNED','CANCELLED','FAILED')),
    stp_order_id         VARCHAR(40),
    banxico_code         INTEGER,
    banxico_reason       VARCHAR(120),
    error_detail         VARCHAR(1000),
    attempt_count        INTEGER       NOT NULL DEFAULT 0,
    last_polled_at       TIMESTAMPTZ,               -- para el poller (§10.4)
    correlation_id       VARCHAR(64),
    created_at           TIMESTAMPTZ   NOT NULL,
    sent_at              TIMESTAMPTZ,
    settled_at           TIMESTAMPTZ,
    CONSTRAINT payment_orders_pk PRIMARY KEY (stp_payment_order_id),
    CONSTRAINT payment_orders_request_uq  UNIQUE (payment_request_id),
    CONSTRAINT payment_orders_tracking_uq UNIQUE (company_id, tracking_key)
);
-- El poller busca exactamente esto
CREATE INDEX payment_orders_inflight_idx
    ON stp.payment_orders (company_id, business_date)
    WHERE status IN ('SENT','ACCEPTED');

--changeset stp-service:006-create-settlement-observations
-- Lo que STP nos dijo cuando le preguntamos. Evidencia cruda, append-only (SO-01).
CREATE TABLE stp.settlement_observations (
    observation_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id         UUID         NOT NULL,
    tracking_key       VARCHAR(30)  NOT NULL,
    observed_status    VARCHAR(10)  NOT NULL,   -- LQ, TLQ, CCO, D, TD, RE, CL, TCL…
    -- NOT NULL con default vacío a propósito: en Postgres los NULL no colisionan en un
    -- UNIQUE, y una orden devuelta puede llegar sin tsLiquidacion. Con NULL, SO-02 no dedupe.
    observed_at_source VARCHAR(30)  NOT NULL DEFAULT '',   -- tsLiquidacion / tsCaptura de STP
    return_cause_code  VARCHAR(10),
    cep_url            VARCHAR(500),
    cep_beneficiary_name VARCHAR(150),
    provider_signature TEXT,                    -- el `sello` que devuelve STP
    signature_ok       BOOLEAN,                 -- resultado de SO-04
    raw_payload        JSONB        NOT NULL,
    observed_via       VARCHAR(30)  NOT NULL
        CONSTRAINT settlement_observations_via_chk
            CHECK (observed_via IN ('POLL_RECONCILIATION','POLL_ORDER','EOD_BATCH','MANUAL')),
    status             VARCHAR(30)  NOT NULL
        CONSTRAINT settlement_observations_status_chk
            CHECK (status IN ('APPLIED','DUPLICATE','UNMATCHED','SIGNATURE_INVALID','FAILED')),
    detail             VARCHAR(1000),
    observed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT settlement_observations_pk PRIMARY KEY (observation_id),
    CONSTRAINT settlement_observations_uq                        -- SO-02
        UNIQUE (company_id, tracking_key, observed_status, observed_at_source)
);
CREATE INDEX settlement_observations_tracking_idx
    ON stp.settlement_observations (company_id, tracking_key, observed_at DESC);

--changeset stp-service:007-create-payment-order-events     (bitácora append-only)
--changeset stp-service:008-create-cep-receipts
--changeset stp-service:009-create-tracking-key-sequences
CREATE TABLE stp.tracking_key_sequences (
    company_id    UUID   NOT NULL,
    business_date DATE   NOT NULL,
    last_value    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT tracking_key_sequences_pk PRIMARY KEY (company_id, business_date)
);
--changeset stp-service:010-create-outbox-messages
--changeset stp-service:011-create-poll-runs        -- traza de cada corrida del poller
--changeset stp-service:012-create-stub-orders      -- sólo la usa el stub de §10.6; vacía en prod
--changeset stp-service:013-create-event-publication
```

**Nota sobre la secuencia:** se usa `UPDATE … SET last_value = last_value + 1 RETURNING last_value`, no una `SEQUENCE` de Postgres, porque hace falta reiniciar por `(empresa, día)` y el valor tiene que ser transaccional con la orden.

---

## 15. Seguridad — dos servicios internos, cero superficie externa

> **Esta sección se reescribió dos veces.** La primera versión diseñaba un ingreso de webhooks vía gateway + BFF de socios + client-credentials de identity. **Ese diseño queda descartado**: `disbursement-service` y `stp-service` son servicios internos y la plataforma no expone nada a STP. Lo que sigue es más simple y más seguro. Los hallazgos sobre `identity-service` y `gateway-service` que salieron de aquella auditoría **siguen siendo reales** y se reportan en el Apéndice D — pero ya no son trabajo de este proyecto.

### 15.1 El modelo, en una frase

**Ninguno de los dos servicios acepta tráfico de internet. La única dirección hacia afuera es egress TLS iniciado por `stp-service` contra los endpoints de STP.**

```
                         ┌───────────────── internet ─────────────────┐
                         │                                             │
      ✗ NADA ENTRA ──────┤                                             │
                         │                          ▲                  │
                         └──────────────────────────┼──────────────────┘
                                                    │ EGRESS TLS, saliente
                                                    │ (nosotros abrimos la conexión)
┌───────────────────────────────────────────────────┼──────────────────────────┐
│  red interna                                      │                          │
│                                                   │                          │
│  gateway :80 ──► channel-mobile / channel-backoffice ──┐                     │
│                                                        │  header-trust        │
│                                     ┌──────────────────┴────────┐            │
│                                     ▼                           ▼            │
│                          disbursement-service :8100   stp-service :8101 ─────┘
│                                     ▲                           ▲            │
│                                     └────────── Kafka ──────────┘            │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 15.2 Autenticación — nada nuevo que construir

| Entrada | Mecanismo | ¿Hay que construir algo? |
|---|---|---|
| Eventos de Kafka | Red interna. Sin autenticación por mensaje, igual que los 20 servicios actuales | No |
| REST de operación / administración | **Header-trust**: el gateway valida el JWT RS256, inyecta `X-User-Id`/`X-Roles`/`X-Channel`; el BFF de backoffice reenvía; el servicio confía. `JwtAuthenticationFilter` **copiado byte a byte de payments**, cambiando sólo el `package` | No |
| API de ingesta de producto (`POST /api/v1/disbursements`) | Mismo header-trust. Si un día se comercializa, el comprador pone su propio borde delante | No |

**No hace falta:** ni mTLS, ni registrar a STP como cliente en `identity`, ni un server block nuevo en el gateway, ni un BFF de socios, ni zonas de rate limit nuevas, ni un catálogo de roles de máquina. Todo eso era el coste de tener una puerta abierta; sin puerta, desaparece.

`SecurityConfig` de ambos servicios es el patrón estándar del repo:

```java
.requestMatchers("/actuator/health", "/actuator/info", "/actuator/metrics", "/actuator/prometheus").permitAll()
.requestMatchers("/error").permitAll()
.requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                 "/swagger-ui.html", "/swagger-ui/**").permitAll()
.requestMatchers("/api/v1/stp/companies/**").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/api/v1/stp/poll").hasAnyRole("ADMIN", "OPS_SUPERVISOR")
.requestMatchers(HttpMethod.GET, "/api/v1/stp/**").hasAnyRole("ADMIN", "OPS_SUPERVISOR", "AUDITOR")
.anyRequest().authenticated()
```

### 15.3 Egress — la única superficie que sí hay que gobernar

`stp-service` es el **único** servicio del monorepo que abre conexiones a un host de internet (`stpmex.com`). Eso merece controles propios:

| ID | Control |
|---|---|
| **EG-01** | Egress permitido **sólo** desde `stp-service`, **sólo** a los hosts de STP, por `NetworkPolicy` de K8s o egress gateway. Ningún otro servicio necesita salir a internet |
| **EG-02** | Se valida la cadena de certificados de STP y se **pinea la CA** (o la huella del certificado de hoja, con procedimiento de rotación). El TLS saliente es lo que autentica al servidor: si el pinning falla, no se habla |
| **EG-03** | Timeouts por request (connect 5 s, read 30 s), **nunca** `Unirest.setTimeouts` global como el legado |
| **EG-04** | Circuit breaker por host. Con el breaker abierto, las órdenes esperan en el outbox — no se pierden ni se marcan fallidas |
| **EG-05** | mTLS hacia STP **si STP lo soporta**: el certificado de cliente por empresa se guarda con el mismo mecanismo de envelope encryption que las llaves de firma. Es defensa en profundidad sobre la firma del body, no la sustituye |
| **EG-06** | Ninguna URL de STP se construye por concatenación de IDs (el legado lo hacía con los webhooks al core legado). Se usan plantillas de URI con variables |

> **EG-02 merece énfasis:** al invertir la dirección del flujo, la autenticación del canal deja de ser un problema. Con webhooks había que probar que quien nos llamaba era STP — difícil. Con polling abrimos nosotros la conexión hacia un host conocido con un certificado que validamos. **La misma garantía, gratis.**

### 15.4 Integridad del dato — lo que sí sigue siendo obligatorio

Que no haya webhooks no elimina la verificación del `sello`. Cambia su naturaleza:

| Antes (webhooks) | Ahora (polling) |
|---|---|
| El sello era la **única** prueba de que el mensaje venía de STP | El canal ya está autenticado por TLS (**EG-02**) |
| Sin sello válido → posible inyección de liquidaciones falsas | Sin sello válido → un dato corrupto o una respuesta inesperada |
| Severidad: **crítica** | Severidad: **alta** — sigue siendo no repudio ante Banxico |

**Se implementa igual** (**SO-04**): la respuesta de `V2/conciliacion` trae un `sello` por transacción (`ConciliacionDetalleResponseDTO.sello`; `des_sello` es la columna de la tabla del legado, no el campo de la API); se verifica contra la llave pública de STP de la empresa (`purpose = 'VERIFICATION'`, §14.2). Inválido → `SIGNATURE_INVALID`, no se aplica, se alerta.

### 15.5 El delta completo respecto al legado

| Superficie | Legado | Objetivo |
|---|---|---|
| Endpoints que STP invoca | **3, abiertos, sin auth** | **Ninguno.** No existe la superficie |
| Cómo nos enteramos de la liquidación | STP nos avisa | **Consultamos** con `V2/conciliacion` (§10.4) |
| Autenticación del canal entrante | Ninguna | No aplica — no hay canal entrante |
| Autenticación del canal saliente | TLS sin pinning | TLS con validación de cadena y pinning (**EG-02**) |
| API interna | Ninguna auth | Header-trust vía gateway, igual que los 20 servicios |
| Integridad del dato de STP | `sello` persistido **sin verificar** | Verificación RSA contra la llave pública de STP (**SO-04**) |
| Idempotencia de entrada | Stub (`return false`) | `UNIQUE` en `settlement_observations` (**SO-02**) |
| Salida al core legado | Token estático de 20 chars en el `.java` | **No existe** — se eliminó el camino |
| Llaves | Filesystem + `application.properties` en Git | Envelope encryption, KEK fuera de la BD |
| PII en logs | `EvidenceService` escribe requests completos a disco | Eliminado. Log estructurado con CLABE enmascarada (últimos 4) |
| Hash de cuenta | SHA-256 sin salt | **HMAC-SHA256** con salt por servicio |
| Egress | Sin política | `NetworkPolicy` restrictiva, sólo `stp-service` (**EG-01**) |

---
---

# Parte III — Plan de ejecución

## 16. Plan por fases

Ocho bloques de fase: **0, 1, 2, 2-B, 3, 4, 5 y 6**.

Las fases 0, 1, 2, 5 y 6 son **aditivas o de documentación**. Sólo **dos** cambian el comportamiento de servicios ya desplegados, y llevan salvaguardas propias:

| Fase | Qué cambia en lo existente | Salvaguarda |
|---|---|---|
| **2-B** | `credit-portfolio` deja de llamar a `speiDispatch`; `credit-account-activated` gana un campo; `disposition-completed` pasa a publicarse más tarde y con datos reales | El campo nuevo es retrocompatible (§8.2, verificado en los 9 consumidores); test de contrato por consumidor; la disposición queda `PROCESSING` en vez de mentir con `COMPLETED` |
| **3** | Corta el tráfico de dispersión del legado a los servicios nuevos | Modo sombra + corte por porcentaje + rollback por flag |

---

### FASE 0 — Fundamentos y documentación (1 sprint)

**Objetivo:** cerrar la sangría de secretos, congelar la especificación del sello y dejar la documentación de dominio escrita **antes** de escribir código.

| # | Entregable |
|---|---|
| 0.1 | **Rotar todos los secretos expuestos** del legado: llave privada RSA, password de BD, connection string de Service Bus, y el token estático del `UserTokenProvider`. Purgar del histórico de Git o declarar el repo comprometido |
| 0.2 | `docs/dominios/T8_stp_signature_spec.md` — la especificación del sello (§3 y §12) con los vectores de oro, versionada y revisada por alguien que conozca el contrato con STP |
| 0.3 | **ADR en el `README.md` raíz**: *"Decisión 4 — Desembolso: dominio y conector de proveedor separados"*, en el formato de las Decisiones 1–3 (contexto · decisión · alternativas descartadas · qué cambió). Debe incluir explícitamente **por qué reacción a hechos y no REST ni topic de comando** (§8.1, §9.2), **qué significa `ACTIVE`** (§9.2c) y **por qué polling y no webhooks** (§10.4) |
| 0.4 | `docs/dominios/10_disbursement_domain.md` — dominio **D10**, formato de `07_wallet_domain.md`: Subdominios · Agregados (tabla Campo\|Tipo\|Nota + línea **Inv:**) · Reglas `DB-01..DB-09` · Eventos emitidos · Eventos consumidos · ACL |
| 0.5 | `docs/dominios/11_stp_connector_domain.md` — dominio **D11** (`[Generic Subdomain / ACL]`), mismo formato. Reglas `SO-01..SO-06`, `KY-01..KY-07`, `EG-01..EG-06` |
| 0.6 | Actualizar `README.md` raíz. **Ojo, hay un desfase previo:** el título dice *"Servicios (16 + gateway)"* pero la tabla lista 17 filas y le faltan `channels-service`, `risk-service` e `invoicing-service` — `settings.gradle.kts` tiene **20** módulos. Corregir ese desfase y sumar los nuevos: **20 → 22**. Añadir los 9 topics nuevos de §8.4 a la tabla §5 (más `stp.incoming-payment-detected` cuando llegue la Fase 4) y anotar el cambio de contrato de `credit-account-activated` |
| 0.7 | Actualizar `docs/dominios/04b_credit_portfolio_domain.md`: documentar que hoy el desembolso de originación **no emite ningún evento** (§6.4), el bloque `disbursement` que se añade a `credit-account-activated`, el hecho nuevo `disposition-authorized`, y la semántica de `ACTIVE` (§9.2c) |
| 0.8 | **ADR de contrato de evento**: `credit-account-activated` cambia y lo consumen 9 servicios. Escribir la nota de compatibilidad (§8.2) y validarla con los dueños de cada consumidor **antes** de la Fase 2-B |
| 0.9 | Entrada nueva en `docs/IMPLEMENTATION_TRACKER.md`: fila en *Estado General* por servicio nuevo y fila en el *Changelog de Implementación* con el formato existente (`\| fecha \| módulo \| cambio \|`, prosa densa con hallazgos verificados) |
| 0.10 | Actualizar `docs/c4_component_diagram.md` y `docs/core_crediticio_dominios.md` |
| 0.11 | **Confirmar con STP** las tres preguntas de las que depende el diseño: (a) ¿el endpoint de consulta por clave de rastreo existe y es estable? (**Q14**); (b) ¿cuál es la frecuencia máxima admitida de `V2/conciliacion`? (**Q15**); (c) ¿soportan mTLS de cliente? (**EG-05**) |
| 0.12 | Inventario firmado por el equipo de qué del legado se migra y qué se retira (§2.4 y §5) |

**Criterios de aceptación**
- Ningún secreto vivo en el repositorio.
- Los vectores de oro están versionados y revisados.
- El ADR está aprobado y el `README.md` raíz refleja el número real de servicios.
- Los dos documentos de dominio siguen el formato de `07_wallet_domain.md`.
- Las tres preguntas a STP tienen respuesta por escrito. **Si (a) es negativa, el poller usa sólo `V2/conciliacion` y el plan no cambia. Si (b) impone un límite bajo, se ajusta el intervalo.**

---

### FASE 1 — `stp-service`: el conector, con el sello como contrato (3–4 sprints)

**Objetivo:** un servicio interno que sabe firmar, enviar órdenes a STP y **consultar su liquidación**, multi-empresa, sin conocer nada del dominio de crédito. Se prueba contra el mock de STP.

| # | Entregable |
|---|---|
| 1.1 | Módulo Gradle `stp-service` en `settings.gradle.kts`; `build.gradle.kts` copiado de `accounting-service` |
| 1.2 | Esqueleto hexagonal (§10.1), `package-info.java` con `@ApplicationModule(allowedDependencies = {"shared"})`, puerto `8101`, schema `stp` |
| 1.3 | **`CadenaOriginalBuilder` + `SigningService`** con `SG-T01..SG-T14` en verde. **Los tests van primero** |
| 1.4 | `BanxicoResponseCode` (32 códigos + `RETRYABLE`/`TERMINAL` + `UNKNOWN` que falla cerrado) y `StpOrderStatusCode` (mapeo `LQ/TLQ/CCO/CXO/CCE`→LIQUIDADA, `D/TD/RE`→DEVUELTA, `CL/TCL`→CANCELADA) en **una sola** fuente de verdad. Cierra **B1** y **B8** |
| 1.5 | Liquibase: `companies`, `company_keys`, `ordering_accounts`, `payment_orders`, `settlement_observations`, `payment_order_events`, `cep_receipts`, `tracking_key_sequences`, `outbox_messages`, `poll_runs`, `stub_orders`, `event_publication` |
| 1.6 | `EnvelopeEncryptedKeyProvider` con los dos propósitos (SIGNING / VERIFICATION) + `KeyEncryptionKeyResolver` + caché. Tests: round-trip, aislamiento entre empresas, llave expirada, KEK ausente, verificación de sello |
| 1.7 | `RestClientStpGateway` con `RestClient` de Spring 6.1: timeouts por request, circuit breaker, pinning (**EG-01..EG-06**). **Adiós Unirest** |
| 1.8 | `StpPaymentRequestedListener` (topic configurable) + `PaymentOrderRegistrationService` + `StpOutboxRelayJob`, con las tres capas de idempotencia (§13.2) |
| 1.9 | **`SettlementPollingService` + `StpSettlementPollingJob`** — el corazón de §10.4: `V2/conciliacion` paginado, cruce por `claveRastreo`, `SettlementObservation` con `SO-01..SO-06`, verificación de sello, publicación de `stp.order-settled` / `stp.order-returned` |
| 1.10 | `BeneficiaryNameMatcher` — port de `compareNames` con los casos reales del legado (`"NIKTE-HA DE GUADALUPE OROPEZA VAZQUEZ"` vs `"NIKTE HA DE GUADALUPE,OROPEZA/VAZQUEZ"`) |
| 1.11 | `KafkaStpEventPublisher` con los 4 topics de salida + `DefaultErrorHandler` con DLT y backoff exponencial |
| 1.12 | `StpAdminController` (rol `ADMIN`) + `StpOperationsController` (ADMIN/OPS/AUDITOR) + `SecurityConfig` de §15.2 + `JwtAuthenticationFilter` copiado de payments |
| 1.13 | **`StubStpGateway` (§10.6)**: eco completo de la orden, conciliación con los mismos datos, `nombreCep` = `nombreBeneficiario` enviado, **sello firmado de verdad** con la llave del stub, los 11 escenarios por centavos, tabla `stp.stub_orders`, y **guarda de arranque que impide `mode=stub` en perfil `prod`** |
| 1.14 | Bloque en `docker-compose.yml` + sección `# ── STP ──` en `.env.example`: `STP_BASE_URL`, `STP_CONSULTA_BASE_URL`, `STP_KEK_<id>`, `STP_POLLING_INTERVAL`, `STP_GATEWAY_MODE` |
| 1.15 | `services/stp-service/README.md` + `docs/modules/stp/README.md` |
| 1.16 | Tests: `SigningServiceTest`, `PaymentOrderRegistrationServiceTest`, **`SettlementPollingServiceTest`**, `StpOperationsControllerTest` (`@WebMvcTest`), `StpAcceptanceTest`, `StpFlowIT` (`@EmbeddedKafka` + Testcontainers + **el stub de 1.13 en vez de WireMock**) |

**Criterios de aceptación**
- `./gradlew :stp-service:test` → BUILD SUCCESSFUL.
- **SG-T01 y SG-T02 pasan con comparación de string exacta** contra los vectores del legado.
- **SG-T04 pasa con `Locale.GERMANY` por defecto.**
- **SG-T12** (equivalencia con el oráculo reflexivo) pasa con 1000 casos aleatorios.
- Dos empresas sembradas producen firmas distintas y usan cuentas ordenantes distintas.
- Un mensaje en el topic de entrada produce una llamada al mock de STP y un `stp.order-accepted`. Reenviarlo **no** produce una segunda llamada.
- **El poller, con una orden en `ACCEPTED` y el mock devolviendo `LQ`, publica `stp.order-settled` y deja la orden en `SETTLED`.**
- **Correrlo dos veces no publica dos eventos** (la segunda observación queda `DUPLICATE`).
- Una observación con sello alterado queda `SIGNATURE_INVALID` y **no** cambia el estado.
- Una observación con una `claveRastreo` desconocida queda `UNMATCHED` y alerta.
- El poller **no se ejecuta** si no hay órdenes en vuelo (`SENT` o `ACCEPTED`).
- **Los 11 escenarios del stub pasan end-to-end** sin un solo mock de Mockito: `.00` liquida, `.02` termina en `REJECTED` con `RECHAZO_POR_PLD` (**no** en éxito — regresión de **B1**), `.03` reintenta sin rechazar, `.06` alerta a las 24 h sin marcar `SETTLED`, `.08` queda `SIGNATURE_INVALID`, `.09` queda `UNMATCHED`.
- **El stub verifica la firma que le enviamos**: si `CadenaOriginalBuilder` cambia, el stub rechaza la orden.
- Arrancar con `SPRING_PROFILES_ACTIVE=prod` y `STP_GATEWAY_MODE=stub` **falla el arranque**.
- El log de una firma no contiene la cadena original.

---

### FASE 2 — `disbursement-service`: el dominio (2 sprints)

**Objetivo:** el servicio de payouts, agnóstico de crédito y de proveedor. En esta fase su única fuente real es `wallet.withdrawal-completed`; la de crédito llega en la 2-B.

| # | Entregable |
|---|---|
| 2.1 | Módulo Gradle `disbursement-service`, puerto `8100`, schema `disbursement` |
| 2.2 | Esqueleto hexagonal (§9.1) con la separación núcleo / ACL de §7.2 |
| 2.3 | `DisbursementOrder` con la máquina de estados e invariantes `DB-01..DB-09` |
| 2.4 | Liquibase: `disbursement_orders`, `disbursement_events`, `routing_rules`, `company_mappings`, `event_publication` |
| 2.5 | `RoutingService` + `CompanyResolutionService` + siembra para la empresa inicial |
| 2.6 | `ClabeValidator` — dígito verificador con las ponderaciones `{3,7,1,…}` del legado, con tests contra CLABEs reales válidas e inválidas |
| 2.7 | `WalletWithdrawalListener` (ACL) — consume `wallet.withdrawal-completed`. Los otros tres ACL llegan en la Fase 2-B, cuando existan sus hechos |
| 2.8 | `KafkaStpDispatchAdapter implements ProviderDispatchPort` → publica `disbursement.stp-requested` |
| 2.9 | Los 4 listeners de retorno (`stp.order-*`) + `DisbursementSettlementService` |
| 2.10 | `KafkaDisbursementEventPublisher` — `disbursement.completed` / `.failed` / `.returned`, con el eco de `sourceMetadata` |
| 2.11 | Ventana operativa en `infrastructure/job/DisbursementDispatchJob` (**DB-05**), con `SELECT … FOR UPDATE SKIP LOCKED` (§13.5) — **sin ShedLock**. Reemplaza el `@Scheduled` por réplica del legado (**B12**) |
| 2.12 | `DisbursementController` (consulta / retry / cancel) + `DisbursementIngestController` (`POST` con `Idempotency-Key`) |
| 2.13 | **Tests de arquitectura** (§7.5, escritos): `DisbursementDecouplingTest` en este módulo (`DC-1`) y `StpDecouplingTest` en `stp-service` (`DC-2`) — uno por módulo, porque `@AnalyzeClasses` sólo importa su propio paquete |
| 2.14 | `docker-compose.yml`, `services/disbursement-service/README.md`, `docs/modules/disbursement/README.md` |
| 2.15 | Tests: unitarios de la máquina de estados y del routing, `@WebMvcTest`, `DisbursementAcceptanceTest`, `DisbursementFlowIT` con los 12 topics que toca (los 9 de §8.4 + los 3 que consume de terceros) en `@EmbeddedKafka` |

**Criterios de aceptación**
- `wallet.withdrawal-completed` → orden `REQUESTED` → `DISPATCHED` → mensaje en `disbursement.stp-requested`.
- Evento duplicado → **una sola** orden (verificado con dos hilos concurrentes contra el `UNIQUE`).
- CLABE con dígito verificador inválido → `FAILED` con `INVALID_BENEFICIARY_ACCOUNT`, sin tocar `stp-service`.
- Fuera de ventana → `REQUESTED` con `scheduled_for`; al abrir la ventana se despacha.
- `stp.order-settled` → `SETTLED` + `disbursement.completed` **con el `sourceMetadata` intacto**.
- `stp.order-rejected` con código `RETRYABLE` → vuelve a `REQUESTED`, no a `REJECTED`.
- **El test de arquitectura pasa**, y falla si se introduce a propósito una referencia a crédito en `domain/`.
- **Borrar los listeners ACL y compilar: el módulo sigue construyendo** (simulacro de extracción, §7.5).

---

### FASE 2-B — Cerrar la coreografía en `credit-portfolio` (2 sprints)

**Objetivo:** corregir §6.4 — que el desembolso de originación viaje en Kafka — y sacar la llamada a un proveedor de pagos de dentro de la transacción de saldos. Todo por **hechos**, sin topics de comando.

> Esta fase **no existía** en la primera versión del plan. Es consecuencia directa de haber verificado que `activate()` no publica nada. Los entregables 2B.1 y 2B.2 son **bloqueantes**: sin la identidad del beneficiario no se puede firmar una orden.

| # | Entregable | Servicio |
|---|---|---|
| 2B.1 | **Identidad del beneficiario, camino de originación** (§9.3): añadir `obligorName` + `obligorTaxId` a `CreditProductCreationRequestedEvent`, a `CreateCreditAccountCommand` y a la entidad `CreditAccount` (+ migración Liquibase). Origination ya los tiene del `Prospect` | origination, credit-portfolio |
| 2B.2 | **Identidad del beneficiario, camino de wallet**: `RequestDispositionCommand` y `wallet.disposition-requested` ganan `beneficiaryName` + `beneficiaryTaxId` para `THIRD_PARTY_CREDIT` | wallet, credit-portfolio |
| 2B.3 | `CreditAccountActivatedEvent` gana el bloque anidado `DisbursementInstruction` (§8.2), poblado según **CP-D1** | credit-portfolio |
| 2B.4 | `CreditAccountService.activate(...)`: la disposición queda en `PROCESSING`, **se elimina la llamada a `speiDispatch`**, se publica el evento enriquecido (§9.3) | credit-portfolio |
| 2B.5 | Añadir `@JsonIgnoreProperties(ignoreUnknown = true)` a `services/wallet-service/.../CreditAccountActivatedPayload` — hoy funciona por el default del mapper de spring-kafka, pero es una mina (§8.2) | wallet |
| 2B.6 | Hecho nuevo `credit-portfolio.disposition-authorized` (§8.3) + cambio en `process(...)`: `SELF_USE` se completa en el acto, el resto queda `PROCESSING` y publica el hecho | credit-portfolio |
| 2B.7 | **Eliminar `SpeiDispatchPort`, `NoopSpeiDispatchAdapter` y el `@Mock SpeiDispatchPort` de `CreditAccountServiceTest`** | credit-portfolio |
| 2B.8 | `DisbursementCompletedListener` → `disposition.complete(externalRef)` → publica `disposition-completed` con el ref **real**. Wallet y notifications lo consumen **sin cambios** | credit-portfolio |
| 2B.9 | `DisbursementFailedListener` / `DisbursementReturnedListener` → `disposition.fail()` + **`BalanceEvent` compensatorio** que revierte `applyDisposition` + `balance-updated` con el saldo corregido | credit-portfolio |
| 2B.10 | Validación local de la CLABE **antes** de publicar, para que el rechazo trivial no dé la vuelta completa (§9.2) | credit-portfolio |
| 2B.11 | **Notificación #3b `DISBURSEMENT_FAILED`** ← `disbursement.failed`: `EventType` nuevo, listener nuevo, plantilla nueva (§8.5) | notifications |
| 2B.12 | Los **tres** listeners ACL de `disbursement-service`: `CreditAccountActivatedListener`, `DispositionAuthorizedListener`, `WalletWithdrawalListener` | disbursement |
| 2B.13 | `wallet`: consumir `credit-portfolio.disposition-rejected` para revertir la reserva optimista. **Cierra un topic que hoy se publica y nadie consume** | wallet |
| 2B.14 | `wallet`: consumir `disbursement.failed` → `WalletWithdrawal.markFailed()`. El método existe y **hoy nadie lo llama** (cierra `WD-02`) | wallet |
| 2B.15 | `wallet`: eliminar `WalletDispatchPort` + `NoopWalletDispatchAdapter` (**Q13**) | wallet |
| 2B.16 | Actualizar `04b_credit_portfolio_domain.md` (semántica de `ACTIVE`, §9.2c), `07_wallet_domain.md` y `T2_notifications.md` | docs |
| 2B.17 | Tests (§ criterios de abajo) | credit-portfolio, wallet |

**Criterios de aceptación**
- Activar un producto **no revolvente** publica **un solo** `credit-account-activated`, con el bloque `disbursement` poblado: CLABE del contrato, nombre y RFC del obligado, y el monto de `resolveAmount`.
- Activar un **revolvente** publica el evento con `disbursement = null`.
- Una disposición `SELF_USE` no publica `disposition-authorized`; una `THIRD_PARTY_CREDIT` sí.
- **Los 9 consumidores actuales de `credit-account-activated` siguen verdes** con el campo nuevo — test de contrato por cada uno.
- **`grep -r "speiDispatch\|WalletDispatchPort" services/credit-portfolio-service/src services/wallet-service/src` → 0 resultados.**
- Ninguna llamada HTTP saliente dentro de `@Transactional` en `CreditAccountService`.
- `disposition-completed` sólo se publica cuando llega `disbursement.completed`, con el `externalRef` real — **la notificación #3 deja de mentir**.
- Un `disbursement.failed` genera el `BalanceEvent` compensatorio, `availableCredit` vuelve al valor previo y sale la notificación #3b.
- La suite completa de `credit-portfolio`, `wallet`, `origination` y `notifications` sigue verde.

---

### FASE 3 — Corte y retiro del legado en la ruta de dispersión (1–2 sprints)

| # | Entregable |
|---|---|
| 3.1 | **Modo sombra**: `stp-service` firma y persiste la orden pero **no** llama a STP; se compara su cadena original y su sello contra los del legado, orden por orden, durante N días |
| 3.2 | Reporte de discrepancias. **Meta: 0 diferencias en la cadena original** |
| 3.3 | Migración de datos: `cat_cuentasstp` → `stp.ordering_accounts` con `company_id`; llave JKS → `stp.company_keys` (§11.3); llave pública de STP cargada con `purpose=VERIFICATION` |
| 3.4 | Backfill opcional de `mae_transaccionesstp` → `stp.payment_orders` (sólo si Auditoría lo pide) |
| 3.5 | Corte por porcentaje: 1% → 10% → 50% → 100%, con rollback por feature flag |
| 3.6 | Apagado del `dispersion-topic` y `validate-card-topic` de Service Bus |
| 3.7 | Borrado del código muerto de §5 en el legado |
| 3.8 | `SG-T12` (oráculo reflexivo) se elimina junto con la dependencia de test a `commons-lang3` |

**Criterios de aceptación**
- Modo sombra: 0 diferencias de cadena original en ≥ 5000 órdenes reales.
- Corte al 100% con métricas estables 72 h.
- Rollback probado en ambiente de pruebas, no sólo documentado.
- **Durante el corte, el poller reconcilia órdenes de ambos sistemas sin marcar `UNMATCHED` las del legado** (regla **SO-03** con lista de exclusión temporal).

---

### FASE 4 — Abonos entrantes, conciliación y cierre de día (3–4 sprints)

**Objetivo:** migrar el resto del legado. Aquí vive la lógica de negocio más densa. Nota: **los abonos entrantes también dejan de ser un webhook** — se descubren por conciliación.

| # | Entregable |
|---|---|
| 4.1 | Descubrimiento de abonos por conciliación: `V2/conciliacion` con `tipoOrden="R"` (recibidas). Whitelist de tipos de pago `[1,5,19,20,21,22]`, detección de fondeo propio comparando contra las cuentas ordenantes de la empresa (ya no `findById(1)`), `fechaAplicacionPagoCalcular` con la ventana de medianoche |
| 4.2 | Nuevo topic `stp.incoming-payment-detected` → consumido por `payments-service` como método `SPEI` (encaja con `PR-01..PR-05` de `06_payments_domain.md`) |
| 4.3 | **Día Banxico** como agregado propio: `stp.business_days` (`OPEN/PENDING/CLOSED`) + `stp.business_holidays` + `siguienteDiaHabil` |
| 4.4 | Consulta de saldo (`consultaSaldoCuenta` con `SaldoCuentaFirma`) + alerta de saldo mínimo **por empresa** (hoy sólo escribe un `log.warn` y la propiedad del umbral ni siquiera se usa) |
| 4.5 | `StpEndOfDayReconciliationJob`: paginación de 1000, cálculo `saldoFinal = saldoInicial + Σ(entradas − salidas)`, doble validación contra el saldo consultado. Reusa `StpOrderStatusCode` de la Fase 1 — **una sola fuente de verdad para el mapeo de estados** |
| 4.6 | **Auto-descubrimiento de pagos perdidos** — la conciliación detecta un abono que nunca se registró y lo inyecta. Esta regla vale oro; hay que preservarla |
| 4.7 | Bulk insert reescrito con JDBC batch tipado o `COPY`, en vez del `INSERT` generado por reflexión con 33 parámetros posicionales a mano |
| 4.8 | El job de cierre corre con perfil que **no** levanta los listeners de Kafka (cierra **B13**), coordinado con `SKIP LOCKED` |
| 4.9 | Alta/baja de CLABEs de clientes: `POST /api/v1/stp/accounts` con generación (`646`+`180`+prefijo de empresa+consecutivo+DV) y reuso de cuentas inactivas |
| 4.10 | Extracto bancario Oracle ERP (5 CSV + ZIP) — **evaluar primero si sigue siendo necesario** (**Q4**); si sí, job separado |

**Criterios de aceptación**
- Una conciliación completa de un día real reproduce **exactamente** los mismos totales que el legado.
- El auto-descubrimiento de pagos perdidos se dispara en un caso sintético.
- El job de conciliación no consume mensajes de Kafka mientras corre.
- El poller de la Fase 1 y el job de cierre no se pisan (misma tabla de observaciones, `SO-02` los reconcilia).

---

### FASE 5 — Semántica de `ACTIVE` y trazabilidad del desembolso (1 sprint)

**Objetivo:** dejar por escrito y visible qué significa cada estado, ahora que activación y liquidación son dos momentos distintos.

> **Esta fase cambió de naturaleza.** En la versión anterior proponía que la cuenta esperara al desembolso para pasar a `ACTIVE`. Con `credit-account-activated` como disparador del desembolso (§9.2c) eso sería una dependencia circular — y además sería contablemente incorrecto, porque la deuda nace en la activación. Lo que queda es documentación y observabilidad, no un cambio de comportamiento.

| # | Entregable |
|---|---|
| 5.1 | Documentar en `04b_credit_portfolio_domain.md`: **`ACTIVE` = crédito vigente y desembolso en camino**; **`Disposition.COMPLETED` = el dinero llegó**. Corregir el javadoc de `PL-02`, que hoy dice *"ACTIVE after first DispositionCompleted"* y describe algo que el código no hace |
| 5.2 | Vista de operación en el backoffice: cuentas `ACTIVE` con disposición en `PROCESSING` más de N horas — el equivalente honesto de "activo pero sin fondear" |
| 5.3 | Alerta: disposición en `PROCESSING` > 24 h (encadena con **SO-06** del lado de `stp-service`) |
| 5.4 | Revisar si algún reporte regulatorio o contable usa `credit-account-activated` como "fecha de desembolso". Si es así, migrarlo a `disposition-completed`, que ahora es el hecho correcto |
| 5.5 | `accounting-service`: verificar que el asiento de desembolso se genere con `disposition-completed` y no con la activación (encadena con la cuenta 2101 `FONDOS_CLIENTES` que el tracker documenta el 2026-07-10) |

**Criterios de aceptación**
- La documentación de dominio describe lo que el código hace, no lo que se pensó que haría.
- Existe una vista y una alerta para disposiciones atascadas.
- Ningún reporte usa la activación como fecha de entrega de fondos.

---

### FASE 6 — Endurecimiento y retiro definitivo (1 sprint)

| # | Entregable |
|---|---|
| 6.1 | Runbooks: DLT de los topics que disparan dinero (`credit-account-activated`, `disposition-authorized`, `disbursement.stp-requested`), rotación de llaves, apertura/cierre de ventana operativa, reproceso de conciliación, **poller atascado** |
| 6.2 | Dashboards Grafana: órdenes por estado y empresa, latencia p50/p95/p99 del egress a STP, tasa de rechazo por `banxicoCode`, profundidad de DLT, edad del mensaje más viejo del outbox, **latencia de confirmación (sent_at → settled_at)** y **tasa de `UNMATCHED`** |
| 6.3 | Alertas: DLT > 0; orden en `DISPATCHED` > 15 min; `ACCEPTED` sin `SETTLED` > 24 h (**SO-06**); llave a < 30 días de expirar (**KY-07**); `SIGNATURE_INVALID` > 0; **poller sin corridas exitosas > 15 min** |
| 6.4 | `audit-service` suscrito a los **10** topics nuevos: los 9 de §8.4 + `stp.incoming-payment-detected` de la Fase 4. **Verificado: hoy escucha 17 topics y ninguno de `wallet.*`** — es "suscriptor global" por intención, no por implementación. Hay que añadir los listeners a mano |
| 6.5 | `NetworkPolicy` de egress (**EG-01**) y pinning de certificado (**EG-02**) verificados en el ambiente real |
| 6.6 | Prueba de carga con el volumen pico real de dispersión, midiendo también el costo del poller |
| 6.7 | Retiro del repo legado: archivar, revocar credenciales, apagar el namespace |
| 6.8 | Cerrar la documentación: `README.md` raíz, `IMPLEMENTATION_TRACKER.md` (estado ✅), C4, y los dos documentos de dominio con el estado real |

---

### Resumen de esfuerzo

| Fase | Sprints | Servicios que toca | Bloquea a |
|---|---|---|---|
| 0 — Fundamentos y documentación | 1 | docs, legado | todas |
| 1 — `stp-service` (incluye poller y stub) | 3–4 | nuevo | 3 |
| 2 — `disbursement-service` | 2 | nuevo | 2-B, 3 |
| **2-B — Cerrar la coreografía** | **2** | **credit-portfolio, wallet, origination, notifications, disbursement** | **3** |
| 3 — Corte | 1–2 | legado | 4, 6 |
| 4 — Abonos, conciliación, cierre | 3–4 | stp, payments | 6 |
| 5 — Semántica y trazabilidad | 1 | docs, backoffice, accounting | — |
| 6 — Endurecimiento y retiro | 1 | todos | — |
| **Total** | **14–17 sprints** | | |

**Paralelización:** 1 y 2 tocan servicios distintos y van a la vez. 2-B necesita 2 y toca cinco servicios existentes — es la fase de mayor coordinación. 4 y 5 son independientes entre sí.

**Cambios de estimación respecto a versiones anteriores del plan:**

| Cambio | Efecto |
|---|---|
| El poller (§10.4) y el stub (§10.6) son trabajo real que no existía | Fase 1: 2–3 → **3–4** sprints |
| Desapareció la fase de identidad e ingreso H2H, y con ella el tercer servicio | **−1 a −2** sprints |
| La Fase 2-B creció: la identidad del beneficiario obliga a tocar origination y wallet (§9.3) | 1–2 → **2** sprints |
| La Fase 5 dejó de ser un cambio de comportamiento y pasó a ser documentación y observabilidad | 1–2 → **1** sprint |

---

## 17. Checklist de conformidad con `base-service.md`

A verificar en **cada uno** de los dos servicios. Tomado de la §14 de `base-service.md`, con los ajustes que refleja el código real (payments/wallet divergen de la plantilla en varios puntos y **manda el código real**).

**Registro en el monorepo**
- [ ] `include("<x>-service")` en `settings.gradle.kts`, en posición de dominio
- [ ] `build.gradle.kts` copiado de `accounting-service`
- [ ] Bloque en `docker-compose.yml` con `<<: *otel-agent`, `<<: *app-mem`, `otel-agent-vol`, `depends_on` con `otel-agent-init: service_completed_successfully`
- [ ] **Sin `ports:`** — los servicios de dominio no se exponen al host
- [ ] **Sin Dockerfile propio** — el raíz está parametrizado con `ARG SERVICE`

**Estructura**
- [ ] `package-info.java` con `@ApplicationModule(allowedDependencies = {"shared"})` y javadoc con código de dominio, tipo y schema
- [ ] `<Modulo>ModuleConfig` con `@EnableConfigurationProperties`
- [ ] Entidades JPA en `domain/`, constructor `protected`, factory `create(...)`, **sólo getters**
- [ ] Excepciones extienden `shared.DomainException` con código `DISBURSEMENT_*` / `STP_*`
- [ ] Ports `in` (`*UseCase`) + records `*Command` en `application/` — estilo wallet
- [ ] `interface Jpa<X>Repository extends JpaRepository<E,UUID>, <X>Repository` con `@Override` en los derivados

**Config**
- [ ] `ddl-auto: validate`, `open-in-view: false`, `hibernate.default_schema: <modulo>`
- [ ] `spring.liquibase.default-schema: <modulo>` **y `liquibase-schema: public`** ← si falta, el primer arranque falla
- [ ] `spring.kafka.consumer.group-id: <modulo>-service`, `auto-offset-reset: earliest`
- [ ] `server.port: ${SERVER_PORT:8100}` / `8101`
- [ ] `management.endpoints.web.exposure.include: health,info,metrics,prometheus`
- [ ] `fintech.<modulo>.*` con `@ConfigurationProperties` + `@Validated`
- [ ] **No replicar** `src/main/resources/<modulo>/application.yml` — archivo huérfano que existe en 7 servicios y no se carga

**Liquibase**
- [ ] `db.changelog-<modulo>.xml` con XSD `4.27`, `relativeToChangelogFile="false"`
- [ ] `001-create-schema.sql` + tablas + **`NNN-create-event-publication.sql` en `<schema>.`**

**Seguridad**
- [ ] `SecurityConfig` con csrf disable, STATELESS, `HttpStatusEntryPoint(UNAUTHORIZED)`, `addFilterBefore(jwtFilter, …)`
- [ ] Los 4 bloques `permitAll` (actuator con `/actuator/prometheus`, `/error`, api-docs, swagger)
- [ ] `JwtAuthenticationFilter` copiado byte a byte de payments, cambiando sólo el `package`
- [ ] **Sin `@Bean PasswordEncoder`** — payments y wallet no lo tienen
- [ ] **Sin endpoints públicos de negocio** — ninguno de los dos servicios recibe tráfico externo

**API**
- [ ] Controller **package-private**, `@RequestMapping("/api/v1/<modulo>")`, `@Tag`/`@Operation`/`@ApiResponses`/`@SecurityRequirement`
- [ ] DTOs `record` con `jakarta.validation`; respuestas con `static from(Entidad)`
- [ ] `<Modulo>ExceptionHandler` `@RestControllerAdvice @Order(1)`, un `@ExceptionHandler` por excepción, todos con `ProblemDetail` + `setType(URI.create("https://fintech.com/errors/" + code))`
- [ ] `OpenApiConfig` con dos `@Server` y `@SecurityScheme(name="bearerAuth")`

**Kafka**
- [ ] Un `@Bean ConcurrentKafkaListenerContainerFactory` por tipo de payload, nombrado `<evento>ListenerContainerFactory`
- [ ] `JsonDeserializer` con `TRUSTED_PACKAGES=com.fintech.*`, `USE_TYPE_INFO_HEADERS=false`, `VALUE_DEFAULT_TYPE`
- [ ] Publisher `@Component` con constantes `static final String TOPIC_*`
- [ ] Payloads de entrada con `@JsonIgnoreProperties(ignoreUnknown = true)`
- [ ] **Extra sobre el estándar del repo:** `DefaultErrorHandler` + DLT + backoff exponencial

**Tests**
- [ ] Unit `@ExtendWith(MockitoExtension.class)` con `@Mock` en puertos out; **sin stubs innecesarios**
- [ ] Controller `@WebMvcTest` + `@Import({SecurityConfig, ExceptionHandler, JwtAuthenticationFilter})` + `@MockitoBean` (Boot 3.4, no `@MockBean`); auth simulada con headers `X-User-Id` / `X-Roles`
- [ ] `<Modulo>AcceptanceTest` `@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` + `@ActiveProfiles("test")` + `@EmbeddedKafka`, javadoc con `AC-1..AC-N`
- [ ] `<Modulo>FlowIT` con `@EmbeddedKafka(bootstrapServersProperty = "spring.kafka.bootstrap-servers")` + Awaitility; eventos publicados como `Map<String,Object>` para probar la deserialización real
- [ ] `application-test.properties` con `jdbc:tc:postgresql:16-alpine:///fintech`
- [ ] **Extra:** test de arquitectura que impide que `disbursement/domain` y `disbursement/application` mencionen crédito

**Verificación final**
- [ ] `./gradlew :<modulo>-service:test` → 0 failures
- [ ] `docker compose up -d` → `/actuator/health` = `{"status":"UP"}`
- [ ] `/swagger-ui/index.html` 200 · `/v3/api-docs` JSON · `/v3/api-docs.yaml` 200
- [ ] Endpoints de negocio → 401 sin headers de auth
- [ ] `README.md` del servicio + `docs/modules/<modulo>/README.md`

---

## 18. Riesgos y cuestiones abiertas

### 18.1 Riesgos

| # | Riesgo | Severidad | Mitigación |
|---|---|---|---|
| **R1** | La cadena original nueva difiere en un byte → STP rechaza todo | **Crítica** | `SG-T01..SG-T14` + **modo sombra con 0 discrepancias en ≥5000 órdenes** antes de cortar |
| **R2** | La llave privada actual lleva tiempo en Git en claro | **Crítica** | Rotación en Fase 0, **antes** de cualquier otra cosa |
| **R3** | `-200 RECHAZO_POR_PLD` se ha estado reportando como éxito (**B1**) | **Alta** | Auditar el histórico buscando `idu_respuestastp = -200` y reconciliar con contabilidad. **Puede ser un hallazgo con impacto regulatorio** |
| **R4** | **El desembolso de originación nunca ha emitido un evento** (§6.4): ningún consumidor de Kafka ha visto jamás esos desembolsos | **Alta** | Es lo que corrige la Fase 2-B. Verificar si algún reporte o conciliación contable dependía de que no existieran |
| **R5** | Doble dispersión durante el corte (dos sistemas activos) | **Alta** | `claveRastreo` única por `(empresa, día, secuencia)`; STP rechaza con `-1` la segunda. Corte por porcentaje |
| **R6** | **El poller no detecta una liquidación** y una orden queda colgada en `ACCEPTED` | **Alta** | **SO-06** (alerta a las 24 h) + barrido de cierre de la Fase 4 como red. **Nunca** se marca `SETTLED` por timeout |
| **R7** | STP limita la frecuencia de `V2/conciliacion` o cobra por llamada | Media | **Q15** — confirmar en Fase 0. El poller sólo corre si hay órdenes en vuelo; el intervalo es configurable |
| **R8** | El nombre truncado a 40 (**B7**) puede haber generado acuses con `isEqualsNames=false` masivos | Media | Consultar el histórico cruzando con `nom_beneficiario > 40` para dimensionar |
| **R9** | La secuencia de clave de rastreo se agota o colisiona | Media | 14 dígitos por empresa y día = 10^14. `UNIQUE (company_id, tracking_key)` como red |
| **R10** | Latencia de confirmación de hasta 3 min podría no ser aceptable para algún caso de negocio | Media | Medir. Si molesta, bajar el intervalo o usar la consulta por rastreo (**Q14**) para órdenes recién enviadas. **No** volver a webhooks |
| **R11** | El extracto Oracle ERP puede tener consumidores que nadie recuerda | Media | Confirmar con Finanzas **antes** de la Fase 4 |
| **R12** | Volumen de conciliación (paginación de 1000, acumulación en memoria) | Media | Streaming + `COPY` en la Fase 4; medir con el volumen pico real |
| **R13** | **Enriquecer `credit-account-activated` rompe a alguno de sus 9 consumidores** | Media | Verificado que es retrocompatible (§8.2): 7 de 9 payloads llevan `@JsonIgnoreProperties`, wallet sobrevive por el default de spring-kafka y audit deserializa a `String`. Aun así: ADR de contrato validado con los dueños (0.8) + test de contrato por consumidor (criterio de aceptación de 2-B) + añadir la anotación que falta en wallet (2B.5) |
| **R14** | `cat_instituciones` no existe en el dump del esquema | Baja | Resembrar desde el catálogo de Banxico; el endpoint del legado está roto igual |
| **R15** | `mae_transaction_users` con `joinColumns` incorrecto | Baja | Verificar contra datos productivos antes de cualquier backfill |

### 18.2 Cuestiones abiertas — requieren respuesta del equipo

> Se numeran `Q1..Q17` y no `D1..D17` a propósito: en este repo `D<N>` es el código de **dominio** (`D9 — Risk`), y los dos dominios nuevos toman `D10` y `D11`.

| # | Pregunta | Impacto | Recomendación |
|---|---|---|---|
| **Q1** | ¿`credit-portfolio` publica `companyId` desde el día 1, o `disbursement` lo infiere? | Alto | El campo ya está en ambos payloads (§8.2, §8.3). Poblarlo en cuanto exista el catálogo de empresas; hasta entonces, `company_mappings` |
| ~~**Q2**~~ | ~~¿`ACTIVE` = liquidado o = orden enviada?~~ | — | **CERRADA por §9.2c.** Al usar `credit-account-activated` como disparador, `ACTIVE` sólo puede significar *"crédito vigente, desembolso en camino"* — lo contrario sería circular. Y es lo correcto: la deuda nace en la activación. El hecho *"el dinero llegó"* es `disposition-completed`. Queda documentarlo (Fase 5) |
| **Q3** | ¿Outbox propio o `event_publication` de Spring Modulith? | Medio | Outbox propio en Fase 1 (control de `next_attempt_at` y backoff). Modulith como evolución |
| **Q4** | ¿El extracto bancario Oracle ERP sigue vivo? | Medio | Confirmar con Finanzas antes de Fase 4 |
| **Q5** | ¿La validación de tarjeta por micro-dispersión de $0.01 se mantiene? | Medio | Si sí, entra como `DisbursementSource.ACCOUNT_VERIFICATION`, no como un caso especial del concepto de pago |
| **Q6** | ¿Backfill del histórico de `mae_transaccionesstp` a `stp.payment_orders`? | Medio | Sólo si Auditoría lo exige. Si no, el legado queda como archivo de sólo lectura |
| **Q7** | ¿Qué KMS para la KEK en producción? | Medio | Empezar con Secret de K8s + `STP_KEK_<id>`; migrar a Key Vault sin cambiar la interfaz |
| **Q8** | ¿`disbursement-service` se expone por el BFF de backoffice? | Bajo | Sí, para operación. Son 7 puntos de toque en `channel-backoffice-service`, ninguno en el gateway |
| **Q9** | ¿`stp.incoming-payment-detected` lo consume `payments-service` o `disbursement-service`? | Medio | `payments-service` — los abonos entrantes son su dominio (`PR-01..PR-05` ya están ahí). **La Fase 4.2 asume esta respuesta**; confirmar antes de llegar ahí |
| **Q10** | ¿Se comercializa `disbursement-service` de verdad, o "vendible" es sólo disciplina de diseño? | Medio — decide si la API de ingesta y el test de arquitectura son obligatorios | Mantener ambos aunque no se venda: cuestan poco y son la garantía de que el desacople es real |
| **Q11** | ¿Intervalo del poller? | Medio | 3 min por defecto. Ajustar con **Q15** y con la latencia observada |
| **Q12** | ¿`stp-service` soporta más de un proveedor en el futuro, o se clona por proveedor? | Bajo | Un servicio por proveedor. El routing vive en `disbursement`, que es donde debe estar |
| **Q13** | ¿Se elimina también `WalletDispatchPort` en la Fase 2-B, o se deja para después? | Bajo | Eliminarlo (2B.15). Dejar dos caminos de salida de dinero, uno de ellos stub, es la clase de deuda que se olvida |
| **Q16** | ¿Meter CLABE, nombre y RFC del beneficiario en `credit-account-activated`, que consumen 10 servicios y audit persiste íntegro? | Medio — dispersión de PII | **Sí, con el bloque anidado presente sólo cuando hay desembolso.** Audit necesita el rastro de todos modos por requisito regulatorio, y la alternativa (que `disbursement` consulte a `party-service`) rompe la comercialización (§9.3). Documentarlo como decisión consciente en `T3_audit_compliance.md` |
| **Q17** | ¿El stub de STP vive dentro de `stp-service` o en un contenedor aparte? | Bajo | Dentro, como los `Noop*Adapter` del repo, con guarda de arranque que impide `mode=stub` en perfil `prod` (§10.6). Un WireMock aparte sólo si hace falta contract testing |
| **Q14** | **¿Existe y es estable el endpoint de consulta de orden por clave de rastreo?** | **Alto — afecta la latencia del poller** | Confirmar con STP en Fase 0. Si no existe, el poller usa sólo `V2/conciliacion` y el plan no cambia |
| **Q15** | **¿Cuál es la frecuencia máxima admitida de `V2/conciliacion`? ¿Tiene costo por llamada?** | **Alto — es la premisa del §10.4** | Confirmar con STP en Fase 0 |

### 18.3 Lo que este plan deliberadamente NO hace

- **No expone ningún servicio a internet.** Ni webhooks, ni un BFF para el proveedor, ni un server block nuevo en el gateway. La única dirección es egress.
- **No introduce mTLS a nivel de servicio.** Queda como control opcional del borde hacia STP (**EG-05**), sin código.
- **No usa REST entre `credit-portfolio` y `disbursement`.** Y tampoco un topic de comando: `disbursement` reacciona a **hechos** del dominio de crédito (§8.1). Sólo las consultas de operación van por REST (§9.2).
- **No retrasa la activación del crédito.** `ACTIVE` significa *"crédito vigente, desembolso en camino"*, y el hecho *"el dinero llegó"* es `disposition-completed` (§9.2c).
- **No deja el proveedor sin ejercitar en ambientes bajos.** El stub firma de verdad y se verifica de verdad (§10.6) — nada de `return true`.
- **No migra `cat_users` ni las notificaciones salientes al legado.** El camino hacia el core legado desaparece; los consumidores nuevos escuchan Kafka.
- **No preserva la arquitectura de arranque dual** (web + CLI en el mismo JAR). Los batches van como jobs con perfil propio.
- **No preserva Azure Service Bus.** Kafka es la columna vertebral del monorepo; no hay razón para dos brokers.
- **No preserva `EvidenceService`.** Escribir requests con PII a disco sin rotación ni cifrado no se migra a ningún lado.
- **No introduce ShedLock.** El `SKIP LOCKED` que ya necesita el outbox resuelve el mismo problema sin dependencia nueva (§13.5).
- **No arregla los hallazgos de `identity-service` y `gateway-service`** — se reportan en el Apéndice D para que el equipo decida, pero ya no son prerrequisito de nada de este plan.

---

## Apéndice A — Mapa de migración clase por clase

| Legado | Destino | Nota |
|---|---|---|
| `service/EncryptService` | `stp/domain/signing/SigningService` + `adapter/out/crypto/*` | Reescrito. Se borra la ruta PEM/BouncyCastle |
| `service/StringService` (cadena original) | `stp/domain/signing/CadenaOriginalBuilder` | **Reescrito explícito**, sin reflexión |
| `service/StyleString`, `MyReflectionToStringBuilder` | — | Eliminados. Su comportamiento queda en `render()` |
| `model/dto/OrdenPagoFirma` y hermanos | `stp/domain/signing/*Firma` | Records inmutables |
| `application/TransferenciaApplication` | Partido: routing → `disbursement`; firma+HTTP → `stp` | |
| `service/TransferenciaService` | `stp/application/service/PaymentOrderRegistrationService` + `adapter/out/http/RestClientStpGateway` | |
| `model/enums/ResponseErrorBanxico` | `stp/domain/BanxicoResponseCode` | + clasificación retryable, + `-200` funcionando |
| `model/TransaccionStp` | `stp.payment_orders` + `disbursement.disbursement_orders` | Se parte en dos: la orden STP y la orden de desembolso |
| `model/BitacoraTransaccionStp` | `stp.payment_order_events` | Append-only |
| `model/CuentaStp` | `stp.ordering_accounts` (+ `company_id`) | |
| `model/TipoTransaccion` | `disbursement.routing_rules` | Reformulado: era un proxy accidental de cuenta ordenante |
| `model/AcuseCEP` | `stp.cep_receipts` | + verificación de sello (**SO-04**) |
| `queues/*` (ServiceBus, QueueService, listeners) | `infrastructure/adapter/*/messaging/*` (Kafka) | |
| `DispersionController` `PUT /dispersar` (STP → nosotros) | **Eliminado** | Lo sustituye `StpSettlementPollingJob` (§10.4) |
| `DispersionController` `POST /acuse-cep` (STP → nosotros) | **Eliminado** | Lo sustituye la observación de conciliación con `urlCEP`/`nombreCep` |
| `PagosController` `POST /pago` (STP → nosotros) | **Eliminado** | Fase 4: descubrimiento por `V2/conciliacion` con `tipoOrden="R"` |
| `ConsultasApplication.consultaConciliacionSTP` | `SettlementPollingService` + `StpEndOfDayReconciliationJob` | **De batch nocturno a camino crítico** |
| `service/WebhookService`, `TokenProvider/*` | — | **Eliminados**. No hay salida al legado |
| `service/EvidenceService` | — | **Eliminado** |
| `utils/Configuraciones` | `@ConfigurationProperties` + `configuration-service` (T5) | |
| `config/AppConfig` (30 `@Value`) | `StpProperties` / `DisbursementProperties` tipadas | |
| `application/PagosApplication` | Fase 4 → `stp` (recepción) + `payments-service` (aplicación) | |
| `application/ConciliacionApplication` | Fase 4 → `stp/application/service/ReconciliationService` | |
| `application/CierreDiarioApplication` | Fase 4 → `stp` (agregado `BusinessDay`) | |
| `application/CuentasBancariasApplication` | Fase 4 → `stp` (alta de CLABE) | |
| `application/ExtractoBancarioApplication` | Fase 4, **sujeto a **Q4**** | |
| `command/*` (Picocli) | Jobs en `infrastructure/job/` con perfil dedicado + `SKIP LOCKED` (§13.5) | |
| `service/DatabaseService` (INSERT por reflexión) | JDBC batch tipado / `COPY` | |
| `service/PostgreSQLEnumType` | — | Eliminado. Enums como `VARCHAR` + `CHECK`, como payments/wallet |

**Del monorepo actual, no del legado:**

| Hoy en `fintech-services` | Destino |
|---|---|
| `credit-portfolio`: `SpeiDispatchPort` + `NoopSpeiDispatchAdapter` | **Eliminados** (Fase 2-B). Su lugar lo toma el hecho `credit-account-activated` enriquecido |
| `credit-portfolio`: la llamada síncrona en `activate()` y en `process()` | Publicación del hecho (`credit-account-activated` enriquecido / `disposition-authorized`) + espera del resultado |
| `wallet`: `WalletDispatchPort` + `NoopWalletDispatchAdapter` | **Eliminados** (Fase 2-B, **Q13**) |
| `wallet`: `WalletWithdrawal.markFailed()` sin callers | Lo llama `DisbursementFailedListener` |
| `credit-portfolio.disposition-rejected` publicado sin consumidor | Lo consume `disbursement-service` |
| `credit-portfolio.disposition-completed` publicado con `externalRef` de stub | Se publica cuando el dinero llegó de verdad — la notificación #3 deja de mentir |
| `notifications`: `#3 DISBURSEMENT_COMPLETED` disparada antes de que haya desembolso | Pasa a ser cierta **sin tocar `notifications-service`**; sólo se añade `#3b DISBURSEMENT_FAILED` |
| `services/wallet-service/CreditAccountActivatedPayload` sin `@JsonIgnoreProperties` | Se le añade (2B.5) |

## Apéndice B — Lógica de negocio a preservar (lista de verificación)

Al terminar la Fase 4, cada uno de estos puntos debe tener una prueba automatizada que lo respalde:

1. Cadena original + sello, los tres beans, byte a byte
2. Formato de clave de rastreo (prefijo + `yyyyMMdd` + 14 dígitos)
3. Truncado de `nombreBeneficiario` a 40 antes de firmar
4. Validación de monto máximo por regla de routing (`BigDecimal.compareTo`, no `double`)
5. Resolución de cuenta ordenante por empresa
6. Whitelist de tipos de pago `[1, 5, 19, 20, 21, 22]` y flujo de devolución
7. Detección de fondeo propio (abono a cuenta propia ≠ abono a cliente)
8. `fechaAplicacionPagoCalcular` — ventana de medianoche a `horaMaxPermitidaPago`
9. Día Banxico (`ABIERTO/ESPERA/CERRADO`) + `siguienteDiaHabil` con días festivos
10. Ventana operativa de suspensión de dispersiones (16:50–17:10)
11. Conciliación: paginación, mapeo de estados, cálculo de saldo final, doble validación
12. Auto-descubrimiento de pagos perdidos en la conciliación
13. Validación de titularidad por micro-dispersión + `compareNames` normalizado (NFD)
14. Generación de CLABE con dígito verificador (ponderaciones `{3,7,1,…}`) y reuso de cuentas inactivas
15. Catálogo completo de 32 códigos Banxico
16. Extracto bancario Oracle ERP (sujeto a **Q4**)
17. Hash del número de cuenta antes de persistir (ahora HMAC con salt)
18. Bitácora inmutable de transacciones
19. `correlationId` end-to-end (ahora vía header de Kafka + Micrometer Tracing)
20. **Mapeo de estados de conciliación** (`LQ/TLQ/CCO/CXO/CCE` · `D/TD/RE` · `CL/TCL`) — ahora es camino crítico del poller, no sólo del cierre de día
21. **La forma de las respuestas de STP** (`{resultado:{id, descripcionError}}` y `ConciliacionDetalleResponseDTO` con sus 30 campos) — es el contrato que el stub tiene que reproducir (§10.6)

---

## Apéndice C — Afirmaciones verificadas contra el código

Todo lo que sigue se contrastó ejecutando búsquedas sobre los dos repos, no se infirió.

| # | Afirmación | Verificación |
|---|---|---|
| | **Sobre el legado** | |
| 1 | Ponderaciones del dígito verificador CLABE | `StringService:111` → `{3,7,1,3,7,1,3,7,1,3,7,1,3,7,1,3,7}` |
| 2 | `compareNames` normaliza con NFD | `StringService:92-97` → `Normalizer.Form.NFD` + `[^\p{ASCII}]` + espacios + `[^A-Za-z]` |
| 3 | Clave de rastreo = 24 caracteres | `StringService:47-54` → prefijo (2) + `yyyyMMdd` (8) + `completarCerosIzquierda(14, PK)` |
| 4 | El vector de oro de la cadena original | `StringServiceTest.generarCadenaOriginalOrdenPago:60-70` |
| 5 | El legado no verifica ninguna firma entrante | Cero `Signature.verify()`, cero cargas de llave pública en 151 clases |
| 6 | El legado no tiene Spring Security | Cero `WebSecurity`/`SecurityFilter`; `jjwt:0.9.1` declarado sin usar |
| 7 | El bug de `-200` | `TransferenciaService:169-173` → `Integer.toString(id).length() <= 3`; `"-200".length() == 4` |
| 8 | STP ofrece `V2/conciliacion` paginado | `ConsultasApplication:55` → `POST {stp.consulta.base.path}V2/conciliacion` con `{empresa, tipoOrden, fechaOperacion, page, firma}`; respuesta `{estado, mensaje, datos[], total}`; paginación de 1000 (`ConciliacionApplication:78,219,245`) |
| 9 | `ConciliacionFirma` tiene 3 campos | `empresa, tipoOrden, fechaOperacion` |
| 10 | El endpoint de consulta por rastreo es dudoso | `TransferenciaApplication.consultarOrdenEnviadaPorRastreo:405` apunta a `{stp.base.path}api/dispersion` (base de dispersión, no de consultas) y **no tiene callers vivos** → **Q14** |
| | **Sobre `fintech-services` — el hallazgo de §6.4** | |
| 11 | **`activate()` NO publica ningún evento de desembolso** | `publishDispositionCompleted` tiene **un solo** caller: `CreditAccountService:253`, dentro de `process(...)` (camino de wallet). El bloque de desembolso de `activate()` (líneas 132-145) no publica nada |
| 12 | `CreditAccountActivatedEvent` no sirve para desembolsar | Sus **15** campos no incluyen CLABE, nombre del beneficiario, RFC ni monto de desembolso como tal |
| 13 | La CLABE existe pero no se publica | `CreateCreditAccountCommand.clabeAccount` llega en `CreditProductCreationRequestedPayload` y se congela en `CreditAccount.fromSnapshot(...)`; hay `getClabeAccount()` |
| 14 | La llamada a SPEI ocurre dentro de una transacción | `CreditAccountService` lleva `@Transactional` a nivel de clase (línea 45); `activate()` (139) y `process()` (237) llaman a `speiDispatch.dispatch()` dentro |
| 15 | Productos revolventes no desembolsan al activar | `resolveAmount(...)` (186-191) devuelve `BigDecimal.ZERO` si `caps.hasCreditLimit()` |
| 16 | `credit-portfolio.credit-account-activated` tiene 9 consumidores | audit, charges, collections, commission, notifications, origination, payments, risk, wallet |
| 17 | **Enriquecer ese evento es retrocompatible** | 7 de los 9 payloads son `record` con `@JsonIgnoreProperties(ignoreUnknown = true)`; `wallet` no la tiene pero sobrevive porque `JsonDeserializer` usa `JacksonUtils.enhancedObjectMapper()`, que desactiva `FAIL_ON_UNKNOWN_PROPERTIES` (ningún servicio inyecta un `ObjectMapper` propio — grep vacío); `audit` deserializa a `String` → `Map` |
| 18 | **Las dos notificaciones ya existen y están bien modeladas** | `NotificationTriggerService`: `#2 WELCOME_ACTIVATED` ← `credit-account-activated`; `#3 DISBURSEMENT_COMPLETED` ← `disposition-completed` (`EventType.java:6-7`). Hoy la #3 se dispara con un `externalRef` de stub |
| 19 | **Falta la identidad del beneficiario** | `CreditAccount` tiene `clabe_account` (línea 93) pero **ningún** campo de nombre ni RFC. Tampoco los llevan `CreditProductCreationRequestedPayload` ni `CreateCreditAccountCommand` ni `RequestDispositionCommand`. STP los exige en las posiciones 14 y 16 de la cadena firmada |
| | **Sobre `fintech-services` — el resto** | |
| 20 | El repo usa **Kafka para comandos entre dominios** | `wallet.disposition-requested`, `origination.credit-product-creation-requested`, `origination.score-requested` — los tres son imperativos y van por topic |
| 21 | El REST **entre servicios de dominio** es sólo de lectura | `RestClientProductCatalogAdapter`, `RestClientPartyAdapter` y `PartyServiceClient` — los tres `.get()`. (Los BFF sí hacen `POST`/`PUT` hacia dominios, pero eso es tráfico de canal, no dominio↔dominio; y `CirculoCreditoAdapter` hace `POST` a un tercero) |
| 22 | `credit-portfolio.disposition-rejected` se publica y nadie lo consume | Sólo aparece como constante en `KafkaCreditPortfolioPublisher:32` y en un javadoc. Cero `@KafkaListener` |
| 23 | `WalletWithdrawal.markFailed()` existe sin callers | Definido en `WalletWithdrawal.java:71`; los únicos `markFailed()` invocados son los de scoring y collections |
| 24 | `wallet.payment-instruction-created` sin consumidor | Sólo el publisher y las listas de `@EmbeddedKafka` de los tests |
| 25 | El repo no tiene DLT ni retry de Kafka | Cero `@RetryableTopic`, `DeadLetterPublishingRecoverer` y `DefaultErrorHandler` en los 1398 archivos Java |
| 26 | `DomainEvent` y `@Externalized` están muertos | La única mención está en el javadoc de `shared/DomainEvent.java` |
| 27 | ShedLock no existe en el repo | Cero menciones en `.kts` y `.java`. Hay 11 `@Scheduled`, en `infrastructure/job/`, sin coordinación |
| 28 | `audit-service` no es realmente global | Escucha 17 topics; ninguno de `wallet.*`, `collections.*`, `risk.*`, `accounting.*`, `commission.*`, `invoicing.*`, `notifications.*` |
| 29 | El gateway es el único puerto de aplicación expuesto | `docker-compose.yml` tiene 6 `ports:`: postgres, redis, **gateway**, y 3 del perfil `observability` |
| 30 | El gateway sólo enruta a los dos BFF | `nginx.conf` declara `mobile_upstream` (66) y `backoffice_upstream` (73), y declara que *"los domain services NO son accesibles desde internet"* |
| 31 | Puertos 8100 y 8101 libres | Los 20 módulos de `settings.gradle.kts` ocupan 8080–8099 sin huecos |
| 32 | Numeración de dominios disponible | `docs/dominios/` llega a `09_risk_domain.md` + `T1..T7` → libres: `10_`, `11_`, `T8_` |

**Lo que NO pude verificar y hay que confirmar antes de construir:**

| Con quién | Qué |
|---|---|
| **STP** | Si el endpoint de consulta por clave de rastreo existe y es estable (**Q14**) |
| **STP** | Frecuencia máxima admitida de `V2/conciliacion` y si tiene costo por llamada (**Q15**) — **es la premisa del §10.4** |
| **STP** | Si soportan mTLS de cliente (**EG-05**) |
| Equipo | Volumen real de dispersión diario, para dimensionar el poller |
| Finanzas | Si el extracto Oracle ERP tiene consumidores vivos (**Q4**) |
| Auditoría | Si hace falta backfill del histórico (**Q6**) |
| Negocio | Cuántas empresas concretas entran el día 1 con contrato STP propio |
| Datos | Si `mae_transaction_users` guarda lo que el mapeo sugiere |

---

## Apéndice D — Hallazgos en `identity-service` y `gateway-service`

Estos nueve puntos salieron de una auditoría hecha cuando el diseño contemplaba que STP entrara por el gateway con client-credentials. **Ese diseño quedó descartado**: los dos servicios nuevos son internos y nadie externo se autentica contra la plataforma por este proyecto.

Los reporto igualmente porque **son reales, están en producción, y no dependen de este proyecto para existir**. No son trabajo de este plan; son insumo para que el equipo de plataforma decida.

| ID | Hallazgo | Severidad | Evidencia |
|---|---|---|---|
| **I-4** | Un token `channel=SERVICE` refrescado por `POST /api/v1/auth/refresh` vuelve como **`channel=MOBILE`, `roles=["CUSTOMER"]`**. `AuthService.refresh` rechaza `BACKOFFICE` pero deja pasar `SERVICE`, y llama a `issue()`, que fuerza `CUSTOMER_ROLES` + `Channel.MOBILE`. El endpoint es **público** | **Alta** — escalación lateral | `AuthService.java:174` |
| **I-2** | `/api/v1/auth/clients/token` no tiene rate limit ni lockout: `identity.clients` no tiene `failed_attempts` ni `locked_until`, a diferencia de `credentials` y `staff_users`. Es fuerza bruta ilimitada contra un secret BCrypt | **Alta** | changeset `identity:003` |
| **I-5** | `Client.roles` es `TEXT NOT NULL DEFAULT ''` **sin catálogo ni `CHECK`**. Un cliente registrado con el rol `ADMIN` obtiene `ROLE_ADMIN` y puede administrar staff y otros clientes | **Alta** | `RegisterClientRequest` sólo valida `@NotEmpty` |
| **I-3** | `ClientAuthController.extractClientIp` lee **sólo `X-Forwarded-For`**, spoofeable → la whitelist CIDR es eludible. `ClientIpResolver`, que sí lee los tres headers, no se usa ahí | Media (hoy inalcanzable) | `ClientAuthController.java:125-131` |
| **I-6** | `DELETE /auth/clients/{id}` **no revoca los tokens vivos** — sólo pone `status=DISABLED`. Cortar el acceso en caliente es esperar 15 min | Media | `Client.disable():65-68`; documentado en `docs/modules/identity/README.md:365` |
| **I-7** | El bloque móvil del gateway llama `require("jwt").validate()` **sin canal** → un token `SERVICE` o `BACKOFFICE` abre `mobile.*` | Media | `nginx.conf:263` |
| **I-9** | El gateway **no consulta la revocación**: los 11 pasos de `jwt.lua` no tocan Redis. `isTokenActive` existe pero sólo se invoca desde `AuthService.validate()` (`:198`), que el gateway nunca llama. Un token revocado abre la puerta hasta 15 min | Media | `jwt.lua` |
| **I-8** | `identity.clients` no tiene `last_used_at` ni bitácora de emisión | Baja | changeset `identity:003` |
| **I-1** | El gateway no expone `/auth/clients/token`: hoy el flujo M2M es **inalcanzable desde internet** | — | Es lo que hace que I-2, I-3 e I-4 no sean explotables hoy |

> **La relación entre I-1 y el resto es lo importante:** el flujo máquina-a-máquina de identity está completo y funcional, pero no está expuesto. Eso es lo único que impide que I-2, I-3 e I-4 sean explotables. **El día que alguien abra ese endpoint —por este proyecto o por otro— hay que cerrar los tres primero.**
