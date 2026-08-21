# D11 — STP Connector [Generic Subdomain / ACL]

**Estado:** 🆕 Nuevo (2026-08-11)
**Tipo:** Generic Subdomain — anti-corruption layer
**Schema DB:** `stp`
**Puerto:** 8101 (local) · 8080 (docker)
**Paquete Java:** `com.fintech.stp`

---

## Responsabilidad

Conector con STP (dispersión SPEI). Arma la cadena original, la firma con la llave de la empresa,
registra la orden y consulta su liquidación.

**Sin exposición a internet.** STP nunca llama a la plataforma; la única dirección hacia afuera es
egress TLS iniciado por este servicio.

---

## Tablas

| Tabla | Para qué |
|---|---|
| `companies` | Catálogo multi-empresa: `stpEmpresa`, prefijo de clave de rastreo |
| `company_keys` | Material criptográfico con envelope encryption. La KEK vive fuera |
| `ordering_accounts` | Cuenta ordenante por empresa |
| `payment_orders` | Órdenes registradas. Guarda el nombre completo **y** el truncado que se firmó |
| `payment_order_events` | Bitácora de transiciones |
| `settlement_observations` | Lo observado en cada corrida de conciliación, con su sello |
| `tracking_key_sequences` | Secuencia por empresa y día, con `ON CONFLICT DO UPDATE ... RETURNING` |
| `outbox_messages` | Outbox transaccional: nunca se llama a STP dentro de la transacción de persistencia |
| `stub_orders` | Estado del stub de ambientes bajos. Sobrevive reinicios |

---

## API

| Método | Ruta | Rol |
|---|---|---|
| `POST` | `/api/v1/stp/companies` · `/keys` · `/ordering-accounts` | ADMIN |
| `POST` | `/api/v1/stp/poll` | ADMIN · OPS_SUPERVISOR |
| `GET` | `/api/v1/stp/orders/**` | ADMIN · OPS_SUPERVISOR · AUDITOR |

No hay endpoints públicos de negocio. Auth header-trust.

---

## Jobs

| Job | Cadencia | Qué hace |
|---|---|---|
| `StpOutboxRelayJob` | 5 s | Firma y despacha lo pendiente, fuera de la transacción de persistencia |
| `StpSettlementPollingJob` | 3 min | Agrupa órdenes en vuelo por (empresa, día hábil) y pagina `V2/conciliacion` |

---

## Pruebas

| Suite | Qué cubre |
|---|---|
| `CadenaOriginalBuilderTest` | Vectores dorados, 34/3/3 campos, `Locale.ROOT`, nulos, truncado a 40 |
| `StpSignerTest` | Firma y verificación; `verify()` devuelve `false`, no lanza |
| `BanxicoResponseCodeTest` | 32 códigos + `UNKNOWN`; el bug B1 (`-200`) |
| `StpOrderStatusCodeTest` | Mapeo completo de estados (bug B8) |
| `BeneficiaryNameMatcherTest` | Normalización NFD y truncado |
| `TrackingKeyTest` · `ClabeValidatorTest` | |
| `StpDecouplingTest` | ArchUnit: sin dependencias de otros servicios; firma sin Spring |

---

Dominio: [`../../dominios/11_stp_connector_domain.md`](../../dominios/11_stp_connector_domain.md) ·
Servicio: [`services/stp-service/README.md`](../../../services/stp-service/README.md)
