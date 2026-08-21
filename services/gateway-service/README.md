# gateway-service (GW)

**Único punto de entrada externo de la plataforma.** OpenResty (nginx + LuaJIT). Toda request a
cualquier microservicio pasa primero por aquí.

**Dos responsabilidades, ninguna más:**

1. **Autenticación** — valida localmente el JWT firmado por identity-service, sin llamar a nadie.
2. **Rate limiting** — protege las rutas públicas (login, OTP, registro, KYC) del abuso.

| | |
|---|---|
| **Puerto** | `:80` interno → **`:8080` en el host** (único publicado; `GATEWAY_PORT` lo cambia) |
| **Tecnología** | OpenResty — nginx + LuaJIT, sin JVM |
| **Modelo** | *Header-trust*: el gateway decide, el resto confía |
| **Routing** | Por **subdominio**, no por prefijo de path |

> **Sin Kafka y sin JVM.** El gateway no participa en el bus de eventos: es nginx + Lua. Su única
> traza propia son los [logs de auditoría](#logs-de-auditoría) en formato JSON.

## Cómo funciona como capa de entrada

```mermaid
flowchart TB
    NET["Internet · app móvil · consola"] --> GW

    subgraph GW["gateway-service · OpenResty · :8080"]
        Q1{"¿La ruta es pública?"}
        RL["Aplica rate limit por zona"]
        Q2{"¿Trae Authorization: Bearer?"}
        Q3{"¿JWT válido?<br/>firma RS256 · exp · iss · channel"}
        INJ["Inyecta X-User-Id · X-Roles · X-Channel · X-Token-Jti"]
        E401["401"]
        E429["429 + Retry-After: 60"]

        Q1 -->|"sí"| RL
        RL -->|"dentro del límite"| PROXY
        RL -->|"excedido"| E429
        Q1 -->|"no"| Q2
        Q2 -->|"no"| E401
        Q2 -->|"sí"| Q3
        Q3 -->|"no"| E401
        Q3 -->|"sí"| INJ --> PROXY["proxy_pass"]
    end

    PROXY -->|"host mobile.*"| CM["channel-mobile-service :8085"]
    PROXY -->|"host backoffice.*"| CB["channel-backoffice-service :8099"]
    CM & CB --> DOM["Servicios de dominio<br/>sólo por red interna de Docker"]
```

Los microservicios **no son accesibles desde el host**: sus puertos no se publican en
`docker-compose.yml`. El gateway es el único ingreso, y `/internal/*` no se enruta nunca.

Los microservicios **no son accesibles directamente** desde el host — sus puertos no están expuestos en `docker-compose.yml`. El gateway es el único punto de ingreso.

---

## Qué es OpenResty y Lua

### nginx

nginx es un servidor HTTP de alto rendimiento basado en un **loop de eventos asíncrono** — maneja miles de conexiones concurrentes con muy poco overhead porque nunca bloquea un hilo esperando I/O. Fue diseñado para ser rápido como proxy y balanceador de carga, no para tener lógica de negocio.

### LuaJIT

LuaJIT es una implementación de **Lua** con compilación JIT (just-in-time). Lua es un lenguaje de scripting diseñado específicamente para ser embebido en otras aplicaciones. LuaJIT convierte el código Lua a código máquina en el primer uso y lo cachea — las llamadas siguientes son tan rápidas como C.

### OpenResty = nginx + LuaJIT

OpenResty empaqueta nginx con LuaJIT embebido. Permite escribir código Lua que corre **dentro del proceso nginx**, en cada fase del pipeline de una request:

```
request entra al proceso nginx
  │
  ├─ rewrite_by_lua_block    ← reescritura de rutas (no usamos)
  ├─ access_by_lua_block     ← AQUÍ corre jwt.lua — autenticación
  │    │
  │    ├─ token inválido → ngx.exit(401) — nginx para aquí
  │    └─ token válido   → continúa a la siguiente fase
  │
  └─ proxy_pass upstream     ← nginx hace el forward al microservicio
```

`access_by_lua_block` es la fase perfecta para autenticación: si el Lua llama `ngx.exit(401)`, nginx **nunca ejecuta el `proxy_pass`** — el upstream no recibe la request ni se entera de que existió.

Un worker de OpenResty ocupa ~5MB de RAM y no tiene JVM, contenedor de Spring ni pool de threads. El overhead por request es de microsegundos.

---

## Por qué la llave pública elimina la llamada a identity

### El problema con HS256 (situación actual)

Con **HS256** (HMAC-SHA256) existe una sola llave simétrica — la misma sirve para firmar y para verificar:

```
identity-service firma:   HMAC-SHA256(header.payload, llave_secreta) → signature
gateway verifica:         HMAC-SHA256(header.payload, llave_secreta) == signature?
```

Para verificar, el gateway **necesita la misma llave secreta**. El problema: quien tiene la llave puede también **crear tokens falsos**. No hay separación entre "quién puede firmar" y "quién puede verificar".

Consecuencias:
- Todos los microservicios que verifican JWT (origination, charges, payments…) tienen la misma llave — si cualquiera se compromete, el atacante puede fabricar tokens
- El gateway no puede verificar solo — o tiene la llave (y puede falsificar) o llama a identity en cada request (latencia + punto de falla)

```
Flujo HS256 con gateway sin llave:

  request → gateway → GET /api/v1/auth/validate → identity-service
                             (round-trip HTTP por cada request)
                                    │
                              ok / no ok
                                    │
                       gateway decide si hace proxy
```

### La solución RS256: dos llaves con roles distintos

**RSA** usa un par de llaves matemáticamente relacionadas pero con roles separados:

```
private.pem ── firmar   ──► solo identity-service la conoce y usa
public.pem  ── verificar ──► cualquier servicio puede tenerla
```

La relación matemática garantiza que:
- Con la llave pública puedes **comprobar** que alguien con la privada firmó el token
- Con la llave pública **es matemáticamente imposible crear tokens válidos** sin la privada
- La llave privada nunca sale de identity-service

Cómo funciona la firma RS256:

```
identity-service al emitir el token:

  1. Calcula hash del contenido:
     h = SHA256("eyJhbGci...eyJzdWIi...")

  2. Cifra ese hash con la llave PRIVADA:
     signature = RSA_private_encrypt(h, private.pem)

  3. El JWT resultante: header.payload.signature
```

```
gateway al recibir el token:

  1. Toma header.payload del token
  2. Recalcula: h_local = SHA256(header.payload)
  3. Descifra la firma con la llave PÚBLICA:
     h_original = RSA_public_decrypt(signature, public.pem)
  4. Compara h_local == h_original
     └── igual   → el token fue firmado por quien tiene private.pem (identity)
     └── distinto → firma inválida, 401
```

**Todo es aritmética local** — cero llamadas HTTP. La verificación tarda microsegundos.

### Cómo lo implementa jwt.lua

`openresty:alpine` no incluye `lua-resty-jwt`. El módulo usa `resty.openssl.pkey` que sí viene built-in desde OpenResty 1.17+:

```lua
local cjson = require "cjson.safe"
local pkey  = require "resty.openssl.pkey"   -- built-in, sin dependencias externas

local _cached_pk  -- variable de módulo: persiste entre requests del mismo worker

local function load_public_key()
    if _cached_pk then return _cached_pk, nil end  -- caché hit: cero I/O
    local f = io.open("/etc/nginx/keys/public.pem", "r")
    local pem = f:read("*a"); f:close()
    _cached_pk = pkey.new(pem, { format = "PEM" })
    return _cached_pk, nil
end

function M.validate()
    -- 1. Extrae Bearer token del header Authorization
    -- 2. Divide en header.payload.signature (3 partes)
    -- 3. Verifica header.alg = "RS256"
    -- 4. pk:verify(signature, header.payload, "sha256", "rsa")
    --    Si falla → ngx.exit(401)  ← nginx para aquí, el upstream nunca recibe la request
    -- 5. Verifica exp > ngx.time() y iss == "identity-service"
    -- 6. Token OK: inyecta headers internos y continúa al proxy_pass
    ngx.req.set_header("X-User-Id",    payload.sub)
    ngx.req.set_header("X-Roles",      table.concat(payload.roles or {}, ","))
    ngx.req.set_header("X-Token-Jti",  payload.jti)
end
```

**El caché de la llave:** `_cached_pk` es una variable de módulo Lua. Se carga una vez por worker en el primer request. Las requests siguientes encuentran `_cached_pk` en RAM y no tocan el disco. No hay estado compartido entre workers nginx — cada uno tiene su copia, pero todos leen la misma llave pública.

**Sin dependencias externas:** la verificación usa `resty.openssl.pkey:verify(sig, data, "sha256", "rsa")` — la misma librería que usa OpenResty internamente para TLS.

### Comparación de enfoques

```
                    HS256 sin llave en gateway    RS256 con llave pública
                    ──────────────────────────    ──────────────────────────────

verificación        HTTP a identity por request   local, aritmética RSA, <1ms
latencia añadida    1–5ms por round-trip          ~0ms
falla de identity   plataforma degradada          gateway sigue funcionando
attack surface      todos los servicios           solo identity-service
                    tienen la misma llave         (privada nunca sale)
puede gateway       no (no tiene la llave)        sí (tiene solo la pública)
verificar solo?                                   y la pública no sirve para firmar
```

---

## Cómo autentica

La autenticación es **stateless y local**: el gateway no llama a `identity-service` en cada request. Carga la llave pública RSA al arrancar (en caché por worker) y verifica la firma criptográfica del token.

```mermaid
flowchart TB
    R["Request con Bearer token"] --> V["conf.d/jwt.lua · M.validate(canal)"]
    V --> S1["1 · Extrae el Bearer del header Authorization"]
    S1 -->|"falta"| X1["401 missing_token"]
    S1 --> S2["2 · Carga public.pem<br/>(en caché por worker de nginx)"]
    S2 -->|"archivo ausente"| X2["500 key_unavailable"]
    S2 --> S3["3 · jwt:verify(pem, token, claim_spec)<br/>exp no vencido · iss = identity-service"]
    S3 -->|"inválido"| X3["401 token_expired · invalid_signature<br/>invalid_issuer · token_invalid"]
    S3 --> S4["4 · ¿El claim channel coincide con el bloque server?"]
    S4 -->|"no"| X4["401 channel_not_allowed"]
    S4 --> OK["X-User-Id = sub<br/>X-Roles = roles<br/>X-Channel = channel<br/>X-Token-Jti = jti"]
    OK --> P["proxy_pass al BFF"]
```

**Por qué sin llamar a identity:** la verificación de firma RSA es criptográficamente suficiente. identity-service solo necesita intervenir para revocar tokens (via lista negra en Redis) — eso se puede agregar al lua si se requiere; por ahora la expiración corta del token (15 min) es el mecanismo principal de revocación.

---

## Formato requerido del token JWT

El gateway **rechaza** cualquier token que no cumpla con todas estas condiciones:

### Header JWT

```json
{
  "alg": "RS256",
  "typ": "JWT"
}
```

| Campo | Valor requerido | Consecuencia si falla |
|---|---|---|
| `alg` | `RS256` | `invalid_signature` — resty.jwt no puede verificar con la llave pública |
| `typ` | `JWT` | Tolerado si falta; error si es otro valor |

### Payload JWT (claims)

```json
{
  "sub":   "00000000-0000-0000-0000-000000000001",
  "iss":   "identity-service",
  "exp":   1750000000,
  "iat":   1749996400,
  "jti":   "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "roles": ["CUSTOMER"],
  "deviceId": "device-abc-123"
}
```

| Claim | Tipo | Requerido | Descripción |
|---|---|---|---|
| `sub` | string (UUID) | **Sí** | Identificador del party. El gateway lo propaga como `X-User-Id` |
| `iss` | string | **Sí** | Debe ser exactamente `"identity-service"`. Rechaza tokens de otros issuers |
| `exp` | número (Unix) | **Sí** | Expiración. Tokens vencidos → 401 `token_expired` |
| `iat` | número (Unix) | No | Fecha de emisión. No verificado activamente por el gateway |
| `jti` | string (UUID) | Recomendado | ID único del token — propagado como `X-Token-Jti` para trazabilidad |
| `roles` | array de strings | Recomendado | Roles del party. Propagados como `X-Roles` (CSV) al upstream |
| `deviceId` | string | No | Propagado por identity; no lo usa el gateway |

### Headers propagados al upstream

Una vez validado el token, el gateway añade estos headers a la request antes de hacer proxy:

| Header | Origen | Ejemplo |
|---|---|---|
| `X-User-Id` | `payload.sub` | `00000000-0000-0000-0000-000000000001` |
| `X-Roles` | `payload.roles` (array → CSV) | `CUSTOMER,ADMIN` |
| `X-Token-Jti` | `payload.jti` | `a1b2c3d4-e5f6-...` |

Los microservicios pueden leer estos headers para autorización fina sin re-validar el JWT.

> **Seguridad:** el gateway **borra** `X-User-Id` y `X-Roles` de la request entrante antes de validar, evitando que un cliente inyecte valores falsos. Solo el gateway los establece, después de verificar la firma.

---

---

## Rutas

El gateway enruta por **subdominio**, no por prefijo de path. Cada BFF tiene su propio bloque
`server` en nginx. Los servicios de dominio (identity, origination, party, scoring…) no aparecen
aquí: sólo son accesibles por la red interna de Docker.

```mermaid
flowchart LR
    H1["mobile.localhost<br/>mobile.fintech-service"] --> M["channel-mobile-service:8085<br/>exige channel = MOBILE"]
    H2["backoffice.localhost<br/>backoffice.fintech-service"] --> B["channel-backoffice-service:8099<br/>exige channel = BACKOFFICE"]
    H3["cualquier otro host"] --> D["default_server<br/>/health → 200 · resto → 404 DENY BY DEFAULT"]
    H4["web.* · admin.* · kyc.*"] -.->|"plantilla, pendientes"| F["futuros bloques server"]
```

**La separación de puertas por canal es el punto.** Un token de la app móvil, con firma
perfectamente válida, recibe `401 channel_not_allowed` en el bloque de backoffice. La firma
demuestra *quién*; el claim `channel` demuestra *por dónde*, y ambas cosas tienen que cuadrar.

### mobile.fintech-service → channel-mobile-service:8085

#### Públicas — sin JWT, con rate limit

| Método | Path | Zona RL | Rate | Burst | `auth_required` log |
|---|---|---|---|---|---|
| `POST` | `/auth/login` | `rl_login` | 5/min | 2 | `false` |
| `POST` | `/auth/refresh` | `rl_refresh` | 10/min | 5 | `false` |
| `POST` | `/auth/register` | `rl_register` | 3/min | 1 | `false` |
| `POST` | `/otp/send` | `rl_otp_send` | 3/min | 1 | `false` |
| `POST` | `/otp/verify` | `rl_otp_verify` | 10/min | 3 | `false` |
| `POST` | `/otp/resend` | `rl_otp_send` | 3/min | 1 | `false` |
| `POST` | `/ocr/extract` | `rl_ocr` | 5/min | 2 | `false` |
| `POST` | `/kyc/submit` | `rl_kyc` | 3/min | 1 | `false` |

Al exceder el rate limit: `429 Too Many Requests` + `Retry-After: 60`.

> **Onboarding:** `/ocr/extract` y `/kyc/submit` son públicas porque el usuario aún no tiene cuenta. La protección es el rate limit + el UUID `folioKyc` no adivinable.

#### Protegidas — JWT RS256 requerido

| Método | Path | `auth_required` log |
|---|---|---|
| `POST` | `/auth/logout` | `true` |
| `GET` | `/credit/products` | `true` |
| `POST` | `/credit/applications` | `true` |
| `GET` | `/credit/applications` | `true` |
| `GET` | `/credit/applications/{id}` | `true` |
| `GET` | `/users/me` | `true` |
| `GET` | `/credit/account` | `true` |
| Todo lo demás (`/`) | * | `true` |

### backoffice.fintech-service → channel-backoffice-service:8099  *(activo)*

BFF de la consola operativa. Su bloque `server` valida el JWT **exigiendo el canal**: `access_by_lua_block { require("jwt").validate("BACKOFFICE") }`. Un token de la app móvil (`channel=MOBILE`), aunque su firma sea válida, recibe `401 channel_not_allowed` — la separación de puertas por canal (ver §"Cómo autentica").

| Método | Path | JWT | Nota |
|---|---|---|---|
| `POST` | `/auth/staff/login` · `/auth/staff/refresh` | público (rate limit) | login de personal |
| Todo lo demás (`location /`) | * | JWT RS256 con `channel=BACKOFFICE` | catch-all: las rutas nuevas del BFF se enrutan solas |

El catch-all `location /` es deliberado: al agregar un endpoint al BFF de backoffice no hay que tocar el gateway. El gateway inyecta `X-User-Id` / `X-Roles` / `X-Channel`; el BFF los reenvía a los dominios.

### Cualquier otro host → default_server

```
GET /health   → 200 {"status":"ok","service":"fintech-gateway"}
/*            → 404 DENY BY DEFAULT
```

### Futuros BFF (plantilla en nginx.conf)

```
web.fintech-service   →  web-bff-service:808X    (pendiente)
admin.fintech-service →  admin-bff-service:808X  (pendiente)
```

Añadir un nuevo BFF = agregar un `server { server_name <subdomain>; ... }` en nginx.conf y el upstream correspondiente. Los domain services internos no requieren ningún cambio en el gateway.

---

## Estructura de archivos

```
gateway-service/
├── nginx.conf            ← configuración principal de OpenResty
│                            (upstreams, zonas de rate limit, locations)
├── conf.d/
│   └── jwt.lua           ← módulo Lua de validación JWT RS256
│                            (cargado con require("jwt").validate())
└── keys/
    ├── .gitkeep          ← instrucciones para generar las llaves
    └── public.pem        ← llave pública RSA (NO commitear private.pem)
```

---

## Generar el par de llaves (una sola vez)

```bash
# Desde la raíz del monorepo

# 1. Llave privada 2048-bit en formato PKCS#8 (SOLO para identity-service — NUNCA en git)
#    IMPORTANTE: usar genpkey, no genrsa.
#    genrsa produce PKCS#1 ("BEGIN RSA PRIVATE KEY") — JwtAdapter.java espera PKCS#8 ("BEGIN PRIVATE KEY")
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out services/identity-service/src/main/resources/keys/private.pem

# 2. Llave pública derivada (para el gateway — SÍ puede ir en git)
openssl rsa \
  -in  services/identity-service/src/main/resources/keys/private.pem \
  -pubout \
  -out services/gateway-service/keys/public.pem

# Verificar formato correcto:
head -1 services/identity-service/src/main/resources/keys/private.pem
# Debe decir: -----BEGIN PRIVATE KEY-----   (PKCS#8)
# Si dice:    -----BEGIN RSA PRIVATE KEY--- (PKCS#1) → identity-service falla al arrancar
```

`public.pem` debe estar presente en `services/gateway-service/keys/` antes de iniciar el contenedor. El archivo ya está en `.gitignore` con la entrada `**/keys/private.pem`.

---

## Logs de auditoría

Cada request genera una línea JSON en stdout. El formato captura todos los campos necesarios para auditoría y correlación:

```json
{
  "time":             "2026-06-29T16:41:25+00:00",
  "request_id":       "537d53339981627d4eeb94741150b1c5",
  "source_ip":        "142.44.139.22",
  "host":             "mobile.localhost",
  "method":           "POST",
  "uri":              "/auth/login",
  "query":            "",
  "user_agent":       "MobileBankingApp/1.0 iOS/17.5",
  "status":           200,
  "bytes_sent":       1163,
  "upstream":         "172.19.0.12:8085",
  "user_id":          "",
  "auth_required":    "false",
  "duration_ms":      0.215,
  "limit_req_status": "PASSED"
}
```

| Campo | Descripción | Valores posibles |
|---|---|---|
| `request_id` | ID único por request (hex 32 chars) — correlaciona logs del gateway con logs del BFF | uuid hex |
| `source_ip` | IP real del cliente | IPv4 / IPv6 |
| `user_id` | `sub` del JWT validado. Vacío en rutas públicas (usuario no autenticado) | UUID del party / `""` |
| `auth_required` | Indica si la ruta exige JWT | `"true"` / `"false"` |
| `bytes_sent` | Tamaño de la respuesta en bytes | número |
| `user_agent` | Client identifier — útil para distinguir app móvil de bots | string |
| `limit_req_status` | Resultado del rate limiting | `"PASSED"` / `"DELAYED"` / `"REJECTED"` / `""` (sin zona RL) |
| `upstream` | IP:puerto del microservicio que respondió. Vacío si el gateway respondió solo (401 sin token) | `"172.x.x.x:8085"` / `""` |
| `duration_ms` | Tiempo total de la request en segundos (resolución ms) | float |

### Para detectar ataques con los logs

```bash
# Intentos de fuerza bruta en login (muchos 401 del mismo source_ip)
docker logs fintech-services-gateway-service-1 2>&1 \
  | grep '"uri":"/auth/login"' | grep '"status":401' \
  | grep -o '"source_ip":"[^"]*"' | sort | uniq -c | sort -rn | head

# Requests a rutas protegidas sin token (potencial exploración de endpoints)
docker logs fintech-services-gateway-service-1 2>&1 \
  | grep '"auth_required":"true"' | grep '"status":401' \
  | grep -o '"uri":"[^"]*"' | sort | uniq -c | sort -rn

# Requests lentas (upstream lento o cuelgue)
docker logs fintech-services-gateway-service-1 2>&1 \
  | python3 -c "
import sys, json
for line in sys.stdin:
    try:
        r = json.loads(line)
        if r.get('duration_ms', 0) > 1.0:
            print(f'{r[\"duration_ms\"]:.3f}s {r[\"method\"]} {r[\"uri\"]} → {r[\"status\"]}')
    except: pass
"
```

---

## Levantar

```bash
# Levantar solo el gateway (requiere que los servicios ya corran)
docker compose up gateway-service

# Levantar toda la plataforma
docker compose up -d

# Ver logs de autenticación
docker compose logs -f gateway-service
```

El gateway arranca en `http://localhost:8080`. Los servicios ya no tienen puertos expuestos directamente.

---

## Probar

### Health check
```bash
curl http://localhost:8080/health
# {"status":"ok","service":"fintech-gateway"}
```

### Sin token → 401
```bash
curl -s http://localhost:8080/api/v1/origination/applications
# {"error":"unauthorized","reason":"missing_token"}
```

### Token inválido → 401
```bash
curl -s -H "Authorization: Bearer token.falso.aqui" \
  http://localhost:8080/api/v1/parties/some-id
# {"error":"unauthorized","reason":"invalid_signature"}
```

### Obtener token y usarlo
```bash
# 1. Login
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"juan@email.com","password":"1234"}' \
  | jq -r .accessToken)

# 2. Request autenticada
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/origination/applications
```

### Rate limit en login
```bash
# 8 requests rápidas al login → las últimas deben devolver 429
for i in $(seq 1 8); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"test","password":"wrong"}'
done
# Esperado: 401 401 401 401 401 429 429 429
```

### OTP rate limit
```bash
# /mobile/otp/send: 3/min burst 1 → tercer intento debe ser 429
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/mobile/otp/send \
    -H "Content-Type: application/json" \
    -d '{"phone":"+521234567890"}'
done
# Esperado: 200 429 429 429
```

---

## Respuestas de error del gateway

Todas las respuestas de error propias del gateway son JSON con `Content-Type: application/json`.

| Situación | Status | Body |
|---|---|---|
| Sin token | 401 | `{"error":"unauthorized","reason":"missing_token"}` |
| Header mal formado | 401 | `{"error":"unauthorized","reason":"malformed_authorization_header"}` |
| Token expirado | 401 | `{"error":"unauthorized","reason":"token_expired"}` |
| Firma inválida | 401 | `{"error":"unauthorized","reason":"invalid_signature"}` |
| Issuer incorrecto | 401 | `{"error":"unauthorized","reason":"invalid_issuer"}` |
| Token inválido (otro) | 401 | `{"error":"unauthorized","reason":"token_invalid"}` |
| Rate limit excedido | 429 | `{"error":"rate_limited","reason":"too many requests — please retry after 60 seconds"}` + `Retry-After: 60` |
| Llave pública no encontrada | 500 | `{"error":"gateway_error","reason":"key_unavailable"}` |
| Ruta no mapeada | 404 | `{"error":"not_found","reason":"no route matches the requested path"}` |

Los errores de los microservicios (4xx/5xx propios de cada servicio) se propagan tal cual al cliente — el gateway no los modifica.

---

## Variables de configuración

No hay variables de entorno propias — toda la configuración es en `nginx.conf` y `conf.d/jwt.lua`. Para cambiar un parámetro:

| Parámetro | Dónde | Default |
|---|---|---|
| Puerto externo | `docker-compose.yml` → `GATEWAY_PORT` | `8080` |
| Ruta de la llave pública | `conf.d/jwt.lua` línea `PUBLIC_KEY_PATH` | `/etc/nginx/keys/public.pem` |
| Issuer esperado | `conf.d/jwt.lua` línea `ISSUER` | `"identity-service"` |
| Rate `/auth/login` | `nginx.conf` zona `rl_login` | `5r/m` burst `2` |
| Rate `/otp/send` | `nginx.conf` zona `rl_otp_send` | `3r/m` burst `1` |
| `Retry-After` en 429 | `nginx.conf` `@rate_limited` | `60` segundos |

---

## Notas de seguridad

- **Header injection**: `X-User-Id` y `X-Roles` enviados por el cliente son **borrados** antes de llegar al microservicio. Solo el gateway los establece tras validar el JWT.
- **Errores sanitizados**: los mensajes de error del gateway nunca exponen detalles internos (path de archivos, stack trace, razones de la librería jwt).
- **Rate limit por IP**: la clave es `$binary_remote_addr`. Si el gateway corre detrás de otro proxy (load balancer en producción), cambiar la clave a `$http_x_forwarded_for` o `$http_x_real_ip` según el header que el proxy establezca.
- **Multi-réplica**: el estado del rate limit es **en memoria por worker**. Si se despliegan múltiples réplicas del gateway, los límites no se comparten. Para producción distribuida, migrar a `lua-resty-limit-traffic` con Redis como backend (el Redis del `docker-compose.yml` ya está disponible en la misma red).
