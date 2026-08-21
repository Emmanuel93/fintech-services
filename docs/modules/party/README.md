# D0 — Party Service

**Estado:** 🔄 En desarrollo (Módulo 4)
**Tipo:** Standalone Spring Boot service
**Schema DB:** `party`
**Paquete Java:** `com.fintech.party`
**Puerto:** `8083`

## Responsabilidad

Sujeto del crédito — raíz de todo el sistema. Todo crédito referencia un `partyId`.
Se crea automáticamente cuando scoring emite `ScoringApprovedEvent` con decisión `AUTO_APPROVED` (riesgo BAJO).

## Flujo de creación

```
scoring-service
  └─ ScoringEvaluationService (AUTO_APPROVED)
       └─ KafkaScoringEventPublisher → topic: scoring.scoring-approved
            └─ party-service
                 └─ ScoringApprovedEventListener
                      └─ PartyService.createFromScoringApproval()
                           └─ party.parties (DB)
```

## Invariantes críticas

- **I-01**: `partyType` **inmutable post-creación**
- **I-02**: `BLACKLISTED` bloquea toda nueva originación de forma inmediata
- **I-03**: Idempotente — si ya existe un Party para el mismo `prospectId`, se ignora el evento

## Modelo de dominio

### `Party` (aggregate root)

| Campo | Tipo | Notas |
|---|---|---|
| `party_id` | UUID PK | Generado al crear |
| `prospect_id` | UUID UNIQUE | Referencia a origination-service (sin FK cross-service) |
| `evaluation_id` | UUID | Evaluación de scoring que aprobó al party |
| `party_type` | `INDIVIDUAL \| BUSINESS` | Inmutable |
| `status` | `ACTIVE \| SUSPENDED \| BLACKLISTED \| CLOSED` | Empieza en ACTIVE |
| `first_name` | VARCHAR(100) | De CirculoReport vía evento |
| `last_name1` | VARCHAR(100) | Apellido paterno |
| `last_name2` | VARCHAR(100) | Apellido materno |
| `curp` | CHAR(18) UNIQUE | |
| `rfc` | VARCHAR(13) | |
| `date_of_birth` | DATE | |
| `risk_level` | VARCHAR(10) | Siempre `BAJO` para AUTO_APPROVED |
| `total_score` | INTEGER | Score final del evaluador |
| `created_at` | TIMESTAMPTZ | |

## Kafka consumer

| Topic | Group ID | Tipo de mensaje |
|---|---|---|
| `scoring.scoring-approved` | `party-service` | `ScoringApprovedPayload` |

## API REST

| Método | Endpoint | Descripción |
|---|---|---|
| GET | `/api/v1/parties/{partyId}` | Buscar party por ID |
| GET | `/api/v1/parties/by-prospect/{prospectId}` | Buscar party por prospecto |

## Tests

| Clase | Tipo | Cobertura |
|---|---|---|
| `PartyServiceTest` | Unit (Mockito) | Creación, idempotencia, lookups |
| `ScoringApprovedEventListenerTest` | Unit (Mockito) | Delegación al servicio, propagación de excepciones |

## Configuración

```yaml
# application.yml
server.port: 8083
spring.kafka.consumer.group-id: party-service
spring.kafka.consumer.auto-offset-reset: earliest
spring.kafka.bootstrap-servers: localhost:9092
```

## Ejecución de tests

```bash
./gradlew :party-service:test
```
