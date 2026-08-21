# D0 — Party [Core Domain · Sujeto del crédito]

> **Quién** es el cliente: identidad, estatus, KYC, consentimientos, perfil fiscal, **roles** (distribuidor/aval/beneficiario) y relaciones entre partes. Es el sujeto que otros dominios referencian por `partyId`. No sabe de crédito ni de saldos.

**Servicio:** `party-service` · **Schema:** `party` · **Puerto:** 8083 (bootRun) / 8080 (Docker)

> Reconciliado con el código (2026-08).

## 1. Agregados

| Agregado | Rol |
|---|---|
| `Party` [root] | Persona/empresa (`PartyType`: INDIVIDUAL · BUSINESS; `PartyStatus`: PROSPECT · ACTIVE · SUSPENDED · BLACKLISTED · CLOSED). |
| `PartyRole` | Rol adicional sobre la misma parte (`PartyRoleType`: DISTRIBUTOR · GUARANTOR · BENEFICIARY) — **no** se crea un PartyType nuevo (I-03). |
| `PartyRelationship` | Relación entre partes (aval, representante…). |
| `KycVerification` | Verificación KYC y su estatus. |
| `ConsentRecord` | Consentimientos (privacidad, buró). |
| Perfil fiscal | RFC, régimen, uso CFDI (para invoicing). |

**Invariante I-03:** un distribuidor es **party + rol** `DISTRIBUTOR`, no un `PartyType` aparte — así el mismo sujeto puede ser cliente y distribuidor sin duplicarse.

## 2. API REST — `/api/v1/parties`

| Método | Ruta | Uso |
|---|---|---|
| `GET` | `/{partyId}` · `/by-prospect/{prospectId}` | Ficha del sujeto. |
| `GET` | `/batch?ids=` | Resuelve nombres de varias partes en **una** consulta (mata el N+1 del BFF). |
| `GET` | `/{partyId}/kyc-verifications` | KYC de la parte. |
| `PUT` | `/{partyId}/kyc-status` · `/fiscal-profile` · `/executive` | Actualizar estatus/perfil/ejecutivo asignado. |
| `POST` | `/{partyId}/blacklist` | Marcar en lista negra. |
| `GET`/`POST`/`DELETE` | `/{partyId}/roles`, `/roles/{roleType}` | Administración de roles (I-03). |

> La búsqueda paginada de partes se apoya en índices trigram (`pg_trgm`) para `LIKE` sobre nombre/CURP/RFC.

## 3. Eventos Kafka

**Consume:** `origination.prospect-created` (crea el `Party` en estatus PROSPECT).

**Produce:** `party.fiscal-profile-updated` (→ invoicing), `party.party-blacklisted`, `party.role-granted`, `party.role-revoked` (estos tres sin consumidor en el código actual — traza/futuro).

**Retry:** régimen por defecto (sin DLT). Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

## 4. Persistencia

Liquibase, schema `party` (10 changesets): `parties`, `kyc_verifications`, `consent_records`, `party_relationships`, `party_roles` (010), perfil fiscal (007), asignación de ejecutivo (009), índices de búsqueda trigram (008).

## 5. Decisiones de diseño

| Decisión | Por qué |
|---|---|
| Distribuidor = rol, no PartyType (I-03) | El mismo sujeto puede tener varios roles sin duplicar identidad. |
| `/batch` de partes | El BFF resuelve nombres de una tabla sin N+1. |
| Perfil fiscal aquí, factura en invoicing | Party es dueño de la identidad fiscal; invoicing la proyecta. |
