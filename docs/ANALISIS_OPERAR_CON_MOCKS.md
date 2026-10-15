# Qué falta para operar, y para tener E2E de verdad con los proveedores

**Verificado contra el código**, no derivado del diseño. Cada cifra sale de un `grep` o de una
consulta, y el comando está al lado.

---

## 1. El inventario: un proveedor de ocho se comporta como un proveedor

El plan fijó la regla en D5: *«Todo proveedor tiene mock con escenarios. Nunca hay un camino que
confirme y ya.»* Se cumplió en uno.

| Proveedor | Adaptador | Escenarios | Qué hace hoy |
|---|---|---|---|
| **SPEI / STP** | `StubStpGateway` | **11** | ✅ liquida, rechaza por PLD, devuelve, nunca liquida, nombre distinto, firma inválida, timeout, clave duplicada |
| Buró de crédito | `MockCirculoAdapter` | **0** | se llama *Mock* y responde siempre lo mismo |
| Firma electrónica | `NoopSignatureValidator` | 0 | `return true` — **cualquier firma es válida** |
| Timbrado CFDI (PAC) | `NoopPacAdapter` | 0 | no timbra |
| Reporte a buró | `NoopBureauReportingAdapter` | 0 | no reporta |
| Correo | `NoopEmailAdapter` | 0 | `return true` |
| WhatsApp | `NoopWhatsAppAdapter` | 0 | `return true` |
| Push | `NoopPushAdapter` | 0 | `return true` |
| Geolocalización | `StubGeoLocationAdapter` | 0 | fija |
| **KYC / identidad** | *no existe* | — | deuda declarada: sin contrato con proveedor |

> **La consecuencia, dicha sin rodeos:** hoy sólo se puede probar de punta a punta el **camino
> feliz** de todo salvo SPEI. Cualquier E2E que diga «qué pasa cuando el proveedor falla» es real
> únicamente para el desembolso.

### Por qué importa cada `Noop`, en concreto

Un `Noop` no es un mock incompleto: es un mock que **miente en la dirección peligrosa**, porque
siempre dice que sí. Lo que cada uno vuelve imposible de probar:

| Adaptador | El fallo que nadie puede ejercitar | Qué cuesta en producción |
|---|---|---|
| `NoopSignatureValidator` | una firma inválida o repudiada | se otorga un crédito con un contrato que no se sostiene |
| `MockCirculoAdapter` | buró caído, sin historial, respuesta lenta | el scoring decide sin saber que decidió a ciegas |
| `NoopPacAdapter` | el PAC rechaza el timbre o tarda | facturas emitidas que el SAT no tiene |
| `NoopBureauReportingAdapter` | el reporte no llega | quebrantos que el buró nunca supo |
| `Noop{Email,WhatsApp,Push}` | rebote, número inválido, token caducado | el aviso legal se da por entregado y no llegó |

Los tres últimos son especialmente engañosos: el sistema **registra el envío como exitoso**, así
que la traza dice que se notificó.

---

## 2. Lo que ningún mock resuelve, y es lo que de verdad impide operar

La pregunta era «qué falta para **operar**». Los mocks son la mitad menos importante.

Medido:

```
consumidores de DLT ................................. 0
endpoint para reprocesar una DLT .................... 0
consumidores de alertas de negocio .................. 0
runbooks ............................................ 0
```

**Lo sé de primera mano.** Durante esta revisión hubo que:

- **reponer una DLT a mano** con `kafka-console-producer`, leyendo el JSON y volviéndolo a publicar;
- **reparar 16 CLABE con `UPDATE`** directo sobre la base;
- **resetear filas del outbox** de `FAILED` a `PENDING` porque se habían agotado los reintentos;
- **reiniciar servicios** para que releyeran configuración.

Ninguna de esas cuatro cosas es una operación: son un ingeniero con una consola. En un ambiente
real, cada una es un incidente sin procedimiento.

### Las cuatro piezas que faltan

| # | Pieza | Por qué no es opcional |
|---|---|---|
| **O-1** | **Alguien mira las DLT** | Hoy un mensaje muere ahí y **nadie se entera**. Los defectos #3, #5 y #6 de esta rama vivieron semanas exactamente así |
| **O-2** | **Reproceso operable** | Un botón —o un endpoint con autorización y bitácora— que reponga una DLT. Hoy exige acceso a Kafka y saber el formato |
| **O-3** | **Alertas de negocio con destinatario** | `reconciliation-alert` se publica y **no lo consume nadie**. Una alerta sin destinatario es un log con otro nombre |
| **O-4** | **Vigilancia de lo colgado** | Una orden `DISPATCHED` que no liquida en N horas, una disposición `PROCESSING` sin orden, un sello que no cuadra. El escenario `NEVER_SETTLES` del stub existe **para probar esto**, y no hay nada que lo detecte |

---

## 3. Qué haría falta para un E2E con proveedores, de verdad

Un E2E creíble necesita **tres** cosas que hoy no están juntas:

1. **Que el proveedor pueda fallar** — hoy sólo STP.
2. **Que el fallo tenga un camino de vuelta** — el `RETURNED` de STP sí lo tiene (cartera revierte);
   un rebote de correo no tiene a dónde ir.
3. **Que el escenario se pueda elegir sin recompilar.** STP lo resuelve con los centavos del importe,
   que es ingenioso y tiene un techo: no sirve donde no hay importe. Para buró, firma o PAC hace
   falta otro selector.

### El selector, que es la decisión de diseño a tomar primero

| Opción | A favor | En contra |
|---|---|---|
| **Por dato del caso** (centavos, un sufijo en el RFC, un nombre) | cero infraestructura; el escenario viaja con el dato y la siembra lo fija | sólo sirve si hay un dato con hueco; ensucia los datos de prueba |
| **Por cabecera o perfil** | explícito y legible | no sobrevive a un salto asíncrono: el aviso se manda tres servicios después |
| **Por tabla de escenarios** en el propio mock | funciona en asíncrono, se cambia sin desplegar, y es **auditable** | es un servicio más que mantener |

**Recomendación:** tabla de escenarios. Los caminos que importan —desembolso, timbrado, aviso— son
todos asíncronos, y ahí la cabecera no llega.

---

## 4. El orden que propondría, y por qué

### Primero lo que impide operar, no lo que impide probar

**O-1 y O-4 antes que cualquier mock.** Sin ellos, la plataforma no es operable ni con proveedores
reales: un pago que se para no lo ve nadie. Y son baratos —un consumidor de DLT que publica una
alerta, y una consulta programada de lo colgado— comparados con lo que evitan.

Los tres defectos más caros de esta rama fueron **silenciosos**. El cuarto —el mock que no podía
firmar— se vio en minutos, y la única diferencia era que el outbox **sí** dejaba rastro y
reintentaba. Esa diferencia es exactamente O-1.

### Después, los mocks por lo que arriesgan

| Orden | Proveedor | Por qué ahí |
|---|---|---|
| 1 | **Firma electrónica** | `return true` sobre lo único que hace exigible un contrato. Es el mayor riesgo por línea de código del repositorio |
| 2 | **Buró** | El scoring decide con él. Sin escenarios no se puede probar qué hace el sistema cuando el buró no contesta — y eso pasa |
| 3 | **PAC** | Obligación fiscal con plazo. Una factura no timbrada es un problema con fecha |
| 4 | **Canales de aviso** | Alto volumen, bajo riesgo unitario. Pero hoy se registra como entregado lo que no salió |
| 5 | **Reporte a buró** | Obligación con plazo, menos inmediata |

### Y una decisión que no es técnica

**KYC no tiene proveedor contratado.** No es que falte el mock: falta el contrato. Todo el análisis
de arriba asume que se sabe *qué* se va a simular, y ahí no se sabe. Conviene separarlo del resto
para que no bloquee lo demás.

---

## 5. Lo que ya está bien y no hay que rehacer

Para no gastar esfuerzo dos veces:

- **`StubStpGateway` es la plantilla.** Once escenarios, selección determinista, y **se comporta
  como STP incluido fallar**. Lo que falta es replicar su enfoque, no inventarlo.
- **El outbox de STP está bien hecho**: reintenta con retroceso, registra el error y deja rastro. Es
  la razón por la que su fallo se vio en minutos y los otros tardaron semanas.
- **Las proyecciones de notifications** hacen que no haga falta llamar a nadie para enriquecer un
  aviso. Un mock de canal no necesita infraestructura extra.
- **`scripts/siembra-completa.sh` y los cinco verificadores** ya recorren el sistema de punta a
  punta. Un E2E con proveedores se monta encima, no desde cero.
