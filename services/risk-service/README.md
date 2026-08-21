# risk-service (D9)

**Riesgo de la cuenta viva** — distinto de [scoring](../scoring-service/README.md), que evalúa a la
persona *antes* de originar. risk mantiene el **perfil IFRS-9 por cuenta** (etapa 1/2/3, bucket de
atraso, provisión) a partir de la actividad de cartera, administra las **políticas de provisión**
por tipo de producto y publica la valuación para contabilidad.

| | |
|---|---|
| **Puerto** | `8094` (bootRun) · `:8080` interno en Docker |
| **Schema** | `risk` |
| **Arquitectura** | Hexagonal + Spring Modulith |
| **Régimen Kafka** | Por defecto (sin DLT) |
| **Dominio** | [docs/dominios/09_risk_domain.md](../../docs/dominios/09_risk_domain.md) |

---

## 1. Mapa del servicio

```mermaid
flowchart LR
    subgraph in["Entrada"]
        L1["credit-account-activated"]
        L2["balance-updated"]
        L3["delinquency-status-updated"]
        L4["collections.agreement-executed"]
        JOB["RiskAssessmentJob · 01:00"]
        REST["RiskController<br/>/api/v1/risk"]
        TS["/internal/test-support (dev-only)"]
    end

    subgraph app["Aplicación"]
        RA["Reevaluación del perfil"]
        PP["Políticas de provisión"]
    end

    subgraph dom["Dominio (funciones puras)"]
        SR["Ifrs9StageResolver<br/>ES-01 · RC-04 · RC-05"]
        DB2["DelinquencyBucket.fromDaysDelinquent<br/>RC-01"]
        RP(("RiskProfile"))
        POL(("ProvisionPolicy<br/>+ ProvisionRateBand"))
    end

    L1 & L2 & L3 & L4 --> RA
    JOB --> RA
    TS --> JOB
    REST --> RA
    REST --> PP
    RA --> SR & DB2 --> RP
    PP --> POL
    RP & POL --> PG[("PostgreSQL<br/>schema risk")]
    RA --> K["risk.assessment-updated"] --> ACC["accounting"]
```

---

## 2. Modelo IFRS-9

| Agregado | Rol |
|---|---|
| `RiskProfile` | Perfil por `creditAccountId`: etapa, bucket, provisión. `ACTIVE` se recalcula cada noche; `CLOSED` congela el `provisionAmount` (**RC-06**) |
| `ProvisionPolicy` | Política por tipo de producto — `DRAFT` · `ACTIVE` · `DEPRECATED` |
| `ProvisionRateBand` | Tasa de pérdida esperada por tramo de atraso dentro de una política |

### Etapas y buckets

```mermaid
flowchart LR
    D["daysDelinquent"] --> B{"DelinquencyBucket<br/>RC-01"}
    B --> C0["CURRENT · 0"]
    B --> C1["B1_30"]
    B --> C2["B31_60"]
    B --> C3["B61_90"]
    B --> C4["B91_120"]
    B --> C5["B121_180"]
    B --> C6["B181_PLUS"]
```

```mermaid
stateDiagram-v2
    direction LR
    [*] --> STAGE_1
    STAGE_1 --> STAGE_2 : días ≥ backstop SICR (30 d)
    STAGE_2 --> STAGE_3 : días ≥ presunción de incumplimiento (90 d)
    STAGE_2 --> STAGE_1 : cura, fuera de ventana de forbearance
    STAGE_3 --> STAGE_2 : RC-05 — sticky: nunca salta a STAGE_1
    note right of STAGE_2
        RC-04: dentro de la ventana de cura
        de un convenio, el perfil queda
        pisado en STAGE_2 aunque los días
        de atraso digan STAGE_1.
    end note
```

| Etapa | Significado | Pérdida esperada |
|---|---|---|
| `STAGE_1` | *Performing* | ECL 12 meses |
| `STAGE_2` | SICR — aumento significativo del riesgo | ECL *lifetime*, backstop 30 d |
| `STAGE_3` | Deteriorado / incumplimiento | ECL *lifetime*, presunción rebatible 90 d |

`Ifrs9StageResolver` y `DelinquencyBucket` son **funciones puras**, sin persistencia ni efectos:
trivialmente testeables y **deliberadamente duplicadas** con collections en vez de acoplar ambos
servicios por un evento `bucket-computed` compartido (ver §Decisiones del documento de dominio).

---

## 3. Cómo se reevalúa un perfil

```mermaid
sequenceDiagram
    autonumber
    participant CP as credit-portfolio ★
    participant COL as collections
    participant R as risk
    participant ACC as accounting

    CP-->>R: credit-account-activated
    R->>R: crea RiskProfile ACTIVE en STAGE_1
    CP-->>R: balance-updated · delinquency-status-updated
    COL-->>R: agreement-executed (abre ventana de cura)
    Note over R: RiskAssessmentJob · 01:00 diario
    R->>R: baseStage(daysDelinquent, 30, 90)
    R->>R: targetStage(base, actual, inCureWindow)
    R->>R: provisión = saldo × banda de la política vigente
    R-->>ACC: risk.assessment-updated
```

---

## 4. API REST — `/api/v1/risk`

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/accounts` | Perfiles con filtros y paginación (sin exigir `partyId`) |
| `GET` | `/accounts/batch?ids=` | Hidrata varias cuentas en **una** consulta — mata el N+1 del BFF |
| `GET` | `/accounts/{creditAccountId}` | Perfil de una cuenta |
| `GET` | `/provisions/summary` | Provisión agregada de la cartera |
| `GET` | `/provision-policies` · `/provision-policies/{productType}` | Políticas vigentes |
| `POST` | `/provision-policies` | Alta de política |

### Soporte (dev-only, `TEST_SUPPORT_ENABLED=true`)

| Método | Ruta | Para qué |
|---|---|---|
| `POST` | `/internal/test-support/run-risk-assessment` | Correr la reevaluación sin esperar a la 01:00 |

---

## 5. Eventos Kafka

**Consume:** `credit-portfolio.credit-account-activated` · `credit-portfolio.balance-updated` ·
`credit-portfolio.delinquency-status-updated` · `collections.agreement-executed`.

**Produce:** `risk.assessment-updated` (→ accounting).

**Reintentos:** régimen por defecto, sin DLT.
Ver [README raíz §5.4](../../README.md#54-política-de-reintentos-y-dlt-kafka).

---

## 6. Jobs programados

| Job | Cron | Qué hace |
|---|---|---|
| `RiskAssessmentJob` | `0 0 1 * * *` | Reevalúa todos los perfiles `ACTIVE` y publica la valuación |

---

## 7. Persistencia y ejecución

Schema `risk`, 7 changesets Liquibase bajo `db/changelog/risk/`. Tablas: `risk_profiles`,
`provision_policies`, `provision_rate_bands`.

```bash
./gradlew :risk-service:test
docker compose up -d risk-service
```
