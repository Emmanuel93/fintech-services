# D2 — Scoring [Core]

**Estado:** 🔄 En progreso  
**Tipo:** Core  
**Schema DB:** `scoring`  
**Puerto:** `8082`  
**Paquete Java:** `com.fintech.scoring`

## Responsabilidad

Motor de evaluación de riesgo crediticio. Consulta el buró de crédito (Círculo de Crédito), persiste el reporte completo y evalúa las cuentas del prospecto contra **políticas de scoring configurables** por tipo de producto. Emite una decisión automática: `AUTO_APPROVED` · `MANUAL_REVIEW` · `REJECTED`.

**No aprueba créditos directamente.** La aprobación es responsabilidad de Origination (D3).

---

## Flujo implementado

```
origination.prospect-created (Kafka)
    │ circuloConsentAccepted = false → skip
    │ circuloConsentAccepted = true  ↓
    ▼
ProspectCreatedEventListener
    ▼
BureauPrefetchService.initiate()
    ├── crea bureau_prefetch (IN_PROGRESS)
    ├── POST /v2/rccficoscore → Círculo de Crédito API
    │       ├── fico_score_valor, créditos, consultas, empleos, domicilios
    │       └── guarda en scoring.circulo_reports + 5 tablas hijas
    ├── marca prefetch COMPLETED | FAILED
    │
    └── (si SUCCESS) ScoringEvaluationService.evaluateAfterPrefetch() [REQUIRES_NEW]
            ├── busca ScoringPolicy activa para (prospectType, productTypeIntent)
            │       └── no encontrada → log warn, skip
            ├── ScoringRuleEvaluator.evaluate(policy, report) [pure Java]
            │       ├── evalúa reglas secuencialmente
            │       ├── regla disqualifying → ALTO/REJECTED inmediato
            │       └── score total → busca RiskThreshold → decisión
            └── guarda ScoreEvaluation (JSONB rule_details)
                    decision: AUTO_APPROVED | MANUAL_REVIEW | REJECTED
```

---

## Tablas implementadas

| Tabla | Descripción |
|---|---|
| `scoring.bureau_prefetches` | Un intento de pre-carga por prospecto (máx 1 activo) |
| `scoring.circulo_reports` | Reporte completo de CDC por llamada |
| `scoring.circulo_credits` | Tradelines / cuentas de crédito del reporte |
| `scoring.circulo_addresses` | Domicilios reportados por CDC |
| `scoring.circulo_employments` | Empleos reportados |
| `scoring.circulo_inquiries` | Consultas previas al buró |
| `scoring.circulo_scores` | Todos los scores del reporte (FICO y otros) |
| `scoring.scoring_policies` | Políticas de scoring por (prospectType, productTypeIntent) |
| `scoring.scoring_rules` | Reglas configurables por política |
| `scoring.risk_thresholds` | Umbrales de riesgo por política |
| `scoring.score_evaluations` | Resultados inmutables de evaluación |

---

## Motor de scoring configurable

### Tipos de regla

| RuleType | Dato evaluado | Filtro creditType |
|---|---|---|
| `FICO_THRESHOLD` | `ficoScoreValor` del reporte | — |
| `MORA_CHECK` | max(`peorAtraso`) de créditos | Sí (null = todos) |
| `CREDIT_COUNT` | count de créditos | Sí (null = todos) |
| `BALANCE_CHECK` | sum(`saldoVencido`) | Sí (null = todos) |
| `INQUIRY_COUNT` | count de consultas en `periodMonths` | — |

### Operadores: `GT · GTE · LT · LTE · EQ`

### Niveles de riesgo y decisión

| RiskLevel | Decisión | Descripción |
|---|---|---|
| `BAJO` | `AUTO_APPROVED` | Score ≥ umbral alto → aprobación automática |
| `MEDIO` | `MANUAL_REVIEW` | Score en rango medio → revisión manual |
| `ALTO` | `REJECTED` | Score bajo o regla disqualifying → rechazo |

### Política semilla (INDIVIDUAL / PERSONAL_LOAN)

| Regla | Tipo | CreditType | Op | Umbral | Puntos | Descalifica |
|---|---|---|---|---|---|---|
| Mora en Financieras | MORA_CHECK | FM | GT | 0 | -200 | No |
| Mora > 90 días | MORA_CHECK | — | GT | 90 | -500 | **Sí** |
| FICO ≥ 750 | FICO_THRESHOLD | — | GTE | 750 | +200 | No |
| FICO ≥ 700 | FICO_THRESHOLD | — | GTE | 700 | +100 | No |
| FICO ≥ 650 | FICO_THRESHOLD | — | GTE | 650 | +50 | No |
| Máx 3 tarjetas TC | CREDIT_COUNT | TC | LTE | 3 | +50 | No |
| Máx 5 consultas 12m | INQUIRY_COUNT | — | LTE | 5 | +30 | No |

Umbrales: BAJO ≥ 200 / MEDIO ≥ 100 / ALTO ≥ -9999

---

## API REST

**Base path:** `/api/v1/scoring`

| Método | Endpoint | Descripción |
|---|---|---|
| `GET` | `/policies` | Lista políticas activas |
| `GET` | `/policies/{policyId}` | Detalle de política |
| `POST` | `/policies` | Crea política (desactiva la anterior del mismo tipo) |
| `DELETE` | `/policies/{policyId}` | Desactiva política |
| `GET` | `/evaluations/{prospectId}/latest` | Evaluación más reciente |
| `POST` | `/evaluate/{prospectId}?prospectType=&productTypeIntent=` | Re-evaluación manual |

Swagger UI: `http://localhost:8082/swagger-ui.html`

---

## Configuración

```yaml
server.port: 8082

fintech.circulo.url: https://services.circulodecredito.com.mx/sandbox
fintech.circulo.api-key: ${CIRCULO_CREDITO_API_KEY}   # requerido en prod
fintech.circulo.timeout-seconds: 30
```

**Variables de entorno:**

| Variable | Default | Descripción |
|---|---|---|
| `CIRCULO_CREDITO_API_KEY` | *(sandbox key en dev)* | API key de Círculo de Crédito |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Brokers Kafka |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/fintech` | |

---

## Tests

| Clase | Tipo | Cobertura |
|---|---|---|
| `ScoringRuleEvaluatorTest` | Unit (puro Java) | Todos los RuleType, bandas FICO aditivas, disqualifying, nulos |
| `ScoringEvaluationServiceTest` | Unit (Mockito) | Sin política→skip, AUTO_APPROVED, MANUAL_REVIEW, REJECTED, disqualifying, re-eval manual |
| `BureauPrefetchServiceTest` | Unit (Mockito) | CDC OK→triggerScoring, CDC ERROR→no scoring, scoring falla→prefetch intacto |
| `ProspectCreatedEventListenerTest` | Unit (Mockito) | consent=true/false, propagación de prospectType/productTypeIntent |
| `CirculoCreditoAdapterIT` | Integración (WireMock 3.4.2) | Respuesta completa, NO_HIT, ERROR, sanitización de acentos |

```bash
./gradlew :scoring:test
```

---

## Levantar localmente

```bash
# Infraestructura
docker compose up postgres kafka zookeeper -d

# Servicio
./gradlew :scoring:bootRun
```

---

## Referencia completa

Ver [docs/dominios/02_scoring_domain.md](../../dominios/02_scoring_domain.md) para el modelo de datos completo, invariantes de dominio, flujo detallado y diseño target.
