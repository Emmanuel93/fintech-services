# T1 — Identity & Auth

**Estado:** ✅ Completo — 2026-05-14  
**Tipo:** Transversal — prerequisito síncrono  
**Schema DB:** `identity`  
**Paquete Java:** `com.fintech.identity`

## Responsabilidad

Autenticación y gestión de tokens JWT. **No emite eventos Kafka.** Expone un endpoint REST que los demás módulos llaman síncronamente para validar sesiones.

## Comunicación

| Dirección | Protocolo | Detalle |
|---|---|---|
| Entrada | REST | `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout` |
| Salida | REST expuesto | `GET /api/v1/auth/validate` — consumido síncronamente por todos los módulos |

## Capas implementadas

### Dominio (`identity/domain/`)

| Clase | Descripción |
|---|---|
| `IdentityCredential` | Entidad JPA; factory `create()`; `isLocked()`, `recordFailure(maxAttempts, lockDurationMinutes)`, `resetFailures()` |
| `AuthToken` | Entidad JPA; factory `create()`; `revoke()`, `isExpired()` |
| `CredentialType` | Enum: `NIP`, `BIOMETRIC`, `ESAT`, `OTP` |
| `CredentialStatus` | Enum: `ACTIVE`, `LOCKED`, `DISABLED` |
| `AccountLockedException` | errorCode `AUTH_ACCOUNT_LOCKED`; expone `lockedUntil` (Instant) |
| `InvalidCredentialsException` | errorCode `AUTH_INVALID_CREDENTIALS` |
| `CredentialNotFoundException` | errorCode `AUTH_CREDENTIAL_NOT_FOUND` |
| `TokenException` | errorCode `AUTH_TOKEN_INVALID` |

### Aplicación (`identity/application/`)

| Clase | Descripción |
|---|---|
| `AuthProperties` | `@ConfigurationProperties("fintech.auth")` — jwtSecret, expiryMinutes, maxFailedAttempts, etc. |
| `JwtService` | JJWT 0.12.x; genera/valida JWT; claims: `sub` (partyId), `roles`, `deviceId`, `jti` |
| `AuthService` | Login con lockout, refresh con rotación, logout global, validate con revocación check, createCredential |

### Infraestructura (`identity/infrastructure/`)

| Clase | Descripción |
|---|---|
| `IdentityCredentialRepository` | `findByPartyIdAndCredentialType` |
| `AuthTokenRepository` | `findByTokenId`, `findByRefreshTokenHash`, `revokeAllByPartyId` |
| `SecurityConfig` | Stateless, `JwtAuthenticationFilter`, rutas públicas, `ROLE_ADMIN` en `/credentials`, `BCryptPasswordEncoder` |
| `JwtAuthenticationFilter` | `OncePerRequestFilter`; extrae Bearer, valida, pone partyId como principal en SecurityContext |
| `IdentityModuleConfig` | `@EnableConfigurationProperties(AuthProperties.class)` |

### API (`identity/api/`)

| Clase | Descripción |
|---|---|
| `AuthController` | Package-private; 5 endpoints |
| `IdentityExceptionHandler` | `@Order(1)`; 401 por credenciales/token inválido, 423 por cuenta bloqueada |
| `LoginRequest` | `@NotNull UUID partyId`, `@NotBlank String pin` |
| `RefreshRequest` | `@NotBlank String refreshToken` |
| `TokenResponse` | `accessToken, refreshToken, expiresIn, tokenType` |
| `TokenValidationResponse` | `UUID partyId, List<String> roles, String deviceId` |
| `CredentialCreateRequest` | `partyId, pin (@Size min=4 max=8), credentialType` |

## Endpoints

| Método | Path | Auth | Descripción |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | Público | Login NIP → `{ accessToken, refreshToken, expiresIn, tokenType }` |
| `POST` | `/api/v1/auth/refresh` | Público | Rota refresh token → nuevo par |
| `POST` | `/api/v1/auth/logout` | JWT Bearer | Revoca todas las sesiones del party |
| `GET` | `/api/v1/auth/validate` | JWT Bearer | Valida token → `{ partyId, roles, deviceId }` |
| `POST` | `/api/v1/auth/credentials` | ROLE_ADMIN | Crea credencial NIP (bootstrap / D0 delegado) |

## Esquema de base de datos

### `identity.credentials`

| Columna | Tipo | Descripción |
|---|---|---|
| `id` | UUID PK | |
| `party_id` | UUID | Índice único con `credential_type` |
| `credential_type` | VARCHAR(20) | NIP / BIOMETRIC / ESAT / OTP |
| `password_hash` | VARCHAR(255) | BCrypt |
| `status` | VARCHAR(20) | ACTIVE / LOCKED / DISABLED |
| `failed_attempts` | INT | Intentos fallidos consecutivos |
| `locked_until` | TIMESTAMP | Fin del bloqueo temporal |
| `created_at` | TIMESTAMP | |
| `updated_at` | TIMESTAMP | |

### `identity.auth_tokens`

| Columna | Tipo | Descripción |
|---|---|---|
| `id` | UUID PK | |
| `token_id` | VARCHAR(36) UNIQUE | JTI del access token |
| `party_id` | UUID | Índice |
| `device_id` | VARCHAR(100) | Opcional |
| `refresh_token_hash` | VARCHAR(64) UNIQUE | SHA-256 hex del refresh token opaco |
| `issued_at` | TIMESTAMP | |
| `expires_at` | TIMESTAMP | |
| `revoked` | BOOLEAN | |
| `revoked_at` | TIMESTAMP | |

## JWT Claims

```json
{
  "sub": "partyId (UUID)",
  "roles": ["CUSTOMER"],
  "deviceId": "device-uuid",
  "jti": "token-uuid"
}
```

## Configuración

```yaml
fintech:
  auth:
    jwt-secret: ${JWT_SECRET}
    access-token-expiry-minutes: 15
    refresh-token-expiry-days: 7
    max-failed-attempts: 5
    lockout-duration-minutes: 30
    mfa-threshold-amount: 10000
```

## Reglas de negocio

- `failedAttempts >= maxFailedAttempts` → credencial LOCKED por `lockoutDurationMinutes` (default 30 min)
- Cuenta bloqueada → 423 Locked con `lockedUntil` en el response body
- Refresh token rotation: el token anterior queda revocado en cada refresh
- Logout revoca **todas** las sesiones activas del party (no solo la actual)
- Refresh token opaco: 64 chars hex, almacenado como SHA-256 hash en DB
- `validate` verifica que el JTI del token no esté revocado en `auth_tokens`

## Tests

| Archivo | Tipo | Cobertura |
|---|---|---|
| `AuthServiceTest` | Unit (Mockito) | Login success/failure/lockout, refresh rotación/revocado, logout, resetFailures |
| `AuthControllerTest` | WebMvcTest slice | Todos los endpoints: 200/201/204/400/401/403/423 |

## Redis — Almacenamiento de tokens y caché de validación

Redis cumple **dos roles** distintos en T1:

### 1. Almacenamiento de tokens (fuente de verdad)

Los `AuthToken` viven **exclusivamente en Redis**. PostgreSQL no tiene tabla `auth_tokens`.

#### Esquema de claves

| Clave | Valor | TTL |
|---|---|---|
| `token:jti:{jti}` | `partyId` (string) | `accessTokenExpiryMinutes` × 60 s |
| `token:refresh:{hash}` | JSON(AuthToken) | segundos hasta `expiresAt` |
| `token:party:{partyId}` | Set de `"jti:{jti}"` y `"refresh:{hash}"` | igual que el refresh más largo |

#### Ciclo de vida

```
login / refresh  →  RedisTokenRepository.save(token no revocado)
                       SET token:jti:{jti}     EX accessTtl
                       SET token:refresh:{hash} EX refreshTtl
                       SADD token:party:{pid}  "jti:…" "refresh:…"

POST /refresh    →  findByRefreshTokenHash → OK
                 →  token.revoke() → save(revocado)
                       DEL token:jti:{jti}
                       DEL token:refresh:{hash}
                       SREM token:party:{pid}

POST /logout     →  revokeAllByPartyId(pid)
                       SMEMBERS token:party:{pid}  → todos los jti + refresh del party
                       DEL <todos los miembros>
                       DEL token:party:{pid}

GET /validate    →  isTokenActive(jti)  →  EXISTS token:jti:{jti}
                    true  → continúa
                    false → 401 TokenException
```

#### Por qué Redis y no PostgreSQL

| Aspecto | PostgreSQL | Redis |
|---|---|---|
| Latencia p99 | ~5–20 ms | <1 ms |
| Expiración automática | Requires job nocturno | TTL nativo |
| Escrituras por login | 1 INSERT + índices | 3 SET + 1 SADD |
| Logout (revocar todas) | UPDATE WHERE partyId (lock table) | DEL O(n) atomic |

---

### 2. Caché de resultados de validación (Spring Cache)

`GET /api/v1/auth/validate` es llamado por todos los módulos en cada request. El resultado se cachea con TTL corto para reducir llamadas a Redis por token.

| Aspecto | Detalle |
|---|---|
| Cache name | `token:validation` (prefijado `fintech:` → `fintech:token:validation::`) |
| Clave | Bearer token completo |
| TTL | `fintech.auth.validation-cache-ttl-seconds` (default 60 s) |
| Eviction en logout | `@CacheEvict(allEntries = true)` — limpia todas las entradas |
| Serialización | `GenericJackson2JsonRedisSerializer` |

---

### Configuración

```yaml
fintech:
  auth:
    validation-cache-ttl-seconds: ${AUTH_VALIDATION_CACHE_TTL_SECONDS:60}

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  cache:
    type: redis
    redis:
      key-prefix: "fintech:"
      use-key-prefix: true
      cache-null-values: false
```

### Docker Compose

```bash
# Levantar infraestructura de desarrollo
docker-compose up postgres kafka redis

# Redis CLI
docker-compose exec redis redis-cli

# Tokens activos de un party
> SMEMBERS token:party:<partyId>

# Verificar si un JTI está activo
> EXISTS token:jti:<jti>
> TTL    token:jti:<jti>

# Caché de validación Spring
> KEYS   fintech:token:validation::*
> TTL    fintech:token:validation::<bearer>
```

### Tradeoff de seguridad — caché de validación

`@CacheEvict(allEntries = true)` en `logout()` limpia **todas** las entradas del Spring cache, no solo las del party que cerró sesión. Es conservador: implica que otros usuarios activos pagan un DB/Redis hit extra en su siguiente validación. Se acepta porque:
1. Logout es operación infrecuente.
2. Los tokens del party ya fueron borrados de Redis; el Spring cache solo guarda resultados de `TokenValidationResult`.
3. Garantía inmediata: ningún token revocado puede ser servido desde caché.

---

## Autenticación Client/Secret — Sistemas Externos

T1 soporta un segundo mecanismo de autenticación orientado a sistemas externos ("expert systems") como motores de scoring, riesgo o integraciones CI/CD.

### Concepto

| Aspecto | Usuario (NIP) | Sistema externo (Client/Secret) |
|---|---|---|
| Identificador | `partyId` (UUID) | `clientId` (string legible, e.g. `scoring-service`) |
| Credencial | PIN 4–8 dígitos | Secret 64 chars hex (BCrypt en DB) |
| Control de acceso | Roles en JWT | Roles en JWT + whitelist de IPs/CIDRs |
| Entidad DB | `identity.credentials` | `identity.clients` + `identity.client_ip_whitelist` |

### Capas añadidas

#### Dominio

| Clase | Descripción |
|---|---|
| `Client` | Entidad JPA; `create()`, `isActive()`, `disable()`; roles almacenados como CSV |
| `ClientIpEntry` | Entidad JPA; entrada CIDR de whitelist por cliente |
| `ClientStatus` | Enum: `ACTIVE`, `DISABLED`, `SUSPENDED` |
| `ClientNotFoundException` | errorCode `AUTH_CLIENT_NOT_FOUND` |
| `ClientSecretInvalidException` | errorCode `AUTH_CLIENT_SECRET_INVALID` |
| `IpNotAllowedException` | errorCode `AUTH_IP_NOT_ALLOWED` |

#### Aplicación

| Clase | Descripción |
|---|---|
| `ClientRegistrationResult` | Record: id, clientId, **clientSecret** (solo en el register), clientName, status, roles, expiresAt |
| `ClientInfo` | Record: datos públicos del cliente sin secret |
| `WhitelistEntryResult` | Record: id, cidr, label, createdAt |
| `ClientAuthUseCase` | `authenticateClient(clientId, secret, requestIp) → TokenPair` |
| `RegisterClientUseCase` | `registerClient(clientId, clientName, roles, expiresAt) → ClientRegistrationResult` |
| `ManageClientWhitelistUseCase` | CRUD whitelist + get/disable cliente |
| `ClientAuthService` | Implementa los tres use cases; IP check vía `IpAddressMatcher` |

#### Infraestructura

| Clase | Descripción |
|---|---|
| `JpaClientRepository` | `findByClientId(String)` |
| `JpaClientIpRepository` | `findByClientId(UUID)` |
| `ClientAuthController` | 7 endpoints bajo `/api/v1/auth/clients` |

### Endpoints

| Método | Path | Auth | Descripción |
|---|---|---|---|
| `POST` | `/api/v1/auth/clients/token` | Público | Autenticación client/secret con verificación de IP |
| `POST` | `/api/v1/auth/clients` | ROLE_ADMIN | Registrar cliente — secret devuelto **una sola vez** |
| `GET` | `/api/v1/auth/clients/{clientId}` | ROLE_ADMIN | Ver info del cliente |
| `DELETE` | `/api/v1/auth/clients/{clientId}` | ROLE_ADMIN | Deshabilitar cliente |
| `POST` | `/api/v1/auth/clients/{clientId}/whitelist` | ROLE_ADMIN | Agregar entrada CIDR |
| `GET` | `/api/v1/auth/clients/{clientId}/whitelist` | ROLE_ADMIN | Listar entradas CIDR |
| `DELETE` | `/api/v1/auth/clients/{clientId}/whitelist/{entryId}` | ROLE_ADMIN | Eliminar entrada CIDR |

### Esquema de base de datos

#### `identity.clients`

| Columna | Tipo | Descripción |
|---|---|---|
| `id` | UUID PK | |
| `client_id` | VARCHAR(100) UNIQUE | Identificador legible, e.g. `scoring-service` |
| `secret_hash` | VARCHAR(255) | BCrypt del secret opaco |
| `client_name` | VARCHAR(200) | Nombre descriptivo |
| `status` | VARCHAR(20) | ACTIVE / DISABLED / SUSPENDED |
| `roles` | TEXT | CSV, e.g. `SYSTEM_SCORING,SYSTEM_RISK` |
| `expires_at` | TIMESTAMP | Opcional; null = sin expiración |
| `created_at` | TIMESTAMP | |
| `updated_at` | TIMESTAMP | |

#### `identity.client_ip_whitelist`

| Columna | Tipo | Descripción |
|---|---|---|
| `id` | UUID PK | |
| `client_id` | UUID FK → clients.id | ON DELETE CASCADE |
| `cidr` | VARCHAR(50) | e.g. `10.0.0.1/32`, `192.168.1.0/24` |
| `label` | VARCHAR(100) | e.g. `prod-cluster`, `github-actions` |
| `created_at` | TIMESTAMP | |

### Flujo de autenticación client/secret

```
POST /api/v1/auth/clients/token {clientId, clientSecret}
  → ClientAuthController.extractClientIp (X-Forwarded-For → remoteAddr)
  → ClientAuthService.authenticateClient
      1. findByClientId → ClientSecretInvalidException si no existe o DISABLED/EXPIRED
      2. passwordEncoder.matches(secret, secretHash) → ClientSecretInvalidException si falla
      3. clientIpRepository.findByClientId → IpAddressMatcher.matches(requestIp)
         • whitelist vacía → permite (sin restricción de IP)
         • whitelist con entradas → IpNotAllowedException si IP no coincide con ningún CIDR
      4. tokenPort.generateAccessToken(client.getId(), client.getRoles(), null)
      5. AuthToken → tokenRepository.save (Redis, igual que usuario)
  ← TokenResponse {accessToken, refreshToken, expiresIn, tokenType}
```

### Reglas de negocio

- El **secret se devuelve en texto plano una única vez** al registrar el cliente; en DB solo se guarda el hash BCrypt
- Whitelist **vacía → sin restricción de IP**; whitelist con entradas → requiere coincidencia exacta con al menos un CIDR
- `IpAddressMatcher` (Spring Security) soporta IPv4 single hosts (`1.2.3.4/32`) y rangos CIDR (`10.0.0.0/8`)
- `expiresAt` null = el cliente no expira nunca
- Deshabilitar un cliente no revoca tokens emitidos activos (expiran naturalmente por TTL de Redis)
- El `sub` del JWT de cliente es el UUID interno del cliente (no el `clientId` string)

---

## Dependencias

- Ninguna dependencia hacia otros módulos del sistema
- Consumido por: **todos los módulos** vía REST (`GET /api/v1/auth/validate`)
- **Runtime**: PostgreSQL (identity, clients, whitelist), Redis (tokens, caché de validaciones)
