# D10 — Disbursement [Supporting]

**Estado:** 🆕 Nuevo (2026-08-11)
**Tipo:** Supporting
**Schema DB:** `disbursement`
**Puerto:** 8100 (local) · 8080 (docker)
**Paquete Java:** `com.fintech.disbursement`

---

## Responsabilidad

Orquestación de pagos salientes multi-rail y multi-empresa. Decide qué pagar, a quién, por qué rail
y con qué proveedor. **No ejecuta el pago** — eso es de los conectores.

**Servicio interno.** No está publicado en el gateway.

---

## Tablas

| Tabla | Para qué |
|---|---|
| `disbursement_orders` | Raíz de agregado. Sin una sola columna del dominio de crédito |
| `disbursement_events` | Bitácora inmutable de transiciones (DB-06) |
| `routing_rules` | (empresa, rail, rango de monto) → proveedor. Cambiar de proveedor es un `INSERT` |
| `company_mappings` | Clave de empresa del emisor → `companyId` de este servicio |

---

## API

| Método | Ruta | Rol | Nota |
|---|---|---|---|
| `POST` | `/api/v1/disbursements` | ADMIN · OPS_SUPERVISOR · SERVICE | `202 Accepted`, no `201`: la orden queda registrada, el pago aún no ocurrió. Idempotente por `Idempotency-Key` |
| `GET` | `/api/v1/disbursements/{id}` | ADMIN · OPS_SUPERVISOR · AUDITOR | La cuenta sale enmascarada |
| `GET` | `/api/v1/disbursements/{id}/events` | ADMIN · OPS_SUPERVISOR · AUDITOR | Bitácora completa |
| `GET` | `/api/v1/disbursements?companyId=` | ADMIN · OPS_SUPERVISOR · AUDITOR | |
| `POST` | `/api/v1/disbursements/{id}/cancel` | ADMIN · OPS_SUPERVISOR | Sólo antes de despachar |
| `POST` | `/api/v1/disbursements/routing` | ADMIN | Alta de regla |
| `POST` | `/api/v1/disbursements/routing/{id}/enabled` | ADMIN | Apagar un proveedor en un incidente |
| `POST` | `/api/v1/disbursements/company-mappings` | ADMIN | |

Auth: header-trust (`X-User-Id` / `X-Roles` que inyecta el gateway), igual que el resto del
monorepo.

---

## Eventos

**Consume:** `credit-portfolio.credit-account-activated` · `credit-portfolio.disposition-authorized`
· `wallet.withdrawal-completed` · resultados de los conectores (`stp.order-*` por default,
configurables).

**Publica:** `disbursement.accepted` · `disbursement.completed` · `disbursement.failed` ·
`disbursement.returned`.

Errores deterministas (empresa sin mapeo, CLABE inválida) van directo al DLT sin gastar reintentos:
reintentarlos sólo retrasa el diagnóstico. Cada mensaje en el DLT es un desembolso que no salió y
necesita a alguien mirándolo.

---

## Jobs

| Job | Cadencia | Qué hace |
|---|---|---|
| `DisbursementDispatchJob` | 5 s | Toma un lote con `FOR UPDATE SKIP LOCKED` y lo entrega al conector. Sin coordinador externo |

`DISBURSEMENT_DISPATCH_ENABLED=false` congela los pagos sin perder órdenes ni tumbar el servicio.

---

## Pruebas

| Suite | Qué cubre |
|---|---|
| `DisbursementOrderTest` | Máquina de estados, DB-01/03/08, PII |
| `OperatingWindowTest` | Ventana envolvente, bordes, 24 h, días hábiles |
| `RoutingRuleTest` | Cobertura, rangos, especificidad |
| `ClabeValidatorTest` | Dígito verificador de Banxico |
| `DisbursementDecouplingTest` | ArchUnit: vocabulario, capas, dependencias entre servicios |

---

Dominio: [`../../dominios/10_disbursement_domain.md`](../../dominios/10_disbursement_domain.md) ·
Servicio: [`services/disbursement-service/README.md`](../../../services/disbursement-service/README.md)
