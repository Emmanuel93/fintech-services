# T3 — Audit & Compliance

**Estado:** ✅ Completo (2026-07-03)  
**Tipo:** Transversal  
**Schema DB:** `audit`  
**Paquete Java:** `com.fintech.audit`  
**Puerto:** `8090` (interno Docker)

## Responsabilidad

Log inmutable regulatorio. Suscriptor global de todos los eventos Kafka del ecosistema. **Append-only — nunca modifica estado de ningún dominio.**

## Comunicación

| Dirección | Medio | Detalle |
|---|---|---|
| **Consume** | Kafka (16 topics) | Todos los eventos de todos los servicios — ver tabla abajo |
| **Publica** | — | No publica eventos |
| **Expone** | REST API | Consultas de expediente para auditores/reguladores |

### Topics consumidos

| Topic | Servicio origen | Evento |
|---|---|---|
| `origination.prospect-created` | origination-service | Onboarding persona nueva |
| `origination.score-requested` | origination-service | Solicitud de evaluación crediticia |
| `origination.contract-signed` | origination-service | Contrato firmado |
| `origination.application-rejected` | origination-service | Solicitud rechazada |
| `scoring.scoring-approved` | scoring-service | Aprobación de scoring |
| `scoring.scoring-completed` | scoring-service | Resultado de evaluación |
| `credit-portfolio.credit-account-activated` | credit-portfolio-service | Cuenta activa |
| `credit-portfolio.balance-updated` | credit-portfolio-service | Actualización de saldo |
| `credit-portfolio.payment-rejected` | credit-portfolio-service | Pago rechazado |
| `credit-portfolio.charge-rejected` | credit-portfolio-service | Cargo rechazado |
| `charges.charge-applied` | charges-service | Cargo aplicado |
| `charges.charge-reversed` | charges-service | Cargo revertido |
| `payments.payment-applied` | payments | Pago aplicado |
| `payments.payment-returned` | payments | Pago devuelto |
| `configuration.configuration-updated` | configuration-service | Parámetro actualizado |
| `product-catalog.product-activated` | credit-product-service | Producto activado |
| `product-catalog.product-retired` | credit-product-service | Producto retirado |

## Entidades

| Tabla | Descripción |
|---|---|
| `audit.audit_entries` | Registro inmutable: eventType, domainSource, aggregateId, partyId, correlationId, payload (TEXT), createdAt |
| `audit.document_file_refs` | Referencias a documentos regulatorios con TTL de retención calculado por tipo |
| `audit.uif_reports` | Reportes UIF (INUSUAL/RELEVANTE/INTERNO) para la Unidad de Inteligencia Financiera |

## Retención regulatoria

| `DocumentType` | Años | Regulación |
|---|---|---|
| `BUREAU_REPORT` | 5 | CNBV Circular 14/2013 |
| `CONTRACT` | 10 | Código de Comercio |
| `ACCOUNT_STATEMENT` | 5 | CONDUSEF |
| `AML_RECORD` | 10 | LFPIORPI |
| `REJECTION_RECORD` | 5 | CONDUSEF |
| `WRITE_OFF_RECORD` | 10 | LFPIORPI |
| `CHARGE_JUSTIFICATION` | 5 | CNBV |
| `KYC_DOCUMENT` | 7 | CNBV post-liquidación |

## API REST

| Método | Path | Roles | Descripción |
|---|---|---|---|
| `GET` | `/api/v1/audit/entries` | AUDITOR, REGULATOR, ADMIN | Consulta con filtros: `?partyId=`, `?aggregateId=`, `?eventType=`, `?from=`, `?to=` |
| `GET` | `/api/v1/audit/entries/{entryId}` | AUDITOR, REGULATOR, ADMIN | Detalle con payload completo |
| `GET` | `/api/v1/audit/parties/{partyId}/documents` | AUDITOR, REGULATOR, ADMIN | Documentos archivados del party |
| `GET` | `/api/v1/audit/parties/{partyId}/uif-reports` | AUDITOR, REGULATOR, ADMIN | Reportes UIF del party |
| `GET` | `/api/v1/audit/parties/{partyId}/expedition` | AUDITOR, REGULATOR, ADMIN | Expediente completo: entradas + documentos + reportes UIF |

## Seguridad

Header-trust: el gateway inyecta `X-User-Id` y `X-Roles`. El audit-service exige `ROLE_AUDITOR`, `ROLE_REGULATOR` o `ROLE_ADMIN` — un `CUSTOMER` recibe `403`.

## Reglas clave

- **AC-01** `AuditEntry` append-only — nunca se modifica ni elimina antes del TTL
- **AC-02** `payload` almacenado como TEXT plano (sin parsear) — resistente a cambios de schema en otros servicios
- **AC-03** Acceso a expedientes requiere rol `AUDITOR` o `REGULATOR` — registrado en el log de acceso del gateway
- **AC-04** Requerimiento CONDUSEF → expediente disponible en ≤5 días hábiles vía `GET /expedition`
- **AC-05** Operación inusual AML → `UIFReport` creado en ≤72h a partir de detección

## Tests

| Clase | Tipo | Casos |
|---|---|---|
| `AuditServiceTest` | Unit (Mockito) | record, findById, findByPartyId, findByAggregateId, not-found |
| `AuditControllerTest` | `@WebMvcTest` | sin auth → 401, CUSTOMER → 403, AUDITOR → 200, filtro partyId, detalle, 404, expediente |

**Total: 12 tests ✅**

## Estructura del servicio

```
audit/
  src/main/java/com/fintech/audit/
    AuditApplication.java
    domain/
      AuditEntry.java              ← aggregate root, append-only
      DocumentFileRef.java         ← referencia con TTL calculado
      UIFReport.java               ← reporte regulatorio UIF
      DocumentType.java            ← enum con retentionYears()
      UIFReportType.java / UIFReportStatus.java
    application/
      port/out/
        AuditEntryRepository.java
        DocumentFileRefRepository.java
        UIFReportRepository.java
      service/
        AuditService.java
        DocumentArchiveService.java
        UIFReportService.java
    infrastructure/
      adapter/in/
        api/           ← AuditController, JwtAuthenticationFilter, DTOs
        messaging/     ← 7 listeners (origination, scoring, portfolio, charges,
                                       payments, configuration, credit-product)
      adapter/out/persistence/  ← JPA adapters + Spring Data repos
      config/  ← KafkaConfig (String factory), SecurityConfig, OpenApiConfig
  src/main/resources/
    application.yml               ← puerto 8090
    application-docker.yml        ← URL postgres:5432
    db/changelog/audit/
      001-create-schema.sql
      002-create-audit-entries.sql
      003-create-document-file-refs.sql
      004-create-uif-reports.sql
      005-create-event-publication.sql
```

## Levantar localmente

```bash
# Incluido en docker-compose.yml — sin config extra necesaria
docker compose up -d audit-service

# Verificar health
curl http://localhost:8090/actuator/health

# Consultar con rol AUDITOR (después de un login)
TOKEN=<token_con_rol_AUDITOR>
curl -H "Authorization: Bearer $TOKEN" \
     "http://localhost:8090/api/v1/audit/entries?partyId=<uuid>"
```
