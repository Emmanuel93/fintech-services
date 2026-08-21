# commission-service (T6)

**Comisiones de la red comercial.** Devenga lo que cada crédito le genera al promotor o
distribuidor que lo originó, lo acumula y lo **liquida** en corridas, con reversa cuando el crédito
se cae. Es el consumidor natural de la resolución `promoterCode → distribuidor` que hace
originación.

| | |
|---|---|
| **Puerto** | `8097` (bootRun) · `:8080` interno en Docker |
| **Schema** | `commission` |
| **Arquitectura** | Hexagonal + Spring Modulith |
| **Régimen Kafka** | Por defecto (sin DLT) |
| **Dominio** | [docs/dominios/T6_commission.md](../../docs/dominios/T6_commission.md) |

---

## 1. Mapa del servicio

```mermaid
flowchart LR
    subgraph in["Entrada"]
        L1["credit-portfolio.credit-account-activated"]
        L2["credit-portfolio.balance-updated"]
        REST["CommissionController<br/>/api/v1/commissions"]
        JOB["CommissionLiquidationJob<br/>día 5 · 03:30"]
    end

    subgraph app["Aplicación"]
        ACC["Devengo según política"]
        LIQ["Corrida de liquidación"]
        REV["Reversa"]
        SH["Proyección de saldo"]
    end

    subgraph dom["Dominio"]
        CP2(("CommissionPolicy"))
        CR(("CommissionRecord"))
        CPA(("CreditPromoterAssignment"))
        LB(("LiquidationBatch"))
        ABS(("AccountBalanceShadow"))
    end

    L1 --> ACC
    L1 --> CPA
    L2 --> SH
    L2 --> ACC
    REST --> ACC & LIQ & CP2
    JOB --> LIQ
    ACC --> CR
    LIQ --> LB
    REV --> CR
    SH --> ABS
    CP2 & CR & CPA & LB & ABS --> DB[("PostgreSQL<br/>schema commission")]
    ACC --> K1["commission.commission-accrued"]
    LIQ --> K2["commission.commission-liquidated"]
    REV --> K3["commission.commission-reversed"]
    K1 & K2 & K3 --> ACCT["accounting"]
```

---

## 2. Dominio

| Agregado | Rol |
|---|---|
| `CommissionPolicy` | Regla de cálculo por producto/segmento — `DRAFT` · `ACTIVE` · `DEPRECATED` |
| `CommissionRecord` | Comisión devengada de un crédito — `ACCRUED` → `LIQUIDATED` \| `REVERSED` |
| `CreditPromoterAssignment` | Vínculo crédito → promotor o distribuidor beneficiario |
| `LiquidationBatch` | Corrida que paga lo devengado pendiente — `PENDING` → `SENT` → `CONFIRMED` |
| `AccountBalanceShadow` | Read model del saldo, desde `balance-updated` |

### Tipos de comisión

| `CommissionType` | Cuándo se devenga | Para quién |
|---|---|---|
| `DISTRIBUTOR_INTEREST_SHARE` | Por pago cobrado (*trailing*, contra cobranza) | Distribuidor B2B2C |
| `ORIGINATION_FEE` | Una sola vez, al activar | Promotor de red propia (B2C) |
| `COLLECTION_BONUS` | Por pago recuperado en un caso asignado | Gestor de cobranza |
| `RENEWAL_BONUS` | Al reactivar o renovar el producto | Promotor original |

> `DISTRIBUTOR_INTEREST_SHARE` se devenga **contra el interés efectivamente cobrado**, no contra el
> facturado: una comisión que se paga sobre lo que nadie cobró es un quebranto con otro nombre.

```mermaid
stateDiagram-v2
    [*] --> ACCRUED : el crédito genera comisión
    ACCRUED --> LIQUIDATED : la corrida la paga
    ACCRUED --> REVERSED : el crédito se cae / se reversa el pago
    LIQUIDATED --> [*]
    REVERSED --> [*]
    note right of LIQUIDATED
        isTerminalForEditing(): ni LIQUIDATED
        ni REVERSED admiten edición.
    end note
```

---

## 3. API REST — `/api/v1/commissions`

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/accounts/{creditAccountId}` | Comisiones generadas por un crédito |
| `GET` | `/beneficiaries/{partyId}/pending` | Lo devengado pendiente de liquidar |
| `GET` | `/promoters/credits` | Créditos por promotor |
| `GET` · `POST` | `/policies` | Catálogo de políticas |
| `POST` | `/liquidation-runs` | Disparar una corrida a mano |

---

## 4. Jobs programados

| Job | Cron | Qué hace |
|---|---|---|
| `CommissionLiquidationJob` | `0 30 3 5 * *` | Día 5 de cada mes, 03:30 — liquida lo devengado del periodo |

---

## 5. Eventos Kafka

**Consume:** `credit-portfolio.credit-account-activated` (crea el vínculo y devenga lo *upfront*) ·
`credit-portfolio.balance-updated` (devenga lo *trailing*).

**Produce:** `commission.commission-accrued` · `commission.commission-liquidated` ·
`commission.commission-reversed` — los tres a **accounting**.

**Reintentos:** régimen por defecto, sin DLT.
Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

---

## 6. Persistencia y ejecución

Schema `commission`, 7 changesets Liquibase bajo `db/changelog/commission/`.

```bash
./gradlew :commission-service:test
docker compose up -d commission-service
```
