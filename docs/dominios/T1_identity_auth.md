# T1 — Identity & Auth [Transversal]

> Emisor único de identidad y **firmante de los JWT** de toda la plataforma. Dos poblaciones: **clientes** (app móvil, credenciales NIP/PASSWORD) y **personal** (backoffice, roles + capacidades). Firma RS256; el gateway valida con la llave pública. Ningún otro servicio ve la llave privada.

**Servicio:** `identity-service` · **Schema:** `identity` · **Puerto:** 8080

> Reconciliado con el código (2026-08).

## 1. Modelo — dos identidades

| Agregado | Población | Notas |
|---|---|---|
| `Client` + `IdentityCredential` | Cliente móvil | `CredentialType`: NIP · PASSWORD; `CredentialStatus`: ACTIVE · LOCKED · DISABLED; bloqueo por intentos (`AccountLockedException`). |
| `StaffUser` + `Role` | Personal backoffice | `StaffRole` (14): ADMIN, OPS_SUPERVISOR, CREDIT_ANALYST, UNDERWRITER, COMMITTEE, EXECUTIVE, COMMERCIAL_MANAGER, COLLECTIONS_AGENT, RISK_ANALYST, PRODUCT_MANAGER, FINANCE, MARKETING, AUDITOR, SUPPORT. `curp` (nulable — la exige la bitácora para identificar plenamente al colaborador; el personal anterior a la columna no la tiene). `EmployeeType`: INTERNO · COLABORADOR_EMPRESARIAL. `StaffStatus`: ACTIVE · SUSPENDED · LOCKED · DISABLED. |
| `Device` | Dispositivo del cliente | `DeviceStatus`: ACTIVE · BLOCKED (device binding). |
| `MfaConfig` | MFA (TOTP) | enroll/confirm/verify. |
| `ClientIpEntry` | Whitelist de IP | Para clientes `SERVICE`. |
| Role capabilities | Matriz rol→capacidades | **Editable**; gobierna los permisos del backoffice. |

## 2. JWT (RS256) — el contrato de identidad

Firma con llave **PKCS#8** (`JwtAdapter`). Claims: `sub` (partyId/staffUserId), `roles`, `deviceId`, **`channel`** (`Channel`: MOBILE · BACKOFFICE · SERVICE), `jti`, `iss=identity-service`, `exp`. El **canal separa las puertas**: un token MOBILE no abre el backoffice aunque su firma sea válida (`ChannelMismatchException` / el gateway responde `channel_not_allowed`). El gateway valida la firma **localmente** con `public.pem` — no llama a identity por request.

> Invariante de custodia: **solo identity** tiene `private.pem`; **solo el gateway** tiene `public.pem`; los servicios internos confían en los headers `X-User-Id`/`X-Roles`/`X-Channel` que inyecta el gateway.

## 3. API REST

| Base | Endpoints |
|---|---|
| `/api/v1/auth` | `POST /login`, `/refresh`, `/logout`, `/token`, `/verify`; `GET /validate`, `/me`, `/credentials/lookup`; `POST /credentials` |
| `/api/v1/auth/staff` | login/refresh/logout/me del personal |
| `/api/v1/auth/mfa` | `POST /enroll`, `/enroll/confirm`, `/verify` |
| `/api/v1/auth/clients` | `GET/POST /{clientId}/whitelist` (IP) |
| `/api/v1/staff` | alta/roles/password/suspend/reactivate del personal. Todo exige ADMIN, **salvo `GET /api/v1/staff/{id}`**, que además acepta el rol de servicio `SERVICE_DIRECTORY`: es como el canal resuelve *quién actuó* para la bitácora sin depender de los permisos del que actúa. Ese rol no da de alta, no cambia roles y no toca contraseñas. |
| `/api/v1/roles` | `GET`, `PUT /{code}/capabilities` (matriz de permisos editable) |
| `/api/v1/executives` | selector de ejecutivos (id+nombre) para la red comercial |

## 4. Eventos Kafka

**Consume:** `origination.prospect-created` (aprovisiona credenciales del cliente; recibe el `password` raw y lo hashea con BCrypt).

**Produce:** `identity.login-attempted` (→ audit — el actor de la seguridad: quién entró y quién falló al entrar).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 5. Persistencia

Liquibase (schema `identity`, changelog YAML): `credentials`, `clients`, `client_ip_whitelist`, `mfa_config`, `login_session_metadata`, `devices`, `staff_users`, seed bootstrap admin, `role_capabilities`, `012-add-staff-curp`, `013-seed-audit-directory-client`.

## 6. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| RS256, no HS256 | El gateway valida con la pública sin conocer el secreto de firma; identity es el único firmante. |
| Canal como claim que separa puertas | Un token de la app no abre el backoffice aunque los roles alcancen. |
| Capacidades por rol editables | Los permisos del backoffice se administran como datos, no como código. |
| Aprovisionar credenciales por evento | El alta de persona vive en origination; identity reacciona al hecho. |
