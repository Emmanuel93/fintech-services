# D3 — Origination [Core Domain]

**Estado:** 🔄 En progreso — Fases A–H implementadas  
**Schema DB:** `origination` · **Puerto:** 8081  
**Spec profunda:** [docs/dominios/03_credit_origination_domain.md](../../dominios/03_credit_origination_domain.md)

---

## Subdominios implementados

| Subdominio | Fases | Estado |
|---|---|---|
| `application-intake` — Prospect (onboarding de persona) | A | ✅ |
| `underwriting` — CreditApplication: scoring loop | B, C, D | ✅ |
| `underwriting` — Decisión manual y comité | H | ✅ |
| `offer-management` — CreditOffer (CAT, TTL) | E | ✅ |
| `offer-management` — Expiración automática (TTL job) | I | ✅ |
| `contract-management` — Contract (firma + snapshot a portfolio) | F | ✅ |
| Desembolso (consumer portfolio.credit-account-activated) | G | ✅ |
| Cooldown post-rechazo (UW-06) | J | ✅ |
| Documentos requeridos por producto (`PENDING_DOCUMENTS`) | — | ⬜ Pendiente |

---

## Flujo completo implementado

```
Prospect (onboarding)  →  ProspectCreated  →  scoring prefetch buró
                                            →  party (crea Party)

CreditApplication      →  ScoreRequested   →  scoring decision engine
                       ←  scoring-completed
                            AUTO_APPROVED   →  APPROVED → offer → contract → DISBURSED
                            MANUAL_REVIEW   →  ¿amount > committee_threshold?
                                                 SÍ → COMMITTEE_REVIEW
                                                 NO → UNDER_MANUAL_REVIEW
                            REJECTED        →  REJECTED (rejectionReason CONDUSEF, rejectedAt)

UNDER_MANUAL_REVIEW / COMMITTEE_REVIEW
    POST /underwriting/applications/{id}/decision
        approved=true   →  APPROVED → offer → contract → DISBURSED
        approved=false  →  REJECTED (rejectionReason obligatorio UW-05)

OFFER_PRESENTED  →  OfferExpirationJob (cada hora) → OFFER_EXPIRED si validUntil pasó
                 →  acceptOffer → OFFER_ACCEPTED → generateContract → signContract
                 →  CONTRACT_SIGNED → CreditProductCreationRequested ──► credit-portfolio
                 ←  credit-portfolio.credit-account-activated → DISBURSED
```

---

## Máquina de estados — CreditApplication

```
PENDING_SCORING → APPROVED ─────────────────────────────────────────► OFFER_PRESENTED
               ↘ UNDER_MANUAL_REVIEW ─► APPROVED ──────────────────► OFFER_PRESENTED
               ↘ COMMITTEE_REVIEW ────► APPROVED ──────────────────► OFFER_PRESENTED
               ↘ REJECTED ✓ (scoring o decisión manual — rejectedAt set)
               ↘ FAILED ✓

OFFER_PRESENTED → OFFER_ACCEPTED → PENDING_SIGNATURE → CONTRACT_SIGNED → DISBURSED ✓
               ↘ OFFER_REJECTED ✓
               ↘ OFFER_EXPIRED ✓ (job automático)

CANCELLED ✓ (cualquier estado pre-firma)
```

---

## Routing automático a COMMITTEE vs MANUAL

```
scoring devuelve MANUAL_REVIEW
  │
  ├─ requestedAmount > committee_threshold (default 500,000 MXN) → COMMITTEE_REVIEW
  └─ requestedAmount ≤ threshold (o es null)                     → UNDER_MANUAL_REVIEW
```

---

## Cooldown post-rechazo (UW-06)

Al intentar iniciar una nueva `CreditApplication` para el mismo `(prospectId, productType)`:
- Si existe un `REJECTED` con `rejectedAt > now - cooldownDays` → `CooldownActiveException` (422)
- `cooldownDays` configurable via `ORIGINATION_COOLDOWN_DAYS` (default 90 días)

---

## Eventos publicados / consumidos

**Publica:**
- `origination.prospect-created`
- `origination.score-requested`
- `origination.application-approved` → Notifications, Audit
- `origination.application-rejected` → Notifications (CONDUSEF), Audit
- `origination.contract-signed`
- `origination.credit-product-creation-requested` → credit-portfolio

**Consume:**
- `scoring.scoring-completed` → transiciona CreditApplication (routing MANUAL vs COMMITTEE)
- `credit-portfolio.credit-account-activated` → marca DISBURSED

---

## API REST

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| `POST` | `/api/v1/origination/prospects` | No | Onboarding de persona |
| `GET` | `/api/v1/origination/prospects/{id}` | No | Detalle prospect |
| `POST` | `/api/v1/origination/applications` | No | Inicia solicitud (cooldown check) |
| `GET` | `/api/v1/origination/applications/{id}` | No | Detalle aplicación |
| `GET` | `/api/v1/origination/applications?prospectId=` | No | Lista por prospect |
| `POST` | `/api/v1/origination/underwriting/applications/{id}/decision` | JWT | Decisión manual/comité |
| `GET` | `/api/v1/origination/underwriting/applications?prospectId=` | JWT | Lista pendientes de decisión |
| `POST` | `/api/v1/origination/offers/{id}/accept` | No | Acepta oferta |
| `POST` | `/api/v1/origination/offers/{id}/reject` | No | Rechaza oferta |
| `POST` | `/api/v1/origination/contracts/{id}/generate` | No | Genera contrato |
| `POST` | `/api/v1/origination/contracts/{id}/sign` | No | Firma contrato |

---

## Tablas

| Tabla | Descripción |
|---|---|
| `origination.prospects` | Onboarding persona — CURP, RFC, dirección, documentos |
| `origination.credit_applications` | Ciclo de vida de la solicitud (con `approval_flow`, `decided_by`, `rejected_at`) |
| `origination.event_publication` | Spring Modulith outbox |

---

## Jobs

| Job | Cron | Acción |
|---|---|---|
| `OfferExpirationJob` | `0 0 * * * *` (cada hora) | OFFER_PRESENTED con `validUntil` expirado → OFFER_EXPIRED |

---

## Pendiente

- [ ] Documentos requeridos por producto (`PENDING_DOCUMENTS`): validar que PAYROLL_LOAN tenga PAYROLL_STUB verificado antes de PENDING_APPROVAL (OA-09)
- [ ] Job de expiración de Prospects (TTL configurable)
- [ ] REST endpoint de cancelación explícita
