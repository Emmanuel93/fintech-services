# identity-service (T1)

**Autenticación y emisión de tokens. Único firmante de JWT de la plataforma.** Ningún request llega
a un microservicio sin que el gateway haya verificado antes un token emitido aquí.

> El gateway valida la firma **localmente** con la llave pública RSA — **no** llama a
> `GET /api/v1/auth/validate` en cada request. Ese endpoint sigue existiendo para cuando un servicio
> necesite validar un token a mano (auditoría, validación interna).

Además publica un evento Kafka en **cada intento de inicio de sesión** —exitoso o fallido— con la
metadata de red, geolocalización y resultado que exige el cumplimiento (CNBV, PCI-DSS, SOC 2, GDPR);
y **consume** `origination.prospect-created` para provisionar la credencial del prospecto en cuanto
se da de alta.

| | |
|---|---|
| **Puerto** | `8080` |
| **Schema** | `identity` (+ Redis para tokens y estado efímero) |
| **Arquitectura** | Hexagonal + Spring Modulith |
| **Régimen Kafka** | Por defecto (sin DLT) |
| **Dominio** | [docs/dominios/T1_identity_auth.md](../../docs/dominios/T1_identity_auth.md) |

**Cuatro poblaciones distintas de identidad:**

| Población | Endpoint base | Credencial |
|---|---|---|
| Prospecto recién capturado | *(provisionado por Kafka)* | `PASSWORD` creada automáticamente |
| Cliente final | `/api/v1/auth` | `username` + `password`, 2FA TOTP opcional |
| Personal interno (staff) | `/api/v1/auth/staff` | Sesión de backoffice con roles y capacidades |
| Sistema a sistema (M2M) | `/api/v1/auth/clients` | `client_id` + secret + whitelist de IPs por CIDR |

---

---

## Arquitectura

El servicio sigue **arquitectura hexagonal** (Ports & Adapters):

```mermaid
flowchart LR
    subgraph in["Adaptadores de entrada"]
        A["AuthController<br/>/api/v1/auth"]
        SA["StaffAuthController<br/>/api/v1/auth/staff"]
        M["MfaController<br/>/api/v1/auth/mfa"]
        CA["ClientAuthController<br/>/api/v1/auth/clients"]
        R["RoleController · StaffController<br/>ExecutiveDirectoryController"]
        KC["ProspectCreatedConsumer<br/>origination.prospect-created"]
    end

    subgraph app["Aplicación (casos de uso)"]
        LU["LoginUseCase → AuthService"]
        MU["VerifyMfaUseCase → MfaService"]
        PU["ProvisionProspectCredentialUseCase"]
        STI["SessionTokenIssuer"]
    end

    subgraph dom["Dominio"]
        CR(("IdentityCredential"))
        ST(("StaffUser · Role"))
        DV(("Device"))
        MF(("MfaConfig"))
        CL(("Client · ClientIpEntry"))
    end

    subgraph out["Adaptadores de salida"]
        PG[("PostgreSQL<br/>schema identity")]
        RD[("Redis<br/>tokens · caché · 2FA pendiente")]
        JWT["JwtAdapter<br/>firma RS256 con llave PKCS#8"]
        GEO["GeoLocationPort<br/>StubGeoLocationAdapter 🧪"]
        KO["identity.login-attempted"]
    end

    A & SA & M & CA --> LU
    M --> MU
    KC --> PU
    R --> ST
    LU & MU --> STI --> JWT
    LU --> GEO
    LU --> KO
    PU --> CR
    LU --> CR & DV
    MU --> MF
    CA --> CL
    CR & ST & DV & MF & CL --> PG
    STI --> RD

    classDef mock fill:#fff4e5,stroke:#d98324,stroke-width:2px;
    class GEO mock
```

**Reglas de dependencia (de afuera hacia adentro):**
- **Infrastructure** conoce Application y Domain; nunca al revés
- **Application** conoce Domain; nunca Infrastructure
- **Domain** no conoce a nadie

---

## Integración con el API Gateway

### Modelo de autenticación distribuida

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant GW as gateway-service
    participant ID as identity-service
    participant MS as Microservicio de dominio

    C->>GW: POST /api/v1/auth/login (público, sin JWT)
    GW->>ID: proxy
    ID->>ID: firma JWT con la llave privada RSA<br/>iss=identity-service · sub=partyId<br/>exp=now+15min · roles=[…]
    ID-->>C: {accessToken, refreshToken}

    C->>GW: request con Bearer token
    GW->>GW: jwt.lua verifica LOCALMENTE<br/>firma RS256 con public.pem · exp · iss
    alt token válido
        GW->>MS: request + X-User-Id · X-Roles · X-Channel
    else inválido o vencido
        GW-->>C: 401
    end
```

**Por qué sin llamar a identity en cada request:** la firma RSA es criptográficamente suficiente. No hay round-trip de red en el camino crítico. La revocación se maneja por expiración corta (15 min) y opcionalmente por lista negra en Redis si se implementa en el Lua del gateway.

### Formato del token que el gateway acepta

El gateway rechaza cualquier token que no cumpla:

```
Header: { "alg": "RS256", "typ": "JWT" }
```

| Claim | Valor | Requerido por gateway |
|---|---|---|
| `sub` | UUID del party (string) | Sí — propagado como `X-User-Id` |
| `iss` | `"identity-service"` | **Sí — falla con `invalid_issuer` si no coincide** |
| `exp` | Unix timestamp | **Sí — falla con `token_expired` si está vencido** |
| `iat` | Unix timestamp | No verificado por gateway |
| `jti` | UUID | Recomendado — propagado como `X-Token-Jti` |
| `roles` | array de strings | Recomendado — propagado como `X-Roles` (CSV) |

### Estado actual: RS256 ✅ operativo

La migración de HS256 a RS256 está completa. El servicio firma tokens con `RSAPrivateKey` (PKCS#8) y el gateway los valida localmente con la llave pública sin ningún round-trip a identity.

**Archivos modificados:**

| Archivo | Cambio |
|---|---|
| `JwtAdapter.java` | Usa `PKCS8EncodedKeySpec` + `X509EncodedKeySpec`. Strip de headers PEM antes del decode base64. Firma con `Jwts.builder().signWith(privateKey)` (RS256 automático con RSAKey) |
| `AuthProperties.java` | `privateKeyPath` + `publicKeyPath` (rutas a archivos montados en Docker) |
| `application-docker.yml` | `auth.private-key-path: /etc/identity/keys/private.pem` |
| `docker-compose.yml` | Volúmenes: `./identity-service/src/main/resources/keys/private.pem:/etc/identity/keys/private.pem:ro` y `./gateway-service/keys/public.pem:/etc/identity/keys/public.pem:ro` |

**Variables de entorno:**

| Variable | Descripción | Valor en Docker |
|---|---|---|
| `AUTH_PRIVATE_KEY_PATH` | Ruta al PEM de llave privada PKCS#8 | `/etc/identity/keys/private.pem` |
| `AUTH_PUBLIC_KEY_PATH` | Ruta al PEM de llave pública (X.509 SubjectPublicKeyInfo) | `/etc/identity/keys/public.pem` |

**Generar el par de llaves (una sola vez por entorno):**
```bash
# PKCS#8 — formato que espera JwtAdapter.java ("BEGIN PRIVATE KEY")
# NO usar openssl genrsa: produce PKCS#1 ("BEGIN RSA PRIVATE KEY") que falla en runtime
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out src/main/resources/keys/private.pem

# Llave pública para el gateway
openssl rsa -in src/main/resources/keys/private.pem \
            -pubout -out ../gateway-service/keys/public.pem

# Verificación:
head -1 src/main/resources/keys/private.pem
# Debe decir: -----BEGIN PRIVATE KEY-----
```

`private.pem` está en `.gitignore` (`**/keys/private.pem`). No lo commitear nunca.

---

## Flujos de autenticación

### Flujo 0 — Provisioning de credencial desde prospecto (Kafka)

Este flujo es **asíncrono y automático**: no requiere intervención del usuario ni del cliente HTTP.

```
origination-service  ──►  Kafka topic: origination.prospect-created
                                   │
                    ┌──────────────▼────────────────────────────────┐
                    │  KafkaProspectCreatedConsumer                  │
                    │  group-id: identity-service                    │
                    │                                                │
                    │  1. Deserializa ProspectCreatedMessage         │
                    │     { prospectId, username, password }         │
                    │     (password en texto plano — identity hashea)│
                    │                                                │
                    │  2. Llama ProvisionProspectCredentialUseCase   │
                    │     → AuthService.provisionFromProspect()      │
                    │                                                │
                    │  3. Verifica si ya existe credencial PASSWORD  │
                    │     para este prospectId → si existe: no-op    │
                    │     (idempotencia — at-least-once delivery)    │
                    │                                                │
                    │  4. Persiste IdentityCredential                │
                    │     { id, partyId=prospectId, username,        │
                    │       credentialType=PASSWORD,                 │
                    │       passwordHash (BCrypt, ya hasheado),      │
                    │       status=ACTIVE }                          │
                    └────────────────────────────────────────────────┘

Resultado: el prospecto ya puede hacer login con su username+password
           desde el momento en que origination-service confirma el alta.
```

**Nota de seguridad sobre la contraseña:**
- `origination-service` recibe la contraseña en texto plano en el POST HTTP
- La hashea con BCrypt antes de publicar el evento — la contraseña en texto plano nunca llega a `identity-service` ni al log de Kafka
- `identity-service` almacena el hash directamente, sin re-hashear (para evitar doble hash que rompería la verificación en login)

**MFA después del provisioning:**
El prospecto inicia sesión con sus credenciales (`POST /api/v1/auth/login`). Si no tiene MFA activo recibe los tokens directamente. Desde ahí puede enrolarse en 2FA (`POST /api/v1/auth/mfa/enroll`) para activar Google Authenticator. La plataforma puede incentivarlo pero no lo bloquea.

---

### Flujo 1 — Login sin 2FA

```
Cliente  →  POST /api/v1/auth/login  { username, password, deviceId? }
             Headers: User-Agent, X-Forwarded-For, X-Request-ID

             1. Resuelve IP real (X-Forwarded-For → X-Real-IP → RemoteAddr)
             2. Busca credencial por username en DB
             3. Registra/actualiza dispositivo en identity.devices
                  → isNewDevice = true si es la primera vez para este party
             4. Verifica bcrypt(password, hash)
             5. Registra intento (reset failed_attempts, actualiza last_login_ip/at)
             6. Consulta si 2FA está activo → No
             7. Emite access token (JWT) + refresh token (opaco)
             8. Guarda tokens en Redis (JTI + refresh hash + IP + User-Agent)
             9. ▶ KAFKA → topic: identity.login-attempted
                  outcome: SUCCESS
                  credentialType: NIP | PASSWORD
                  deviceRegistryId, isNewDevice, ip, geoLocation, userAgent, sessionTokenId…

         ←  200 OK { accessToken, refreshToken, expiresIn, tokenType }
```

### Flujo 2 — Login con 2FA activo (dos pasos)

```
Paso 1 — Credencial primaria:

Cliente  →  POST /api/v1/auth/login  { username, password, deviceId? }

             1–4. Igual que Flujo 1
             5. Consulta si 2FA está activo → Sí
             6. Genera mfaToken (UUID opaco) → Redis con { partyId, username } TTL=5min
             7. ▶ KAFKA → topic: identity.login-attempted
                  outcome: SUCCESS_MFA_REQUIRED, ip, geoLocation, userAgent…

         ←  202 Accepted { mfaRequired: true, mfaToken }

Paso 2 — Código TOTP:

Cliente  →  POST /api/v1/auth/mfa/verify  { mfaToken, code }
             Headers: User-Agent, X-Forwarded-For, X-Request-ID

             1. Consume mfaToken de Redis (atómico GETDEL, no reutilizable)
                Recupera { partyId, username } del token
             2. Busca config MFA del party → obtiene totpSecret
             3. Verifica código TOTP (window ±1 período de 30s)
             4. Emite access token + refresh token
             5. ▶ KAFKA → topic: identity.login-attempted
                  outcome: SUCCESS_MFA_VERIFIED, ip, geoLocation, mfaUsed: true…

         ←  200 OK { accessToken, refreshToken, expiresIn, tokenType }
```

> El `mfaToken` expira en 5 minutos y se consume en un solo uso — no puede reutilizarse.

### Flujo 3 — Enrolamiento de 2FA (primera vez)

```
Requiere JWT activo (party ya autenticado).

1. Iniciar:
   Cliente  →  POST /api/v1/auth/mfa/enroll  (Bearer JWT)
           ←  200 { totpSecret, qrUri }
              Escanear qrUri con Google Authenticator o Authy.

2. Confirmar:
   Cliente  →  POST /api/v1/auth/mfa/enroll/confirm  { code }  (Bearer JWT)
               Enviar primer código de 6 dígitos del autenticador.
           ←  204 No Content  — 2FA activado.

A partir de aquí cada login retorna 202 en lugar de 200.
```

### Flujo 4 — Deshabilitar 2FA

```
Requiere JWT activo + código TOTP vigente.

Cliente  →  DELETE /api/v1/auth/mfa  { code }  (Bearer JWT)
        ←  204 No Content  — 2FA deshabilitado.
```

### Flujo 5 — Client Auth (M2M)

```
Sistema  →  POST /api/v1/auth/clients/token  { clientId, clientSecret }

             1. Busca cliente en DB por clientId
             2. Verifica bcrypt(secret, hash)
             3. Valida whitelist de IPs (si tiene entradas configuradas)
             4. Emite tokens con los roles del cliente

         ←  200 OK { accessToken, refreshToken, expiresIn, tokenType }
```

### Flujo 6 — Validación de token (inter-servicio)

```
Servicio externo  →  GET /api/v1/auth/validate  (Bearer JWT)

                      1. Parsea y verifica firma JWT
                      2. Verifica que el JTI esté activo en Redis (no revocado)
                      3. Cachea resultado en Redis (TTL=60s) — reduce carga

                  ←  200 OK { partyId, roles, deviceId }
```

### Flujo 7 — Login fallido (intentos y bloqueo)

```
Cliente  →  POST /api/v1/auth/login  { username, password_incorrecta }

             1. Busca credencial por username
             2. Verifica bcrypt → No coincide
             3. Incrementa failed_attempts
             4. Si failed_attempts >= MAX → bloquea cuenta (lockedUntil = now + 30min)
             5. ▶ KAFKA → topic: identity.login-attempted
                  outcome: FAILED_CREDENTIALS  (o ACCOUNT_LOCKED si alcanzó el umbral)
                  ip, geoLocation, userAgent, failedAttemptCount, accountLockedUntil…

         ←  401 Unauthorized  (o 423 Locked si la cuenta quedó bloqueada)
```

---

## Eventos Kafka

### Topics consumidos

#### Topic: `origination.prospect-created` (consumidor)

**Group ID:** `identity-service` (configurable con `KAFKA_CONSUMER_GROUP_ID`).  
**Auto-offset-reset:** `earliest` — procesa eventos desde el inicio si el grupo es nuevo.  
**Auto-commit:** deshabilitado — confirmación manual implícita por el listener container.

| Campo del mensaje | Tipo   | Descripción                                                                   |
|-------------------|--------|-------------------------------------------------------------------------------|
| `prospectId`      | UUID   | Usado como `partyId` en la credencial hasta que party-service genere el party |
| `username`        | String | Nombre de usuario elegido por el prospecto en el registro                     |
| `password`        | String | Contraseña en **texto plano**. identity la hashea con BCrypt (`provisionFromProspect` → `passwordEncoder.encode`) antes de almacenar — origination nunca persiste ni hashea la contraseña |

Los demás campos del evento (`curp`, `phone`, `email`, etc.) son ignorados por identity — solo consume lo que necesita para provisionar la credencial.

**Comportamiento ante errores:**
- Si el `username` ya existe (constraint UNIQUE en DB): el consumer lanzará excepción; Spring Kafka reintentará según la política por defecto. El esquema de unicidad previene credenciales duplicadas.
- Si la credencial del mismo `prospectId` ya existe (idempotencia): retorna sin error — safe para redelivery de Kafka.

---

**Reintentos:** régimen por defecto de la plataforma (`DefaultErrorHandler`, reintento acotado y
*seek-past*; sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

### Topics publicados

### Topic: `identity.login-attempted`

Publicado en **cada intento de inicio de sesión**, exitoso o fallido. La clave del mensaje es el `eventId` (UUID) para garantizar idempotencia en los consumidores.

**Serialización:** JSON (sin headers de tipo Java — portable a cualquier consumidor).  
**Particionado:** por `eventId` (distribución uniforme).  
**Productor:** idempotente, `acks=all`, 3 retries — garantía at-least-once.

#### Estructura del evento

```json
{
  "eventName":         "identity.login-attempted",
  "eventId":           "550e8400-e29b-41d4-a716-446655440000",
  "eventVersion":      "1.0",
  "occurredAt":        "2026-05-15T14:32:10.123456Z",

  "username":          "juan@email.com",
  "partyId":           "00000000-0000-0000-0000-000000000001",

  "outcome":           "SUCCESS",

  "ipAddress":         "187.155.23.41",
  "geoLocation": {
    "countryCode":     "MX",
    "countryName":     "Mexico",
    "region":          "Ciudad de Mexico",
    "city":            "Cuauhtemoc",
    "postalCode":      "06600",
    "latitude":        19.4284,
    "longitude":       -99.1277,
    "timezone":        "America/Mexico_City",
    "isp":             "Megacable",
    "asn":             "AS13977",
    "vpn":             false,
    "proxy":           false
  },

  "userAgent":         "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)...",
  "requestId":         "req-xyz-789",

  "deviceId":          "device-abc-123",
  "deviceRegistryId":  "aaaabbbb-cccc-dddd-eeee-ffffffffffff",
  "isNewDevice":       false,

  "credentialType":    "NIP",
  "mfaUsed":           false,

  "sessionTokenId":    "jti-of-issued-access-token",
  "sessionExpiresAt":  "2026-05-15T14:47:10.123456Z",

  "failureReason":     null,
  "failedAttemptCount": 0,
  "accountLockedUntil": null
}
```

#### Valores del campo `outcome`

| Outcome                  | Cuándo se emite                                                     |
|--------------------------|---------------------------------------------------------------------|
| `SUCCESS`                | Login exitoso sin 2FA — tokens emitidos                            |
| `SUCCESS_MFA_REQUIRED`   | Credenciales válidas, usuario tiene 2FA activo — paso 1 completado |
| `SUCCESS_MFA_VERIFIED`   | Código TOTP verificado — login completo con MFA — tokens emitidos  |
| `FAILED_CREDENTIALS`     | Contraseña/NIP incorrectos (cuenta aún no bloqueada)              |
| `ACCOUNT_LOCKED`         | Credencial incorrecta que alcanzó el umbral — cuenta bloqueada ahora |
| `ACCOUNT_ALREADY_LOCKED` | Intento mientras la cuenta ya estaba bloqueada                     |
| `CREDENTIAL_NOT_FOUND`   | El username no existe en el sistema                                |
| `FAILED_MFA_CODE`        | Código TOTP incorrecto en el paso 2                               |

#### Campos regulatorios clave

| Campo                  | Regulación         | Propósito                                                        |
|------------------------|--------------------|------------------------------------------------------------------|
| `eventName`            | SOC2               | Nombre fijo del evento — enrutamiento sin acoplar al topic       |
| `occurredAt`           | PCI-DSS, CNBV      | Timestamp preciso con timezone (UTC)                             |
| `partyId` + `username` | CNBV, GDPR         | Identificación del sujeto del acceso                             |
| `ipAddress`            | PCI-DSS, CNBV      | Origen del acceso (con resolución de proxies)                    |
| `geoLocation`          | CNBV, SOC2         | País, ciudad, ISP, ASN — detecta accesos inusuales               |
| `outcome`              | PCI-DSS, SOC2      | Éxito o fallo — requerido en logs de control de acceso          |
| `credentialType`       | CNBV               | Tipo de factor de autenticación usado (NIP / PASSWORD)           |
| `deviceRegistryId`     | PCI-DSS, CNBV      | FK al dispositivo en identity.devices — historial de accesos     |
| `isNewDevice`          | CNBV, SOC2         | Alerta de primer acceso desde un dispositivo desconocido         |
| `sessionTokenId`       | PCI-DSS            | JTI del token emitido — permite correlación forense              |
| `mfaUsed`              | CNBV (RDSI)        | Evidencia de autenticación multifactor                           |
| `failedAttemptCount`   | PCI-DSS 8.3        | Conteo de intentos — requerido para monitoreo de ataques         |
| `requestId`            | SOC2               | Correlación de trazas distribuidas                               |

#### Integración con otros servicios

El módulo `audit` (cuando se implemente) consumirá este topic para:
- Persistir el registro de acceso en base de datos con retención regulatoria
- Generar alertas por comportamiento anómalo (IPs nuevas, países inusuales, FAILED_CREDENTIALS repetidos)
- Producir reportes de acceso para auditorías CNBV / CONDUSEF

```
identity-service ──► Kafka topic: identity.login-attempted
                                            │
                              ┌─────────────▼─────────────┐
                              │       audit-service        │
                              │  (consumidor — aún no impl)│
                              │  Persiste + alerta          │
                              └────────────────────────────┘
```

> **Nota sobre geolocalización:** La implementación actual incluye un adaptador stub (`StubGeoLocationAdapter`) que retorna `GeoLocation.unknown()`. Para producción, reemplazar con MaxMind GeoIP2, IPinfo.io o similar implementando `GeoLocationPort`.

---

## Correr localmente

**Prerequisitos:** Java 21, Docker.

```bash
# Desde la raíz del monorepo

# 1. Infraestructura (PostgreSQL + Redis + Kafka)
docker compose up postgres redis kafka -d

# 2. Servicio
./gradlew :identity-service:bootRun
```

Inicia en `http://localhost:8080`.  
Swagger UI: `http://localhost:8080/swagger-ui.html`

### Con Docker (imagen completa)

```bash
# Construir desde la raíz del monorepo
docker build --build-arg SERVICE=identity-service -t fintech/identity-service .

# Levantar con todo el stack
docker compose --profile identity-service up
```

---

## Variables de entorno

| Variable                            | Default                                    | Requerido | Descripción                                               |
|-------------------------------------|--------------------------------------------|-----------|-----------------------------------------------------------|
| `JWT_SECRET`                        | *(ver nota)*                               | **Sí**    | Mínimo 256 bits. Generar con `openssl rand -base64 32`    |
| `KAFKA_BOOTSTRAP_SERVERS`           | `localhost:9092`                           | **Sí (prod)** | Brokers Kafka separados por coma                     |
| `KAFKA_CONSUMER_GROUP_ID`           | `identity-service`                         | No            | Group ID del consumer de `origination.prospect-created` |
| `SPRING_DATASOURCE_URL`             | `jdbc:postgresql://localhost:5432/fintech` | No        | URL JDBC de PostgreSQL                                    |
| `SPRING_DATASOURCE_USERNAME`        | `fintech`                                  | No        |                                                           |
| `SPRING_DATASOURCE_PASSWORD`        | `fintech`                                  | No        |                                                           |
| `REDIS_HOST`                        | `localhost`                                | No        |                                                           |
| `REDIS_PORT`                        | `6379`                                     | No        |                                                           |
| `DB_POOL_SIZE`                      | `5`                                        | No        | Conexiones máximas en el pool de Hikari                   |
| `SERVER_PORT`                       | `8080`                                     | No        |                                                           |
| `AUTH_ACCESS_TOKEN_EXPIRY_MINUTES`  | `15`                                       | No        | Vigencia del access token JWT                             |
| `AUTH_REFRESH_TOKEN_EXPIRY_DAYS`    | `7`                                        | No        | Vigencia del refresh token opaco                          |
| `AUTH_MAX_FAILED_ATTEMPTS`          | `5`                                        | No        | Intentos fallidos antes de bloquear la credencial         |
| `AUTH_LOCKOUT_DURATION_MINUTES`     | `30`                                       | No        | Duración del bloqueo por intentos fallidos                |
| `AUTH_VALIDATION_CACHE_TTL_SECONDS` | `60`                                       | No        | TTL de la caché de validación de tokens en Redis          |
| `AUTH_MFA_CODE_WINDOW_SIZE`         | `1`                                        | No        | Períodos TOTP de tolerancia (±30s por período)            |
| `AUTH_MFA_PENDING_TTL_SECONDS`      | `300`                                      | No        | TTL del `mfaToken` pendiente en Redis (mínimo 60s)        |

> `JWT_SECRET` tiene un default inseguro para desarrollo. **Siempre cambiar en producción.**

---

## Endpoints

### Staff Auth y directorio — `/api/v1/auth/staff` · `/api/v1/staff` · `/api/v1/roles`

La consola de backoffice tiene su propia superficie: sesión de personal, alta y baja de usuarios,
roles y capacidades.

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/v1/auth/staff/login` · `/refresh` · `/logout` | Sesión de personal interno |
| `GET` | `/api/v1/auth/staff/me` | Sesión vigente, con roles y capacidades |
| `GET` · `POST` | `/api/v1/staff` | Directorio de personal · alta |
| `GET` | `/api/v1/staff/{staffUserId}` | Ficha |
| `PUT` | `/api/v1/staff/{staffUserId}/roles` · `/password` · `/suspend` · `/reactivate` | Administración |
| `DELETE` | `/api/v1/staff/{staffUserId}` | Baja |
| `GET` | `/api/v1/roles` · `/{code}` | Catálogo de roles |
| `PUT` | `/api/v1/roles/{code}/capabilities` | Matriz de capacidades del rol |
| `GET` | `/api/v1/executives` · `/executives/staff` | Directorio de ejecutivos, para asignar cartera |

### Party Auth — `/api/v1/auth`

| Método   | Path            | Auth    | Status  | Descripción                                                    |
|----------|-----------------|---------|---------|----------------------------------------------------------------|
| `POST`   | `/login`        | Público | 200/202 | Login con username+password. 200 = tokens, 202 = 2FA requerido |
| `POST`   | `/refresh`      | Público | 200     | Rota el refresh token (rotación obligatoria)                   |
| `POST`   | `/logout`       | JWT     | 204     | Revoca todas las sesiones activas del party                    |
| `GET`    | `/validate`     | JWT     | 200     | Valida token y retorna claims — usado por otros servicios      |
| `POST`   | `/credentials`  | ADMIN   | 201     | Crear credencial (NIP o PASSWORD) con username para un party   |

### 2FA / TOTP — `/api/v1/auth/mfa`

| Método   | Path              | Auth    | Status | Descripción                                                  |
|----------|-------------------|---------|--------|--------------------------------------------------------------|
| `POST`   | `/enroll`         | JWT     | 200    | Inicia enrolamiento — devuelve secret y QR URI para escanear |
| `POST`   | `/enroll/confirm` | JWT     | 204    | Confirma enrolamiento con primer código válido — activa 2FA  |
| `POST`   | `/verify`         | Público | 200    | Verifica TOTP tras login paso 1 — completa auth y da tokens  |
| `DELETE` | `/`               | JWT     | 204    | Deshabilita 2FA (requiere código TOTP activo para confirmar) |

### Client Auth (M2M) — `/api/v1/auth/clients`

| Método   | Path                         | Auth    | Status | Descripción                                              |
|----------|------------------------------|---------|--------|----------------------------------------------------------|
| `POST`   | `/token`                     | Público | 200    | Auth client/secret → tokens. Verifica IP contra whitelist |
| `POST`   | `/`                          | ADMIN   | 201    | Registrar cliente externo (secret texto plano, una sola vez) |
| `GET`    | `/{clientId}`                | ADMIN   | 200    | Info de un cliente                                       |
| `DELETE` | `/{clientId}`                | ADMIN   | 204    | Deshabilitar cliente                                     |
| `POST`   | `/{clientId}/whitelist`      | ADMIN   | 201    | Agregar CIDR a whitelist de IPs                          |
| `GET`    | `/{clientId}/whitelist`      | ADMIN   | 200    | Listar whitelist                                         |
| `DELETE` | `/{clientId}/whitelist/{id}` | ADMIN   | 204    | Eliminar entrada de whitelist                            |

---

## Contratos de API

### POST `/api/v1/auth/login`

**Request:**
```json
{
  "username": "juan@email.com",
  "password": "1234",
  "deviceId": "device-abc-123"
}
```

El `username` es opaco para el servicio — puede ser email, teléfono, alias o cualquier string único.  
El `password` puede ser un NIP numérico o una contraseña alfanumérica.  
El `deviceId` es **opcional** — lo envía la app móvil para vincular el token al dispositivo.

**Headers capturados automáticamente (no van en el body):**

| Header            | Uso                                                              |
|-------------------|------------------------------------------------------------------|
| `User-Agent`      | Identificar tipo de cliente (app móvil, browser, SDK)            |
| `X-Forwarded-For` | IP real del cliente detrás de proxy/balanceador                  |
| `X-Real-IP`       | Alternativa a X-Forwarded-For                                    |
| `X-Request-ID`    | ID de correlación para trazas distribuidas                       |

**Response 200 — Sin 2FA:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "a1b2c3d4e5f6...",
  "expiresIn": 900,
  "tokenType": "Bearer"
}
```

**Response 202 — Con 2FA activo:**
```json
{
  "mfaRequired": true,
  "mfaToken": "f47ac10b58cc4372a5670e02b2c3d479"
}
```

---

### POST `/api/v1/auth/mfa/verify`

**Request:**
```json
{
  "mfaToken": "f47ac10b58cc4372a5670e02b2c3d479",
  "code": "123456"
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "b2c3d4e5f6a1...",
  "expiresIn": 900,
  "tokenType": "Bearer"
}
```

---

### POST `/api/v1/auth/mfa/enroll`

**Request:** sin body. Requiere `Authorization: Bearer {accessToken}`.

**Response 200:**
```json
{
  "totpSecret": "JBSWY3DPEHPK3PXP",
  "qrUri": "otpauth://totp/fintech:00000000-0000-0000-0000-000000000001?secret=JBSWY3DPEHPK3PXP&issuer=fintech&algorithm=SHA1&digits=6&period=30"
}
```

---

### POST `/api/v1/auth/refresh`

**Request:**
```json
{ "refreshToken": "a1b2c3d4e5f6..." }
```

**Response 200:** igual que login exitoso.

---

### GET `/api/v1/auth/validate`

**Header:** `Authorization: Bearer {accessToken}`

**Response 200:**
```json
{
  "partyId": "00000000-0000-0000-0000-000000000001",
  "roles": ["CUSTOMER"],
  "deviceId": null
}
```

---

### POST `/api/v1/auth/credentials`

Requiere rol `ADMIN`.

**Request:**
```json
{
  "partyId": "00000000-0000-0000-0000-000000000001",
  "username": "juan@email.com",
  "password": "1234",
  "credentialType": "NIP"
}
```

- `credentialType`: `NIP` (numérico, típico para apps móviles) o `PASSWORD` (alfanumérico, para web)

**Response:** `201 Created`

---

### Respuestas de error (RFC 9457 Problem Details)

```json
{
  "type": "https://fintech.com/errors/AUTH_INVALID_CREDENTIALS",
  "title": "Unauthorized",
  "status": 401,
  "detail": "PIN incorrecto o credencial no encontrada"
}
```

| Código de error               | Status | Cuándo ocurre                                  |
|-------------------------------|--------|------------------------------------------------|
| `AUTH_INVALID_CREDENTIALS`    | 401    | PIN incorrecto                                 |
| `AUTH_ACCOUNT_LOCKED`         | 423    | Cuenta bloqueada por intentos fallidos         |
| `AUTH_TOKEN_INVALID`          | 401    | JWT inválido, expirado o revocado              |
| `AUTH_CLIENT_SECRET_INVALID`  | 401    | Secret incorrecto o cliente no existe          |
| `AUTH_IP_NOT_ALLOWED`         | 403    | IP fuera de whitelist                          |
| `AUTH_CLIENT_NOT_FOUND`       | 404    | clientId no encontrado                         |
| `AUTH_CLIENT_ALREADY_EXISTS`  | 409    | clientId duplicado                             |
| `AUTH_INVALID_MFA_CODE`       | 400    | Código TOTP incorrecto o mfaToken expirado     |
| `AUTH_MFA_NOT_ENROLLED`       | 404    | Operación MFA pero no hay config activa        |
| `AUTH_MFA_ALREADY_ENROLLED`   | 409    | Intentar enrollar cuando ya está activo        |

---

## Dependencias externas y sus simuladores locales

| Dependencia real | Puerto de salida | Adaptador | Comportamiento |
|---|---|---|---|
| Geolocalización por IP (MaxMind GeoIP2, ip-api.com, IPinfo…) | `GeoLocationPort` | `StubGeoLocationAdapter` 🧪 | Devuelve `GeoLocation.unknown()` siempre |

El evento `identity.login-attempted` **igual se publica** con la geolocalización desconocida: el
hecho regulatorio es el intento de sesión, no el país desde el que ocurrió. Sustituir el stub es
registrar otro bean de `GeoLocationPort`; ni el dominio ni el caso de uso cambian.

---

## Base de datos

Schema PostgreSQL: **`identity`**. Liquibase gestiona las migraciones automáticamente al arrancar.

### `identity.credentials`

| Columna           | Tipo         | Descripción                                                                   |
|-------------------|--------------|-------------------------------------------------------------------------------|
| `id`              | UUID PK      | Identificador interno                                                         |
| `party_id`        | UUID         | Referencia al party (sin FK cross-schema)                                     |
| `username`        | VARCHAR(255) | Identificador de login único en todo el sistema                               |
| `credential_type` | VARCHAR(20)  | `NIP` \| `PASSWORD` — check constraint                                        |
| `password_hash`   | VARCHAR(255) | Hash bcrypt de la credencial                                                  |
| `status`          | VARCHAR(20)  | `ACTIVE` \| `LOCKED` \| `DISABLED`                                           |
| `failed_attempts` | INT          | Intentos fallidos consecutivos (se resetea al autenticar)                     |
| `locked_until`    | TIMESTAMPTZ  | Fin del bloqueo. `null` = no bloqueado                                        |
| `last_login_at`   | TIMESTAMPTZ  | Timestamp del último login exitoso. `null` si nunca ha iniciado sesión        |
| `last_login_ip`   | VARCHAR(45)  | IP del último login exitoso. Soporta IPv4 y IPv6                              |
| `created_at`      | TIMESTAMPTZ  | Inmutable                                                                     |
| `updated_at`      | TIMESTAMPTZ  | Actualizado en cada operación                                                 |

### `identity.clients`

| Columna       | Tipo         | Descripción                                                       |
|---------------|--------------|-------------------------------------------------------------------|
| `id`          | UUID PK      | Identificador interno                                             |
| `client_id`   | VARCHAR(100) | Identificador legible único (`scoring-service`)                   |
| `secret_hash` | VARCHAR(255) | Bcrypt del secret. El texto plano solo se entrega al registrar.   |
| `client_name` | VARCHAR(200) | Nombre descriptivo                                                |
| `status`      | VARCHAR(20)  | `ACTIVE` \| `DISABLED`                                           |
| `roles`       | TEXT         | CSV de roles (`SYSTEM_SCORING,SYSTEM_RISK`)                       |
| `expires_at`  | TIMESTAMPTZ  | Expiración. `null` = sin expiración                               |
| `created_at`  | TIMESTAMPTZ  | Inmutable                                                         |
| `updated_at`  | TIMESTAMPTZ  | Actualizado en cada operación                                     |

### `identity.client_ip_whitelist`

| Columna     | Tipo         | Descripción                                 |
|-------------|--------------|---------------------------------------------|
| `id`        | UUID PK      | Identificador de la entrada                 |
| `client_id` | UUID FK      | `identity.clients.id` (cascade delete)      |
| `cidr`      | VARCHAR(50)  | Rango en notación CIDR (`10.0.0.0/8`)       |
| `label`     | VARCHAR(100) | Etiqueta opcional (`prod`, `staging`)       |
| `created_at`| TIMESTAMPTZ  | Inmutable                                   |

### `identity.devices`

Registro de dispositivos únicos por party. Un dispositivo se crea la primera vez que autentica y se actualiza (`last_seen_at`, `last_seen_ip`, `user_agent`) en cada autenticación posterior.

| Columna            | Tipo         | Descripción                                                                   |
|--------------------|--------------|-------------------------------------------------------------------------------|
| `id`               | UUID PK      | Identificador interno del registro                                            |
| `party_id`         | UUID         | Party al que pertenece el dispositivo (sin FK cross-schema)                   |
| `client_device_id` | VARCHAR(255) | ID enviado por el cliente (UUID de instalación de la app, fingerprint, etc.)  |
| `platform`         | VARCHAR(20)  | `IOS` \| `ANDROID` \| `WEB` \| `API` \| `UNKNOWN` — derivado del User-Agent         |
| `os`               | VARCHAR(100) | Sistema operativo (ej. "iOS 17.4", "Android 14", "Windows 11")               |
| `browser`          | VARCHAR(100) | Navegador o app (ej. "Chrome 124", "Safari 17")                               |
| `model`            | VARCHAR(100) | Modelo de hardware (ej. "iPhone 15 Pro")                                      |
| `user_agent`       | TEXT         | User-Agent completo del **último** acceso — fuente de verdad para reanálisis  |
| `first_seen_at`    | TIMESTAMPTZ  | Primer registro del dispositivo (inmutable)                                   |
| `last_seen_at`     | TIMESTAMPTZ  | Última autenticación desde este dispositivo                                   |
| `first_seen_ip`    | VARCHAR(45)  | IP del primer acceso (inmutable)                                              |
| `last_seen_ip`     | VARCHAR(45)  | IP del último acceso                                                          |
| `trusted`          | BOOLEAN      | El party marcó este dispositivo como de confianza (default `false`)           |
| `status`           | VARCHAR(20)  | `ACTIVE` \| `BLOCKED` — bloqueado por fraude o política                       |
| `created_at`       | TIMESTAMPTZ  | Inmutable                                                                     |

Restricciones: `UNIQUE(party_id, client_device_id)` — un registro por combinación party/device.  
Índices: `idx_devices_party_id`, `idx_devices_last_seen_at DESC`.

### `identity.mfa_config`

| Columna       | Tipo        | Descripción                                              |
|---------------|-------------|----------------------------------------------------------|
| `id`          | UUID PK     | Identificador interno                                    |
| `party_id`    | UUID UNIQUE | Un solo registro TOTP por party                          |
| `totp_secret` | VARCHAR(64) | Secret Base32 del autenticador                           |
| `enabled`     | BOOLEAN     | `true` = 2FA activo; `false` = enrolamiento pendiente    |
| `enrolled_at` | TIMESTAMPTZ | Cuándo se confirmó el enrolamiento                       |
| `created_at`  | TIMESTAMPTZ | Inmutable                                                |
| `updated_at`  | TIMESTAMPTZ | Actualizado en cada operación                            |

---

## Cache (Redis)

Prefijo global: `identity:`.

### Tokens activos

| Key                       | Tipo   | Valor              | TTL                        | Propósito                                  |
|---------------------------|--------|--------------------|----------------------------|--------------------------------------------|
| `token:jti:{jti}`         | String | `partyId`          | `accessTokenExpiryMinutes` | Confirmar que el JWT no fue revocado       |
| `token:refresh:{sha256}`  | String | JSON(`AuthToken`)  | `refreshTokenExpiryDays`   | Lookup para rotación del refresh token     |
| `token:party:{partyId}`   | Set    | `jti:…`,`refresh:…`| `refreshTokenExpiryDays`   | Índice inverso para logout (revoca todo)   |

`AuthToken` en Redis incluye ahora `ipAddress` y `userAgent` de la sesión, disponibles al rotar el refresh token.

### Cache de validación

| Cache              | Key      | Valor                   | TTL                              |
|--------------------|----------|-------------------------|----------------------------------|
| `token:validation` | JWT raw  | `TokenValidationResult` | `AUTH_VALIDATION_CACHE_TTL_SECONDS` |

### Estado pendiente de 2FA

| Key                   | Tipo   | Valor                             | TTL                            |
|-----------------------|--------|-----------------------------------|--------------------------------|
| `mfa:pending:{token}` | String | JSON `{ partyId, username }`      | `AUTH_MFA_PENDING_TTL_SECONDS` |

El `mfaToken` se consume atómicamente con `GETDEL`. Almacena `username` además de `partyId` para incluirlo en el evento Kafka del paso 2 sin necesidad de consultar la DB.

---

## Tests

| Clase                      | Tipo                | Descripción                                                          |
|----------------------------|---------------------|----------------------------------------------------------------------|
| `AuthServiceTest`          | Unitario (servicio) | Login, refresh, logout, validate. Sin Spring context, Mockito.       |
| `ClientAuthServiceTest`    | Unitario (servicio) | Auth client/secret. Sin Spring context, Mockito.                     |
| `MfaServiceTest`           | Unitario (servicio) | Enroll, confirm, verify TOTP, disable. Sin Spring context, Mockito.  |
| `AuthControllerTest`       | Unitario (HTTP)     | `@WebMvcTest` — capa HTTP, use cases mockeados.                      |
| `ClientAuthControllerTest` | Unitario (HTTP)     | `@WebMvcTest` — capa HTTP de client auth.                            |
| `ClientAuthAcceptanceTest` | Aceptación          | `@SpringBootTest` + Testcontainers (PostgreSQL + Redis reales).      |

```bash
# Suite completa
./gradlew :identity-service:test

# Solo unitarios (rápidos, sin Docker)
./gradlew :identity-service:test --tests "com.fintech.identity.AuthServiceTest"
./gradlew :identity-service:test --tests "com.fintech.identity.MfaServiceTest"

# Solo aceptación (requiere Docker)
./gradlew :identity-service:test --tests "com.fintech.identity.ClientAuthAcceptanceTest"
```

---

## Swagger / OpenAPI

| Recurso               | URL / Ruta                                                                                        |
|-----------------------|---------------------------------------------------------------------------------------------------|
| Swagger UI            | `http://localhost:8080/swagger-ui.html`                                                           |
| OpenAPI JSON          | `http://localhost:8080/v3/api-docs`                                                               |
| OpenAPI YAML (live)   | `http://localhost:8080/v3/api-docs.yaml`                                                          |
| OpenAPI YAML (static) | [`docs/openapi.yaml`](docs/openapi.yaml)                                                          |
| Postman Collection    | [`docs/identity-service.postman_collection.json`](docs/identity-service.postman_collection.json)  |
| Actuator health       | `http://localhost:8080/actuator/health`                                                           |

El spec estático `docs/openapi.yaml` refleja el mismo contrato que genera springdoc en runtime.
Importarlo directamente en Postman o en el editor de Swagger UI (`editor.swagger.io`).


