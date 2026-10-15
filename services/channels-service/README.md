# channels-service (D1b)

**Captación y enrutamiento.** Gestiona sesiones de negocio, captura la intención del cliente y la
enruta al dominio que corresponde. **No toma decisiones crediticias**: su responsabilidad termina
al emitir `channels.application-started`.

| | |
|---|---|
| **Puerto** | `8091` (bootRun) · `:8080` interno en Docker |
| **Schema** | `channels` |
| **Auth** | **Header-trust** — `/api/v1/channels` exige `ADMIN` o `SUPPORT` |
| **Régimen Kafka** | Sólo produce (no consume) |
| **Dominio** | [docs/dominios/01_channels_domain.md](../../docs/dominios/01_channels_domain.md) |

## Mapa del servicio

```mermaid
flowchart LR
    subgraph ch["Canales de entrada"]
        C1["MOBILE_APP · WEB"]
        C2["BRANCH · FIELD_PROMOTER · CORRESPONDENT"]
        C3["API_B2B · IVR · WHATSAPP"]
    end

    C1 & C2 & C3 --> API["REST /api/v1"]

    subgraph svc["channels-service"]
        API --> SES["SessionService<br/>ciclo de la sesión"]
        API --> INT["Captura y routing de intents"]
        API --> LEAD["Leads de campo"]
        DEV["ClientIpExtractor<br/>DeviceContext server-side"]
        API --- DEV
    end

    INT -->|"GET /api/v1/parties/{id}"| PTY["party-service<br/>verificación de lista negra"]
    SES & INT & LEAD --> DB[("PostgreSQL<br/>schema channels")]
    INT --> K1["channels.application-started"] --> ORI["origination-service"]
    SES & INT & LEAD --> K2["session-started · session-expired<br/>intent-captured · intent-routed · intent-abandoned<br/>lead-created · lead-converted"]
```

## Tipos de Canal

| Canal | Intents permitidos | TTL sesión |
|---|---|---|
| `MOBILE_APP` | CREDIT_APPLICATION, PAYMENT, ACCOUNT_INQUIRY, REFINANCING, SUPPORT | 30 min |
| `WEB` | CREDIT_APPLICATION, PAYMENT, ACCOUNT_INQUIRY, REFINANCING, SUPPORT | 30 min |
| `API_B2B` | CREDIT_APPLICATION, REFINANCING | 60 min |
| `BRANCH` | todos | 120 min |
| `FIELD_PROMOTER` | CREDIT_APPLICATION | 60 min |
| `CORRESPONDENT` | CREDIT_APPLICATION, PAYMENT | 60 min |
| `IVR` | PAYMENT, ACCOUNT_INQUIRY, SUPPORT | 15 min |
| `WHATSAPP` | CREDIT_APPLICATION, ACCOUNT_INQUIRY, SUPPORT | 60 min |

## Autenticación

Header-trust — el gateway inyecta `X-User-Id` y `X-Roles`. Todos los endpoints requieren autenticación. `/api/v1/channels` requiere rol `ADMIN` o `SUPPORT`.

## Endpoints

| Método | Path | Descripción |
|---|---|---|
| `GET` | `/api/v1/channels` | Canales activos (ADMIN) |
| `POST` | `/api/v1/sessions` | Iniciar sesión de negocio |
| `GET` | `/api/v1/sessions/{id}` | Consultar sesión |
| `PUT` | `/api/v1/sessions/{id}/close` | Cerrar sesión |
| `POST` | `/api/v1/sessions/{id}/intents` | Capturar intent |
| `PUT` | `/api/v1/sessions/{id}/intents/{intentId}/route` | Enrutar intent |
| `PUT` | `/api/v1/sessions/{id}/intents/{intentId}/abandon` | Abandonar intent |
| `POST` | `/api/v1/leads` | Registrar lead (campo) |
| `GET` | `/api/v1/leads/{id}` | Consultar lead |
| `PUT` | `/api/v1/leads/{id}/convert` | Convertir lead |

## Device Context

Cada sesión captura el contexto completo del dispositivo para trazabilidad regulatoria y detección de fraude. Todos los campos de riesgo se extraen del lado servidor — el cliente **no puede falsificar la IP ni el User-Agent**.

| Campo | Fuente | Descripción |
|---|---|---|
| `deviceId` | Cliente (SDK) | Identificador único del dispositivo |
| `deviceType` | SDK o inferido de UA | MOBILE / TABLET / DESKTOP / UNKNOWN |
| `deviceModel` | Cliente (SDK) | Modelo del dispositivo |
| `deviceManufacturer` | Cliente (SDK) | Fabricante |
| `os` / `osVersion` | Cliente (SDK) | Sistema operativo y versión |
| `appVersion` / `sdkVersion` | Cliente (SDK) | Versión de la app / SDK de integración |
| `networkType` | Cliente (SDK) | WIFI / LTE / 5G / ETHERNET |
| `userAgent` | Header `User-Agent` (servidor) | Siempre server-side |
| `browser` / `browserVersion` | Parseado del User-Agent | Chrome / Firefox / Safari / Edge / Opera |
| `ipAddress` | `X-Forwarded-For[0]` → `X-Real-IP` → `RemoteAddr` | Cadena de proxies estándar |
| `ipCountry` | Header `X-Country-Code` (gateway GeoIP) | ISO 3166-1 alpha-2 |
| `isTrustedDevice` | Cliente (SDK) | Dispositivo registrado previamente |
| `isRooted` / `isEmulator` | SDK auto-reportado | Indicadores de riesgo — limitación: spoofable en ataques avanzados |

### Extracción de IP

```mermaid
flowchart LR
    A["X-Forwarded-For:<br/>client-ip, proxy1, proxy2"] -->|"primer elemento"| IP["ipAddress"]
    B["X-Real-IP"] -->|"si no hay XFF"| IP
    C["REMOTE_ADDR"] -->|"último recurso, sin proxy"| IP
```

La lógica vive en `ClientIpExtractor` (util público, testeable de forma aislada).

---

## Reglas clave

- **CH-01** Canal `DISABLED` no genera sesiones
- **S-03** Una sola sesión activa por `partyId+channelType` — nueva sesión expira la anterior
- **IR-02** `Party.status=BLACKLISTED` → `IntentAbandoned(PARTY_BLACKLISTED)` — `ApplicationStarted` **nunca** se emite
- **IR-03** `intentType` debe estar en `Channel.allowedIntents`
- **L-02** Lead `CONVERTED` es inmutable

## Eventos publicados

| Topic | Cuándo |
|---|---|
| `channels.session-started` | Al iniciar sesión |
| `channels.session-expired` | Al expirar o cerrar sesión |
| `channels.intent-captured` | Al capturar intent |
| `channels.intent-routed` | Al enrutar intent |
| `channels.intent-abandoned` | Al abandonar intent (incluye BLACKLISTED) |
| `channels.application-started` | Intent CREDIT_APPLICATION o REFINANCING enrutado con partyId presente |
| `channels.lead-created` | Al registrar lead |
| `channels.lead-converted` | Al convertir lead |

## Flujo principal

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente / promotor
    participant CH as channels
    participant P as party-service
    participant O as origination

    C->>CH: POST /api/v1/sessions
    CH->>CH: CH-01 canal habilitado · S-03 expira la sesión previa
    CH->>CH: Session ACTIVE (+ DeviceContext server-side)
    CH-->>O: channels.session-started
    C->>CH: POST /sessions/{id}/intents (CREDIT_APPLICATION)
    CH->>CH: IR-03 intentType permitido en el canal → Intent CAPTURED
    C->>CH: PUT /intents/{intentId}/route
    CH->>P: GET /api/v1/parties/{partyId}
    alt party BLACKLISTED (IR-02)
        P-->>CH: status = BLACKLISTED
        CH-->>O: channels.intent-abandoned (PARTY_BLACKLISTED)
        Note over CH,O: application-started NUNCA se emite
    else party apto
        CH->>CH: Intent ROUTED
        CH-->>O: channels.intent-routed
        CH-->>O: channels.application-started
        O->>O: crea CreditApplication en PENDING_SCORING
    end
```

## Tests

```
./gradlew :channels:test
# SessionServiceTest      — 5 unit (Mockito): startSession, S-03, CH-01, not found, closeSession
# ChannelControllerTest   — 8 @WebMvcTest: 401/403/200 channels, 201/401/400/404 sessions, 400 intents
# SessionControllerIpTest — 7 unit: X-Forwarded-For, X-Real-IP, RemoteAddr, IPv6, whitespace, single IP, blank header
# Total: 20 ✅
```



---

## Eventos Kafka

**Consume:** ninguno.

**Produce:** `channels.application-started` → **origination**. Los demás
(`session-started`, `session-expired`, `intent-captured`, `intent-routed`, `intent-abandoned`,
`lead-created`, `lead-converted`) **no tienen consumidor hoy**: quedan como traza de captación y
analítica.

**Reintentos:** régimen por defecto, sin DLT.
Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

---

## IR-02 · el control de lista negra falla CERRADO

Un intent de una parte en lista negra se abandona antes de llegar a originación. Y si **no se puede
comprobar**, el intent tampoco avanza.

```mermaid
flowchart LR
    classDef ok fill:#bbf7d0,stroke:#166534,color:#052e16,font-weight:bold
    classDef gt fill:#fef08a,stroke:#854d0e,color:#422006,font-weight:bold
    classDef no fill:#fecaca,stroke:#991b1b,color:#450a0a,font-weight:bold

    I(["routeIntent"]):::ok --> C{"consultar estatus<br/>en party"}:::gt
    C -->|"party no contesta"| U["RECHAZA el ruteo<br/>reintentable"]:::no
    C -->|"BLACKLISTED"| A["ABANDONA el intent<br/>con motivo"]:::no
    C -->|"limpio"| R(["enruta"]):::ok
```

**No saber no es rechazar, y tampoco es aprobar.** Un party en lista negra *abandona* el intent con
motivo, porque se sabe. Uno que no se pudo comprobar *rechaza el enrutamiento* —reintentable en
cuanto party conteste— en vez de abandonar, que afirmaría algo que nadie comprobó.

> Antes esta rama registraba un `WARN` y **continuaba sin comprobar**. Para un control regulatorio la
> polaridad estaba invertida: con el screening de listas como condición para colocar, fallar abierto
> significa dispersar dinero sin haber comprobado listas porque un servicio estaba caído. Y ninguna
> prueba cubría la rama — por eso sobrevivió.

## Convención de idioma

**Todo el código de este servicio se escribe en inglés** — clases, métodos, campos, enums, pruebas
y claves de configuración. Los **comentarios van en español**, que es donde vive el razonamiento.

Excepciones: los nombres que viajan en el contrato de un proveedor externo, y los términos
regulatorios sin equivalente (`CLABE`, `CURP`, `RFC`, `CEP`). Detalle y guía de migración en
[`docs/CONVENCION_IDIOMA.md`](../../docs/CONVENCION_IDIOMA.md).
