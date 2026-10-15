# Proveedores por subdominio: el patrón `disbursement ↔ stp`, replicado

**Verificado contra el código.** El patrón existe implementado **una vez**. Este plan lo extrae, lo
mide contra el resto y propone replicarlo donde corresponde — que no es en todas partes igual.

---

## 1. Qué hace que `disbursement ↔ stp` funcione

No es «un servicio por proveedor». Son **cuatro** propiedades, y las cuatro importan:

| # | Propiedad | Cómo está impuesta hoy |
|---|---|---|
| **P1** | El **proceso** es dueño del contexto y decide *qué* hay que hacer | `disbursement` conoce la orden, la idempotencia y la máquina de estados |
| **P2** | El **conector** no sabe de crédito | `StpDecouplingTest`: *«debe poder extraerse a otro repositorio sin borrar un solo archivo»* |
| **P3** | Hablan por **hechos**, no por llamadas | `disbursement.stp-requested` → `stp.order-{accepted,rejected,settled,returned}` |
| **P4** | **Quién** ejecuta lo decide un tercero, no el proceso ni el conector | `banking.payout_routes` devuelve rail + proveedor |

**P4 es la que evita el lock-in**, y es la que suele olvidarse. Sin ella, «cambiar de proveedor» es
cambiar una inyección de dependencias — que funciona hasta que hacen falta **dos a la vez**, que es
lo normal: uno de respaldo, uno por región, uno por costo.

---

## 2. El inventario contra ese patrón

| Capacidad | Proceso | Conector | P1 | P2 | P3 | P4 |
|---|---|---|---|---|---|---|
| **Desembolso SPEI** | `disbursement` | `stp` | ✅ | ✅ | ✅ | ✅ |
| **Verificación de identidad (KYC)** | `beneficiary` | *ninguno* | ✅ | — | 🔴 síncrono | 🔴 |
| **Buró de crédito** | `scoring` | *en proceso* | ✅ | 🔴 | 🔴 síncrono | 🔴 |
| **Firma electrónica** | `origination` | *en proceso* | ✅ | 🔴 | 🔴 síncrono | 🔴 |

### Lo que ya está bien y no hay que rehacer

**`beneficiary` tiene la forma correcta.** `KycProviderPort` e `IdentityVerificationGateway` son
nombres neutrales, y detrás hay un `UnavailableKycProviderAdapter` que **reporta indisponibilidad**
—la ruta de degradación ya se ejerce— y un `ConfigurableIdentityVerificationGateway`. Le falta el
proveedor, no el diseño.

**`origination` también**: `SignatureValidator` es neutral.

### 🔴 Lo que está mal y es el ejemplo del problema

**`scoring` lleva el nombre del vendor en el tipo, no en el adaptador:**

```java
public interface CirculoGateway {
    CirculoReport query(UUID reportId, …, CirculoQueryRequest request);
}
```

Puerto, respuesta y petición, los tres. Cambiar de buró no es escribir otro adaptador: es tocar el
dominio. **Eso es lock-in de verdad** — el que no se ve hasta que se intenta salir.

---

## 3. La observación que decide la arquitectura: un proveedor, varias capacidades

Incode hace **KYC y firma electrónica**. Si el conector se organiza por *capacidad*, se integra
Incode dos veces: dos autenticaciones, dos manejos de sesión, dos webhooks, dos juegos de
credenciales que rotan por separado.

> **El conector es por proveedor. La capacidad es del subdominio.**

```
                    ┌──────────────┐
   beneficiary ────▶│              │
   (KYC)            │   incode     │──▶ Incode
   origination ────▶│  (conector)  │
   (firma)          └──────────────┘

   scoring     ────▶ circulo ──▶ Círculo de Crédito
                     (conector)
   disbursement ───▶ stp ──▶ SPEI            ← ya existe
```

Es exactamente el reparto que ya tiene `stp`: **un** conector que expone registrar orden y consultar
conciliación, y podría exponer más sin que `disbursement` cambie.

---

## 4. Qué transporte para cada uno — y por qué NO todos son un servicio

Copiar `stp` en todas partes sería cargar con su complejidad donde no hace falta. La regla:

> **El transporte lo decide la naturaleza de la interacción, no el gusto por la simetría.**

| Capacidad | Duración | Transporte | Por qué |
|---|---|---|---|
| **Desembolso** | horas (liquidación) | servicio + eventos | ya está |
| **KYC** | minutos u horas — el usuario completa una sesión en webview y hay callback | **servicio + eventos** | la respuesta llega **después** y por fuera; un `RestClient` síncrono no tiene dónde recibirla |
| **Firma** | igual: ceremonia con callback | **el mismo servicio `incode`** | comparte sesión, credenciales y webhook con KYC |
| **Buró** | segundos la consulta, pero **ya es asíncrono**: prefetch disparado por `prospect-created` | **adaptador en proceso, como hoy** | la frontera asíncrona ya existe; lo que falta no es transporte sino reintento, vigilancia y ruteo |

> ⚠️ **Corrección a una versión anterior de este documento.** Aquí decía que el buró es «síncrono,
> petición-respuesta de dos segundos». **Es incorrecto como as-is**, y lo desmiente
> [`AS_IS_PROVEEDORES.md`](AS_IS_PROVEEDORES.md) §3: el buró se consulta en un **prefetch disparado
> por evento** (`origination.prospect-created`), y `scoring` **no llama al buró al evaluar** — lee lo
> que el prefetch dejó. La llamada HTTP es síncrona *dentro* de ese listener, que no es lo mismo.

**El buró es la excepción a propósito**, y conviene decirlo: no necesita servicio, porque la
frontera asíncrona **ya la tiene**. Lo que necesita es **dejar de llamarse Círculo** —el puerto, el
DTO y la petición llevan el nombre del vendor— y que el fallo del prefetch tenga dueño.

---

## 5. Dónde vive la elección de proveedor (P4)

Hoy la elige la inyección de Spring. Eso no es una decisión de negocio, es un despliegue.

`banking.payout_routes` ya resuelve esto para pagos: `(empresa, monto, rail, beneficiario)` → cuenta
+ rail + proveedor, con desempate por prioridad y especificidad, y **regla nueva = un `INSERT`**.

Para el resto hace falta lo mismo, y **no debería ser una tabla por capacidad**: una sola, en
`configuration-service`, que responda

```
(capacidad, contexto)  →  proveedor
```

Ejemplos de contexto que ya existen y que un día van a importar: el país del prospecto, el tipo de
persona (física o moral), el monto, la unidad de origen. Y el caso que **siempre** llega:
*«Incode está caído, manda todo a X durante dos horas»* — que con inyección de dependencias es un
despliegue y con tabla es un `UPDATE`.

> **La prueba de que P4 está bien resuelto:** poder tener **dos proveedores activos a la vez** para
> la misma capacidad. Si sólo se puede uno, es configuración, no ruteo.

---

## 6. El plan, por fases

### Fase 0 · Quitar el lock-in que ya existe — ~0.5 día, sin proveedor nuevo

Renombrar en `scoring`: `CirculoGateway` → `BureauGateway`, `CirculoReport` → `BureauReport`,
`CirculoQueryRequest` → `BureauQuery`. El adaptador **sí** se llama `CirculoAdapter`, que es donde
el nombre del vendor pertenece.

**Va primera porque no depende de nadie** y porque hasta que no esté, cualquier segundo buró obliga
a tocar el dominio. Es el cambio de mayor relación valor/esfuerzo del plan.

### Fase 1 · La tabla de ruteo de proveedores — ~1 día

`(capacidad, contexto) → proveedor` en `configuration-service`, con desempate por especificidad,
como `payout_routes`. Sin proveedores nuevos todavía: se siembra con los actuales y **nada cambia
de comportamiento**. Es el andamio.

*Prueba:* con dos filas para la misma capacidad, gana la más específica; sin fila aplicable, falla
explícito y no elige uno por omisión — como `CompanyResolutionService`, que aprendió eso a golpes.

### Fase 2 · `incode-service`, el conector — ~3 días

Un servicio, dos capacidades, mismas cuatro propiedades que `stp`:

- **P2** con su `IncodeDecouplingTest`: no conoce prospectos, beneficiarias ni contratos.
- **P3** con sus tópicos: `beneficiary.kyc-requested` → `incode.kyc-{approved,rejected,expired}`;
  `origination.signature-requested` → `incode.signature-{completed,declined,expired}`.
- El **webhook** entra por aquí y sale como hecho. Es la razón de que sea un servicio: hoy no hay
  dónde recibir la respuesta.

*Prueba:* `StubIncodeGateway` con escenarios, al estilo de `StubStpGateway` — aprobado, rechazado
por prueba de vida, documento ilegible, sesión expirada, webhook duplicado, webhook fuera de orden.

### Fase 3 · Conectar los dos subdominios — ~2 días

`beneficiary` y `origination` pasan de llamada síncrona a hecho + espera. Es el cambio más
delicado: **la máquina de estados de los dos crece un estado** —«esperando al proveedor»— y hay que
decidir qué pasa si no llega nunca. `disbursement` ya resolvió esa pregunta y su respuesta sirve.

### Fase 4 · Que se pueda cambiar de proveedor de verdad — ~1 día

La prueba que cierra el plan: **un segundo proveedor de mentira** —`stub-b`— y un caso E2E que
cambia el ruteo a mitad y comprueba que las sesiones en vuelo terminan con el primero y las nuevas
salen por el segundo.

Sin esta fase, P4 es una tabla que nadie ejerció.

---

## 7. Lo que hay que decidir antes de escribir código

| # | Decisión | Por qué no la puedo tomar yo |
|---|---|---|
| 1 | **¿Incode hace de verdad las dos cosas en el contrato que se firme?** | Todo el plan supone un conector; si son dos productos con dos integraciones, son dos conectores |
| 2 | **¿Qué pasa si el proveedor no responde nunca?** | En KYC se puede esperar; en firma, el contrato queda a medias. Es una decisión de negocio con consecuencia legal |
| 3 | **¿El buró se queda síncrono?** | Recomiendo que sí, pero si se quiere prefetch masivo nocturno cambia a evento |
| 4 | **¿Quién puede cambiar el ruteo en caliente?** | Con maker-checker es más lento y auditable; sin él, más rápido y sin rastro. En un incidente esa diferencia se nota |

---

## 8. Lo que este plan **no** hace

- **No monta un servicio por proveedor por simetría.** El buró se queda en proceso, con su nombre
  arreglado. Simetría que añade latencia y modos de fallo no es arquitectura, es estética.
- **No resuelve la operación.** Un conector nuevo hereda los mismos huecos: sin consumidor de DLT,
  sin reproceso operable, sin vigilancia de lo colgado. Está en
  [`ANALISIS_OPERAR_CON_MOCKS.md`](ANALISIS_OPERAR_CON_MOCKS.md), y **debería ir antes que la fase 2**:
  duplicar conectores antes de saber mirarlos duplica también lo que se falla en silencio.
