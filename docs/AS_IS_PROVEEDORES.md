# As-is: los proveedores externos, tal como están hoy

**Todo lo de abajo sale del código o de la base corriendo.** Donde algo no existe, lo dice — no se
describe lo que debería haber.

---

## 1. Incode: no existe

`grep -ri incode` sobre el código devuelve **cuatro comentarios** y ningún byte de integración:

| Dónde | Qué dice |
|---|---|
| `KycProviderPort` | *«El proveedor externo de KYC (Incode, hoy sin contrato)»* |
| `VerificationConfig` | *«El día que llegue Incode se registra su adaptador como bean»* |
| `UnavailableKycProviderAdapter` | *«El proveedor que no existe todavía. No hay contrato con Incode»* |
| `OnboardingController` | `// TODO: integrar con proveedor OCR (Incode, Jumio, etc.)` |

> **No hay endpoints, ni métodos, ni payloads que mapear.** Incode es hoy el **nombre de referencia**
> del proveedor de KYC, no una integración.

Lo que sí existe es el hueco donde iría, y está bien hecho — ver §2.

---

## 2. KYC, as-is

### La forma

Dos puertos, separados a propósito:

```
IdentityVerificationGateway   ← la POLÍTICA: qué se hace con el resultado
KycProviderPort               ← la INTEGRACIÓN: pedirle al proveedor que evalúe
```

El propio código explica por qué: *«separarlos es lo que permite probar la política de degradación
sin un proveedor, y cambiar de proveedor sin tocar la política»*.

### Qué corre hoy

`UnavailableKycProviderAdapter`, registrado con `@ConditionalOnMissingBean` — se retira solo el día
que aparezca el real:

```java
public Assessment assess(UUID placementId, UUID prospectId) {
    return Assessment.unavailable("Sin contrato con proveedor de KYC");
}
public String providerName() { return "NINGUNO"; }
```

### El contrato del puerto es explícito sobre el fallo

`Assessment.available` es un campo, no una excepción. El javadoc lo justifica: *«un proveedor de KYC
se cae, tarda, o devuelve basura; el contrato lo dice explícitamente en vez de dejar que una
excepción se escape»*. Y `assess` **promete no lanzar**.

### 🔎 Qué pasa si el proveedor no responde nunca

| Escenario | Hoy |
|---|---|
| Proveedor no disponible | `Assessment.unavailable` → la política devuelve `requiresHumanReview("PROVEEDOR_NO_DISPONIBLE: …")` |
| El adaptador lanza pese a la promesa | `catch (RuntimeException)` → `requiresHumanReview("PROVEEDOR_ERROR: …")`. El comentario dice por qué existe: *«que un proveedor mal portado tumbe una colocación es justo lo que este bloque impide»* |
| **Cuelga sin responder** | **No hay timeout configurado en este puerto.** No importa hoy porque el adaptador responde en memoria, sin red. **El día que haya proveedor real, este es el hueco** |

**Conclusión honesta:** la ruta de degradación está escrita, probada y **se ejerce en cada
colocación** —porque el proveedor siempre reporta indisponible—. Lo que **no** está resuelto es el
colgado: hoy es inalcanzable porque no hay llamada de red.

---

## 3. Buró de crédito, as-is — **corrijo lo que dije antes**

En el plan anterior escribí que el buró es *«síncrono, petición-respuesta de dos segundos»*. **Es
incorrecto como as-is.** El flujo real:

```
origination.prospect-created
        │
        ▼
ProspectCreatedEventListener  (scoring)
        │
        ▼
BureauPrefetchService.initiate()  ──HTTP──▶ Círculo
        │                                      │
        │ guarda CirculoReport                 │
        ▼                                      ▼
   markCompleted()                        markFailed(motivo)
```

Y **scoring no llama al buró al evaluar**: lee lo que el prefetch dejó.

```java
// ScoringEvaluationService
// El prefetch de onboarding aún no ha completado (o no hubo consentimiento).
// TODO: prefetch on-demand / reintento. Por ahora se omite — la aplicación queda PENDING_SCORING.
```

Es decir: **ya hay una frontera asíncrona**, disparada por evento. Lo que no hay es un conector
separado ni ruteo de proveedor.

### Qué corre hoy

| | |
|---|---|
| Adaptador real | `CirculoCreditoAdapter` — existe, apunta al **sandbox** de Círculo por defecto |
| Adaptador de dev | `MockCirculoAdapter` — reporte determinista por CURP, **siempre `SUCCESS`** |
| Selector | `SCORING_CIRCULO_MOCK_ENABLED`, hoy **`true`** en el compose |
| Timeout | `CIRCULO_CREDITO_TIMEOUT_SECONDS`, por defecto **30 s** |
| Estado en la base | **117 prefetches, todos `COMPLETED`** — porque el mock siempre acierta |

> Nota: la clave de API del sandbox viene **con valor por defecto en el `yml` y en el compose**.
> Es de sandbox, pero está en el repositorio.

### 🔎 Qué pasa si el proveedor no responde nunca

Éste es el que sí tiene respuesta medible, y no es buena:

1. La llamada corta a los **30 s** (timeout configurado).
2. `catch (Exception)` → `prefetch.markFailed(motivo)`. **Se guarda el fallo.**
3. **El prefetch no se reintenta.** No hay `@Scheduled` en scoring ni cola de reproceso.
4. Cuando el prospecto pide un crédito, `ScoreRequestedEventListener` **sí reintenta**:

   ```java
   for (int attempt = 1; attempt <= retryAttempts; attempt++) {   // 5 por defecto
       …
       Thread.sleep(retryBackoffMs);                              // 600 ms
   }
   ```

   **Pero está diseñado para una carrera, no para un fallo.** Cinco intentos por 600 ms son **tres
   segundos**: cubren «el prefetch va en camino y aún no aterriza». Si el prefetch terminó en
   `FAILED` en el onboarding —hace horas o días—, esperar tres segundos no cambia nada.
5. Agotados los cinco, se registra un `WARN` y el mensaje se consume. La solicitud queda en
   `PENDING_SCORING`.

> 🔴 **Hay reintento para la carrera y no para el fallo.** Un prospecto cuyo prefetch de buró
> terminó en `FAILED` consume sus cinco intentos en tres segundos, cae al `WARN`, y **nada vuelve a
> intentarlo nunca**. El fallo quedó registrado con su motivo, y sin dueño.

Dos apuntes sobre ese bucle, ninguno de los cuales es el fallo principal:

- El `Thread.sleep` está **dentro del listener de Kafka**, así que bloquea la partición tres
  segundos por solicitud. Queda muy por debajo de `max.poll.interval.ms`, así que no provoca
  rebalanceos — pero es rendimiento que se paga en el camino feliz para cubrir una carrera.
- La distinción «no hay reporte» / «no hay política activa» se pierde: las dos salen por el mismo
  `Optional.empty()` y el mismo `WARN`. Son problemas distintos con dueños distintos.

Es el mismo patrón que los tres defectos caros de la rama: se registra, no se avisa, nadie mira.

---

## 4. Firma electrónica, as-is

| | |
|---|---|
| Puerto | `SignatureValidator` — nombre neutral |
| Adaptador | `NoopSignatureValidator` → **`return true`** |
| Qué valida | nada |
| Si el proveedor no responde | **no aplica: no hay proveedor ni llamada** |

`ContractService` sí lo invoca y sí lanza si devuelve falso. La ruta existe; lo que no existe es
algo que pueda devolver falso.

---

## 5. Resumen del as-is, en una tabla

| Capacidad | Proveedor | Frontera | Si no responde nunca |
|---|---|---|---|
| **Desembolso SPEI** | `stp` (servicio) | asíncrona, con outbox y reintentos | `NEVER_SETTLES` existe como escenario; **nada lo vigila** |
| **Buró** | `CirculoCreditoAdapter` en proceso | asíncrona por evento (prefetch) | timeout 30 s → `markFailed` → **sin reintento**, solicitud en `PENDING_SCORING` para siempre |
| **KYC** | ninguno | síncrona en memoria | inalcanzable hoy; la política degrada a revisión humana |
| **Firma** | ninguno | síncrona en memoria | inalcanzable hoy |

### Lo que este as-is deja claro

1. **La frontera asíncrona no es exclusiva de `stp`.** El buró ya la tiene, disparada por evento.
   Lo que le falta no es asincronía: es **reintento, vigilancia y ruteo**.
2. **Dos de las cuatro capacidades no tienen proveedor**, así que su comportamiento ante un colgado
   es hoy **teórico**. La política está escrita y probada; el timeout no existe porque no hay red.
3. **El único fallo con consecuencia medible hoy es el del buró**, y su consecuencia es que un
   prospecto se queda sin decisión sin que nadie se entere.
