# D4 — Credit Product (Catálogo)

Catálogo **versionado** de definiciones de producto. Fuente de verdad del diseño del producto, no de la cuenta activa. Ver especificación profunda: [docs/dominios/04_credit_product_domain.md](../../dominios/04_credit_product_domain.md).

**Servicio:** `credit-product-service` · **Puerto:** 8084 · **Schema:** `credit_product`

> **No confundir con credit-portfolio-service (D4★)**: este módulo define *qué es* un producto; credit-portfolio administra *las cuentas vivas*.

---

## Productos disponibles

| Código | Tipo | Audiencia | Behavior | Amortización |
|---|---|---|---|---|
| `PL-IND-STD-V1` | PERSONAL_LOAN | B2C | INSTALLMENT | FRENCH |
| `RL-IND-STD-V1` | REVOLVING_LINE | B2C | REVOLVING | — |
| `PY-IND-STD-V1` | PAYROLL_LOAN | B2C | INSTALLMENT | FRENCH |
| `DL-DIST-STD-V1` | DISTRIBUTOR_LINE | B2B2C | REVOLVING | BULLET |
| `GL-IND-STD-V1` | GROUP_LOAN | B2C | INSTALLMENT | GERMAN |
| `CC-IND-STD-V1` | CREDIT_CARD | B2C | REVOLVING | — |
| `ML-IND-STD-V1` | MICRO_LOAN | B2C | INSTALLMENT | FRENCH |
| `SME-LOAN-STD-V1` | SME_LOAN | **B2B** | INSTALLMENT | FRENCH |
| `BRL-BUS-STD-V1` | BUSINESS_REVOLVING_LINE | **B2B** | REVOLVING | — |

---

## Configurabilidad

### Capabilities JSONB
Cada definición lleva una matriz que governa el motor de credit-portfolio:

```json
{
  "hasAmortizationSchedule": true/false,
  "hasCreditLimit": true/false,
  "allowsMultipleDispositions": true/false,
  "dispositionType": "SELF_USE|THIRD_PARTY_CREDIT|PAYROLL",
  "hasCutoffDate": true/false,
  "hasMinimumPayment": true/false,
  "allowsMultipleObligors": true/false,
  "commissionsEnabled": true/false,
  "requiresBeneficiaryPartyId": true/false
}
```

Se derivan automáticamente del `productType`; se pueden sobrescribir por definición.

### Rate cards — precios por banda
Tasas diferenciadas por tier de riesgo, tramo de monto o plazo. La fila más específica gana:

```
PERSONAL_LOAN: T1=28%, T2=32%, T3=38%
SME_LOAN: $50k-$500k=26%, $500k-$2M=22%, $2M+=$18%
DISTRIBUTOR_LINE: $100k-$1M=22%, $1M+=18%
BUSINESS_REVOLVING: T1=16%, T2=18%, T3=22%
```

### Eligibility rules — restricciones por producto
Configurables sin cambios de código: `MIN_AGE`, `MAX_AGE`, `MIN_SCORE`, `MAX_EXISTING_ACTIVE_CREDITS`, `MIN_MONTHLY_INCOME`, `MAX_DEBT_TO_INCOME_RATIO`, `REQUIRED_PARTY_TYPE`, `MIN_SENIORITY_MONTHS`, `MIN_GROUP_MEMBERS`, `MAX_GROUP_MEMBERS`.

---

## Versionado

```
productCode (estable)  →  productVersion (1, 2, 3…)
                           solo una versión ACTIVE por código a la vez
                           activar V2 → V1 pasa a RETIRED automáticamente
```

---

## Estado de implementación

| Componente | Estado |
|---|---|
| Domain (entidad, enums, capabilities, rate cards, eligibility rules) | ✅ |
| Migrations 001-012 (schema, seeds B2C + B2B, rate_cards, eligibility_rules) | ✅ |
| API REST (10 endpoints) | ✅ |
| Versionado con partial unique index | ✅ |
| Capabilities JSONB (`@JdbcTypeCode`) | ✅ |
| Kafka events (ProductActivated, ProductRetired) | ✅ |
| JWT security (GETs públicos, mutations auth) | ✅ |
| Unit tests (30) + IT Testcontainers (23) | ✅ |

Ver README del servicio: [services/credit-product-service/README.md](../../../services/credit-product-service/README.md)
