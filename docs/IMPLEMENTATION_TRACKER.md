# fintech-services — Implementation Tracker

> **Microservicios totalmente desacoplados** (ver [ADR-001](../README.md)). Un repo, **24 servicios** `*-service`, comunicación por Kafka (async) o REST (sync solo T1 auth). Database-per-service: cada servicio su propio schema en PostgreSQL (sin queries cross-schema), Liquibase por servicio. Spring Modulith se mantiene para disciplina de fronteras dentro de cada servicio.
>
> **Cambios recientes (2026-06-04, ADR-001):** split de "D4 Credit Product" → `credit-product` (catálogo) + `credit-portfolio` ★ (corazón / cuentas vivas); re-orientación de D3 (persona `Prospect` ≠ crédito `CreditApplication`). **2026-07-08:** nuevo dominio **D9 Risk** (etapa IFRS-9 + estimación preventiva de reservas, spec) — 16→17 servicios. **2026-07-10:** **D8 Collections implementado por completo** (cobranza temprana, `CollectionAgreement` reestructura/quita parcial, reporte a buró, 40 tests) junto con sus dos prerequisitos en credit-portfolio (`UpcomingInstallmentJob`, consumer `collections.agreement-executed`); puerto de D9 Risk corregido de 8092→8094 (Collections tomó 8093). **2026-07-11:** barrida de tests E2E (aceptación + integración) en 10 servicios (+54 tests) — 3 bugs latentes corregidos. **2026-07-12:** **D9 Risk** (etapa IFRS-9, EPR/ECL, 46 tests, puerto 8094) **+ T4 Accounting** (libro mayor IFRS-9 a nivel préstamo, provisión por delta, reconocimiento de ingresos, 16 tests, puerto 8095) **+ nuevo servicio de Facturación** `invoicing` (CFDI 4.0, timbrado stub, 8 tests, puerto 8096) **+ Party enriquecido** con perfil fiscal CFDI — 17→18 servicios. **Decisiones contables confirmadas:** asientos a nivel préstamo (auxiliar CNBV R04-C) agregados al mayor; facturación consolidada **un CFDI por party/período** (el receptor es el party por RFC, no el préstamo). **2026-07-14:** **T6 Commission implementado por completo** (`commission`, puerto 8097, 31 tests) con **modelo del distribuidor B2B2C corregido a petición del usuario**: `DISTRIBUTOR_INTEREST_SHARE` — % del interés efectivamente cobrado, devengado plazo a plazo contra cada pago (nunca upfront contra la colocación, para no incentivar crédito mal validado) — junto con su prerequisito, la propagación end-to-end de `promoterCode` (channels→origination→credit-portfolio→commission) y el posteo correspondiente en T4 Accounting (2 cuentas nuevas, `CommissionPostingService`, 5 tests nuevos). **17/18 servicios completos** — falta solo T2 Notifications. **2026-07-15:** **T2 Notifications — plan ampliado con lente de valor de marketing** (solo diseño, sin código): catálogo real de ~45 eventos tiereados (🟢 alto valor/🟡 medio/⚪ compliance/🔵 partner B2B2C), 3 canales priorizados PUSH/EMAIL/WHATSAPP, y un **hallazgo crítico de prerequisito**: no existe hoy un directorio de contacto (phone/email) por `partyId` en ningún servicio — mecanismo de resolución 100% event-driven propuesto sin tocar otros servicios. **2026-07-16:** alcance de T2 Notifications **acotado a 4 notificaciones para v1** (a petición explícita): ofertas de crédito, bienvenida, desembolso, recordatorio de pago; **ampliado el mismo día a 6** con 2 notificaciones de celebración (cuota pagada, crédito liquidado) — hallazgo al añadirlas: no existe tracking de pago por cuota individual en credit-portfolio (`InstallmentStatus.PAID` nunca se asigna), resuelto con una heurística local documentada como aproximación. El resto del catálogo (30 eventos) queda como backlog documentado. **2026-07-17: T2 Notifications implementado por completo** (`notifications`, puerto 8098, 41 tests) — las 6 notificaciones de v1, 3 canales PUSH/EMAIL/WHATSAPP (adaptadores `Noop*` + `SmtpEmailAdapter` real vía SMTP gratuito, deshabilitado por default), **cero jobs `@Scheduled`** (NT-12, confirmado explícitamente con el usuario — todo reacciona a Kafka, incluido el recordatorio de pago que reutiliza el cron que ya existe en credit-portfolio), directorio de contacto por join de 3 pasos, aproximación local para "cuota pagada" documentada como best-effort. **🎉 18/18 servicios de dominio completos — el catálogo completo del sistema queda implementado.** **2026-07-18:** **🎉 T7 Observability implementado y verificado por completo** (OTel Java Agent auto-instrumentado para trazas → Tempo, Micrometer/Prometheus para RED metrics → Grafana, Fluent Bit → Elasticsearch para logs, 19 servicios instrumentados) — **las 3 señales confirmadas end-to-end con Docker real**, incluida la correlación log↔trace bidireccional por `trace_id` en Elasticsearch/Tempo y el dashboard de Grafana devolviendo datos reales a través de su propia API. Hallazgo real de infraestructura: la asignación actual de Docker Desktop (7.65GB) no alcanza para los 19 servicios + observability completa simultáneos — confirmado con OOM-kills reales, no solo teoría; recomendado subir a 16GB. Ver `docs/dominios/T7_observability.md` y Módulo 19.

> **De 2026-08 en adelante los cambios se registran como entradas de la tabla** (BO-1…BO-6, D13 y
> sus fases), no en este párrafo. Creció hasta volverse ilegible, que es justo lo contrario de para
> lo que sirve un registro de cambios.

**Estado a 2026-08-17:** 24 servicios. El ciclo completo está implementado; lo que queda a medias es
la **captura de identidad del beneficiario** (D13) y los **emisores de notificaciones** — el carril
por eventos existe y nadie publica todavía. Detalle en [README §9](../README.md#9-estado-de-implementación).

---

## Stack

| Componente | Versión |
|---|---|
| Java | 21 LTS |
| Spring Boot | 3.4.x |
| Spring Modulith | 1.3.x |
| PostgreSQL | 16 |
| Apache Kafka | 3.8.x |
| Liquibase | 4.x |
| JJWT | 0.12.x |
| Springdoc OpenAPI | 2.x |
| Docker base image | eclipse-temurin:21-jdk-alpine |
| Terraform | >= 1.7 (estructura placeholder) |

---

## Estado General

| Fase | Módulo | Estado | Schema DB | Completado |
|---|---|---|---|---|
| 0 | Proyecto Base | ✅ Completo | — | 2026-05-14 |
| GW | **API Gateway (OpenResty + JWT RS256)** | ✅ Completo | — | RS256 operativo (PKCS#8 + jwt.lua pk:verify sin padding arg), logs enriquecidos (request_id, source_ip, auth_required, user_agent, bytes_sent, query), BFF /users/me + /credit/account — 2026-07-03 |
| 1 | T1: Identity & Auth | ✅ Completo | `identity` | 2026-05-14 |
| 2 | T5: Configuration | ✅ Completo | `configuration` | 2026-06-04 |
| 3 | T3: Audit & Compliance | ✅ Completo | `audit` | Suscriptor global Kafka (16 topics), AuditEntry append-only, DocumentFileRef (retención regulatoria), UIFReport, API REST (AUDITOR/REGULATOR role), header-trust — 2026-07-03 |
| 4 | D0: Party | ✅ Completo | `party` | KYC verifications, consent records, party relationships, PUT /kyc-status, POST /blacklist, PartyBlacklisted event — 2026-07-05. **26 tests ✅** |
| 5 | D1: Channels | ✅ Completo | `channels` | Channel config (8 tipos, seed), Session lifecycle (S-03 one-per-party+channelType), CustomerIntent (IR-02 BLACKLISTED→Abandoned, IR-03 allowedIntents), LeadRequest (promotor, TTL), ApplicationStarted event, header-trust — 2026-07-04 |
| 6 | D2: Scoring | ✅ Completo | `scoring` | Motor de scoring con CDC buró, políticas configurables, 48 tests — 2026-06-05 |
| 7 | D3: Origination (Prospect + CreditApplication + Offer + Contract) | ✅ Completo | `origination` | Fases A-H + canal; 119 tests — 2026-07-06 |
| 8 | D4: Credit Product (catálogo) | ✅ Completo | `credit_product` | Versionado, capabilities JSONB, rate_cards, eligibility_rules, B2B seeds, Kafka; 62 tests — 2026-06-10 |
| 9 | D4★: Credit Portfolio (corazón) | ✅ Completo | `credit_portfolio` | Config-driven, AmortizationEngine, balance engine, jobs nocturnos, ProcessDispositionUseCase (wallet-driven), ProcessAgreementExecutedUseCase (collections-driven, 2026-07-10), 82 tests (79 unit + 3 IT sin Docker) — 2026-07-10 |
| 10 | D5: Charges | ✅ Completo | `charges` | Motor de devengamiento + doble validación de saldo: AccountBalanceSnapshot (snapshot local), pre-check en chargeOpeningFee, ChargeRejectedListener (post-check), GET /balance endpoint — 2026-07-06 |
| 11 | D6: Payments | ✅ Completo | `payments` | Doble validación de saldo: snapshot local + post-check autoritativo en credit-portfolio. PaymentOrder (PENDING→CONFIRMED/REJECTED/REVERSED), AccountBalanceSnapshot, Kafka in/out, REST API, WRITTEN_OFF guard, reversal window 72h, overpayment (RETURN_TO_PAYER/APPLY_NEXT_INSTALLMENT), VENTANILLA no reversible — 2026-07-05. **23 tests ✅** |
| 12 | D7: Wallet | ✅ Completo | `wallet` | Proyección de saldos de deuda + `walletBalance` propio (dispuesto y no gastado), PaymentInstruction, WalletWithdrawal, DispositionOrchestration fail-fast, agregación por party (`?partyId=`, `/summary`), 5 Kafka listeners + 5 publishers, 6 Liquibase changelogs, 6 REST endpoints — **38 tests ✅** — 2026-07-08 |
| 13 | D8: Collections | ✅ Completo | `collections` | Cobranza temprana, `CollectionAgreement` (reestructura/quita parcial vía `AccountBalanceSnapshot` local), `WriteOffRecord`, `BureauReport` con reintento indefinido, 4 jobs nocturnos, 11 endpoints REST — **40 tests ✅** — 2026-07-10 |
| 14 | T2: Notifications | ✅ Completo | `notifications` | Alcance v1 — 6 notificaciones (ofertas, bienvenida, desembolso, recordatorio de pago, cuota pagada, crédito liquidado), 3 canales PUSH/EMAIL/WHATSAPP, cero jobs `@Scheduled` (NT-12), directorio de contacto por join de 3 pasos, 7 listeners + 5 endpoints — **41 tests ✅** — 2026-07-17 |
| 15 | T4: Accounting / GL | ✅ Completo | `accounting` | Libro mayor IFRS-9, asientos **a nivel préstamo** (auxiliar) por delta de saldos (shadow local), provisión EPR por delta (GL-09) + consumo de reserva en quebranto/quita (GL-10), reconocimiento de ingresos → **facturación consolidada por party/período**, catálogo de cuentas configurable, **posteo de comisiones T6 (nuevo)**, 7 listeners + 4 endpoints — **21 tests ✅** — 2026-07-14 |
| 16 | T6: Commission | ✅ Completo | `commission` | Comisión del distribuidor B2B2C **contra el pago** (`DISTRIBUTOR_INTEREST_SHARE`, % del interés cobrado, plazo a plazo — nunca upfront por colocación), tasa versionada por producto/distribuidor, atribución vía `promoterCode` (prerequisito propagado end-to-end channels→origination→credit-portfolio→commission), batch de liquidación mensual con mínimo, 2 listeners + 5 endpoints — **31 tests ✅** — 2026-07-14 |
| 17 | **D9: Risk** | ✅ Completo | `risk` | Etapa IFRS-9 (Stage 1/2/3), EPR/ECL por tabla de tasas versionada (`ProvisionPolicy`), job nocturno full-recompute, forbearance/cura, sticky STAGE_3, 6 endpoints REST, 4 listeners + `risk.assessment-updated` — **46 tests ✅** — 2026-07-12 |
| 18 | **Facturación (CFDI 4.0)** *(nuevo)* | ✅ Completo | `invoicing` | Servicio de facturación: consume `accounting.invoice-requested` + `party.fiscal-profile-updated`, genera `Invoice` (CFDI) con receptor (o RFC genérico), timbrado **stub** (`NoopPacAdapter`), 2 endpoints — **8 tests ✅** — 2026-07-12 |
| 19 | **T7: Observability** *(nuevo)* | ✅ Completo | — (infra, sin schema propio) | OTel Java Agent (auto-instrumentación, traces) → Collector → Tempo; Micrometer/Prometheus (RED metrics: throughput/p95/p99/error rate) → Grafana; Fluent Bit → Elasticsearch (logs) → Grafana. Dashboard único parametrizado por `$service`, no 19 copias. Perfil opt-in (`--profile observability`) por costo. **Verificado end-to-end con Docker real**: trazas, métricas y logs correlacionados por `trace_id` confirmados con datos reales. Ver `docs/dominios/T7_observability.md` — **2026-07-18** |

| 20 | **D13: Beneficiary (colocación B2B2C)** *(nuevo)* | 🔄 En progreso — fase 1 de 8 | `beneficiary` | Agregado `Placement` con máquina de estados de 11 estados y **15 aristas fijadas por test**, `PlacementTransition` (bitácora append-only → `timeline[]` de la app), 5 tipos de evento sobre 11 tópicos, publisher Kafka particionado por `placementId`, `CHECK` en Liquibase que codifican invariantes del dominio (expediente todo-o-nada, disposición obligatoria para desembolsar, `FAILED` siempre con motivo), 2 endpoints de lectura. **La deudora es la distribuidora**: una sola cuenta de crédito y cada colocación es una `Disposition THIRD_PARTY_CREDIT`. **Invitar no crea prospecto** — hacerlo dispararía el prefetch de buró de scoring sin autorización. — **199 tests ✅** (132 pares de la máquina, exhaustivos + 5 IT Testcontainers) — 2026-08-17. Plan: `docs/BENEFICIARY_SERVICE_PLAN.md` |

**Leyenda:** ⬜ Pendiente · 🔄 En progreso · ✅ Completo · 🔒 Bloqueado

### D13 — Fases pendientes

| Fase | Alcance | Estado |
|---|---|---|
| 1 | Esqueleto, agregado y máquina de estados | ✅ 2026-08-16 |
| BO-1 | **Backoffice · Beneficiarios (mesa de KYC)** — `beneficiary-service`: `GET /api/v1/backoffice/placements` (+`/{id}`), consulta transversal paginada con filtros de distribuidora, estado, `identityStatus` (derivado) y SLA (`stalledDays` sobre `updatedAt`); raíz separada de `/api/v1/placements` para que la consulta de la app —que deriva el distribuidor del token— no pueda convertirse en fuga de cartera ajena. BFF: `BeneficiariesController` + `BeneficiaryClient`, capacidad nueva **`beneficiaries.view`**. Gateway: sin cambios (el bloque backoffice tiene catch-all). **Ficha de identidad granular no incluida**: la captura (coincidencia facial, prueba de vida, INE/RENAPO) no existe todavía; se responde `identityEvidence.available=false` con motivo | ✅ 2026-08-17 — **91 tests BFF ✅** |
| BO-2 | **Backoffice · bandejas de cobranza** — `collections-service`: `GET /api/v1/collections/payment-promises` y `/contact-attempts`, cruzando casos con `JOIN` + dos subconsultas correlacionadas (intentos de hoy, último resultado). Flags de trato al cliente calculados en el dueño: `contactCapReached`, `contactable`, ventana horaria en el sobre. Índices `011-queue-indexes.sql`. BFF: los dos GET en `CollectionsController` + enriquecimiento por `/batch` (2 llamadas por página, nunca una por fila). Capacidad: reusa `portfolio.view` | ✅ 2026-08-17 — **64 tests ✅** (8 IT nuevas contra Postgres) |
| BO-7 | **Dictamen manual de identidad y documentos (sin proveedor de KYC)** — `origination` `015`: estado por documento en el expediente (`review_status`, `verification_source`, `reviewed_by`, `reviewed_at`, `rejection_reason`) + `PUT /prospects/{id}/documents/{type}/review`; volver a subir un archivo **borra el dictamen**. `beneficiary` `006`: veredicto de identidad propio —deja de derivarse del avance de la colocación— con `POST /backoffice/placements/{id}/identity-review`, y **`approve()` exige identidad VERIFIED** (regla también en `CHECK`). Dos puertos: `IdentityVerificationGateway` (política) y `KycProviderPort` (integración). Bandera `identity-verification.mode` = `MANUAL` (hoy) | `AUTOMATIC`, que **controla el flujo, no el arranque**. En automático **todo tropiezo del proveedor —caído, excepción, documento no validado, umbral no alcanzado— degrada a revisión humana**: es el mecanismo de resiliencia, y se ejerce desde hoy porque `UnavailableKycProviderAdapter` reporta indisponibilidad. El motivo queda en `identity_review_notes`. BFF: `POST /beneficiaries/placements/{id}/identity-review` y `PUT /origination/applications/{id}/documents/{type}/review`, con capacidades nuevas **`beneficiaries.review-identity`** (sólo ADMIN y CREDIT_ANALYST — el auditor no decide, soporte no firma, riesgo evalúa cartera y no identidades) y **`applications.review-documents`**. El autor sale de la sesión, **nunca del cuerpo**: la firma es lo que vuelve evidencia a un dictamen | ✅ 2026-08-18 — **212 + 144 + 95 tests ✅** |
| BO-6 | **Notifications · carril por eventos (fases 1, 2 y 4)** — tópico único `notifications.notification-requested` con el **mismo contrato que el POST**: notifications consume UNO y no sabe nada de nadie; el emisor decide destinatario, clave y canales. Un destinatario no registrado se **descarta y no rebota** (rebotar bloquearía la partición para los avisos que sí tienen a quién llegarle). BFF: alta perezosa del empleado al pedir su buzón vía `staffMe` — repara sin script a todo el personal ya sembrado. Migración `013`: políticas y plantillas `IN_APP` de las 3 claves de backoffice, con `event_type` NULL a propósito. Los 10 listeners de dominio **conviven mientras se migran** (fase 5, posterior). Plan y análisis del journey de los 3 perfiles de app en `docs/NOTIFICATIONS_EVENT_PLAN.md` | ✅ 2026-08-17 — **55 + 95 tests ✅** |
| BO-5 | **Notifications · destinatario abstracto (desacople del micro)** — migración `012`: tabla `notification_recipients (recipient_type, recipient_id)` con tipo **texto libre que el servicio nunca interpreta**; `recipient_type` en registros y preferencias; **`event_key` sustituye al enum `EventType` como llave** de políticas y plantillas (con un enum cerrado, cada emisor nuevo obligaba a recompilar el notificador). API: `PUT /recipients/{tipo}/{id}`, `POST /notifications` (el emisor decide destinatario, clave y canales), `GET /feed/{tipo}/{id}` + `unreadCount`, `PUT .../read-all`. BFF: `GET /notifications` y `/read-all` con `staffUserId` de la sesión, tipo `STAFF`, **sin capacidad** (buzón propio, como `/permissions/me`) y degradado a vacío si el servicio no responde. El journey de crédito sigue igual: pasa a ser **un emisor más** con tipo `PARTY` | ✅ 2026-08-17 — **50 tests ✅** |
| BO-4 | **Backoffice · rollup de cartera por unidad de ORIGEN (cierra D-1)** — `credit-portfolio`: `GET /api/v1/portfolio/accounts/stats/by-origin-unit?unitCodes=`, `GROUP BY origin_unit_code` con `FILTER` por tramo IFRS-9 en una pasada (patrón C, agregado en la base del dueño). BFF: `GET /dashboard/commercial/by-origin-unit?unitId=`, capacidad `dashboard.commercial` (existente, **sin capacidad nueva**), **2 llamadas fijas** (subárbol + agregado) sin importar cuántas unidades cuelguen. **Atribución elegida: unidad de ORIGEN sellada**, publicada en endpoint aparte del rollup por ejecutivo actual que ya vivía en `/dashboard/commercial`, con `attribution: "ORIGIN_UNIT"` en el cuerpo. Cotejo en la base sembrada: las dos atribuciones dan 53 cuentas y $3,443,321, cero unidades con diferencia | ✅ 2026-08-17 — **132 + 95 tests ✅** |
| BO-3 | **Backoffice · decisiones cerradas** — **Contratos:** no se hace. `Contract` es `@Embeddable` sin id ni repositorio y la tabla de amortización es de credit-portfolio con tres formas según producto; lo que se vería ya está en la ficha de cuenta. **Notificaciones:** se construye el feed de staff (pendiente). **Rollups D-1:** camino A (fan-out acotado con `partyIds=` en `/accounts/stats`) — pendiente | 🔄 2026-08-17 |
| 1d | **Cadencia quincenal de la colocación** — `credit-product` `016`: `DISTRIBUTOR_LINE` pasa a `BIWEEKLY` (cadencia que ya existía; no se agrega enum) y sus plazos de 3–24 **meses** a 6–48 **quincenas**. `credit-portfolio`: `generarCalendarioDeDisposicion` deja de tener `"MONTHLY"` fijo y toma la cadencia del pin `(productCode, productVersion)` vía `ProductConfigResolver.resolveForAccount(...)`, con `AmortizationEngine.firstDueDate(...)` para el primer vencimiento. **Pendiente de producto:** `BIWEEKLY` son 26 períodos/año (cada 14 días), no 24 — el `$868.06` del contrato de la app supone 24; con 26 son ~$858.97. Test que fija el supuesto | ✅ 2026-08-17 — **127 tests ✅** |
| 1c | **Tope de colocación por beneficiario, configurable por producto** — `credit-product` `015`: columna `term_step`, `CHECK` de rangos coherentes, y `DISTRIBUTOR_LINE` con `min_amount 5000` / `max_amount 60000` / `amount_step 1000`; `termStep` expuesto en entidad, request y response. `beneficiary`: `PlacementLimits` valida monto, plazo y escalones contra el producto (ya no contra constantes) + `InsufficientLineException` → 409. **El tope por persona no es el tamaño de la línea**: sin él, un distribuidor podría colgar toda su línea del historial de un solo desconocido | ✅ 2026-08-17 — **205 + 63 tests ✅** |
| 1b | **Alineación con `KREDIUS_COLOCACION_API.md`** — `PAID_OFF` (16 aristas), proyección `wireName()` a los 9 estados de cable + `failed`, objeto Colocación campo por campo (`beneficiaryName`, `relationship`, `fortnightlyPayment`, `placedOn`, `inviteExpiresAt`, `PlacementMetrics`), envoltura `{"placements":[…]}`, índice único de liga viva por (distribuidor, celular), migración `005` | ✅ 2026-08-17 |
| 2 | Invitación y liga: token, vigencia 7 días, OTP, rate limit por token/teléfono/IP, reenvío, revocación, sweeper de vencimiento | ⬜ |
| 3 | **Alta previa y carga del KYC** — API pública de 7 pasos, OCR, prueba de vida, CLABE obligatoria, consentimientos con constancia (IP + hora + versión del texto), `submit` todo-o-nada → prospecto + Party + relación | ⬜ |
| 4 | Buró y entrega **sin filtrar por score** + evidencia de asunción de riesgo | ⬜ |
| 5 | Aprobación → disposición `THIRD_PARTY_CREDIT` → desembolso, con `disposition-rejected` → `FAILED` | ⬜ |
| 6 | Consultas del distribuidor (colocaciones, beneficiarios, resumen de línea) + republicación en el BFF + rutas de gateway | ⬜ |
| 7 | Bonificación 20% decreciente en **commission-service**: `commission_policy_tiers`, `basis` (`COLLECTED_TOTAL`\|`COLLECTED_INTEREST`), `DISTRIBUTOR_PUNCTUALITY_SHARE`, `daysDelinquent` en el shadow | ⬜ |
| 8 | Docker secuencial, E2E de punta a punta y **pruebas de performance** sobre la API pública | ⬜ |

---

## FASE GW — API Gateway (gateway-service)

**Estado:** ✅ Completo — 2026-07-03  
**Imagen:** `openresty/openresty:alpine`  
**Puerto externo:** `8080` (host) → `80` (contenedor). Los microservicios dejan de exponer puertos al host.  
**Directorio:** `services/gateway-service/`

### Archivos creados

- [x] `services/gateway-service/nginx.conf` — upstreams, rate limit zones, rutas públicas con `limit_req`, `access_by_lua_block` DENY BY DEFAULT, respuesta 429 JSON con `Retry-After`
- [x] `services/gateway-service/conf.d/jwt.lua` — validación RS256 con `resty.jwt`; cache de llave pública por worker; propaga `X-User-Id`, `X-Roles`, `X-Token-Jti`
- [x] `services/gateway-service/keys/.gitkeep` — instrucciones de generación de llaves
- [x] `docker-compose.yml` — servicio `gateway-service` en puerto `8080:80`; red explícita `fintech-network`; puertos de microservicios eliminados

### Rate Limiting en rutas públicas

Algoritmo **leaky bucket** (`ngx_http_limit_req_module`), clave por IP (`$binary_remote_addr`), estado en memoria compartida entre workers. Para multi-réplica del gateway usar `lua-resty-limit-traffic` + Redis.

| Zona | Rate | Burst | `nodelay` | Ruta(s) | Razón |
|---|---|---|---|---|---|
| `rl_login` | 5 req/min | 2 | ✓ | `/api/v1/auth/login`, `/mobile/auth/login` | Protege PIN contra brute force |
| `rl_refresh` | 10 req/min | 5 | ✓ | `/api/v1/auth/refresh`, `/mobile/auth/refresh` | Rotación normal de tokens |
| `rl_m2m` | 20 req/min | 10 | ✓ | `/api/v1/auth/clients/token` | Servicios M2M hacen retry automático |
| `rl_register` | 3 req/min | 1 | ✓ | `/mobile/auth/register` | Previene flood de cuentas falsas |
| `rl_otp_send` | 3 req/min | 1 | ✓ | `/mobile/otp/send`, `/mobile/otp/resend` | SMS = costo real por envío |
| `rl_otp_verify` | 10 req/min | 3 | ✓ | `/mobile/otp/verify` | Permite reintentos de código |

`nodelay`: el burst se consume inmediato sin cola — excedente → 429 de inmediato (no gasta recursos en peticiones que van a fallar de todas formas).

Respuesta de rate limit: `HTTP 429` JSON con `Retry-After: 60` (estándar RFC 6585).

### Rutas públicas (sin JWT)

| Ruta | Upstream | Descripción |
|---|---|---|
| `POST /api/v1/auth/login` | identity-service:8080 | Login — obtener token |
| `POST /api/v1/auth/refresh` | identity-service:8080 | Renovar token |
| `POST /api/v1/auth/clients/token` | identity-service:8080 | M2M client credentials |
| `POST /mobile/auth/login` | channel-mobile-service:8085 | Login BFF móvil |
| `POST /mobile/auth/refresh` | channel-mobile-service:8085 | Refresh BFF móvil |
| `POST /mobile/auth/register` | channel-mobile-service:8085 | Registro pre-KYC |
| `POST /mobile/otp/send` | channel-mobile-service:8085 | Envío OTP (pre-auth) |
| `POST /mobile/otp/verify` | channel-mobile-service:8085 | Verificación OTP (pre-auth) |
| `POST /mobile/otp/resend` | channel-mobile-service:8085 | Reenvío OTP (pre-auth) |
| `GET /health` | nginx local | Health check del gateway |

### Rutas protegidas (JWT RS256 requerido)

| Prefijo | Upstream | Notas |
|---|---|---|
| `/api/v1/auth/` | identity-service:8080 | Logout, validate, MFA, admin |
| `/api/v1/parties/` | party-service:8080 | — |
| `/api/v1/scoring/` | scoring-service:8080 | — |
| `/api/v1/origination/` | origination-service:8080 | — |
| `/api/v1/credit-products/` | credit-product-service:8080 | — |
| `/api/v1/portfolio/` | credit-portfolio-service:8080 | — |
| `/api/v1/config/` | configuration-service:8080 | — |
| `/api/v1/charges/` | charges-service:8080 | — |
| `/api/v1/payments/` | payments-service:8080 | — |
| `/mobile/` | channel-mobile-service:8085 | Strip `/mobile/` prefix; puerto 8085 |

### Prueba rápida (cuando la migración RS256 esté completa)

```bash
# Sin token → 401
curl -s http://localhost:8080/api/v1/origination/applications
# {"error":"unauthorized","reason":"missing_token"}

# Con token válido → 200
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"partyId":"<uuid>","pin":"1234"}' | jq -r .accessToken)

curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/origination/applications
```

### Verificación

- [x] RS256 operativo: PKCS#8 private key en identity-service, `public.pem` en services/gateway-service/keys/
- [x] `jwt.lua` — validación RS256 con `resty.jwt`; cache de llave pública por worker
- [x] Headers propagados: `X-User-Id`, `X-Roles`, `X-Token-Jti`
- [x] BFF endpoints: `GET /users/me` → identity, `GET /credit/account` → credit-portfolio
- [x] Header-trust adoptado en microservicios (eliminada validación individual de JWT)

---

## FASE 0 — Proyecto Base

**Estado:** ✅ Completo — 2026-05-14

### Entregables

- [x] `build.gradle.kts` + `settings.gradle.kts` — dependencias completas (web, jpa, security, modulith, kafka, liquibase, jjwt, openapi, postgresql), Gradle 8.12
- [x] `FintechServicesApplication.java`
- [x] `application.yml` — datasource, liquibase, kafka, openapi + namespaces por módulo
- [x] `application-local.yml` — overrides para dev local sin Docker
- [x] `application-docker.yml` — overrides para contenedor
- [x] `shared/event/DomainEvent.java` — clase base abstracta para todos los eventos de dominio
- [x] `shared/exception/DomainException.java` — excepción base con errorCode
- [x] `shared/exception/GlobalExceptionHandler.java` — `@RestControllerAdvice` + RFC 7807 ProblemDetail
- [x] `db/changelog/db.changelog-master.xml` — root Liquibase con includes por módulo
- [x] `package-info.java` en cada uno de los 15 paquetes de módulo (stubs con `@ApplicationModule`)
- [x] `Dockerfile` — multi-stage build Java 21 (eclipse-temurin:21-jdk-alpine → jre-alpine), usuario no-root
- [x] `docker-compose.yml` — app + postgres:16 + kafka:3.8 (cp-kafka:7.7.0) + zookeeper
- [x] `docker-compose.override.yml` — overrides dev local
- [x] `.env.example` — todas las variables de entorno documentadas
- [x] `.dockerignore`
- [x] `docker/postgres/init.sql`
- [x] `infra/terraform/` — estructura placeholder (dev/staging/prod + modules/)
- [x] `infra/README.md`
- [x] `ModularityTest.java` — Spring Modulith boundary test + documentación PlantUML
- [x] `README.md` raíz del proyecto
- [x] `docs/modules/{módulo}/README.md` — README por cada uno de los 15 módulos

### Verificación

- [ ] `./gradlew bootRun` arranca sin errores
- [ ] `./gradlew test` — `ModularityTest` pasa
- [ ] `docker-compose up postgres kafka` — servicios levantan correctamente

### Notas

- Paquete Java para D4 Credit Product: `com.fintech.creditproduct` (sin underscore — Java convention)
- DB schema de D4 sigue siendo `credit_product` (con underscore — SQL convention)
- Liquibase changelog de D4 en `db/changelog/creditproduct/` (alineado con paquete Java)
- `shared` es un módulo Spring Modulith con `allowedDependencies = {}` para que sea importable por todos
- `@EnableScheduling` en FintechServicesApplication (necesario para jobs D4/D5/T4)

### Notas
<!-- Agregar notas, decisiones o blockers durante la implementación -->

---

## MÓDULO 1 — T1: Identity & Auth

**Estado:** ✅ Completo — 2026-05-14  
**Doc de referencia:** `docs/dominios/T1_identity_auth.md`  
**Schema DB:** `identity`  
**Comunicación:** REST síncrono saliente — `/api/v1/auth/validate` (consumido por otros módulos). Sin eventos Kafka propios.

### Entidades

- [x] `identity.credentials` — id, party_id, credential_type, password_hash, status, failed_attempts, locked_until, created_at, updated_at
- [x] `identity.auth_tokens` — id, token_id, party_id, device_id, refresh_token_hash, issued_at, expires_at, revoked, revoked_at

### Liquibase Changelogs

- [x] `db/changelog/identity/001-create-schema.sql`
- [x] `db/changelog/identity/002-create-credentials-table.sql`
- [x] `db/changelog/identity/003-create-auth-tokens-table.sql`

### Código

- [x] `identity/domain/IdentityCredential.java` — entidad JPA con factory `create()`, `isLocked()`, `recordFailure()`, `resetFailures()`
- [x] `identity/domain/AuthToken.java` — entidad JPA con `create()`, `revoke()`, `isExpired()`
- [x] `identity/domain/CredentialType.java` — enum (NIP/BIOMETRIC/ESAT/OTP)
- [x] `identity/domain/CredentialStatus.java` — enum (ACTIVE/LOCKED/DISABLED)
- [x] `identity/domain/AccountLockedException.java` — errorCode AUTH_ACCOUNT_LOCKED, stores lockedUntil
- [x] `identity/domain/InvalidCredentialsException.java` — errorCode AUTH_INVALID_CREDENTIALS
- [x] `identity/domain/CredentialNotFoundException.java` — errorCode AUTH_CREDENTIAL_NOT_FOUND
- [x] `identity/domain/TokenException.java` — errorCode AUTH_TOKEN_INVALID
- [x] `identity/application/AuthProperties.java` — `@ConfigurationProperties("fintech.auth")`
- [x] `identity/application/JwtService.java` — JJWT 0.12.x; genera/valida JWT; claims: sub, roles, deviceId, jti
- [x] `identity/application/AuthService.java` — login, refresh (rotación), logout, validate, createCredential; refresh token como SHA-256 hex opaco
- [x] `identity/infrastructure/IdentityCredentialRepository.java` — `findByPartyIdAndCredentialType`
- [x] `identity/infrastructure/AuthTokenRepository.java` — `findByTokenId`, `findByRefreshTokenHash`, `revokeAllByPartyId`
- [x] `identity/infrastructure/SecurityConfig.java` — stateless, JWT filter, rutas públicas, ROLE_ADMIN en /credentials
- [x] `identity/infrastructure/JwtAuthenticationFilter.java` — OncePerRequestFilter; principal = partyId (subject del JWT)
- [x] `identity/infrastructure/IdentityModuleConfig.java` — `@EnableConfigurationProperties(AuthProperties.class)`
- [x] `identity/api/AuthController.java` — package-private
- [x] `identity/api/IdentityExceptionHandler.java` — 401 InvalidCredentials, 423 AccountLocked (con lockedUntil), 401 TokenException
- [x] `identity/api/dto/LoginRequest.java` — `@NotNull UUID partyId`, `@NotBlank String pin`
- [x] `identity/api/dto/RefreshRequest.java` — `@NotBlank String refreshToken`
- [x] `identity/api/dto/TokenResponse.java` — `accessToken, refreshToken, expiresIn, tokenType`
- [x] `identity/api/dto/TokenValidationResponse.java` — `UUID partyId, List<String> roles, String deviceId`
- [x] `identity/api/dto/CredentialCreateRequest.java` — `partyId, pin (@Size min=4 max=8), credentialType`

### Endpoints

- [x] `POST /api/v1/auth/login` → `{ accessToken, refreshToken, expiresIn, tokenType }`
- [x] `POST /api/v1/auth/refresh` → nuevo par de tokens con rotación (revoca el anterior)
- [x] `POST /api/v1/auth/logout` → revoca todas las sesiones del party (requiere auth)
- [x] `GET /api/v1/auth/validate` → valida JWT, retorna `{ partyId, roles, deviceId }` (requiere auth)
- [x] `POST /api/v1/auth/credentials` → crea credencial NIP (solo ROLE_ADMIN)

### Configuración

```yaml
fintech:
  auth:
    jwt-secret: ${JWT_SECRET}
    access-token-expiry-minutes: 15
    refresh-token-expiry-days: 7
    max-failed-attempts: 5
    lockout-duration-minutes: 30
    mfa-threshold-amount: 10000
```

### Tests

- [x] `identity/AuthServiceTest.java` — 8 unit tests: login success/not-found/wrong-pin/lockout/locked-account/reset-on-success; refresh valid/revoked/not-found; logout revokes all
- [x] `identity/AuthControllerTest.java` — 9 `@WebMvcTest` slice tests: login 200/401/400; refresh 200; logout 204; validate 200/401; createCredential 201 (ADMIN) / 403 (CUSTOMER)
- [x] `ModularityTest` sigue pasando

### Verificación

- [ ] `POST /api/v1/auth/login` → 200 con JWT válido
- [ ] `GET /api/v1/auth/validate` con Bearer token → 200 con claims
- [ ] `POST /api/v1/auth/refresh` → nuevo par de tokens
- [ ] `POST /api/v1/auth/logout` → 204; reuso del refresh token → 401
- [ ] 5 intentos fallidos → account LOCKED (423)
- [ ] `./gradlew test` completo pasa

### Notas

- Refresh token: opaco, 64 chars hex (dos UUID concatenados sin guiones). Almacenado como SHA-256 hash en DB — previene leakage en caso de brecha.
- `logout()` revoca **todas** las sesiones del party (no solo la actual), llamando `revokeAllByPartyId`. El partyId lo extrae del JWT subject.
- `@AuthenticationPrincipal` devuelve el subject del JWT (partyId como String) → `UUID.fromString()` en el controller.
- `IdentityExceptionHandler` usa `@Order(1)` para tomar precedencia sobre `GlobalExceptionHandler`.
- El endpoint `/credentials` es exclusivo para bootstrap/D0 delegado; en producción solo ADMIN puede crearlo.
- `@WebMvcTest` requiere `@Import({SecurityConfig.class, JwtAuthenticationFilter.class})` + `@TestPropertySource` con las propiedades `fintech.auth.*` para que el contexto de seguridad cargue correctamente.

---

## MÓDULO 2 — T5: Configuration

**Estado:** ✅ Completo — 2026-06-04  
**Doc de referencia:** `docs/dominios/T5_configuration.md`  
**Schema DB:** `configuration`  
**Puerto:** `8086`  
**Comunicación:** Publica `configuration.configuration-updated` (Kafka) al aprobar un parámetro.

### Entidades

- [x] `configuration.config_parameters` — id, param_key, value, product_type, channel_type, version, status, effective_date, created_by, approved_by, previous_version_ref
- [x] `configuration.config_audit_trail` — id, parameter_id, action, actor_id, timestamp, old_value, new_value

### Liquibase Changelogs

- [x] `db/changelog/configuration/001-create-schema.sql`
- [x] `db/changelog/configuration/002-create-config-parameters.sql`
- [x] `db/changelog/configuration/003-create-config-audit-trail.sql`
- [x] `db/changelog/configuration/004-create-event-publication.sql`
- [x] `db/changelog/configuration/005-seed-initial-params.sql` — 7 parámetros semilla (vat_rate, grace_period_days, return_window_hours, …)

### Código

**Dominio:**
- [x] `ConfigParameter.java` — aggregate root, factory `create()`, `approve()`, `reject()`, `deprecate()`
- [x] `ConfigParameterStatus.java` — DRAFT | PENDING_APPROVAL | ACTIVE | DEPRECATED
- [x] `ConfigAuditTrail.java` — append-only, factory `record()`
- [x] `ConfigAuditAction.java` — CREATED | APPROVED | REJECTED | DEPRECATED
- [x] `ConfigParameterNotFoundException.java` — CONFIGURATION_NOT_FOUND
- [x] `InvalidConfigStateTransitionException.java` — CONFIGURATION_INVALID_TRANSITION
- [x] `DuplicateActiveParameterException.java` — CONFIGURATION_DUPLICATE_ACTIVE

**Puertos:**
- [x] `ConfigParameterRepository.java` — save | findById | findByParamKeyAndStatus | findAllByParamKeyOrderByVersionDesc | findMaxVersionByParamKey
- [x] `ConfigAuditRepository.java` — save (append-only)
- [x] `ConfigEventPublisher.java` — publishConfigurationUpdated

**Servicios de aplicación:**
- [x] `ConfigurationService.java` — implements 4 use cases; @CacheEvict allEntries on approve; deprecates previous ACTIVE version on approval
- [x] `ConfigurationProperties.java` — `@ConfigurationProperties("fintech.configuration")`: jwtSecret, cacheTtlSeconds
- [x] `ConfigCacheNames.java` — `configuration.params`

**Infraestructura:**
- [x] `JwtAuthenticationFilter.java` — JJWT 0.12.x; reads secret from ConfigurationProperties
- [x] `JpaConfigParameterAdapter.java` / `JpaConfigParameterRepository.java`
- [x] `JpaConfigAuditAdapter.java` / `JpaConfigAuditRepository.java`
- [x] `KafkaConfigEventPublisher.java` — topic `configuration.configuration-updated`; fallo no revierte aprobación
- [x] `CacheConfig.java` — RedisCacheManager con TTL configurable
- [x] `KafkaConfig.java` / `SecurityConfig.java` / `OpenApiConfig.java` / `ConfigurationModuleConfig.java`

**API REST:**
- [x] `ConfigController.java` — package-private; 4 endpoints
- [x] `ConfigExceptionHandler.java` — @Order(1); 404/422/409
- [x] DTOs: `CreateConfigParameterRequest`, `ConfigParameterResponse`

### Endpoints

- [x] `GET /api/v1/config/{key}` — lectura con cache Redis (TTL configurable, default 300s)
- [x] `POST /api/v1/config` — crear parámetro (maker) → 201 PENDING_APPROVAL
- [x] `PUT /api/v1/config/{id}/approve` — aprobación (checker) → 200 ACTIVE + evicta cache + publica Kafka
- [x] `GET /api/v1/config/{key}/history` — versiones históricas ordenadas desc

### Eventos Publicados

- [x] `configuration.configuration-updated { key, newValue, effectiveDate, productType, channelType }`

### Reglas de Negocio implementadas

- [x] Ciclo maker-checker: crear → PENDING_APPROVAL → approve → ACTIVE
- [x] Al aprobar nueva versión → depreca automáticamente la ACTIVE anterior (CF-02)
- [x] Nunca eliminar: solo DEPRECATED
- [x] Cache Redis con @Cacheable / @CacheEvict; invalidación total en aprobación (CF-03)
- [x] Segmentado por `(productType?, channelType?)` — partial unique index ACTIVE
- [x] `event_publication` table creada para Spring Modulith
- [x] Parámetros semilla: vat_rate, grace_period_days, return_window_hours, max_contact_attempts_per_day, contact_allowed_hours, score_validity_days, codi_token_ttl_minutes

### Tests

- [x] `ConfigurationServiceTest` — 7 unit (Mockito): create, increment version, approve+event, deprecate prev active, approve notFound, approve invalid transition, getHistory
- [x] `ConfigControllerTest` — 7 @WebMvcTest: GET 200/404, GET noToken 401, POST 201/400, PUT 200/404, history 200
- [x] `ConfigurationAcceptanceTest` — 5 Testcontainers: AC-1..5

### Verificación

- [ ] `GET /api/v1/config/vat_rate` → 200 con `value: "0.16"` (parámetro semilla)
- [ ] `POST /api/v1/config` → 201 PENDING_APPROVAL
- [ ] `PUT /api/v1/config/{id}/approve` → 200 ACTIVE; anterior versión DEPRECATED
- [ ] `GET /api/v1/config/vat_rate/history` → lista con versiones
- [ ] `./gradlew :configuration-service:test` → todos los tests pasan

### Notas

- Kafka failure en `publishConfigurationUpdated` no revierte la aprobación — try-catch aislado en el publisher
- Cache evicta `allEntries = true` al aprobar (no key-specific) — conservador, cubre todos los segmentos del mismo parámetro
- El `configuration` stub original en `settings.gradle.kts` fue reemplazado por `configuration-service`

---

## MÓDULO 3 — T3: Audit & Compliance

**Estado:** ✅ Completo — 2026-07-03  
**Doc de referencia:** `docs/dominios/T3_audit_compliance.md`  
**Schema DB:** `audit`  
**Puerto:** `8090`  
**Comunicación:** Suscriptor global Kafka — 16 listeners: origination×4, scoring×2, credit-portfolio×4, charges×2, payments×2, configuration×1, credit-product×1. Append-only — nunca retroalimenta.

### Entidades

- [x] `audit.audit_entries` — id, event_type, aggregate_type, aggregate_id, actor_id, party_id, correlation_id, payload (jsonb), severity, created_at. Inmutable — sin UPDATE/DELETE.
- [x] `audit.document_file_refs` — id, doc_type, reference_id, party_id, storage_url, retention_years, created_at, expires_at. Retención calculada por tipo.
- [x] `audit.uif_reports` — id, party_id, report_type (INUSUAL/RELEVANTE/INTERNO), amount, description, submitted_at, status.

### Liquibase Changelogs

- [x] `db/changelog/audit/001-create-schema.sql`
- [x] `db/changelog/audit/002-create-audit-entries.sql`
- [x] `db/changelog/audit/003-create-document-file-refs.sql`
- [x] `db/changelog/audit/004-create-uif-reports.sql`
- [x] `db/changelog/audit/005-create-event-publication.sql`

### Endpoints

- [x] `GET /api/v1/audit/entries` — filtros: partyId, aggregateId, eventType, dateFrom, dateTo (roles AUDITOR/REGULATOR/ADMIN)
- [x] `GET /api/v1/audit/entries/{id}` — detalle entrada
- [x] `GET /api/v1/audit/parties/{partyId}/documents` — referencias de documentos del party
- [x] `GET /api/v1/audit/parties/{partyId}/uif-reports` — reportes UIF del party
- [x] `GET /api/v1/audit/parties/{partyId}/expedition` — resumen de expediente

### Retención Regulatoria

| Tipo | Años | Regulación |
|---|---|---|
| Reportes de buró | 5 | CNBV Circular 14/2013 |
| Contratos | 10 | Código de Comercio |
| Estados de cuenta | 5 | CONDUSEF |
| AML/UIF | 10 | LFPIORPI |

### Tests

- [x] `AuditEntryServiceTest` — 5 unit (Mockito): append-only, retención por tipo, listeners Kafka
- [x] `AuditControllerTest` — 7 @WebMvcTest: filtros, roles, 401/403/404

**Total: 12 tests ✅ (5 unit + 7 WebMvcTest)**

---

## MÓDULO 4 — D0: Party (party-service)

**Estado:** ✅ Completo — 2026-07-05  
**Doc de referencia:** [`docs/modules/party/README.md`](modules/party/README.md) · [`docs/dominios/00_party_domain.md`](dominios/00_party_domain.md)  
**Schema DB:** `party`  
**Puerto:** `8083`  
**Comunicación:** Consume `origination.prospect-created` (Kafka). Publica `party.party-blacklisted`.

### Entidades

- [x] `party.parties` — aggregate root, PROSPECT→ACTIVE→SUSPENDED→BLACKLISTED→CLOSED
- [x] `party.kyc_verifications` — verificationId, partyId, documentType (INE/PASSPORT/RFC/CURP/ACTA_CONSTITUTIVA/PODER_NOTARIAL), verificationStatus (PENDING/IN_PROGRESS/VERIFIED/REJECTED/EXPIRED), verifiedBy, verifiedAt, rejectionReason, documentRef, expiresAt
- [x] `party.consent_records` — consentId, partyId, consentType (CREDIT_BUREAU/MARKETING/PRIVACY_POLICY/DATA_PROCESSING/ARCO_CANCELLATION), status (GRANTED/REVOKED/EXPIRED), grantedAt, expiresAt, documentRef
- [x] `party.party_relationships` — relationshipId, partyId, relatedPartyId, relationshipType (GUARANTOR/BENEFICIARY/LEGAL_REPRESENTATIVE/DISTRIBUTOR/COSIGNER), creditProductId?, active

### Liquibase Changelogs

- [x] `001-create-schema.sql`
- [x] `002-create-parties.sql` — UNIQUE(prospect_id), UNIQUE(curp), CHECK constraints
- [x] `003-party-prospect-status.sql` — agrega PROSPECT al ciclo de vida
- [x] `004-create-kyc-verifications.sql`
- [x] `005-create-consent-records.sql`
- [x] `006-create-party-relationships.sql`

### Endpoints

- [x] `GET /api/v1/parties/{partyId}`
- [x] `GET /api/v1/parties/by-prospect/{prospectId}`
- [x] `PUT /api/v1/parties/{partyId}/kyc-status` — crea/actualiza verificación KYC (VERIFIED/REJECTED)
- [x] `GET /api/v1/parties/{partyId}/kyc-verifications` — lista todas las verificaciones del party
- [x] `POST /api/v1/parties/{partyId}/blacklist` — blacklistea el party + emite `PartyBlacklisted`

### Reglas de Negocio implementadas

- [x] Creación automática vía `origination.prospect-created` (idempotente por prospectId)
- [x] `partyType` inmutable post-creación
- [x] **I-07** BLACKLISTED — `Party.blacklist()` cambia estado; emite `PartyBlacklisted(partyId, reason, sourceList)` → Channels (IR-02), Scoring, Collections
- [x] KYC: `addKycVerification` reutiliza el registro existente del mismo documentType; `verify()` o `reject()` según el status recibido
- [x] Kafka failure en blacklist no revierte el cambio de estado (try-catch aislado)

### Eventos

- [x] Consume `origination.prospect-created` → `ProspectCreatedEventListener` → `createFromProspect()`
- [x] Publica `party.party-blacklisted` → `KafkaPartyEventPublisher` → payload `{partyId, reason, sourceList, blacklistedAt}`

### Tests

- [x] `PartyServiceTest` — 11 unit (Mockito): createFromProspect OK, idempotencia duplicado, findById, findByProspectId, addKycVerification (nuevos/existentes/REJECTED/notFound), blacklist (OK, notFound, ya-blacklisted, Kafka falla)
- [x] `PartyControllerTest` — 10 @WebMvcTest: GET 200/404, PUT kyc-status 200/404/400, GET kyc-verifications 200, POST blacklist 200/404/409/400
- [x] `ScoringApprovedEventListenerTest` — 2 unit Mockito
- [x] `PartyCreationFlowIT` — 3 IT Testcontainers
- [x] **Total: 26 tests ✅**

### Notas

- `partyType` mapeado directamente desde `prospectType` del evento: `INDIVIDUAL → INDIVIDUAL`, `BUSINESS → BUSINESS`.
- Changelog `003` existía en disco pero no estaba incluido en `db.changelog-party.yaml` — corregido.

---

## MÓDULO 5 — D1: Channels

**Estado:** ✅ Completo — 2026-07-04  
**Doc de referencia:** `docs/dominios/01_channels_domain.md`  
**Schema DB:** `channels`  
**Puerto:** `8091`  
**Comunicación:** ACL síncrona → party-service (blacklist check). Publica `ApplicationStarted`, `SessionStarted/Expired`, `IntentCaptured/Routed/Abandoned`, `LeadCreated/Converted`.

### Entidades

- [x] `channels.channels` — channelId, channelType (8 tipos), status, allowedIntents (TEXT), sessionTtlMinutes, maxIdleMinutes, rateLimitPerHour — seed con 8 canales
- [x] `channels.sessions` — sessionId, channelId, channelType, partyId?, **DeviceContext @Embeddable** (deviceId, deviceType, deviceModel, deviceManufacturer, os, osVersion, appVersion, sdkVersion, networkType, userAgent TEXT, browser, browserVersion, ipAddress, ipCountry, isTrustedDevice, isRooted, isEmulator), status (ACTIVE/IDLE/EXPIRED/CLOSED), startedAt, expiresAt, closedAt. Indexes de fraude: ip_address, device_id, (is_rooted, is_emulator).
- [x] `channels.customer_intents` — intentId, sessionId, intentType, status (CAPTURED/ROUTED/COMPLETED/ABANDONED), routedTo (DomainTarget), productTypeHint, requestedAmount, abandonReason
- [x] `channels.lead_requests` — leadId, channelId, intentType, status (NEW/CONTACTED/QUALIFIED/CONVERTED/EXPIRED), firstName, lastName1, phone, email, promoterPartyId, convertedPartyId, expiresAt

### Liquibase Changelogs

- [x] `001-create-schema.sql`
- [x] `002-create-channels.sql`
- [x] `003-create-sessions.sql` (indexes: party+channel, status, expiresAt)
- [x] `004-create-customer-intents.sql`
- [x] `005-create-lead-requests.sql`
- [x] `006-seed-default-channels.sql` (8 canales ACTIVE con intents permitidos, TTLs y rate limits)
- [x] `007-add-device-context-columns.sql` (14 columnas device + 3 indexes anti-fraude)

### Endpoints

- [x] `GET /api/v1/channels` — lista canales activos (ADMIN/SUPPORT)
- [x] `POST /api/v1/sessions` — inicia sesión de negocio
- [x] `GET /api/v1/sessions/{id}` — consulta sesión
- [x] `PUT /api/v1/sessions/{id}/close` — cierra sesión y abandona intents pendientes
- [x] `POST /api/v1/sessions/{id}/intents` — captura intent
- [x] `PUT /api/v1/sessions/{id}/intents/{intentId}/route` — enruta intent (→ ApplicationStarted)
- [x] `PUT /api/v1/sessions/{id}/intents/{intentId}/abandon` — abandona intent
- [x] `POST /api/v1/leads` — registra lead (promotor de campo)
- [x] `GET /api/v1/leads/{id}` — consulta lead
- [x] `PUT /api/v1/leads/{id}/convert` — convierte lead (→ LeadConverted)

### Reglas de Negocio implementadas

- [x] **CH-01**: canal DISABLED no genera sesiones
- [x] **S-03**: una sola sesión ACTIVE por `partyId+channelType` — nueva sesión expira la anterior automáticamente
- [x] **IR-02**: `Party.status=BLACKLISTED` → `IntentAbandoned(PARTY_BLACKLISTED)` — ApplicationStarted NO se emite
- [x] **IR-03**: `intentType` debe estar en `Channel.allowedIntents`
- [x] **L-02**: lead CONVERTED es inmutable
- [x] **L-03**: TTL 7d para FIELD_PROMOTER, 30d para digital (configurable)
- [x] `ApplicationStarted` solo se emite para `CREDIT_APPLICATION` o `REFINANCING` con `partyId` presente
- [x] Fail-open en party-service: si la llamada falla, se loguea warning y se procede (evitar bloqueo por indisponibilidad)

### Eventos Publicados

| Topic | Evento clave |
|---|---|
| `channels.session-started` | sessionId, partyId?, channelType, device |
| `channels.session-expired` | sessionId, reason, pendingIntents[] |
| `channels.intent-captured` | intentId, sessionId, intentType |
| `channels.intent-routed` | intentId, routedTo |
| `channels.intent-abandoned` | intentId, reason |
| `channels.application-started` | **intentId, partyId, channelId**, productTypeHint, requestedAmount |
| `channels.lead-created` | leadId, channelType, promoterPartyId? |
| `channels.lead-converted` | leadId, convertedPartyId |

### Device Context — `DeviceContext @Embeddable`

Todos los campos se resuelven del lado servidor (anti-spoofing). IP y User-Agent **nunca** provienen del body:

| Campo | Fuente | Notas |
|---|---|---|
| `ipAddress` | `X-Forwarded-For[0]` → `X-Real-IP` → `RemoteAddr` | Cadena de proxies estándar |
| `ipCountry` | Header `X-Country-Code` (gateway, GeoIP) | Opcional |
| `userAgent` | Header `User-Agent` HTTP | Servidor |
| `browser` / `browserVersion` | Parseado de userAgent | Chrome/Firefox/Safari/Edge/Opera |
| `deviceType` | SDK o inferido del User-Agent | MOBILE/TABLET/DESKTOP/UNKNOWN |
| `isRooted` / `isEmulator` | SDK auto-reportado | Limitación conocida: spoofable en ataques sofisticados |

Lógica de extracción en `ClientIpExtractor` (util público, testeable de forma aislada).

### ACL

- [x] `PartyServiceClient` — RestClient → `GET /api/v1/parties/{id}` con `X-User-Id`; fail-open en error de red

### Tests

- [x] `SessionServiceTest` — 5 unit (Mockito): startSession OK, S-03 expire existing, CH-01 disabled channel, channel not found, closeSession+abandon intents
- [x] `ChannelControllerTest` — 8 @WebMvcTest: 401 sin auth, 403 CUSTOMER en /channels, 200 ADMIN, 201 startSession, 401 sin auth session, 400 bad request, 404 session not found, 400 intent type not allowed
- [x] `SessionControllerIpTest` — 7 unit: X-Forwarded-For first IP, fallback X-Real-IP, fallback RemoteAddr, IPv6, whitespace trim, single IP, blank header fallback

**Total: 20 tests ✅ (5 unit + 8 WebMvcTest + 7 IP unit)**

---

## MÓDULO 6 — D2: Scoring

**Estado:** ✅ Completo — 2026-06-05  
**Doc de referencia:** [`docs/dominios/02_scoring_domain.md`](dominios/02_scoring_domain.md)  
**Schema DB:** `scoring`  
**Puerto:** `8082`  
**Comunicación:** Consume `origination.prospect-created` (→ prefetch-only) y `origination.score-requested` (→ decision engine, Fase D ✅). Llama Círculo de Crédito REST (ACL). Publica `scoring.scoring-completed` (con `applicationId`) → origination, party.

### Entidades implementadas

- [x] `scoring.bureau_prefetches` — prefetch_id, prospect_id, curp, consent_ref, prospect_type, product_type_intent, status, requested_at, completed_at, failure_reason
- [x] `scoring.circulo_reports` — reporte completo CDC con FICO, persona, error
- [x] `scoring.circulo_credits` — tradelines de crédito (peor_atraso, saldo_vencido, tipo_credito…)
- [x] `scoring.circulo_addresses` — domicilios reportados
- [x] `scoring.circulo_employments` — empleos reportados
- [x] `scoring.circulo_inquiries` — consultas previas al buró
- [x] `scoring.circulo_scores` — scores del reporte (FICO y otros)
- [x] `scoring.scoring_policies` — políticas de scoring por (prospectType, productTypeIntent), partial unique index active
- [x] `scoring.scoring_rules` — reglas configurables: type, operator, threshold, scoreContribution, disqualifying
- [x] `scoring.risk_thresholds` — umbrales BAJO/MEDIO/ALTO con minScore y decision
- [x] `scoring.score_evaluations` — resultado inmutable con rule_details JSONB

### Liquibase Changelogs

- [x] `db/changelog/scoring/001-create-schema.sql`
- [x] `db/changelog/scoring/002-create-bureau-prefetches.sql`
- [x] `db/changelog/scoring/003-create-circulo-reports.sql`
- [x] `db/changelog/scoring/004-add-prospect-type-to-prefetch.sql`
- [x] `db/changelog/scoring/005-create-scoring-policies.sql`
- [x] `db/changelog/scoring/006-create-score-evaluations.sql`
- [x] `db/changelog/scoring/007-seed-initial-scoring-policy.sql`

### Código

**Dominio:**
- [x] `BureauPrefetch.java` — aggregate root, máquina de estados (PENDING→IN_PROGRESS→COMPLETED|FAILED)
- [x] `CirculoReport.java` — aggregate root, Builder pattern, 5 colecciones hijas
- [x] `CirculoCredit.java` / `CirculoAddress.java` / `CirculoEmployment.java` / `CirculoInquiry.java` / `CirculoScore.java`
- [x] `ScoringPolicy.java` — aggregate root con `addRule()`, `addThreshold()`, `deactivate()`
- [x] `ScoringRule.java` — inmutable, factory method
- [x] `RiskThreshold.java` — inmutable, factory method
- [x] `ScoreEvaluation.java` — inmutable, `rule_details` como JSONB
- [x] `RuleEvaluationDetail.java` — record: ruleId, ruleType, matched, scoreApplied, disqualifying, detail
- [x] `RuleType.java` — MORA_CHECK | FICO_THRESHOLD | CREDIT_COUNT | BALANCE_CHECK | INQUIRY_COUNT
- [x] `RuleOperator.java` — GT | GTE | LT | LTE | EQ con `apply(BigDecimal, BigDecimal)`
- [x] `RiskLevel.java` — BAJO | MEDIO | ALTO
- [x] `ScoringDecision.java` — AUTO_APPROVED | MANUAL_REVIEW | REJECTED

**Puertos:**
- [x] `BureauPrefetchRepository.java` — save | findById | existsActiveByProspectId
- [x] `CirculoReportRepository.java` — save | findByProspectId
- [x] `CirculoGateway.java` — query(reportId, prefetchId, prospectId, request)
- [x] `ScoringPolicyRepository.java` — save | findById | findActiveBy | findAllActive
- [x] `ScoreEvaluationRepository.java` — save | findLatestByProspectId
- [x] `ScoringEventPublisher.java` — publishApproved(ScoringApprovedEvent)

**Servicios de aplicación:**
- [x] `BureauPrefetchService.java` — orquesta CDC fetch + dispara scoring en `REQUIRES_NEW`
- [x] `ScoringRuleEvaluator.java` — pure Java, sin Spring; short-circuit en disqualifying; FICO aditivo
- [x] `ScoringEvaluationService.java` — evalúa política, resuelve threshold, guarda `ScoreEvaluation`
- [x] `ScoringPolicyService.java` — CRUD de políticas; desactiva anterior al crear nueva

**Dominio — Eventos:**
- [x] `ScoringApprovedEvent.java` — record con todos los campos del prospecto; `from(ScoreEvaluation, CirculoReport, ScoringPolicy)`

**Infraestructura:**
- [x] `CirculoCreditoAdapter.java` — POST /v2/rccficoscore; sanitiza acentos/ñ; ACL completa; 5xx → throws (→ ERROR)
- [x] `CirculoConfig.java` / `CirculoProperties.java` — RestClient con header `x-api-key`
- [x] `ScoreEvaluation.ruleDetails` — JSONB nativo vía `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6; reemplazó al `RuleDetailsConverter` que causaba mismatch varchar→jsonb en Postgres real)
- [x] `JpaBureauPrefetchAdapter.java` / `JpaCirculoReportAdapter.java`
- [x] `JpaScoringPolicyAdapter.java` / `JpaScoreEvaluationAdapter.java`
- [x] `ProspectCreatedEventListener.java` — `@KafkaListener` en `origination.prospect-created`
- [x] `KafkaScoringEventPublisher.java` — publica en `scoring.scoring-approved`; key=prospectId; fallo no revierte ScoreEvaluation

**API REST:**
- [x] `ScoringPolicyController.java` — GET /policies, GET /{id}, POST (201), DELETE /{id}
- [x] `ScoreEvaluationController.java` — GET /evaluations/{prospectId}/latest, POST /evaluate/{prospectId}
- [x] DTOs: `ScoringPolicyRequest`, `ScoringPolicyResponse`, `ScoreEvaluationResponse`

### Endpoints

- [x] `GET /api/v1/scoring/policies`
- [x] `GET /api/v1/scoring/policies/{policyId}`
- [x] `POST /api/v1/scoring/policies` → 201
- [x] `DELETE /api/v1/scoring/policies/{policyId}`
- [x] `GET /api/v1/scoring/evaluations/{prospectId}/latest`
- [x] `POST /api/v1/scoring/evaluate/{prospectId}?prospectType=&productTypeIntent=`

### Reglas de Negocio implementadas

- [x] Política seleccionada por `(prospectType, productTypeIntent)` — configurable en caliente, sin redeploy
- [x] Un solo prefetch activo por `prospect_id` (partial unique index + JPQL)
- [x] Evaluación aislada en `REQUIRES_NEW` — falla de scoring no revierte datos CDC
- [x] Regla `disqualifying=true` + condición cumplida → ALTO/REJECTED inmediato
- [x] FICO bands aditivas — múltiples GTE se suman independientemente
- [x] Resolución de threshold: ordered desc por minScore, primer match gana
- [x] Al crear nueva política del mismo tipo → desactiva automáticamente la anterior
- [x] Toda decisión (AUTO_APPROVED | MANUAL_REVIEW | REJECTED) emite `ScoringCompletedEvent` a `scoring.scoring-completed` (con `applicationId` cuando viene de `score-requested`)
- [x] Fallo de publicación Kafka no revierte `ScoreEvaluation` persisitida (try-catch aislado)

### Tests

- [x] `ScoringRuleEvaluatorTest` — unit, todos los RuleType, bandas FICO, disqualifying, nulos
- [x] `ScoringEvaluationServiceTest` — unit Mockito; sin política/skip, AUTO_APPROVED (verifica event published), MANUAL_REVIEW/REJECTED (verifica event NOT published), re-eval manual
- [x] `BureauPrefetchServiceTest` — unit Mockito; CDC OK→triggerScoring, ERROR→no scoring, scoring falla→prefetch COMPLETED
- [x] `ProspectCreatedEventListenerTest` — unit Mockito; consent=true/false, prospectType/productTypeIntent propagados
- [x] `CirculoCreditoAdapterTest` — unit (RestClient mock); null→NO_HIT, exception→ERROR, sanitización, municipio fallback
- [x] `CirculoCreditoAdapterIT` — WireMock; SUCCESS mapping, child collections, 204→NO_HIT, 500→ERROR, connection refused→ERROR

### Verificación

- [ ] `GET /api/v1/scoring/policies` → 200 con política semilla INDIVIDUAL/PERSONAL_LOAN
- [ ] `POST /api/v1/scoring/policies` → 201 con nueva política BUSINESS/REVOLVING_LINE
- [ ] Publicar `origination.prospect-created` con `circuloConsentAccepted=true` → `SELECT * FROM scoring.score_evaluations` → 1 fila
- [ ] `GET /api/v1/scoring/evaluations/{prospectId}/latest` → 200 con decision
- [ ] `POST /api/v1/scoring/evaluate/{prospectId}` → 200 re-evaluación
- [ ] Tras AUTO_APPROVED → `SELECT * FROM party.parties` → 1 fila creada automáticamente
- [ ] `GET /api/v1/parties/by-prospect/{prospectId}` → 200 con datos del party
- [x] `./gradlew :scoring-service:test` → 48 tests ✅
- [x] `./gradlew :party-service:test` → 6 tests ✅

### Notas

- API key de Círculo de Crédito inyectada por `CIRCULO_CREDITO_API_KEY`. El default en `application.yml` es solo para sandbox local.
- `ruleDetails` se mapea a JSONB con `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6 nativo). Reemplazó al `RuleDetailsConverter` (AttributeConverter→String) que enviaba `varchar` y rompía el INSERT en Postgres real (`column "rule_details" is of type jsonb but expression is of type character varying`) — detectado por `ScoringFlowIT`.
- Migración 007 usa `splitStatements="false"` en Liquibase por bloque PL/pgSQL `DO $...$`.

---

## MÓDULO 7 — D3: Origination (origination-service)

**Estado:** ✅ Completo — 2026-07-06  
**Doc de referencia:** [`services/origination-service/README.md`](../services/origination-service/README.md) · [`03_credit_origination_domain.md`](dominios/03_credit_origination_domain.md) · [ADR-001](../README.md)  
**Schema DB:** `origination`  
**Puerto:** `8081`  
**Comunicación:** Publica `origination.prospect-created` (onboarding persona) y `origination.score-requested` (al elegir producto). Consume `scoring.scoring-completed` (Fase C ✅). Pendiente: `ProductDefined` (catálogo).

### Fases implementadas (ADR-001)

- [x] **Fase A** — `Prospect` = onboarding puro sin `productTypeIntent`. `ProspectCreated` solo dispara prefetch de buró.
- [x] **Fase B** — `CreditApplication` (subdominio underwriting): selección de producto → emite `ScoreRequested(productType)`.
- [x] **Fase C** — Consumer `scoring.scoring-completed` → transiciona CreditApplication (APPROVED/UNDER_MANUAL_REVIEW/COMMITTEE_REVIEW/REJECTED). Idempotente. `ScoringDecisionFlowIT` ✅
- [x] **Fase D** — `score-requested` listener en scoring-service; `ScoringCompletedEvent` con `applicationId`. `ScoringFlowIT` ✅
- [x] **Fase E+F+G** — Offer management (CAT BdM), contract management (firma + CLABE), `CreditProductCreationRequested` snapshot → credit-portfolio, consumer `credit-account-activated` → DISBURSED. `ContractToPortfolioFlowIT` ✅
- [x] **Fase H** — Aprobación/rechazo manual: `RecordApprovalDecisionUseCase`, `UnderwritingController` (`PUT /applications/{id}/approve`, `PUT /applications/{id}/reject`), routing COMMITTEE_REVIEW por umbral de monto.
- [x] **Canal → Originación** — Consumer `channels.application-started` (`ApplicationStartedEventListener`): resuelve `partyId → prospectId` via ACL a party-service, mapea `productTypeHint → ProductType`, crea `CreditApplication` idempotente. `ApplicationStartedEventListenerTest` ✅ (7 unit)

### Implementado (subdominio application-intake)

- [x] `origination.prospects` — prospect_id, prospect_type, status, CURP, RFC, nombre, dirección completa, canal, privacy_notice_accepted, circulo_consent_accepted, expires_at (sin productTypeIntent — Fase A)
- [x] `Prospect.java` — aggregate root con factory, máquina de estados (CAPTURED→SUBMITTED→CONVERTED|EXPIRED)
- [x] `ProspectService.java` — registro con validación unicidad CURP/teléfono, publicación Kafka (password en texto plano; identity hace el BCrypt)
- [x] `ProspectController.java` — `POST /api/v1/origination/prospects` → 201
- [x] `KafkaProspectPublisher.java` — publica `ProspectCreatedEvent` (address completo, prospectType, channelType, password raw; sin productTypeIntent)
- [x] `ProspectServiceTest` + `ProspectControllerTest` + `ProspectRegistrationAcceptanceTest`

### Implementado (subdominio underwriting — Fases B-H)

- [x] `origination.credit_applications` — application_id, prospect_id, prospect_type, product_type, status, requested_amount, requested_term, score_request_id, risk_level, decision, rejection_reason, approval_flow, decided_by, rejected_at, created_at, updated_at (migración 006)
- [x] `CreditApplication.java` — aggregate root; embebe `CreditOffer` (Fase E) y `Contract` (Fase F) como value objects; máquina de estados completa (ver §Estados)
- [x] `CreditOffer.java` (embedded, migración 007) — productCode, productVersion, productBehavior, amortizationType, offeredAmount, offeredLine, offeredTerm, nominalRate, moratoriumRate, openingFeeRate, cat (CAT BdM), validUntil, presentedAt, acceptedAt
- [x] `Contract.java` (embedded, migración 008) — contractNumber, signatureMethod, clabeAccount, documentRef, signedAt
- [x] `ApprovalFlow.java` — enum AUTOMATIC | MANUAL | COMMITTEE (asignado al recibir la decisión de scoring)
- [x] Campos de aprobación manual (migración 009-add-approval-fields.sql): decidedBy, rejectionReason, rejectedAt

### Liquibase Changelogs

- [x] `db/changelog/origination/001-create-origination-schema.sql`
- [x] `db/changelog/origination/002-create-prospects.sql`
- [x] `db/changelog/origination/003-create-event-publication.sql`
- [x] `db/changelog/origination/004-add-bureau-consent-and-documents.sql`
- [x] `db/changelog/origination/005-drop-product-type-intent.sql` (Fase A — re-orientación)
- [x] `db/changelog/origination/006-create-credit-applications.sql` (Fase B)
- [x] `db/changelog/origination/007-add-offer-columns.sql` (Fase E)
- [x] `db/changelog/origination/008-add-contract-columns.sql` (Fase F)
- [x] `db/changelog/origination/009-add-approval-fields.sql` (Fase H)

### Endpoints

- [x] `POST /api/v1/origination/applications` — selección de producto (Fase B); emite `ScoreRequested`
- [x] `GET /api/v1/origination/applications/{id}`
- [x] `GET /api/v1/origination/applications?prospectId=` — lista por prospecto
- [x] `POST /api/v1/origination/applications/{id}/offer` — presenta oferta (CAT BdM), requiere APPROVED
- [x] `POST /api/v1/origination/applications/{id}/offer/accept`
- [x] `POST /api/v1/origination/applications/{id}/offer/reject`
- [x] `POST /api/v1/origination/applications/{id}/contract/generate` — requiere OFFER_ACCEPTED
- [x] `POST /api/v1/origination/applications/{id}/contract/sign` — valida CLABE + firma; publica `CreditProductCreationRequested`
- [x] `POST /api/v1/origination/underwriting/applications/{id}/decision` — aprobación/rechazo manual o de comité (UNDERWRITER/COMMITTEE, JWT); `rejectionReason` obligatorio si `approved=false`
- [x] `GET /api/v1/origination/underwriting/applications?prospectId=` — pendientes de decisión humana

### Estados

```
DRAFT → PENDING_SCORING → SCORING → { UNDER_MANUAL_REVIEW | COMMITTEE_REVIEW } → APPROVED
→ OFFER_PRESENTED → OFFER_ACCEPTED | OFFER_REJECTED | OFFER_EXPIRED
→ PENDING_SIGNATURE → CONTRACT_SIGNED → DISBURSED (terminal)

Terminales de rechazo: REJECTED | FAILED
```

### Reglas de Negocio

- [x] Routing AUTOMATIC: asignado por scoring cuando score ≥ umbral configurado en la política (sin intervención humana)
- [x] Routing MANUAL: `UNDER_MANUAL_REVIEW` → decisión de underwriter vía `UnderwritingController`
- [x] Routing COMMITTEE: `COMMITTEE_REVIEW` → decisión de comité, mismo endpoint con rol distinto
- [x] UW-05/CONDUSEF: `rejectionReason` obligatorio cuando `approved=false` (400 si falta)
- [x] `OfferExpirationJob` — expira ofertas `OFFER_PRESENTED` cuya `validUntil` venció → `OFFER_EXPIRED`
- [x] Contrato inmutable post-firma (`CONTRACT_SIGNED` es terminal para edición; reestructuras van por D4★)

### Tests

- [x] `CreditApplicationServiceTest` / `OfferServiceTest` / `ContractServiceTest` — unit, máquina de estados y transiciones Fase B/E/F
- [x] `OfferExpirationJobTest` — expiración de ofertas vencidas
- [x] `UnderwritingControllerTest` / `ContractControllerTest` / `OfferControllerTest` — WebMvcTest
- [x] `UnderwritingApprovalFlowIT` / `ContractToPortfolioFlowIT` / `ScoringDecisionFlowIT` — Testcontainers, flujo completo hasta snapshot a credit-portfolio
- [x] `ApplicationStartedEventListenerTest` — 7 unit (canal → originación)
- [x] `ModularityTest` sigue pasando — **119 tests ✅** en total

### Notas

- Fase H (aprobación manual/comité) completó lo que en la snapshot del 2026-06-08 quedó marcado como "Pendiente: aprobaciones manuales/comité" — ver changelog 2026-06-26.
- `obligorPartyId` en `CreditProductCreationRequestedEvent` usa `prospectId` como placeholder (TODO: resolver `partyId` real vía party-service, igual que ya hace `ApplicationStartedEventListener`).

---

## MÓDULO 8 — D4: Credit Product (catálogo / fábrica)

**Estado:** ✅ Completo — 2026-06-10  
**Doc de referencia:** [`docs/dominios/04_credit_product_domain.md`](dominios/04_credit_product_domain.md) · [ADR-001](../README.md)  
**Schema DB:** `credit_product`  
**Comunicación:** No consume eventos (catálogo autorado vía REST, no event-driven). Publica `product-catalog.product-activated` / `product-catalog.product-retired`. Consumido por Origination (qué ofrecer) y credit-portfolio (snapshot inmutable al originar vía `CreditProductCreationRequested`, no llamada runtime).

> **No administra cuentas ni saldos.** Eso es credit-portfolio (MÓDULO 9). Aquí solo viven las **definiciones** de producto.

### Entidades

- [x] `credit_product.credit_product_definitions` — productDefinitionId, productCode, productVersion (int, business), productType, name, description, status (DRAFT/ACTIVE/RETIRED/DEPRECATED), targetAudience (B2C/B2B2C/B2B), currency, nominalRateAnnual, moratoriumRateAnnual, minTerm/maxTerm/defaultTerm, minAmount/maxAmount, defaultCreditLine/minCreditLine/maxCreditLine, amountStep, amortizationType, defaultPaymentFrequency, minApprovalScore, defaultApprovalFlow, openingFeeRate, prepaymentFeeRate, capabilities (jsonb vía `@JdbcTypeCode(SqlTypes.JSON)`), createdAt/activatedAt/retiredAt/deprecatedAt, `@Version` (optimistic lock, distinto de productVersion de negocio); partial unique index `WHERE status='ACTIVE'` por productCode
- [x] `credit_product.eligible_party_types` / `required_documents` / `channel_availabilities` / `allowed_payment_frequencies` — element collections (`@ElementCollection`, EAGER)
- [x] `credit_product.rate_cards` — bandas por tier (B2C), monto (B2B2C) o ambos (B2B): tierBand, minAmount/maxAmount, minTerm/maxTerm, nominalRate, moratoriumRate
- [x] `credit_product.eligibility_rules` — ruleType (MIN_SCORE, MAX_DTI, REQUIRED_PARTY_TYPE, …), operator + thresholdValue (numéricas) o stringValue (REQUIRED_PARTY_TYPE), errorCode

### Liquibase Changelogs

- [x] `db/changelog/creditproduct/001-create-schema.sql`
- [x] `db/changelog/creditproduct/002-create-credit-product-definitions.sql`
- [x] `db/changelog/creditproduct/003-create-eligible-party-types.sql`
- [x] `db/changelog/creditproduct/004-create-required-documents.sql`
- [x] `db/changelog/creditproduct/005-create-channel-availability.sql`
- [x] `db/changelog/creditproduct/006-seed-initial-products.sql`
- [x] `db/changelog/creditproduct/007-create-payment-frequencies-table.sql`
- [x] `db/changelog/creditproduct/008-seed-credit-card-micro-loan.sql`
- [x] `db/changelog/creditproduct/010-create-rate-cards.sql`
- [x] `db/changelog/creditproduct/011-create-eligibility-rules.sql`
- [x] `db/changelog/creditproduct/012-seed-b2b-products.sql`

### Endpoints

- [x] `GET /api/v1/credit-products` — lista ACTIVE, filtrable por `productType`/`targetAudience` (público)
- [x] `GET /api/v1/credit-products/{id}` — por UUID, cualquier status (incluye rate cards + eligibility rules)
- [x] `GET /api/v1/credit-products/code/{code}` — versión ACTIVE vigente de un productCode
- [x] `GET /api/v1/credit-products/code/{code}/versions` — historial de versiones desc
- [x] `GET /api/v1/credit-products/{id}/rate-cards` / `GET /api/v1/credit-products/{id}/eligibility-rules`
- [x] `POST /api/v1/credit-products` — crea/versiona (auth requerida)
- [x] `PUT /api/v1/credit-products/{id}/activate` / `deactivate` / `reactivate` / `deprecate`
- [x] `PUT /api/v1/credit-products/code/{code}/retire` — retira la versión ACTIVE vigente

### Reglas de Negocio

- [x] Un solo `ACTIVE` por `productCode` — partial unique index `WHERE status='ACTIVE'`; nueva versión → anterior `RETIRED`
- [x] Catálogo nunca borra — versiona (trazabilidad regulatoria); `productVersion` de negocio (int) separado del `@Version` de JPA
- [x] `capabilities` como dato (jsonb, no código) → gobierna el motor de credit-portfolio (amortización, disposición)
- [x] `amountStep` — fuerza montos múltiplos limpios en la oferta
- [x] 9 productos seed: B2C (7, rate cards por tier) + B2B2C (DL-DIST, rate cards por monto) + B2B (SME_LOAN, BUSINESS_REVOLVING_LINE, rate cards por monto+plazo)

### Tests

- [x] 30 unit + 23 IT (`CreditProductCatalogIT`) — versionado, una sola versión ACTIVE, rate cards/eligibility rules coherentes con productType, JWT (GET público / mutaciones autenticadas) — **62 tests ✅**
- [x] `ModularityTest` sigue pasando

### Notas

- Fix histórico: `LazyInitializationException` en element collections resuelto con fetch `EAGER`; doble bean `CreditProductProperties` y orden de migración de `payment_frequencies` corregidos — ver changelog 2026-06-08.

---

## MÓDULO 9 — D4★: Credit Portfolio (corazón / cuentas vivas)

**Estado:** ✅ Completo — 2026-07-08  
**Doc de referencia:** [`docs/dominios/04b_credit_portfolio_domain.md`](dominios/04b_credit_portfolio_domain.md) · [ADR-001](../README.md)  
**Schema DB:** `credit_portfolio`  
**Comunicación:** Consume `CreditProductCreationRequested` (snapshot de Origination), `OrdinaryInterestAccrued/MoratoriumInterestCharged/*FeeCharged/ChargeReversed/ChargeWaived` (Charges), `PaymentApplied/PaymentReturned` (Payments), `collections.write-off-executed`, `collections.agreement-executed` (nuevo 2026-07-10), `wallet.disposition-requested` (Wallet, nuevo 2026-07-08). Publica `CreditAccountActivated`, `BalanceUpdated`, `DispositionCompleted/Rejected`, `InstallmentDue`, `InstallmentUpcoming` (nuevo 2026-07-10), `DelinquencyStatusUpdated`. **No existen eventos propios `DelinquencyCleared`/`ProductSettled`/`ProductWrittenOff`/`ProductRestructured`/`AccountStatementGenerated`** — Collections deriva "cleared"/"settled" de `DelinquencyStatusUpdated(days=0)` y `BalanceUpdated(accountStatus=SETTLED)` respectivamente (ver Notas de Módulo 13); write-off y reestructura se resuelven vía los consumers de `collections.write-off-executed`/`collections.agreement-executed` sin un evento de salida dedicado.

> ★ **El corazón del core.** Única fuente de verdad de saldos. Agregado `CreditAccount` (antes `CreditProduct`). Motor de saldos en un solo servicio por consistencia fuerte.

### Entidades

- [x] `credit_portfolio.credit_accounts` — creditAccountId, contractId, contractNumber, productCode, productVersion (fijado al originar, pin/latest/degraded vía `ProductConfigResolver`), productType, productBehavior, obligorPartyId, status, nominalRate, moratoriumRate, openingFeeRate, assignedTerm, creditLimit, principalBalance, accruedInterestBalance, penaltyBalance, availableCredit, amortizationType, riskTier, clabeAccount, balanceVersion (auto-increment, optimistic concurrency de negocio)
- [x] `credit_portfolio.dispositions` — dispositionId, creditAccountId, dispositionType, amount, beneficiaryPartyId, status, externalRef, createdAt, completedAt
- [x] `credit_portfolio.installments` — installmentId, scheduleId, installmentNumber, dueDate, principalAmount, interestAmount, totalAmount, status (PENDING/PAID/OVERDUE)
- [x] `credit_portfolio.product_config_versions` — read-model versionado que proyecta `product-catalog.product-activated/retired` (pin/latest/degraded + reconciliación)
- [x] `credit_portfolio.balance_events` — auditoría inmutable de cada mutación de saldo + idempotencia por `source_event_id`
- [ ] `credit_portfolio.restructures` / `account_statements` — no implementado (restructuración y `AccountStatementGenerated` quedan pendientes; ver Notas)

### Liquibase Changelogs

- [x] `db/changelog/creditportfolio/001-create-schema.sql`
- [x] `db/changelog/creditportfolio/002-create-credit-accounts.sql`
- [x] `db/changelog/creditportfolio/003-create-dispositions.sql`
- [x] `db/changelog/creditportfolio/004-create-installments.sql`
- [x] `db/changelog/creditportfolio/005-create-event-publication.sql`
- [x] `db/changelog/creditportfolio/006-create-product-config-versions.sql`
- [x] `db/changelog/creditportfolio/007-create-balance-events.sql`
- [x] `db/changelog/creditportfolio/008-add-balance-version.sql`
- [x] `db/changelog/creditportfolio/009-add-delinquency-indexes.sql`
- [x] `db/changelog/creditportfolio/010-add-disposition-source-event-id.sql` — `dispositions.source_event_id` (traza hacia el `dispositionRequestId` de Wallet)

### Endpoints

- [x] `GET /api/v1/portfolio/accounts/{creditAccountId}`
- [x] `GET /api/v1/portfolio/accounts?partyId=` — lista por obligor (uso BFF)
- [x] `GET /api/v1/portfolio/accounts/{creditAccountId}/dispositions`
- [x] `GET /api/v1/portfolio/accounts/{creditAccountId}/amortization-schedule`
- [ ] `GET /api/v1/portfolio/accounts/{id}/balance` (redundante con GET base, no separado) / `GET .../statement` — no implementado (ver Notas)

### Modelo de Saldos

```
totalDebt = principalBalance + accruedInterestBalance + penaltyBalance
availableCredit = creditLimit − principalBalance − pendingDispositions  [solo revolving]

Jerarquía de pago (configurable T5):
  1. penaltyBalance (mora + fees)  →  2. accruedInterestBalance  →  3. principalBalance
```

### Estados

```
PENDING_ACTIVATION → ACTIVE ↔ RESTRUCTURED → SETTLED | WRITTEN_OFF | CLOSED
SUSPENDED (bloquea disposiciones, mantiene estado)
```

### Reglas de Negocio

- [x] Motor único por `productType` — comportamiento gobernado por `capabilities` del snapshot del catálogo (`AmortizationEngine` FRENCH/GERMAN/BULLET × WEEKLY/BIWEEKLY/MONTHLY)
- [x] Snapshot inmutable de términos al crear vía `CreditProductCreationRequested` (no llama al catálogo en runtime); `productVersion` es versión-referencia, no copia congelada
- [x] Charges/Payments/Collections emiten eventos; credit-portfolio aplica vía `BalanceReconciliationService` (jerarquía penalty→interest→principal), idempotente por `source_event_id`
- [x] **(2026-07-08)** PL-01 corregido: activación de cuentas revolventes (`hasCreditLimit`) abre en `principalBalance=0`/`availableCredit=creditLimit` — antes disbursaba el límite completo al originar, dejando `availableCredit=0` desde el día uno y volviendo inútil cualquier disposición posterior. No-revolventes (préstamos a plazo) siguen disbursando completo al originar — correcto para CP-05 (disposición única)
- [x] **(2026-07-08)** `ProcessDispositionUseCase` — consume `wallet.disposition-requested`, idempotente por `sourceEventId` (`BalanceEventRepository.existsBySourceEventId`). SELF_USE: `applyDisposition` (principal+=amount, availableCredit-=amount) **sin SPEI** — el dinero se queda en la plataforma para que Wallet lo acredite. THIRD_PARTY_CREDIT/PAYROLL: mismo efecto de saldo + SPEI real (stub) a `payeeAccount`. Guardas: SUSPENDED/terminal bloquea, `amount > availableCredit` rechaza, CP-05 (segunda disposición en no-revolvente) rechaza — rechazos publican `disposition-rejected` en vez de perder el mensaje. Publica `disposition-completed` (nuevo) además del `balance-updated` ya existente
- [x] `daysDelinquent` por job nocturno (`DelinquencyCalculationJob`), no reactivo

### Jobs

- [x] `DelinquencyCalculationJob` (23:59) — recalcula `daysDelinquent` en todas las cuentas ACTIVE; publica `credit-portfolio.delinquency-status-updated`; aislamiento `REQUIRES_NEW` por cuenta vía `DelinquencyAccountProcessor`
- [x] `InstallmentDueJob` (00:01) — encuentra installments PENDING con `dueDate = today`; publica `credit-portfolio.installment-due` por cuota
- [x] `@EnableScheduling` en `CreditPortfolioApplication`
- [x] Migración `009-add-delinquency-indexes.sql` — índices en `credit_accounts(status)` + `installments(due_date, status)`
- [x] **(2026-07-10, prerequisito de Módulo 13 Collections)** `UpcomingInstallmentJob` (00:00, antes de `InstallmentDueJob`) — mismo patrón que `InstallmentDueJob` pero con lead time T5 (`reminder-lead-days`, default 3d) antes de `dueDate`; publica `credit-portfolio.installment-upcoming` para cobranza temprana
- [x] **(2026-07-10, prerequisito de Módulo 13 Collections)** `CollectionAgreementExecutedListener` + `ProcessAgreementExecutedUseCase` — consume `collections.agreement-executed`, idempotente por `sourceEventId`. QUITA_PARCIAL: `CreditAccount.applyForgiveness` (jerarquía penalty→interest→principal, sin efectivo) + `BalanceUpdated`. RESTRUCTURE: `CreditAccount.applyRestructure` solo actualiza `nominalRate`/`assignedTerm` — **sin regenerar calendario de amortización ni sincronizar la tasa cacheada en Charges** (limitación documentada, mismo criterio que la decisión GL-08 de T4: no existe motor de NPV/recálculo de flujos en el sistema)

### Tests

- [x] Unit tests — balance model, jerarquía pago, state machine, cada productType (`CreditAccountBalanceTest`)
- [x] Unit tests — BalanceReconciliationService enruta eventos entrantes (`BalanceReconciliationServiceTest`)
- [x] `DelinquencyCalculationJobTest` — 5 casos: sin cuentas, sin mora, con mora, múltiples cuotas vencidas, aislamiento de fallo por cuenta
- [x] `InstallmentDueJobTest` — 4 casos: sin cuotas, cuota individual, múltiples cuotas, tolerancia a fallo de publisher
- [x] `CreditAccountServiceTest` — activación revolvente en cero + 6 casos de `ProcessDispositionUseCase` (SELF_USE sin SPEI, THIRD_PARTY_CREDIT con SPEI, SUSPENDED bloquea, `amount>availableCredit` bloquea, CP-05 bloquea, idempotencia por `sourceEventId`) + 3 casos nuevos (2026-07-10) de `ProcessAgreementExecutedUseCase` (QUITA_PARCIAL aplica forgiveness + publica BalanceUpdated, RESTRUCTURE actualiza tasa/plazo sin publicar BalanceUpdated, idempotencia por `sourceEventId`) — **15 casos en total**
- [x] `ModularityTest` sigue pasando — **82 tests ✅** en total (79 unit + 3 IT que requieren Docker, no verificadas en este entorno; 76→79 unit tests con los 3 casos nuevos de `ProcessAgreementExecutedUseCase`)

### Notas

- **Gap cerrado 2026-07-08:** ver Reglas de Negocio arriba — `ProcessDispositionUseCase` + fix de activación revolvente resuelven el gap que existía entre Wallet (`disposition-requested`) y credit-portfolio.
- **Prerequisitos de Collections cerrados 2026-07-10:** `UpcomingInstallmentJob` y `CollectionAgreementExecutedListener`/`ProcessAgreementExecutedUseCase` — ver Reglas de Negocio y Jobs arriba. Ambos habilitaron la implementación completa de Módulo 13.
- `restructures` y `account_statements` (mencionados en `04b_credit_portfolio_domain.md`) no están implementados como entidades propias — la reestructuración vive ahora como mutación in-place de `CreditAccount` (ver `ProcessAgreementExecutedUseCase` arriba, sin historial propio) y `AccountStatementGenerated` queda como trabajo futuro.
- `NoopSpeiDispatchAdapter` — stub que confirma disbursement inmediato (usado tanto en originación como en dispositions THIRD_PARTY_CREDIT); falta integración real SPEI/BANXICO.

---

## MÓDULO 10 — D5: Charges

**Estado:** ✅ Completo — 2026-07-06  
**Doc de referencia:** `docs/dominios/05_charges_domain.md`  
**Schema DB:** `charges`  
**Comunicación:** Consume `credit-portfolio.credit-account-activated`, `credit-portfolio.balance-updated`. Publica `charges.charge-applied`, `charges.charge-reversed`.

### Entidades

- [x] `charges.accrual_schedules` — scheduleId, creditAccountId(UNIQUE), obligorPartyId, productType, productBehavior, status (ACTIVE/SUSPENDED/CLOSED), nominalRate, moratoriumRate, moratoriumActive, gracePeriodDays, lastAccrualDate, principalBalance, approvedAmount
- [x] `charges.charge_records` — chargeId, creditAccountId, obligorPartyId, chargeType, status (APPLIED/REVERSED/WAIVED), basis, rate, days, amount, taxAmount, totalAmount, accrualDate, linkedChargeId(FK→charge_records)

### Liquibase Changelogs

- [x] `db/changelog/charges/001-create-schema.sql`
- [x] `db/changelog/charges/002-create-accrual-schedules.sql`
- [x] `db/changelog/charges/003-create-charge-records.sql`
- [x] `db/changelog/charges/004-create-event-publication.sql`

### Endpoints

- [x] `GET /api/v1/charges/accounts/{creditAccountId}` — lista charge records
- [x] `GET /api/v1/charges/accounts/{creditAccountId}/schedule` — estado del accrual schedule
- [x] `POST /api/v1/charges/{chargeId}/reverse` — reversión técnica (require auth)
- [x] `POST /api/v1/charges/{chargeId}/waive` — condonación (require auth)

### Reglas de Negocio

- [x] Interés ordinario: `principal × nominalRate / 360` diario (base 360)
- [x] Interés moratorio: activado explícitamente vía `activateMoratorium()`; `moratoriumRate` default = `nominalRate × 1.5`
- [x] IVA 16%: `ChargeRecord` separado con `linkedChargeId`, chargeType=IVA → penaltyBalance en credit-portfolio
- [x] `ORDINARY_INTEREST` → `accruedInterestBalance`; todos los demás (incluido IVA) → `penaltyBalance`
- [x] OPENING_FEE: disparado al activar cuenta; idempotente (CR-05 guard)
- [x] Reversal cascada: reverse/waive aplica también al linked IVA
- [x] `moratoriumRateMultiplier=1.5` como fallback cuando `moratoriumRate` es 0

### Jobs

- [x] `DailyAccrualJob` (23:00) — interés ordinario con aislamiento de tx por schedule
- [x] `MoratoriumAccrualJob` (23:30) — interés moratorio cuando aplica

### Tests

- [x] `AccrualScheduleServiceTest` — create, idempotency, updateBalance, openingFee, settled close
- [x] `InterestAccrualServiceTest` — cálculo, skip zero balance, mora, skip mora inactiva
- [x] `ChargesControllerTest` — GET 200, GET schedule 200/404, POST reverse/waive 200, no token 401
- [x] `ChargesAcceptanceTest` — @EmbeddedKafka + @Testcontainers: Kafka listener → schedule + openingFee, accrual IT
- [x] `ChargeReversalServiceTest` — reverse/waive status, cascada IVA, waived flag distinction (añadido 2026-07-05/06, no estaba en el changelog original del 2026-06-26)
- [x] `ModularityTest` sigue pasando — **26 tests ✅** en total

### Notas
<!-- -->

---

## MÓDULO 11 — D6: Payments

**Estado:** ✅ Completo — 2026-07-05  
**Doc de referencia:** `docs/dominios/06_payments_domain.md`  
**Schema DB:** `payments`  
**Comunicación:** Consume `credit-portfolio.credit-account-activated` (init snapshot), `credit-portfolio.balance-updated` (actualiza snapshot), `credit-portfolio.payment-rejected` (marca REJECTED). Publica `payments.payment-applied`, `payments.payment-returned`.

### Patrón de consistencia: Doble validación

```
POST /api/v1/payments
  → PRE-CHECK: amount <= snapshot.totalDebt (eventual, fast, ~0ms)
  → si OK: PaymentOrder=PENDING + publica payment-applied{snapshotVersion}
  → credit-portfolio: verifica amount <= totalDebt (autoritativo, transaccional)
  → si OK: aplica saldo + publica balance-updated → snapshot se actualiza → PaymentOrder=CONFIRMED
  → si KO: publica payment-rejected → PaymentOrder=REJECTED
```

### Entidades

- [x] `payments.payment_orders` — paymentOrderId, creditAccountId, amount, **requestedAmount**, paymentMethod, externalRef(UNIQUE), status (PENDING/CONFIRMED/REJECTED/REVERSED), snapshotVersion, rejectionReason, reversalReason, **overpaymentStrategy**
- [x] `payments.account_balance_snapshots` — creditAccountId (PK), principalBalance, accruedInterestBalance, penaltyBalance, availableCredit, totalDebt, creditLimit, balanceVersion, accountStatus, snapshotAt

### Liquibase Changelogs

- [x] `db/changelog/payments/001-create-schema.sql`
- [x] `db/changelog/payments/002-create-payment-orders.sql`
- [x] `db/changelog/payments/003-create-account-balance-snapshots.sql`
- [x] `db/changelog/payments/004-create-event-publication.sql`
- [x] `db/changelog/payments/005-add-overpayment-columns.sql` — `requested_amount NUMERIC(19,2)` + `overpayment_strategy VARCHAR(30)`

### Endpoints

- [x] `POST /api/v1/payments` — submit payment (require auth, pre-validates balance)
- [x] `GET /api/v1/payments/{paymentOrderId}` — get single order
- [x] `GET /api/v1/payments/accounts/{creditAccountId}` — list orders by account
- [x] `GET /api/v1/payments/accounts/{creditAccountId}/balance` — query balance snapshot
- [x] `POST /api/v1/payments/{paymentOrderId}/reverse` — reverse CONFIRMED payment

### Reglas de Negocio

- [x] Idempotencia por `externalRef` (SPEI/CoDi clave_rastreo o UUID cliente) — PY-01
- [x] amount > 0 — PY-02
- [x] PRE-CHECK: amount ≤ snapshot.totalDebt (pre-validation, eventual)
- [x] POST-CHECK: en credit-portfolio, amount ≤ account.getTotalDebt() (autoritativo, publica payment-rejected si no)
- [x] Reversión: solo PaymentOrder CONFIRMED → REVERSED; publica payment-returned → credit-portfolio restaura saldo
- [x] Métodos: SPEI, CoDi, DOMICILIACION, VENTANILLA, TARJETA, INTERNAL_TRANSFER — enum `PaymentMethod`
- [x] **PY-05** Validación: cuenta WRITTEN_OFF o CLOSED → `InvalidPaymentStateException` antes de cualquier procesamiento
- [x] **PY-07** VENTANILLA no reversible: `PaymentMethod.allowsReversal()` → `false` para VENTANILLA
- [x] **PY-08** Ventana de devolución: `reversalWindowHours` configurable (default 72h) en `PaymentsProperties`; reverso fuera de ventana → excepción
- [x] **PY-06** Excedente (amount > totalDebt): `OverpaymentStrategy` configurable
  - `RETURN_TO_PAYER` (default): aplica solo `totalDebt`, publica `payment-returned` por el exceso inmediatamente
  - `APPLY_NEXT_INSTALLMENT`: reenvía monto completo a credit-portfolio; strategy queda registrada en `PaymentOrder`

### Tests

- [x] `PaymentServiceTest` — 13 casos: PY-T01 submit válido, PY-T02 overpayment (RETURN_TO_PAYER por defecto), PY-T03 idempotencia, PY-T04 sin snapshot, PY-T05 reject por eventId, PY-T06 reverse CONFIRMED, PY-T07 WRITTEN_OFF, PY-T08 CLOSED, PY-T09 RETURN_TO_PAYER explícito, PY-T10 APPLY_NEXT_INSTALLMENT, PY-T11 ventana expirada, PY-T12 VENTANILLA, PY-T13 exceso correcto
- [x] `BalanceSnapshotServiceTest` — 5 casos: initSnapshot, idempotencia, upsert, canAcceptPayment (overpayments permitidos/positivos, rechaza ≤0), isAccountActive (ACTIVE/SUSPENDED=true, WRITTEN_OFF/CLOSED=false)
- [x] `PaymentsControllerTest` — 5 casos WebMvcTest: 401 sin token, submit 200, balance 200, balance 404, reverse 200
- [x] **Total: 23 tests ✅** (`./gradlew :payments:test` → BUILD SUCCESSFUL)
- [n/a] `ModularityTest` — no aplica: `payments` es un servicio aislado (no un módulo Modulith interno del monolito)

### Notas
<!-- -->

---

## MÓDULO 12 — D7: Wallet

**Estado:** ✅ Completo — 2026-07-08  
**Doc de referencia:** `docs/dominios/07_wallet_domain.md`  
**Schema DB:** `wallet`  
**Comunicación:** Consume `credit-portfolio.credit-account-activated`, `credit-portfolio.balance-updated`, `credit-portfolio.installment-due`, `credit-portfolio.disposition-completed` (nuevo). Publica `wallet.payment-instruction-created`, `wallet.disposition-requested`, `wallet.withdrawal-completed` (nuevo), `wallet.snapshot-updated`. **Sigue sin ser fuente de verdad de la deuda** — pero desde 2026-07-08 sí mantiene su propio `walletBalance` (proyección acumulada, no espejo — ver doc de dominio §Decisiones de diseño).

### Entidades

- [x] `wallet.wallet_views` — walletId, creditAccountId, obligorPartyId, productType, balances (principal/interest/penalty/totalDebt), availableCredit, **walletBalance** (nuevo), minimumPayment, paymentDueDate, nextInstallmentAmount, status, lastUpdatedAt, registeredClabe, balanceVersion
- [x] `wallet.payment_instructions` — instructionId, creditAccountId, obligorPartyId, paymentMethod, amount, paymentType, scheduledAt, status (PENDING/SENT/CANCELLED/EXPIRED), expiresAt, paymentRef
- [x] `wallet.wallet_withdrawals` (nuevo) — withdrawalId, creditAccountId, obligorPartyId, method (SPEI/CODI), amount, payeeAccount, status (PENDING/SENT/FAILED), externalRef, createdAt, updatedAt — debita `walletBalance` sincrónicamente al crear (gasto/transferencia de lo ya dispuesto, distinto de `PaymentInstruction` que paga la deuda)

### Liquibase Changelogs

- [x] `db/changelog/wallet/001-create-schema.sql`
- [x] `db/changelog/wallet/002-create-wallet-views.sql`
- [x] `db/changelog/wallet/003-create-payment-instructions.sql`
- [x] `db/changelog/wallet/004-create-event-publication.sql`
- [x] `db/changelog/wallet/005-add-wallet-balance.sql` (nuevo)
- [x] `db/changelog/wallet/006-create-wallet-withdrawals.sql` (nuevo)

### Endpoints

- [x] `GET /api/v1/wallet/{creditAccountId}` — WalletView con todos los saldos, incl. `walletBalance`
- [x] `GET /api/v1/wallet?partyId=` (nuevo) — todos los instrumentos de crédito del party ("wallet general")
- [x] `GET /api/v1/wallet/summary?partyId=` (nuevo) — rollup: totalAvailableCredit/totalWalletBalance/totalDebt + lista de instrumentos
- [x] `POST /api/v1/wallet/{creditAccountId}/payment-instructions` — crea PaymentInstruction (SPEI/CoDi/DOMICILIACION/etc.)
- [x] `POST /api/v1/wallet/{creditAccountId}/dispositions` — emite DispositionRequested con validación fail-fast (ahora con `dispositionRequestId` para idempotencia en credit-portfolio)
- [x] `POST /api/v1/wallet/{creditAccountId}/withdrawals` (nuevo) — retira de `walletBalance` vía SPEI/CoDi (stub), 422 si excede saldo

### Reglas de Negocio

- [x] Proyección de deuda read-only — WalletView sincronizada por eventos de credit-portfolio (WV-01)
- [x] Fail-fast: `amount ≤ availableCredit` antes de DispositionRequested (DO-02)
- [x] SUSPENDED → bloquea disposición en Wallet (DO-05)
- [x] PI-06: una sola instrucción PENDING por (creditAccountId, paymentMethod) — partial unique index en DB
- [x] PI-03: DOMICILIACION requiere registeredCLABE
- [x] `WalletSnapshotUpdated` consolida múltiples eventos → un solo refresh de UI
- [x] **(nuevo)** DO-06/DO-07: `DispositionCompleted` credita `walletBalance` solo si `dispositionType=SELF_USE` — THIRD_PARTY_CREDIT/PAYROLL no tocan el saldo gastable (el dinero salió a un tercero)
- [x] **(nuevo)** WD-01/WD-03: retiro debita `walletBalance` sincrónicamente antes de despachar — `amount > walletBalance` rechaza sin crear registro huérfano (422, `InsufficientWalletBalanceException`)
- [x] **(nuevo)** Agregación por party reutiliza `productType` como discriminador de instrumento — no se introdujo un tipo de instrumento nuevo ni un dominio de captación/débito real (ver doc de dominio §Contexto)

### Tests

- [x] `WalletProjectionServiceTest` — 10 unit tests (activación, balance sync, installmentDue, duplicado, **onDispositionCompleted × 3, listByPartyId**)
- [x] `PaymentInstructionServiceTest` — 5 unit tests (PARTIAL, MINIMUM, duplicado, DOMICILIACION sin CLABE, wallet no encontrado)
- [x] `WalletWithdrawalServiceTest` (nuevo) — 3 unit tests (éxito debita + dispatch, fondos insuficientes no muta ni despacha, wallet no encontrado)
- [x] `WalletControllerTest` — 20 @WebMvcTest (GET 200/401/404, POST payment-instruction 201/401/409/422, disposition 202/401, **withdraw 201/401/422, list/summary por party**)
- [x] `WalletAcceptanceTest` — 6 Testcontainers acceptance tests (AC-1..AC-6) — requieren Docker, no verificados en este pase (sandbox sin Docker)
- [x] `ModularityTest` sigue pasando — **38 tests ✅** en total (26 + 12 nuevos)

### Notas

- `WalletAcceptanceTest` no se corrió en este cambio por falta de Docker en el entorno de desarrollo — verificar manualmente con `docker-compose up` antes de dar el flujo end-to-end por bueno (ver §Verificación de la sesión 2026-07-08 en el Changelog).
- Autorización de tarjeta física/virtual (POS/e-commerce) queda fuera de alcance — requiere integración con un procesador de tarjetas real, no simulado (ver doc de dominio).

---

## MÓDULO 13 — D8: Collections

**Estado:** ✅ Completo — 2026-07-10  
**Doc de referencia:** `docs/dominios/08_collections_domain.md`  
**Schema DB:** `collections`  
**Puerto:** `8093` (siguiente libre tras wallet 8092)  
**Comunicación:** Consume `credit-portfolio.credit-account-activated` (siembra el snapshot local con `productType`), `credit-portfolio.balance-updated` (siembra/actualiza el split principal/interés/penalidad), `credit-portfolio.delinquency-status-updated` (abre/escala/cierra el caso — `days=0` es la señal de "cleared", no existe un evento separado), `credit-portfolio.installment-upcoming` (cobranza temprana), `payments.payment-applied` (promesas cumplidas + detección de recuperación post-quebranto). **Nunca modifica saldos** — solo decide estrategia de cobranza y solicita a credit-portfolio que aplique quitas/reestructuras. Publica `collections.pre-due-reminder-triggered`, `collections.case-created/escalated`, `collections.contact-attempt-registered`, `collections.payment-promise-made/broken`, `collections.agreement-proposed/executed`, `collections.write-off-requested/executed`, `collections.bureau-report-submitted`, `collections.recovery-payment-applied`.

> **Terminología:** "write-off"/"quebranto" = quita total (unilateral, sin acuerdo del deudor). "Reestructura" y "quita parcial" son acuerdos bilaterales — comparten el agregado `CollectionAgreement`. Ver `08_collections_domain.md` para el razonamiento completo.

### Entidades

- [x] `collections.collection_cases` — caseId, creditAccountId, obligorPartyId, productType, status (OPEN→MANAGED→LEGAL→WRITTEN_OFF|CLOSED), currentBucket (B1_30|B31_60|B61_90|**B91_120|B121_180|B181_PLUS**), daysDelinquent, totalDebt, assignedAgentId, externalAgencyId, strategy, openedAt, closedAt — CC-01: partial unique index, una sola OPEN/MANAGED/LEGAL por creditAccountId
- [x] `collections.payment_promises` — promiseId, caseId, amount, promisedDate, status (ACTIVE→KEPT|BROKEN|EXPIRED), recordedBy, linkedPaymentId — PP-01: partial unique index, una sola ACTIVE por caseId
- [x] `collections.contact_attempts` — attemptId, caseId, channel, result (ANSWERED/NO_ANSWER/WRONG_NUMBER/PROMISE_MADE), agentId, attemptedAt
- [x] `collections.write_off_records` — writeOffId, caseId, creditAccountId, obligorPartyId, principalWrittenOff, interestWrittenOff, penaltyWrittenOff, totalWrittenOff, authorizedBy, authorizationRef, writeOffDate, reason, bureauReported — WO-03: unique en creditAccountId
- [x] `collections.collection_agreements` — agreementId, caseId, creditAccountId, obligorPartyId, type (RESTRUCTURE|QUITA_PARCIAL), status (PROPOSED→ACCEPTED→EXECUTED|REJECTED|EXPIRED), originalDebt, forgivenAmount, newTerms (**JSONB nativo vía `@JdbcTypeCode(SqlTypes.JSON)`**, record `RestructureTerms`), authorizedBy, authorizationRef, proposedAt, respondedAt, executedAt, bureauReported — AG-01: partial unique index, un solo PROPOSED/ACCEPTED por caseId
- [x] `collections.bureau_reports` — reportId, creditAccountId, obligorPartyId, eventType (WRITE_OFF|QUITA_PARCIAL), sourceRecordId, amountReported, status (PENDING→SUBMITTED|FAILED), bureauReference, submittedAt — BR-01: unique en sourceRecordId
- [x] `collections.account_balance_snapshots` (**nuevo, no estaba en el spec original**) — read-model local (creditAccountId, obligorPartyId, productType, principal/interest/penalty/totalDebt, balanceVersion) alimentado por `credit-account-activated` + `balance-updated`. Necesario porque `DelinquencyStatusUpdated` no trae `productType`/`totalDebt` ni el split para el write-off — mismo patrón ya usado por charges-service/payments/wallet

### Liquibase Changelogs

- [x] `db/changelog/collections/001-create-schema.sql` … `008-create-event-publication.sql` (Spring Modulith)
- [x] `db/changelog/collections/009-create-account-balance-snapshots.sql` (**nuevo**)

### Endpoints

- [x] `GET /api/v1/collections/cases/{caseId}`
- [x] `GET /api/v1/collections/accounts/{creditAccountId}/case`
- [x] `POST /api/v1/collections/cases/{caseId}/payment-promises` (201)
- [x] `POST /api/v1/collections/cases/{caseId}/contact-attempts` (201, **nuevo, no estaba en el spec original** — CT-01..CT-04 no tenían endpoint de creación)
- [x] `POST /api/v1/collections/cases/{caseId}/agreements` (201) — propone RESTRUCTURE o QUITA_PARCIAL
- [x] `PUT /api/v1/collections/agreements/{id}/accept` — el deudor acepta
- [x] `PUT /api/v1/collections/agreements/{id}/reject` (**nuevo**, no estaba en el spec original)
- [x] `PUT /api/v1/collections/agreements/{id}/authorize` — aprobación interna → EXECUTED (AG-02)
- [x] `POST /api/v1/collections/cases/{caseId}/request-write-off` (202) — intent, WO-01
- [x] `POST /api/v1/collections/cases/{caseId}/write-offs` (201) — la aprobación/ejecución, WO-02
- [x] `GET /api/v1/collections/bureau-reports` — siempre PENDING+FAILED, sin filtro de status (el único caso de uso del panel operativo, CC-07)

### Buckets de Mora (extendido — Stage 3 subdividido para la tabla de reservas de D9 Risk)

| Bucket | Días | Acción | IFRS-9 (D9, referencia — no implementado aún) |
|---|---|---|---|
| CURRENT (pre-vencimiento) | — | `PreDueReminderTriggered` T-3d (T5) — **sin CollectionCase** | STAGE_1 |
| B1_30 | 1–30d | Notificación automática | STAGE_1 |
| B31_60 | 31–60d | Agente asignado, oferta `CollectionAgreement(RESTRUCTURE)` | STAGE_2 |
| B61_90 | 61–90d | Cobranza intensiva, aviso legal | STAGE_2 |
| B91_120 | 91–120d | Pre-quebranto, agencia externa | STAGE_3 |
| B121_180 | 121–180d | Agencia externa, oferta `CollectionAgreement(QUITA_PARCIAL)` | STAGE_3 |
| B181_PLUS | 181+d | Candidato a `WriteOffRequested` (quita total) | STAGE_3 |

### Reglas de Negocio

- [x] **Cobranza temprana:** `InstallmentUpcoming` (T-lead_days, T5=`reminder-lead-days`, default 3d) → `PreDueReminderTriggered` — no abre caso, sin segmentación de riesgo en v1; si la cuenta no está en el snapshot local se loguea y se omite (no puede fallar por falta de `obligorPartyId`, igual que `InstallmentDueListener` de Wallet)
- [x] CM-01/02/03: `DelinquencyStatusUpdated` abre (bucket≥B1_30), escala (OPEN→MANAGED al salir de B1_30), o cierra (days=0) el caso — **no existe un evento `DelinquencyCleared` separado**, se deriva del mismo evento con days=0 (desviación real del spec original, confirmada al revisar `CreditPortfolioEventPublisher` — documentado aquí)
- [x] CM-05: `BalanceUpdated.accountStatus=SETTLED` → CLOSED — tampoco existe `ProductSettled` como evento propio, misma derivación
- [x] CC-06: escalamiento a agencia externa solo desde B91_120+ (`DelinquencyBucket.isEscalatable()`)
- [x] `WriteOffRequested` (intent, **sin persistencia**) → aprobación → `WriteOffExecuted` (authorization, crea `WriteOffRecord` inmutable) → credit-portfolio consume vía el `onWriteOffExecuted` ya existente → saldos a 0
- [x] **`CollectionAgreement`:** PROPOSED→ACCEPTED (deudor) →EXECUTED (autorización interna, mismo nivel que write-off, AG-02); RESTRUCTURE aplica nuevos términos en credit-portfolio (`CreditAccount.applyRestructure`, solo tasa/plazo — sin regenerar calendario, limitación documentada); QUITA_PARCIAL reduce saldo (`CreditAccount.applyForgiveness`, jerarquía penalty→interest→principal, no a cero) y genera reporte a buró
- [x] AG-01/AG-07/AG-08/AG-09: un solo agreement activo por caso; solo se propone sobre casos MANAGED/LEGAL; límites T5 `max-forgiveness-pct` (default 0.30 del originalDebt, `ForgivenessLimitExceededException` si se excede) y `max-term-extension-months` (default 12)
- [x] AG-05: `AgreementExpirationJob` expira PROPOSED sin respuesta tras `agreement-response-days` (default 5d)
- [x] **Reporte a buró:** todo `WriteOffExecuted` y `CollectionAgreementExecuted(QUITA_PARCIAL)` → `BureauReport(PENDING)`, idempotente por `sourceRecordId` (BR-01) → `BureauReportingJob` nocturno vía `BureauReportingAdapter` (stub `NoopBureauReportingAdapter`) → reintento indefinido, FAILED se reintenta la noche siguiente (BR-05, obligación regulatoria, no best-effort)
- [x] `RecoveryPaymentApplied`: `PaymentApplied` sin caso activo pero con `WriteOffRecord` existente para la cuenta → evento de recuperación (no reabre el caso, RR-01)
- [x] PP-01/PP-04/PP-05: una sola promesa ACTIVE por caso; `PromiseBrokenCheckJob` nocturno marca BROKEN las que pasaron `promisedDate` sin cumplirse; un pago que cubre el monto prometido marca KEPT
- [x] CT-02/CT-03/CC-04 (CONDUSEF): máx 3 intentos contacto/día, horario 08:00–20:00, rechazado en casos terminales (CLOSED/WRITTEN_OFF)
- [x] Límites configurables T5 (no discrecionales, `CollectionsProperties`): `write-off-threshold-days` (181), `max-contact-attempts-per-day` (3), `contact-allowed-hours-start/end` (8/20), `reminder-lead-days` (3), `agreement-response-days` (5), `max-forgiveness-pct` (0.30), `max-term-extension-months` (12)

### Jobs

- [x] `PromiseBrokenCheckJob` (`0 0 8 * * *`) — PP-04
- [x] `AgreementExpirationJob` (`0 15 8 * * *`) — AG-05
- [x] `WriteOffCandidatesJob` (`0 0 6 * * MON`) — recorre casos MANAGED/LEGAL con `daysDelinquent ≥ write-off-threshold-days` y solicita write-off por cada uno (try/catch por candidato, un fallo no bloquea el resto)
- [x] `BureauReportingJob` (`0 0 2 * * *`) — BR-04/BR-05

### Tests

- [x] `CaseManagementServiceTest` — 7 casos (abre caso nuevo, no abre si bucket=CURRENT, escala bucket existente, cierra al limpiar mora, sync de snapshot por `balance-updated`, caso no encontrado, **thread-safety: 16 cuentas distintas concurrentes sin excepciones ni cross-contamination**)
- [x] `PromiseServiceTest` — 8 casos (creación exitosa, caso terminal rechaza, PP-01 rechaza duplicado, pago cubre monto→KEPT, pago parcial no afecta, recuperación post-quebranto sin caso activo, no-op sin caso ni quebranto, job marca BROKEN)
- [x] `ContactServiceTest` — 4 casos (éxito dentro de ventana, caso terminal rechaza CC-04, fuera de horario rechaza CT-02, límite diario alcanzado rechaza CT-03 — ventana horaria parametrizada vía `CollectionsProperties` para no depender de la hora real de ejecución)
- [x] `AgreementServiceTest` — 9 casos (propone RESTRUCTURE, plazo excede máximo rechaza, quita excede límite rechaza `ForgivenessLimitExceededException`, caso no MANAGED/LEGAL rechaza AG-07, AG-01 rechaza duplicado, autoriza QUITA_PARCIAL genera reporte a buró, autoriza RESTRUCTURE no genera reporte, accept/reject delegan al dominio)
- [x] `WriteOffServiceTest` — 5 casos (request publica sin persistir, cuenta ya quebrada rechaza WO-03, bajo el umbral rechaza, approve crea registro inmutable + marca caso + reporta a buró con el split real del snapshot, approve rechaza cuenta ya quebrada)
- [x] `BureauReportingServiceTest` — 5 casos (crea reporte nuevo, idempotente si ya existe BR-01, envío exitoso marca SUBMITTED y publica evento, fallo marca FAILED y continúa con el siguiente reporte sin abortar el lote, **thread-safety: race de 4 hilos sobre el mismo sourceRecordId no genera pánico a nivel servicio** — mismo patrón que `BalanceReconciliationServiceTest` en credit-portfolio)
- [x] `EarlyCollectionsServiceTest` — 2 casos (publica recordatorio con cuenta conocida, omite silenciosamente con cuenta desconocida)
- [x] **40 tests unitarios ✅** — `ModularityTest` no aplicable aún (módulo de un solo paquete `application`/`domain`/`infrastructure`, sin submódulos Modulith adicionales que verificar)
- [x] `CollectionsAcceptanceTest` (9, Testcontainers Postgres + EmbeddedKafka) — GET caso, GET por cuenta, POST promesa, POST contacto, ciclo agreement (propose→accept→authorize), ciclo write-off (request 202 → approve 201), GET bureau-reports, 401, 404
- [x] `CollectionsFlowIT` (2) — `credit-account-activated`+`delinquency-status-updated` abre caso con productType; `delinquency-status-updated(days=0)` cierra caso activo
- [x] **51 tests ✅** en total (40 unit + 11 E2E) — verificados con Docker el 2026-07-11

### Notas

- **Prerequisitos en credit-portfolio (D4★) — cerrados 2026-07-10:** `UpcomingInstallmentJob` y `CollectionAgreementExecutedListener`/`ProcessAgreementExecutedUseCase`. Ver Módulo 9 §Reglas de Negocio y §Jobs.
- **Desviación real del spec original de diseño (2026-07-08):** el spec asumía eventos `DelinquencyCleared` y `ProductSettled` como señales de entrada separadas. Al implementar se confirmó que `credit-portfolio` nunca los publicó (no existen en `CreditPortfolioEventPublisher`) — Collections deriva ambas condiciones de eventos que sí existen (`DelinquencyStatusUpdated` con `days=0`, `BalanceUpdated` con `accountStatus=SETTLED`). Funcionalmente equivalente, sin bloquear nada; `08_collections_domain.md` todavía describe la versión con eventos separados y debería corregirse en una pasada de documentación futura.
- RESTRUCTURE es una limitación de alcance conocida y documentada: `CreditAccount.applyRestructure` solo cambia `nominalRate`/`assignedTerm`, sin regenerar el calendario de amortización ni sincronizar la tasa cacheada en Charges — mismo criterio que la decisión GL-08 de T4 (no existe motor de recálculo de flujos en el sistema; construirlo no fue parte del alcance pedido).
- Depende de **D9 Risk** solo de forma opcional (ES-06) — Collections está completo y operable sin que Risk exista; el consumo de `RiskAssessmentUpdated` queda como mejora de priorización futura, no como bloqueo.
- `BureauReportingAdapter` — stub `NoopBureauReportingAdapter` (mismo patrón que `NoopSpeiDispatchAdapter`); falta integración real con Círculo de Crédito/Buró de Crédito.

---

## MÓDULO 14 — T2: Notifications

**Estado:** ✅ Completo — 2026-07-17 — alcance v1 (6 notificaciones); catálogo completo (30 eventos) queda documentado como backlog en `T2_notifications.md`  
**Doc de referencia:** `docs/dominios/T2_notifications.md`  
**Schema DB:** `notifications`  · **Puerto:** `8098`  
**Comunicación:** consume 7 topics (ver tabla de alcance). Publica `notifications.notification-sent`/`notification-failed`. Solo escribe, nunca modifica estado de otro dominio. **Cero jobs `@Scheduled` — 100% reactivo a Kafka (NT-12, confirmado explícitamente con el usuario, verificado con un guard test).**

> **18/18 servicios de dominio completos.** Con T2 Notifications implementado, el catálogo completo del sistema queda cerrado.
>
> **Implementado 2026-07-17** sobre el plan v1 ampliado el 2026-07-16 (4 notificaciones iniciales + 2 de celebración — cuota pagada, crédito liquidado — a petición explícita del usuario, con copy tipo "felicidades"). **Un ajuste de diseño hecho al implementar** (documentado, no en el plan original): `application_prospect_link` (paso 2 de la correlación de contacto) se pobló desde `origination.offer-presented` en vez de `application-approved` — offer-presented ya se consume para la notificación #1 y trae `applicationId`+`prospectId`+`offeredTerm` en un solo evento, evitando un listener adicional solo para la correlación (`application-approved` nunca se llegó a consumir).

### Alcance v1 — 6 notificaciones

| # | Notificación | Evento fuente | Correlación de contacto |
|---|---|---|---|
| 1 | Ofertas de crédito | `origination.offer-presented` | `prospectId` directo → `prospect_contact_shadow` |
| 2 | Bienvenida | `credit-portfolio.credit-account-activated` | join de 3 pasos → `party_contact_directory` (se resuelve aquí) |
| 3 | Desembolso | `credit-portfolio.disposition-completed` | `obligorPartyId` directo → `party_contact_directory` |
| 4 | Recordatorio de pago | `collections.pre-due-reminder-triggered` (no `installment-due`, queda para fase 2) | `obligorPartyId` directo → `party_contact_directory` |
| 5 | Cuota pagada completamente | `payments.payment-applied`, correlacionado contra la cuota vigente local — **aproximación, no dato exacto** (ver Notas) | vía `credit_account_progress.obligorPartyId` → `party_contact_directory` |
| 6 | Crédito liquidado por completo | `credit-portfolio.balance-updated` filtrado a `accountStatus=SETTLED` — dato limpio | `obligorPartyId` directo → `party_contact_directory` |

### Entidades

- [x] `notifications.notification_templates` — templateId, event_type, channel, locale, subject (solo EMAIL), body (placeholders `{{var}}`) — 15 filas seed (copy real de `T2_notifications.md`, es-MX)
- [x] `notifications.notification_preferences` — partyId (PK), push_token, whatsapp_number, updated_at — capturadas vía `PUT /preferences/{partyId}`, no vienen de ningún evento de dominio
- [x] `notifications.notification_records` — notificationId, source_event_id, recipient_id, event_type, channel, status (SENT/FAILED), failure_reason, sent_at — único (source_event_id, channel) para idempotencia. `recipient_id` (no `party_id`): es `prospectId` para #1 (todavía no existe Party correlacionable en ese punto) y `obligorPartyId` para el resto
- [x] `notifications.notification_policies` — policyId, event_type (única ACTIVE por evento), value_tier, channel_strategy (SIMULTANEOUS/SEQUENTIAL_FALLBACK), primary_channel, fallback_channels (tabla hija ordenada), version, status — 6 policies seed (ver tabla de alcance)
- [x] `notifications.prospect_contact_shadow` — prospectId (PK), first_name, phone, email — paso 1, desde `origination.prospect-created`
- [x] `notifications.application_prospect_link` — applicationId (PK), prospect_id, offered_term — paso 2, desde `origination.offer-presented` (ver ajuste de diseño arriba)
- [x] `notifications.party_contact_directory` — partyId (PK), first_name, phone, email, resolved_at — paso 3, resultado del join en `credit-account-activated`
- [x] `notifications.credit_account_progress` — creditAccountId (PK), obligor_party_id, product_type (para el copy de #6, `balance-updated` no lo trae), total_installments (de `offeredTerm`), installments_paid_count, current_due_date/total_amount (de `pre-due-reminder-triggered`) — soporta #5; es la pieza que materializa la aproximación, no reemplaza un `markPaid()` real en credit-portfolio

**Diferidos a fase 2 (no se crearon):** `credit_account_party_shadow`, `case_party_shadow` — ninguna de las 6 notificaciones de v1 los necesitó.

### Liquibase Changelogs

- [x] `db/changelog/notifications/001-create-schema.sql` .. `008-create-event-publication.sql` (8 tablas + event_publication)
- [x] `009-seed-notification-policies.sql` — 6 policies + fallback channels
- [x] `010-seed-notification-templates.sql` — 15 templates (copy real)

### Endpoints

- [x] `GET /api/v1/notifications/history/{partyId}`
- [x] `GET /api/v1/notifications/preferences/{partyId}` (404 si no registrada)
- [x] `PUT /api/v1/notifications/preferences/{partyId}` — registra `pushToken`/`whatsappNumber`
- [x] `GET /api/v1/notifications/policies`
- [x] `POST /api/v1/notifications/policies` — rol MARKETING/ADMIN, versiona (deprecia la anterior)

### Prerequisitos (resueltos)

- [x] **Directorio de contacto por `partyId`.** Ningún servicio persiste `phone`/`email` contra un `partyId` (`Party.create()` descarta el contacto que ya recibe en el payload de `origination.prospect-created`, verificado en código). Resuelto 100% event-driven, sin tocar ningún otro servicio: `ContactResolutionService` arma el join de 3 pasos (prospect-contact → application-prospect-link → join en `credit-account-activated` → `party_contact_directory`).
- [x] **No existe tracking de pago por cuota individual.** `credit-portfolio.InstallmentStatus.PAID`/`PARTIAL` están definidos en el enum pero ningún código los asigna (verificado: no hay `markPaid()` en todo `credit-portfolio-service`) — los pagos se aplican contra el saldo agregado de la cuenta, no contra una cuota. #5 se resuelve con la heurística `credit_account_progress` (match de `payment-applied.amount` contra la cuota vigente conocida vía `pre-due-reminder-triggered`) — **falla en pagos parciales o que cubren varias cuotas de golpe** (NT-11, documentado, no bloqueante). Fix limpio a mediano plazo: `markPaid()` real en credit-portfolio.
- [ ] **Complementario, no bloqueante, no implementado:** enriquecer `Party` con `phone`/`email` (ya llegan en el payload que `PartyService.createFromProspect` recibe pero no persiste) sería la fuente de verdad más limpia a mediano plazo — mismo criterio que el perfil fiscal CFDI.

### Reglas de Negocio

- [x] NT-02: `quietHours` — campo reservado en preferencias, no se aplicó lógica de horario de silencio en v1 (no había caso de uso urgente que lo requiriera; los 6 eventos son o instantáneos o de vigencia corta)
- [x] NT-03: fallback `PUSH → WHATSAPP → EMAIL` según el orden de `NotificationPolicy.orderedChannels()`
- [x] NT-05: plantillas versionables por `(eventType, channel, locale)`
- [x] NT-07: sin contacto resuelto en ningún canal candidato → `NotificationRecord` FAILED con `NO_CONTACT_INFO`, nunca bloquea el evento origen (mismo criterio "no se inventa dato" que CM-06/PR-03)
- [x] NT-08: `SIMULTANEOUS` (ofertas #1, bienvenida #2, liquidación #6 — picos emocionales) envía a todos los canales disponibles a la vez; `SEQUENTIAL_FALLBACK` (desembolso #3, recordatorio #4, cuota pagada #5) intenta en orden y se detiene en el primer envío exitoso — **incluye fallback real por fallo de adaptador**, no solo por canal no disponible (bug encontrado y corregido al escribir el test de este caso: la primera versión solo intentaba un canal en modo secuencial sin importar si el adaptador fallaba)
- [x] NT-11: #5 (cuota pagada) es best-effort — sin cuota vigente cargada, o monto que no la cubre, se omite en silencio, nunca genera `NotificationFailed`
- [x] **NT-12 (confirmado explícitamente con el usuario): cero jobs `@Scheduled`.** El recordatorio de pago (#4) no tiene cron propio — `UpcomingInstallmentJob` (cron diario que ya existe en `credit-portfolio`, ya sirve a Collections/EC-01) hace el cálculo de fecha; Notifications solo se suma como consumer de su salida (`collections.pre-due-reminder-triggered`). Se evaluó y se descartó explícitamente ir más lejos (retirar ese cron y reemplazarlo por schedule-al-activar + `TaskScheduler` en Notifications) — se prefirió no tocar otros servicios.

**Diferidas a fase 2 (no implementadas):** NT-01 (regulatorias no opt-outable), NT-04 (rate limit diario), NT-06 (retención CONDUSEF), NT-09 (segmentación partner B2B2C), NT-10 (hitos derivados #27/#28) — ninguna aplica todavía porque v1 no incluye eventos ⚪ compliance ni 🔵 partner ni los hitos fuera de #5.

### Canales (adaptadores)

- [x] `NoopPushAdapter` — stub, log únicamente. Real: Firebase Cloud Messaging + APNs (gratis, sin costo por volumen)
- [x] `NoopWhatsAppAdapter` — stub. Real: WhatsApp Cloud API oficial de Meta (1,000 conversaciones/mes gratis directo, sin BSP intermediario)
- [x] `SmtpEmailAdapter` — **real, no stub** — vía `JavaMailSender`, funciona con cualquier SMTP (Gmail dev, Brevo/Resend free tier, SES a volumen); deshabilitado por default (`fintech.notifications.email.smtp-enabled=false`), activable solo con config, sin tocar código
- [x] `NoopEmailAdapter` — default cuando SMTP no está configurado (`@ConditionalOnProperty` complementario al de `SmtpEmailAdapter`)

### Tests

- [x] `NotificationTemplateTest` (4) — sustitución de placeholders, placeholder faltante se deja igual, subject null en canales sin asunto
- [x] `CreditAccountProgressTest` (5) — cuota cubierta avanza contador, pago parcial no cubre (NT-11), monto que excede sí cubre, sin cuota vigente es no-op, cuotas restantes null si total desconocido
- [x] `NotificationPolicyTest` (3) — orden de canales, arranca ACTIVE, deprecate
- [x] `NotificationDispatchServiceTest` (6) — sin policy es no-op, SIMULTANEOUS manda a todos los disponibles, SEQUENTIAL_FALLBACK salta canal no disponible, sin contacto en ningún lado → NO_CONTACT_INFO, idempotencia por (sourceEventId, channel), **fallo de adaptador en modo secuencial sí prueba el siguiente canal** (el bug corregido)
- [x] `ContactResolutionServiceTest` (5) — guarda shadow de prospecto, guarda link de aplicación, join completo resuelve contacto + inicializa progreso, sin link resuelve contacto vacío pero igual inicializa progreso, contacto de party ausente devuelve vacío
- [x] `NotificationTriggerServiceTest` (7) — ofertas usa prospectId como recipient, pago sin progreso no dispara (NT-11), pago cubre cuota dispara #5, pago no cubre no dispara, `balance-updated` no-SETTLED no dispara, SETTLED dispara #6, recordatorio actualiza progreso y dispara #4
- [x] `NotificationPolicyServiceTest` (3) — primera versión ACTIVE, versiona+deprecia anterior, listActive delega
- [x] `NotificationAcceptanceTest` (7, Testcontainers) — 401 sin token, historial 200, preferencias 404/200, políticas 200, POST rol MARKETING 201, rol incorrecto 403
- [x] `NotificationFlowIT` (1, Testcontainers) — flujo completo prospect-created → offer-presented (#1) → credit-account-activated (join + #2) → balance-updated SETTLED (#6), end-to-end sobre Kafka+Postgres reales
- [x] **41 tests ✅** — verificados con Docker el 2026-07-17

### Notas

- El catálogo completo (30 eventos, `valueTier`, canal, copy real) vive en `T2_notifications.md` §Enumeración completa — es el backlog priorizado para cuando se amplíe v1.
- **18/18 servicios de dominio completos.**
- Suite completa del monorepo compilada sin regresiones (excluyendo un fallo preexistente y no relacionado en `identity-service` — tests desalineados con la migración a JWT RS256 del 2026-06-28, ninguno de sus archivos fue tocado en esta sesión).

---

## MÓDULO 15 — T4: Accounting / GL

**Estado:** ✅ Completo — 2026-07-12  
**Doc de referencia:** `docs/dominios/T4_accounting_gl.md`  
**Schema DB:** `accounting`  · **Puerto:** `8095`  
**Comunicación:** Consume **`credit-portfolio.balance-updated`** como fuente primaria (plantilla resuelta por `triggerEvent`), `risk.assessment-updated` (provisión IFRS-9), `collections.recovery-payment-applied`, `wallet.withdrawal-completed`. Publica `accounting.journal-entry-created`, `accounting.invoice-requested` (a Facturación), `accounting.reconciliation-alert`. Nunca modifica estado de otros dominios.

> **Decisiones de negocio confirmadas 2026-07-12 (a petición del usuario):**
> - **Asientos a nivel préstamo.** Cada `JournalEntry` lleva `creditAccountId`/`obligorPartyId` (auxiliar, cumple CNBV R04-C e IFRS-9 por instrumento); el mayor es la agregación por `(cuenta, período)`. El **monto** de cada asiento se deriva del **delta** de saldos (`AccountBalanceShadow` local) porque `balance-updated` trae saldos nuevos, no el monto de la transacción.
> - **Facturación consolidada por party/período** (un CFDI por cliente obligado, líneas por concepto/crédito). El receptor del CFDI es el **party (RFC)**, nunca el préstamo ni el "negocio" (en México un contribuyente = un RFC). Los ingresos (interés, comisiones, IVA) se acumulan como `InvoiceableItem` y el `BillingRunJob` emite `accounting.invoice-requested` al servicio de Facturación (Módulo 18).
> - Datos fiscales: se **enriqueció Party** con perfil fiscal CFDI (razón social, régimen SAT, CP, uso CFDI) + evento `party.fiscal-profile-updated`.

### Entidades

- [ ] `accounting.account_catalog` — id, code, name, account_type (ASSET/LIABILITY/INCOME/EXPENSE), product_type, trigger_event (renombrado de `charge_type` — ahora cubre cualquier `triggerEvent`, no solo cargos)
- [ ] `accounting.journal_entries` — id, entry_date, description, correlation_id, trigger_event, status (DRAFT/POSTED/REVERSED)
- [ ] `accounting.journal_lines` — id, entry_id, account_code, debit_amount, credit_amount, credit_account_id, party_id
- [ ] `accounting.provision_ledger` (**nuevo**) — creditAccountId (unique), lastBookedProvision, lastBookedAt — necesario para asentar `risk.assessment-updated` por delta, no por monto absoluto (GL-09)

### Liquibase Changelogs

- [ ] `db/changelog/accounting/001-create-schema.sql`
- [ ] `db/changelog/accounting/002-create-account-catalog.sql`
- [ ] `db/changelog/accounting/003-create-journal-entries.sql`
- [ ] `db/changelog/accounting/004-create-journal-lines.sql`
- [ ] `db/changelog/accounting/005-create-provision-ledger.sql` (**nuevo**)
- [ ] `db/changelog/accounting/006-seed-account-catalog.sql` (**nuevo**) — incluye las 2 cuentas nuevas: "Fondos de clientes por disponer" (pasivo) y "Estimación preventiva para riesgos crediticios" (contra-activo)

### Endpoints

- [ ] `GET /api/v1/accounting/entries` — filtros: dateRange, creditAccountId, triggerEvent
- [ ] `GET /api/v1/accounting/entries/{id}`
- [ ] `GET /api/v1/accounting/reconciliation/{date}` — ahora devuelve los 3 checks (CARTERA, FONDOS_CLIENTE, RESERVA)

### Reglas de Negocio

- [ ] Partida doble por cada evento económico
- [ ] Verdad operativa (credit-portfolio) ≠ verdad contable (GL)
- [ ] `WAIVED ≠ REVERSED`: condonación = gasto P&L; reversión = cancelación ingreso
- [ ] Reconciliación diaria — **3 checks (extendido 2026-07-10):** (1) Cartera de crédito (GL) vs `principalBalance` agregado credit-portfolio; (2) **Fondos de clientes por disponer (GL) vs Σ `walletBalance` de Wallet (nuevo, GL-07)**; (3) **Estimación preventiva (GL) vs agregado de `provisionAmount` de Risk (nuevo, GL-11)** — alerta en cualquier delta
- [ ] Quebranto a cuentas "castigo"; recuperación post-quebranto a ingreso extraordinario
- [ ] **(nuevo)** Provisión EPR se asienta por delta vía `ProvisionLedger`, nunca por el monto absoluto de `risk.assessment-updated` (GL-09)
- [ ] **(nuevo)** Quebranto y quita parcial consumen primero la reserva ya provisionada para la cuenta (`ProvisionLedger`) — el excedente golpea P&L directo como gasto extraordinario (GL-10)
- [ ] **(nuevo, decisión de alcance — GL-08)** `CollectionAgreementExecuted(RESTRUCTURE)` no genera asiento al ejecutarse — tratado como modificación no sustancial sin motor de NPV (no existe en el sistema). Limitación conocida, documentada explícitamente; revisar antes de auditoría externa

### Jobs

- [ ] `ProvisionPostingListener` (**nuevo**) — reactivo (Kafka listener, no job): al recibir `risk.assessment-updated`, calcula delta contra `ProvisionLedger` y asienta inmediatamente
- [ ] `DailyReconciliationJob` (**nuevo**, 04:00 — después de `RiskAssessmentJob` de D9 a las 01:00 y `BureauReportingJob` de D8 a las 02:00) — corre los 3 checks de conciliación, publica `ReconciliationAlert` por cada delta

### Tests

- [ ] Unit tests — doble entrada balanceada, waived vs reversed, reconciliación
- [ ] Unit tests (**nuevo**) — plantilla contable resuelta por `triggerEvent` (incluyendo DISPOSITION_SELF_USE → Fondos de clientes, no Bancos)
- [ ] Unit tests (**nuevo**) — `ProvisionLedger` delta positivo/negativo, quebranto/quita consumiendo reserva ya provisionada + excedente a P&L
- [ ] `DailyReconciliationJobTest` (**nuevo**) — los 3 checks, alerta en cada uno de forma independiente
- [ ] `ModularityTest` sigue pasando

### Notas

- Depende de que **D9 Risk** y el **D8 Collections robustecido** existan para las reglas nuevas (GL-07 a GL-11) — las reglas originales (cartera, cargos, pagos, quebranto simple) no dependen de ninguno de los dos y podrían implementarse antes si se prioriza Accounting temprano.
- GL-08 (reestructura sin asiento) es una decisión de alcance explícita, no un olvido — ver `T4_accounting_gl.md` §Decisiones de diseño.
- **(nuevo, 2026-07-14)** `CommissionPostingService` — posteo de los 3 eventos de T6 Commission (`commission-accrued`/`reversed`/`liquidated`): devengo = debe `5104 Gasto por comisiones` / haber `2120 Comisiones por pagar`; reversa = asiento espejo; liquidación = debe `2120` / haber `Bancos`. 2 cuentas nuevas en el catálogo (`005-seed-catalog.sql`), 3 listeners nuevos en `KafkaConfig`, 5 tests nuevos (`CommissionPostingServiceTest`) — incluidos en el conteo de 21 tests del módulo.

---

## MÓDULO 16 — T6: Commission

**Estado:** ✅ Completo — 2026-07-14  
**Doc de referencia:** `docs/dominios/T6_commission.md`  
**Schema DB:** `commission`  · **Puerto:** `8097`  
**Comunicación:** Consume `credit-portfolio.balance-updated` (`PAYMENT_APPLIED` → interés cobrado; `PAYMENT_RETURNED` → reversa), `credit-portfolio.credit-account-activated` (enriquecido con `promoterCode`). Publica `commission.commission-accrued`, `commission.commission-reversed`, `commission.commission-liquidated` → consumidos por T4 Accounting.

> **Corrección de modelo (2026-07-14, a petición del usuario):** el distribuidor B2B2C **ya no cobra por adelantado** (el spec original tenía un `DISPOSITION_FEE` sobre el monto dispuesto — creaba el incentivo perverso de colocar crédito sin validar su calidad, ya que el distribuidor cobraba sin importar si el cliente pagaba). El modelo implementado es **`DISTRIBUTOR_INTEREST_SHARE`**: un % del **interés efectivamente cobrado**, devengado **plazo a plazo** contra cada pago recibido, configurable por producto (y opcionalmente por distribuidor). El distribuidor comparte el riesgo de recuperación en vez de cobrar por colocar. Ver `T6_commission.md` §Modelo del distribuidor.

> **Prerequisito implementado — propagación de `promoterCode` end-to-end:** no existía forma de saber a qué distribuidor atribuir un crédito activo. `channels.CustomerIntent.promoterCode` (capturado pero previamente sin uso) ahora viaja por 4 servicios: `channels.application-started` → `origination.CreditApplication.promoterCode` → `origination.credit-product-creation-requested` → `credit-portfolio.CreateCreditAccountCommand.promoterCode` → `credit-portfolio.credit-account-activated`. **Limitación conocida y documentada:** no existe un directorio distribuidor↔código en el sistema; Commission exige que `promoterCode` ya sea el UUID de Party del beneficiario — si no parsea como UUID, no se crea asignación y el crédito simplemente no genera comisión (CM-07, log de warning, no falla dura).

### Entidades

- [x] `commission.commission_policies` — policyId, productType, distributorPartyId (nullable), commissionType, rate (% del interés, 0–1), version, status (DRAFT/ACTIVE/DEPRECATED) — índice único parcial `WHERE status='ACTIVE'` por (productType, commissionType, distributorPartyId); `distributorPartyId IS NULL` = tasa default del producto. Mismo patrón que `ProvisionPolicy` (Risk) / `ScoringPolicy` (Scoring)
- [x] `commission.commission_records` — commissionId, commissionType, creditAccountId, beneficiaryPartyId, sourceEventId (unique, idempotencia CR-04), basis (interés recuperado), rate (snapshot al devengar, CR-02), amount (basis × rate, CR-01), status (ACCRUED/LIQUIDATED/REVERSED), period, accrualDate, liquidationBatchId
- [x] `commission.liquidation_batches` — batchId, beneficiaryPartyId, period, totalAmount, status (PENDING/SENT/CONFIRMED), paymentRef, processedAt
- [x] `commission.account_balance_shadows` — creditAccountId, interestBalance, balanceVersion, updatedAt — read-model local para derivar el interés cobrado (delta) de `balance-updated`, ya que el evento trae saldos nuevos, no montos de transacción (mismo patrón que `PostingService`/`ProvisionPostingService` en Accounting)
- [x] `commission.credit_promoter_assignments` — creditAccountId (unique), beneficiaryPartyId, productType, active — atribución distribuidor↔crédito, creada al recibir `credit-account-activated` con `promoterCode` parseable a UUID

### Liquibase Changelogs

- [x] `db/changelog/commission/001-create-schema.sql`
- [x] `db/changelog/commission/002-create-commission-policies.sql`
- [x] `db/changelog/commission/003-create-commission-records.sql`
- [x] `db/changelog/commission/004-create-liquidation-batches.sql`
- [x] `db/changelog/commission/005-create-readmodels.sql` — `account_balance_shadows` + `credit_promoter_assignments`
- [x] `db/changelog/commission/006-create-event-publication.sql`
- [x] `db/changelog/commission/007-seed-commission-policies.sql` — tasas default ACTIVE v1, `distributorPartyId=NULL`: `DISTRIBUTOR_LINE` 0.30000, `SME_LOAN` 0.20000 — placeholder comercial, calibrar antes de producción (mecanismo listo, no el número, mismo criterio que Risk)

### Endpoints

- [x] `GET /api/v1/commissions/accounts/{creditAccountId}` — historial de comisiones de un crédito
- [x] `GET /api/v1/commissions/beneficiaries/{partyId}/pending` — comisiones ACCRUED sin liquidar de un distribuidor
- [x] `GET /api/v1/commissions/policies` — tasas vigentes (transparencia)
- [x] `POST /api/v1/commissions/policies` — versiona/crea una política (rol COMMERCIAL/ADMIN, 201)
- [x] `POST /api/v1/commissions/liquidation-runs?period=` — corrida de liquidación batch (rol FINANCE/ADMIN)

### Tipos de Comisión

| Tipo | Trigger | Base | Recurrencia |
|---|---|---|---|
| **`DISTRIBUTOR_INTEREST_SHARE`** (implementado, núcleo B2B2C) | `PAYMENT_APPLIED` con interés cobrado | interés recuperado (delta de `accruedInterestBalance`) | plazo a plazo, contra el pago |
| `ORIGINATION_FEE` (enum, no implementado) | activación | monto aprobado | una vez |
| `COLLECTION_BONUS` (enum, no implementado) | pago en cartera asignada | capital pagado | por pago |
| `RENEWAL_BONUS` (enum, no implementado) | reactivación | monto aprobado | una vez |

### Reglas de Negocio

- [x] CM-01: solo `PAYMENT_APPLIED` con caída de `accruedInterestBalance` devenga comisión — `CHARGE_*` nunca devenga (probado explícitamente en `CommissionFlowIT` y `CommissionAccrualServiceTest`)
- [x] CM-05: `PAYMENT_RETURNED` con alza de `accruedInterestBalance` → reversa el `CommissionRecord` ACCRUED más reciente de la cuenta — **aproximación conservadora documentada**: credit-portfolio hoy restaura lo devuelto en `penaltyBalance` (no en `accruedInterestBalance`) y `PAYMENT_RETURNED` no lleva `sourceEventId` correlacionado al pago original, así que no hay match exacto por pago disponible aún; ver comentario extenso en `CommissionAccrualService.onBalanceUpdated()`
- [x] CM-06: sin `CommissionPolicy` ACTIVE (ni específica del distribuidor ni default del producto) → se omite, no se inventa tasa
- [x] CM-07: sin `CreditPromoterAssignment` para la cuenta → se omite (no hay beneficiario a quien pagar)
- [x] CR-01: `amount = basis × rate`
- [x] CR-02: `rate` se congela (snapshot) en el `CommissionRecord` al devengar — cambios posteriores de política no afectan devengos ya creados
- [x] CR-04: idempotencia por `sourceEventId` único — replay del mismo evento no duplica el devengo
- [x] CP-01: una sola `CommissionPolicy` ACTIVE por (productType, commissionType, distributorPartyId) — `distributorPartyId=NULL` es la tasa default del producto; resolución busca primero la específica del distribuidor, si no existe cae al default
- [x] CP-02: la tasa es capturada comercialmente (vía POST /policies), nunca calculada por el modelo
- [x] LB-03: liquidación agrupa por beneficiario y período; lotes por debajo del mínimo configurado (`fintech.commission.min-liquidation-amount`, default 500) se omiten, no se crean

### Jobs

- [x] `CommissionLiquidationJob` (`0 30 3 5 * *`, día 5 03:30, después del cierre de Accounting) — corre `LiquidationService.runLiquidation(periodoAnterior)`: agrupa `CommissionRecord` ACCRUED por beneficiario, crea `LiquidationBatch` sobre el mínimo, marca los registros LIQUIDATED, publica `commission.commission-liquidated`

### Tests

- [x] `CommissionRecordTest` (5) — devengo calcula amount, transiciones de reverse, reverse-ya-reversado lanza, markLiquidated exige ACCRUED, markLiquidated setea status+batchRef
- [x] `PromoterAssignmentServiceTest` (4) — UUID válido crea asignación, promoterCode null no crea nada, promoterCode no-UUID no crea nada (CM-07), ya asignado es idempotente
- [x] `CommissionAccrualServiceTest` (8) — devengo con caída de interés (basis=300, amount=90.00 a tasa 0.30), tasa específica de distribuidor gana sobre default, sin asignación omite (CM-07), sin política ACTIVE omite (CM-06), `CHARGE_*` nunca devenga (CM-01), `balanceVersion` obsoleto se ignora, `PAYMENT_RETURNED` reversa el devengo más reciente (CM-05), reversa sin devengo previo es no-op
- [x] `CommissionPolicyServiceTest` (3) — primera versión ACTIVE con tasa default, versiona+deprecia la anterior, override de distribuidor no toca la política default del producto
- [x] `LiquidationServiceTest` (3) — agrupa por beneficiario y crea un batch cada uno sobre el mínimo, bajo el mínimo se omite, sin registros ACCRUED es no-op
- [x] `CommissionAcceptanceTest` (7, Testcontainers) — GET por cuenta/beneficiario/políticas, POST política (rol COMMERCIAL 201, rol incorrecto 403), POST liquidation-run (rol FINANCE 200), 401 sin token
- [x] `CommissionFlowIT` (1, Testcontainers) — activación con `promoterCode` crea asignación; `CHARGE_ORDINARY_INTEREST` aislado no genera registros (prueba CM-01); `PAYMENT_APPLIED` con caída de interés 1000→400 genera 1 `CommissionRecord` con basis=600, beneficiario correcto, status ACCRUED
- [x] **31 tests ✅** — verificados con Docker el 2026-07-14

### Notas

- El patrón (shadow → delta de interés → devengo → batch → liquidación) reutiliza directamente lo ya construido en Accounting (`AccountBalanceShadow`) y en Risk (política versionada) — sin abstracciones nuevas.
- Es el mecanismo que **cierra el modelo B2B2C**: el distribuidor comparte el riesgo de recuperación en vez de cobrar por colocar. La corrección de modelo (contra el pago, no upfront) fue una decisión explícita del usuario, no un default de diseño.
- La reversa CM-05 queda marcada como aproximación conservadora — depende de una mejora futura en credit-portfolio (bucket correcto para `PAYMENT_RETURNED` + `sourceEventId` correlacionado al pago original) para volverse exacta por pago.

---

## MÓDULO 17 — D9: Risk

**Estado:** ✅ Completo — 2026-07-12  
**Doc de referencia:** `docs/dominios/09_risk_domain.md`  
**Schema DB:** `risk`  
**Puerto:** `8094` (siguiente libre tras wallet 8092, collections 8093)  
**Comunicación:** Consume `credit-portfolio.credit-account-activated`, `credit-portfolio.balance-updated`, `credit-portfolio.delinquency-status-updated`, `collections.agreement-executed` (solo tipo RESTRUCTURE). Publica `risk.assessment-updated` (nightly, por cuenta). **Nunca modifica saldos ni ejecuta cobranza — solo clasifica.**

> **Desviación real de spec (misma que Collections):** el spec del 2026-07-08 listaba `DelinquencyCleared`, `ProductSettled`, `ProductWrittenOff` como eventos de entrada separados; no existen en credit-portfolio. Risk los deriva: "cleared" = `DelinquencyStatusUpdated(days=0)`; "settled"/"written-off" = `BalanceUpdated.accountStatus` ∈ {SETTLED, WRITTEN_OFF} → `RiskProfile.close()`.

> Por qué es un servicio nuevo y no vive en credit-portfolio o Collections: tres razones de cambio distintas (ledger / operación de cobranza / metodología contable-regulatoria IFRS-9). Mismo criterio que ya separó `credit-product` de `credit-portfolio` en ADR-001. Ver `09_risk_domain.md` §Decisiones para el análisis completo, incluyendo la distinción entre `riskTier` (Scoring, estático, al originar) y `ifrs9Stage` (Risk, dinámico, transiciona con el comportamiento de pago).

### Entidades

- [x] `risk.risk_profiles` — riskProfileId, creditAccountId (unique), obligorPartyId, productType, daysDelinquent, bucket (CURRENT|B1_30|B31_60|B61_90|B91_120|B121_180|B181_PLUS), ifrs9Stage (STAGE_1|STAGE_2|STAGE_3), stageEnteredAt, isForborne, ead, expectedLossRate, provisionAmount, status (ACTIVE|CLOSED), lastCalculatedAt
- [x] `risk.provision_policies` — policyId, productType, version, status (DRAFT|ACTIVE|DEPRECATED) — partial unique `WHERE status='ACTIVE'` por productType, mismo patrón que `credit_product.credit_product_definitions`
- [x] `risk.provision_rate_bands` — id, policyId, bucket, expectedLossRate (NUMERIC(6,5)) — una fila por cada uno de los 7 buckets, obligatorias (PP-02, validado en el factory de dominio + unique (policy_id, bucket) en DB)

### Liquibase Changelogs

- [x] `db/changelog/risk/001-create-schema.sql`
- [x] `db/changelog/risk/002-create-risk-profiles.sql`
- [x] `db/changelog/risk/003-create-provision-policies.sql`
- [x] `db/changelog/risk/004-create-provision-rate-bands.sql`
- [x] `db/changelog/risk/005-create-event-publication.sql`
- [x] `db/changelog/risk/006-seed-initial-provision-policies.sql` — tasas placeholder para PERSONAL_LOAN, REVOLVING_CREDIT, PAYROLL_LOAN, SME_LOAN (CURRENT 1% → B181_PLUS 100%); **deben calibrarse con el equipo de riesgo antes de producción** — lo que importa día uno es el mecanismo de tabla versionada, no el número exacto

### Endpoints

- [x] `GET /api/v1/risk/accounts/{creditAccountId}` — RiskProfile vigente
- [x] `GET /api/v1/risk/accounts?partyId=` — todas las cuentas de un party (patrón `?partyId=`)
- [x] `GET /api/v1/risk/provisions/summary` — rollup de provisión vigente por productType/stage (para Finanzas/Auditoría, futuro T4). Sin `?date=`: se calcula sobre las cuentas ACTIVE actuales — no se almacenan snapshots históricos (sería un dato inventado)
- [x] `GET /api/v1/risk/provision-policies` / `GET /{productType}` — consulta de tasas vigentes (transparencia)
- [x] `POST /api/v1/risk/provision-policies` — crea/versiona una política (rol RISK_ANALYST/ADMIN, mismo patrón que `ScoringPolicyController`)

### Etapas IFRS-9 (algoritmo determinístico — ver Reglas)

```
CURRENT (0d) / B1_30 (1-29d)              → STAGE_1  (ECL 12 meses)
B31_60 / B61_90 (30-89d)                  → STAGE_2  (ECL lifetime — SICR, backstop 30d)
B91_120 / B121_180 / B181_PLUS (90d+)     → STAGE_3  (ECL lifetime — default, presunción rebatible 90d IFRS-9/Basel)

Forbearance (reestructura activa) → mínimo STAGE_2 durante cureMonths (T5, default 6) aunque daysDelinquent baje a 0
```

### Reglas de Negocio

- [x] RC-01: `bucket` derivado localmente de `daysDelinquent` (`DelinquencyBucket.fromDaysDelinquent`, misma tabla que Collections, sin acoplar servicios)
- [x] RC-02: `provisionAmount = ead × expectedLossRate` — calculado en `RiskProfile.reassess`, nunca capturado a mano
- [x] RC-03: transición de stage solo por el job nocturno (`reassess`) — los handlers de eventos sincronizan days/ead/forbearance pero **no** cambian el stage (evita flapping intradía)
- [x] RC-04: reestructura (`markForborne`) reinicia el reloj de cura; el siguiente `reassess` fuerza mínimo STAGE_2 mientras `now < stageEnteredAt + cureMonths`; al vencer la ventana se limpia `isForborne`
- [x] RC-05: STAGE_3 pegajoso hacia abajo — desde STAGE_3 solo se puede bajar a STAGE_2 en un reassessment (nunca directo a STAGE_1), vía `Ifrs9StageResolver.targetStage`
- [x] RC-06: `status=CLOSED` (derivado de `BalanceUpdated.accountStatus` SETTLED/WRITTEN_OFF) congela `provisionAmount` — `syncEad`/`syncDaysDelinquent`/`reassess` son no-op en CLOSED
- [x] RC-07 (algoritmo EPR): tabla de tasas por (productType, bucket) — determinística, auditable, sin componente estadístico. Extensión futura a PD×LGD queda aditiva sobre `ProvisionRateBand`
- [x] PR-01: recálculo nightly de **todas** las cuentas ACTIVE (`RiskAssessmentService.reassessAll`, full-recompute)
- [x] PR-02: `ead` es snapshot del último `BalanceUpdated.totalDebt` — sin llamada síncrona a credit-portfolio
- [x] PR-03: sin `ProvisionPolicy` ACTIVE para un productType → `MissingProvisionPolicyException`, el job lo registra y salta esa cuenta (no inventa tasa, no aborta el lote)

### Jobs

- [x] `RiskAssessmentJob` (`0 0 1 * * *`, 01:00 — después del `DelinquencyCalculationJob` de credit-portfolio) — recalcula bucket/stage/provisión de todas las `RiskProfile` ACTIVE con aislamiento por cuenta (`RiskProfileAssessor` con `REQUIRES_NEW`, mismo patrón que `DelinquencyAccountProcessor`); publica `risk.assessment-updated` por cuenta siempre (incluso sin cambio de stage)

### Tests

- [x] `Ifrs9StageResolverTest` (13) — mapeo días→stage (9 casos parametrizados), forbearance floor, STAGE_3 sticky, base peor gana
- [x] `RiskProfileTest` (6) — create STAGE_1, provisión = ead×rate, STAGE_3, forbearance floor en cura, cura vencida limpia forbearance, close congela
- [x] `ProvisionPolicyServiceTest` (3) — primera versión ACTIVE, versiona+depreca anterior, bucket faltante rechazado (PP-02)
- [x] `RiskProfileAssessorTest` (3) — aplica tasa+publica, sin política ACTIVE lanza PR-03, perfil CLOSED es no-op
- [x] `RiskAssessmentServiceTest` (2) — conteos + aislamiento de fallos (PR-03 skip vs failed), todos exitosos
- [x] `RiskProfileServiceTest` (8) — handlers de eventos (activación crea/idempotente, ead sync, close SETTLED/WRITTEN_OFF, days sync, forborne, not found)
- [x] `RiskAcceptanceTest` (9, Testcontainers) — GET account/party/summary/policies, POST política (rol RISK_ANALYST 201, wrong role 403), 401, 404
- [x] `RiskFlowIT` (2, Testcontainers) — activación crea perfil; pipeline activación+balance+delinquency → `reassessAll` computa provisión (STAGE_2, ead×0.15) y emite `risk.assessment-updated`
- [x] **46 tests ✅** en total — verificados con Docker el 2026-07-12

### Notas

- Sin ACL síncrona — 100% event-driven, ver `09_risk_domain.md` §Decisiones.
- Las tasas del seed (006) son placeholder — calibrar con riesgo/finanzas antes de producción; el mecanismo (tabla versionada) es lo listo, no el número.
- T4 Accounting (Módulo 15) es el consumidor natural de `risk.assessment-updated` para el asiento de la reserva — no implementado todavía; el evento queda publicado y sin consumir (mismo patrón incremental que otros eventos huérfanos, p.ej. `ApplicationRejected` sin Notifications).
- **Race conocido (patrón común a todos los read-models locales):** `balance-updated` y `delinquency-status-updated` son tópicos distintos → listeners concurrentes que mutan la misma fila `risk_profiles` sin `@Version` → posible lost-update si llegan en el mismo instante. Tolerado igual que en collections/payments/wallet; el `RiskFlowIT` secuencia los eventos. Si se quisiera blindar, se añadiría optimistic locking + retry en todos los servicios, no solo Risk.

---

## MÓDULO 19 — T7: Observability

**Estado:** ✅ Completo — 2026-07-18. Código, config y verificación end-to-end con Docker real cerrados: trazas, métricas y logs correlacionados por `trace_id`, confirmados con datos reales incluyendo a través del propio Grafana (ver §Verificación en vivo).  
**Doc de referencia:** `docs/dominios/T7_observability.md`  
**Schema DB:** ninguno (infraestructura transversal, no tiene datos de negocio propios)  
**Comunicación:** los 19 servicios exportan trazas por OTLP al `otel-collector`; exponen métricas Prometheus vía `/actuator/prometheus` (scrape); Fluent Bit lee sus logs de contenedor. No consume ni publica eventos de dominio — es puramente observacional.

> No es un microservicio de negocio como los 18 anteriores — es la capa de trazabilidad/métricas/logs para poder operar el sistema completo. Diseño en detalle en el doc de referencia: por qué se separan las 3 señales (trazas por OTel/Tempo, métricas por Micrometer/Prometheus, logs por Fluent Bit/Elasticsearch) en vez de mandarlas todas por un solo pipeline.

### Componentes nuevos (7)

- [x] `otel-agent-init` (`curlimages/curl:8.10.1`, `user: "0:0"` — fix real, el usuario default no podía escribir en el volumen recién creado) — descarga el jar del agente a un volumen compartido, corre siempre (no detrás del perfil)
- [x] `otel-collector` (`otel/opentelemetry-collector:0.110.0`) — recibe OTLP, reenvía a Tempo — **confirmado recibiendo y exportando spans reales en vivo**
- [x] `tempo` (`grafana/tempo:2.6.1`) — **confirmado: trazas reales consultables vía `/api/search` y `/api/traces/{id}`**
- [x] `prometheus` (`prom/prometheus:v2.55.1`) — **confirmado: 18-20/20 targets `UP` scrapeando `/actuator/prometheus` real**
- [x] `fluent-bit` (`fluent/fluent-bit:3.2.2`) — filtro `docker_metadata` planeado originalmente **no existe en Fluent Bit** (hallazgo en vivo) — quitado, no hacía falta (`service_name` ya viaja en el JSON de cada log)
- [x] `elasticsearch` (`docker.elastic.co/elasticsearch/elasticsearch:8.15.3`) — límite subido 1024M→1536M tras 2 OOM-kills reales (exit 137) en verificación
- [x] `grafana` (`grafana/grafana:11.3.0`) — datasources + dashboard provisionados, **confirmado con datos reales vía la API de Grafana** (throughput y p95 de `commission` a través del proxy de Prometheus de Grafana, no solo consultando Prometheus directo)

Los últimos 6 quedan detrás de `profiles: ["observability"]` — `docker compose up` sin el perfil sigue funcionando igual que antes (OBS-04).

### Cambios por servicio existente (19 — 5 cambios, no 4; el quinto se descubrió en vivo)

- [x] `build.gradle.kts` — `micrometer-registry-prometheus` + `logstash-logback-encoder:8.0` (19/19)
- [x] `application.yml` — `prometheus` agregado a `exposure.include` + `percentiles-histogram.http.server.requests: true` (19/19, YAML-validado)
- [x] `logback-spring.xml` — `<include resource="logback-base.xml"/>` (19/19) + nuevo `shared/src/main/resources/logback-base.xml` (JSON vía `LogstashEncoder`, MDC automático, loggers de framework centralizados a WARN)
- [x] `docker-compose.yml` — anchor `<<: *otel-agent` + `OTEL_SERVICE_NAME` + volumen + `depends_on: otel-agent-init` (19/19) + límite de memoria `deploy.resources.limits.memory: 512M` por servicio
- [x] **`SecurityConfig.java` (nuevo, no estaba en el plan original) — agregado `/actuator/prometheus` a `permitAll()` en 17/19 servicios** (party-service y scoring-service no tienen `SecurityConfig` propio). Sin esto, Prometheus recibía 401 en vez de scrapear — encontrado al verificar en vivo, no predecible solo leyendo código.

`gateway-service` (OpenResty/nginx) queda fuera — no es una JVM.

### Archivos de configuración nuevos

- [x] `services/observability-service/otel-collector/otel-collector-config.yaml`
- [x] `services/observability-service/tempo/tempo.yaml`
- [x] `services/observability-service/prometheus/prometheus.yml` (19 targets estáticos)
- [x] `services/observability-service/fluent-bit/fluent-bit.conf` (ajustado — sin `docker_metadata`) + `parsers.conf`
- [x] `services/observability-service/grafana/provisioning/datasources/datasources.yml` (Prometheus/Tempo/Elasticsearch, UIDs explícitos)
- [x] `services/observability-service/grafana/provisioning/dashboards/dashboards.yml` + `services/observability-service/grafana/dashboards/service-overview.json`
- [x] `shared/src/main/resources/logback-base.xml`

### Dashboard — paneles

Throughput, error rate %, p50/p95/p99, tiempo de respuesta promedio, throughput por endpoint (top 10), JVM heap, GC pause time, panel de logs (Elasticsearch). Parametrizado por variable `$service` — un dashboard, no 19. **Confirmado con datos reales:** las 8 queries del dashboard (throughput/p95/etc.) se ejecutaron vía la API de Grafana (`/api/datasources/uid/prometheus/resources/api/v1/query`) contra `job="commission"` y devolvieron valores reales no nulos.

### Reglas de diseño

- [x] OBS-01: métricas solo por Micrometer, nunca también por el SDK de OTel — respetado (`OTEL_METRICS_EXPORTER=none`)
- [x] OBS-02: agente inyectado por volumen + env var, nunca horneado en la imagen — `Dockerfile` no se tocó
- [x] OBS-03: toda config versionada en `observability/` — cero clickops
- [x] OBS-04: `docker compose up` sin el perfil sigue funcionando igual — verificado indirectamente (los servicios de negocio no dependen de ningún contenedor del perfil `observability` para arrancar)

### Verificación en vivo (2026-07-18, con Docker real — completa)

**Confirmado end-to-end, sesión 1:**
- [x] Las 19 imágenes compilan y construyen sin errores (`./gradlew compileJava` + `docker compose build` × 19)
- [x] Pipeline de trazas completo: request real → agente OTel → Collector (spans recibidos, log `TracesExporter` confirmado) → Tempo (`GET /api/search` devuelve trazas reales de `commission`, p. ej. `GET /actuator/health` con `traceID` real, 5ms de duración) → `GET /api/traces/{id}` devuelve el detalle completo del span
- [x] Prometheus: 18-20/20 targets `UP` de forma sostenida con el subconjunto estable (6 servicios de negocio + infra + observability corriendo)
- [x] JSON estructurado confirmado en logs reales de contenedor (`{"@timestamp":...,"service_name":"commission",...}`)

**Confirmado end-to-end, sesión 2 (retomada tras la pausa):**
- [x] **Correlación log↔trace bidireccional confirmada con datos reales.** Documento real en Elasticsearch: `{"trace_id":"3fb9d20fc41cc816e3df540df506bca7","span_id":"a1d72dc230e17aa0","trace_flags":"03","service_name":"identity-service","message":"Initializing Spring DispatcherServlet 'dispatcherServlet'",...}` — instrumentación MDC del agente de OTel confirmada, no solo asumida. **Ese mismo `trace_id` consultado en Tempo devolvió el span exacto** (`GET /actuator/prometheus`, 102ms) — confirma que el mecanismo "click en un log, salta a su traza" es real, no teórico.
- [x] **Dashboard de Grafana confirmado con datos reales, no solo provisionado.** Datasources (`Prometheus`/`Tempo`/`Elasticsearch`) confirmados conectados vía `/api/datasources`; dashboard "Fintech — Service Overview" (8 paneles) confirmado en el buscador (`/api/search`); las queries de throughput (`0.024 req/s`) y p95 (`0.071s`) del dashboard se ejecutaron literalmente a través del proxy de datasource de Grafana (no contra Prometheus directo) y devolvieron valores reales para `job="commission"`.
- [x] Elasticsearch reinició limpio en la segunda sesión (10,075 documentos indexados) sin volver a fallar por OOM con el límite de 1536M ya aplicado.

**Hallazgos reales de infraestructura, no relacionados con T7 pero que bloqueaban verificarlo con los 19 servicios a la vez:**
- **Postgres se queda sin conexiones** ("sorry, too many clients already") cuando los 19 servicios arrancan simultáneamente — cada uno abre su pool HikariCP contra el `max_connections` default (100). Corregido: `command: ["postgres", "-c", "max_connections=250"]`.
- **La asignación actual de Docker Desktop (7.65 GB) no alcanza para correr los 19 servicios + infra + el perfil `observability` completo a la vez de forma estable** — confirmado empíricamente con crash-loops reales (`exit 137`, OOM) rotando entre distintos contenedores bajo el arranque simultáneo de los 19. Con un subconjunto reducido (6 servicios de negocio + infra + observability) el stack corrió estable. **Recomendación (no aplicada — es config de Docker Desktop, no del repo):** subir la memoria de Docker Desktop a 16 GB (deja 8 GB de reserva para el host de 24 GB, tal como se pidió) vía Settings → Resources → Memory.
- Los builds de imágenes en paralelo (`docker compose up --build` sin especificar servicios) también agotaron memoria durante la construcción (`cannot allocate memory` en Gradle) — se resolvió construyendo secuencialmente, una imagen a la vez.

### Notas

- Sin alertas (Alertmanager/Grafana Alerting), sin Kibana, sin autenticación más allá del default de Grafana — decisiones de alcance explícitas. Ver `T7_observability.md` §Alcance excluido.
- **19/19 servicios de dominio (incluyendo T7 Observability como capa transversal) quedan con instrumentación completa y verificada.** Stack detenido de forma segura (`docker compose down`, volúmenes intactos) al cerrar la verificación — no queda corriendo en background.
- **Pendiente real, no bloqueante:** stress-testear con los 19 servicios de negocio a la vez (no solo el subconjunto de 6) requiere subir la memoria de Docker Desktop a ~16GB primero — confirmado empíricamente que 7.65GB no alcanza para eso. No se hizo en esta sesión porque es una acción manual fuera del repo.

---

## Verificación Final del Sistema

- [ ] `docker-compose up` → Postgres 16 + Kafka + servicios levantan sin errores
- [ ] Liquibase ejecuta todos los changelogs: 17 schemas creados correctamente
- [ ] Happy path completo: T1 login → D3 onboarding persona (Prospect) → D2 prefetch → D3 CreditApplication (elige producto) → D2 evaluación → D3 aprobación + contrato → D4 catálogo (snapshot) → D4★ credit-portfolio cuenta activa
- [ ] Ciclo de pago: D5 job accrual → D6 pago → D4★ credit-portfolio balance update via Kafka
- [ ] Ciclo de mora completo: D4★ delinquency job → D8 CollectionCase → D9 RiskProfile (stage) → D8 CollectionAgreement o WriteOff → D4★ aplica → D9 CLOSED → D8 reporte a buró
- [ ] `./gradlew test` completo → `ModularityTest` + todos los tests de módulos pasan
- [ ] `docker build -t fintech-services .` → imagen construida correctamente

---

## Changelog de Implementación

| Fecha | Módulo | Cambio |
|---|---|---|
| 2026-05-14 | Fase 0 | Scaffold completo: Gradle, Spring Modulith, 15 módulos stub, Docker, Liquibase master, shared kernel |
| 2026-05-14 | T1: Identity & Auth | Implementación completa: dominio, aplicación, infraestructura, API, tests unit + WebMvcTest |
| 2026-05-15 | D3: Origination | Subdominio application-intake: Prospect entity, ProspectService, POST /prospects, ProspectCreatedEvent (Kafka) con address completo + prospectType + productTypeIntent. 19 tests (unit + WebMvc + Testcontainers) |
| 2026-05-16 | D2: Scoring — CDC | BureauPrefetch + CirculoReport (5 tablas hijas), CirculoCreditoAdapter (REST ACL, sanitización UTF-8), ProspectCreatedEventListener. Migraciones 001–004 |
| 2026-05-17 | D2: Scoring — Motor | ScoringPolicy/Rule/RiskThreshold/ScoreEvaluation, ScoringRuleEvaluator (pure Java, 5 RuleType, disqualifying, FICO aditivo), ScoringEvaluationService (REQUIRES_NEW), ScoringPolicyService, REST API /policies + /evaluations. Migraciones 005–007 con seed policy INDIVIDUAL/PERSONAL_LOAN |
| 2026-05-18 | D2: Scoring — Tests | ScoringRuleEvaluatorTest (15 casos), ScoringEvaluationServiceTest (9 casos), BureauPrefetchServiceTest ampliado (scoring isolation), ProspectCreatedEventListenerTest. Docs actualizados: 02_scoring_domain.md + scoring README + tracker |
| 2026-06-04 | T5: Configuration | Implementación completa: dominio (ConfigParameter + ConfigAuditTrail, estado DRAFT/PENDING_APPROVAL/ACTIVE/DEPRECATED), application (4 use cases, Redis cache con @Cacheable/@CacheEvict), infraestructura (JWT filter, JPA adapters, KafkaConfigEventPublisher, CacheConfig, SecurityConfig), REST API (4 endpoints), Liquibase (2 tablas + event_publication + 7 seed params), tests (7 unit + 7 WebMvcTest + 5 Testcontainers). Publica `configuration.configuration-updated`. Puerto 8086. |
| 2026-06-05 | D2: Scoring — fix JSONB | `ScoreEvaluation.ruleDetails` migrado de `RuleDetailsConverter` (AttributeConverter→String) a `@JdbcTypeCode(SqlTypes.JSON)` nativo. El converter enviaba `varchar` y rompía el INSERT en Postgres real (`ScoringFlowIT` lo detectó tras habilitar Docker). Converter eliminado. scoring 49/49 ✅ |
| 2026-06-05 | Infra de tests | Fix de 2 blockers pre-existentes: `services/identity-service/application-test.yml` apuntaba a `db.changelog-identity.xml` (era `.yaml`); `party-service` y `scoring-service` build.gradle.kts sin `systemProperty("api.version","1.44")` (Docker Desktop). identity 71 ✅, party 9 ✅ |
| 2026-06-05 | D3: Origination — Fase A+B | Re-orientación implementada: `Prospect` sin `productTypeIntent` (migración 005), nuevo agregado `CreditApplication` (migración 006) → emite `ScoreRequested`. README reescrito. origination 43/43 ✅ |
| 2026-06-05 | D3: Origination — Fase C | Consumer `scoring.scoring-completed` (`ScoringCompletedEventListener` + `KafkaConfig` consumer) → transiciona la CreditApplication por `(prospectId, productType)`. `ApplyScoringDecisionUseCase`. `ScoringDecisionFlowIT` (EmbeddedKafka). origination 51/51 ✅ |
| 2026-06-05 | D2/D3: Fase D — re-orientación scoring | scoring `prospect-created`→prefetch-only (quitado `triggerScoring`); nuevo `ScoreRequestedEventListener` (`origination.score-requested`)→`evaluateForApplication` (reusa reporte SO-02); `ScoringCompletedEvent` + `apply()` ahora llevan/usan `applicationId` (correlación directa, fallback prospect+producto). `ScoringFlowIT` reescrito al flujo de 2 pasos. scoring 48/48 ✅, origination 52/52 ✅ |
| 2026-06-04 | Arquitectura (ADR-001) | **Solo documentación.** Pivot narrativo monolito modular → microservicios desacoplados. **Split D4 Credit Product** → `credit-product` (catálogo/fábrica, agregado `ProductDefinition`) + `credit-portfolio` ★ (corazón/cuentas vivas, agregado `CreditAccount`). **Re-orientación D3**: `Prospect` (onboarding persona, sin producto, trigger solo prefetch) ≠ `CreditApplication` (selección de producto → `ScoreRequested` → decision engine). Nuevos eventos: `ScoreRequested/ScoreGenerated/ScoreRejected/ScoreFailed`, `ProductDefined/Versioned`. 15→16 servicios. Docs actualizados: ADR-001, 03/02/04/04b dominios, core_crediticio_dominios, PROJECT_ANALYSIS, tracker. |
| 2026-06-08 | D4: Credit Product — catálogo completo | Versionado (partial unique `WHERE status='ACTIVE'` + `productVersion`), `capabilities` JSONB, `rate_cards` (bandas tier/monto/plazo), `eligibility_rules`, `amountStep` (montos múltiplos limpios), tipos B2B (`SME_LOAN`, `BUSINESS_REVOLVING_LINE`), 9 seeds (B2C+B2B2C+B2B), Kafka (`product-catalog.product-activated/retired`), JWT (GET público / mutations auth). Fix `LazyInitializationException` (ElementCollections→EAGER) + doble bean `CreditProductProperties` + orden migración payment_frequencies + flush versionado. **62 tests ✅** (30 unit + 23 IT, +9). |
| 2026-06-08 | D3: Origination — Paso 4 (E+F+G) | Offer management (CAT BdM), contract management (firma), snapshot `CreditProductCreationRequested` (con `productVersion`), consumer `credit-account-activated`→DISBURSED. WebMvc + `ContractToPortfolioFlowIT`. **82 tests ✅** |
| 2026-06-10 | D4★: Credit Portfolio — Fase 0/1 config-driven | Read-model versionado `product_config_versions` (proyecta `product-catalog.product-activated/retired`), `ProductConfigResolver` (pin/latest/degraded+reconciliación), motor por `capabilities` (no string), `AmortizationEngine` FRENCH/GERMAN/BULLET × WEEKLY/BIWEEKLY/MONTHLY, `productVersion` propagado catálogo→origination→portfolio y fijado en `CreditAccount`. Modelo: versión-referencia inmutable, no copia congelada. |
| 2026-06-10 | D4★: Credit Portfolio — Fase 2 balance engine | `BalanceReconciliationService` enruta charges/payments/collections → aplica saldos (jerarquía penalty→interest→principal), `balance_events` (auditoría inmutable + idempotencia por `source_event_id`), `CreditAccount` muta saldos (accrual/penalty/payment/reverse/writeOff/settle), publica `credit-portfolio.balance-updated`. **52 tests ✅** (40 unit + 6 IT, +6 nuevos). |
| 2026-06-10 | Infra de tests — Testcontainers determinista | **Causa raíz:** `~/.testcontainers.properties` `docker.host` apuntaba a un socket muerto (`docker.raw.sock` legacy de Docker Desktop) → hang intermitente en init de Testcontainers (portfolio/origination sin socket override). **Fix:** corregido el `docker.host` al socket vivo (`~/.docker/run/docker.sock`) + defensa en build.gradle de los 3 servicios (`DOCKER_HOST=unix:///var/run/docker.sock` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`). Verificado: contenedor arranca en ~5-10s, 2 corridas consecutivas verdes. |
| 2026-06-26 | credit-portfolio — balance versioning + payment-rejected | `balanceVersion` (long, auto-increment en cada mutación) añadido a `CreditAccount` + `BalanceUpdatedEvent`. `BalanceReconciliationService.onPaymentApplied` valida `amount ≤ totalDebt`, publica `payment-rejected` si no. Nuevo `PaymentRejectedEvent`. Migración `008-add-balance-version.sql`. |
| 2026-06-26 | D6: Payments — Doble validación de saldo | `payments` service completo: `PaymentOrder` (PENDING→CONFIRMED/REJECTED/REVERSED), `AccountBalanceSnapshot` (read model de balance-updated), `PaymentService` (submit con PRE-CHECK, confirmByEventId, reverse), `BalanceSnapshotService` (upsert/init), Kafka in: `balance-updated`/`credit-account-activated`/`payment-rejected`, out: `payment-applied`/`payment-returned`, REST 5 endpoints (submit+list+get+balance+reverse), JWT, Liquibase 4 migraciones, `application.yml` puerto 8089. Tests: `PaymentServiceTest` (6 casos), `BalanceSnapshotServiceTest` (4 casos), `PaymentsControllerTest` (5 casos). |
| 2026-06-26 | D5: Charges — Motor de devengamiento completo | Implementación completa: dominio (`AccrualSchedule` + `ChargeRecord`, enums, 4 excepciones), application services (`AccrualScheduleService`, `InterestAccrualService`, `ChargeReversalService`), puertos out (3 interfaces), infraestructura (`JpaAccrualSchedule/ChargeRecordRepository`, `KafkaChargesEventPublisher`, Kafka listeners `CreditAccountActivated/BalanceUpdated`, REST `ChargesController`, `JwtAuthenticationFilter`, `SecurityConfig`, `KafkaConfig`, `OpenApiConfig`), jobs (`DailyAccrualJob` 23:00 + `MoratoriumAccrualJob` 23:30 con aislamiento de tx por schedule), Liquibase 4 migraciones (schema+2 tablas+event_publication), `application.yml` puerto 8088. Tests: `AccrualScheduleServiceTest` (5 casos), `InterestAccrualServiceTest` (5 casos), `ChargesControllerTest` (5 casos WebMvcTest), `ChargesAcceptanceTest` (@EmbeddedKafka+@Testcontainers). `CreditAccountActivatedEvent` actualizado en credit-portfolio (+`moratoriumRate`+`openingFeeRate`). Registrado en `settings.gradle.kts` + `docker-compose.yml`. |
| 2026-06-27 | GW: API Gateway + Rate Limiting | `services/gateway-service/` creado con OpenResty: `nginx.conf` (10 upstreams, 6 zonas `limit_req_zone`, `limit_req` en todas las rutas públicas, 429 JSON + `Retry-After: 60`, DENY BY DEFAULT), `conf.d/jwt.lua` (RS256 + cache por worker + headers X-User-Id/X-Roles/X-Token-Jti). `docker-compose.yml` actualizado: red `fintech-network` explícita, servicio `gateway-service` puerto 8080:80, puertos de microservicios eliminados. **Bloqueado** hasta migración HS256→RS256 de identity-service. |
| 2026-07-03 | GW: Gateway + BFF endpoints | RS256 operativo, audit logs enriquecidos, GET /users/me + GET /credit/account, header-trust en credit-portfolio. |
| 2026-07-03 | T3: Audit & Compliance — implementación completa | audit-service: AuditEntry (append-only, 16 Kafka listeners: origination×4 + scoring×2 + credit-portfolio×4 + charges×2 + payments×2 + configuration×1 + credit-product×2), DocumentFileRef (retención regulatoria por tipo), UIFReport (INUSUAL/RELEVANTE/INTERNO), API REST (GET /entries con filtros partyId/aggregateId/eventType/dateRange, GET /entries/{id}, GET /parties/{id}/documents, GET /parties/{id}/uif-reports, GET /parties/{id}/expedition), SecurityConfig AUDITOR/REGULATOR/ADMIN roles, Liquibase 5 migraciones (schema+3 tablas+event_publication). 12 tests ✅ (5 unit + 7 WebMvcTest). Puerto 8090. |
| 2026-07-03 | tracker | GW actualizado a ✅ Completo; T3 actualizado a ✅ Completo. |
| 2026-07-04 | D1: Channels — implementación completa | channels-service: Channel (8 tipos, seed Liquibase), Session (ACTIVE/IDLE/EXPIRED/CLOSED, S-03 one-per-party+channelType con expiración automática de sesión anterior), CustomerIntent (IR-02 blacklist via party-service ACL, IR-03 allowedIntents, DomainTarget routing), LeadRequest (promotor campo, TTL 7d/30d), 8 eventos Kafka (channels.session-started/expired, intent-captured/routed/abandoned, application-started, lead-created/converted), REST 10 endpoints, SecurityConfig header-trust, Liquibase 6 migraciones + seed 8 canales. Puerto 8091. 13 tests ✅ (5 unit + 8 WebMvcTest). |
| 2026-07-04 | D3: Origination — consumer channels.application-started | `ApplicationStartedEventListener` + `PartyReader` port + `RestClientPartyAdapter` ACL (resuelve partyId→prospectId vía party-service REST). Fail-closed: sin prospectId no se crea aplicación. Idempotencia ante `DuplicateActiveApplicationException`. `CooldownActiveException` logueado como warning. `PARTY_SERVICE_URL` en OriginationProperties + RestClientConfig + application.yml + docker-compose. Fix pre-existing: `synchronizedList` sin `new`, `ChannelType.WEB` (era WEB_APP), import `List` en AcceptanceTest, `containsIgnoringCase` en assertion. 7 tests ✅ (unit Mockito). |
| 2026-07-04 | D1: Channels — DeviceContext enriquecido | `DeviceContext @Embeddable` con 18 campos: ID, tipo/modelo/fabricante, OS/versión, app/SDK version, red, userAgent, browser/versión, IP, país, isTrustedDevice, isRooted, isEmulator. IP siempre server-side: cadena `X-Forwarded-For[0]`→`X-Real-IP`→`RemoteAddr` en `ClientIpExtractor` (util público). Inferencia de deviceType y parseo browser desde User-Agent. `X-Country-Code` inyectado por gateway desde GeoIP. Migración 007: 14 columnas + 3 índices anti-fraude (ip_address, device_id, rooted+emulator). `SessionControllerIpTest` (7 casos IP). Total 20 tests ✅. |
| 2026-07-05 | D4★: Credit Portfolio — jobs nocturnos | `DelinquencyCalculationJob` (`@Scheduled` 23:59) + `DelinquencyAccountProcessor` (`REQUIRES_NEW` por cuenta): recalcula `daysDelinquent` desde la cuota PENDING más antigua con `dueDate < today`, publica `credit-portfolio.delinquency-status-updated`. `InstallmentDueJob` (`@Scheduled` 00:01): encuentra PENDING con `dueDate = today`, publica `credit-portfolio.installment-due`. Nuevos puertos: `CreditAccountRepository.findAllByStatus()`, `InstallmentRepository.findPendingOverdueByScheduleId()`, `findDueToday()`. Eventos: `DelinquencyStatusUpdatedEvent`, `InstallmentDueEvent`. `@EnableScheduling` en `CreditPortfolioApplication`. Migración `009`: índices en `credit_accounts(status)` + `installments(due_date, status)`. 9 tests ✅ (5 DQ + 4 ID). |
| 2026-07-05 | D6: Payments — reglas de negocio D6 completas | **PY-05** WRITTEN_OFF/CLOSED guard: `AccountBalanceSnapshot.isAccountActive()` (ACTIVE/SUSPENDED=true) rechaza antes de procesar. **PY-06** Overpayment handling: enum `OverpaymentStrategy` (RETURN_TO_PAYER default / APPLY_NEXT_INSTALLMENT), configurable en `PaymentsProperties.overpaymentStrategy`; RETURN_TO_PAYER aplica solo totalDebt y publica `payment-returned` para el exceso de inmediato; APPLY_NEXT_INSTALLMENT reenvía monto completo. `PaymentOrder` extendido: `requestedAmount` + `overpaymentStrategy` + `getExcessAmount()`. Migración `005-add-overpayment-columns.sql`. **PY-07** VENTANILLA no reversible: `PaymentMethod.allowsReversal()`. **PY-08** Ventana configurable `reversalWindowHours=72` en `PaymentsProperties`. `canAcceptPayment()` actualizado: solo rechaza ≤0 (overpayment manejado en servicio). Tests: 6→23 ✅ (+PY-T07/08/09/10/11/12/13, +`isAccountActive`, +`canAcceptPayment` con nueva semántica). |
| 2026-06-26 | D5: Charges — Doble validación de saldo (mismo patrón que Payments) | `AccountBalanceSnapshot` (entity, repo port, JPA adapter, migración `005-create-account-balance-snapshots.sql`), `BalanceSnapshotService` (initSnapshot/upsert/find), `BalanceUpdatedPayload` ampliado (obligorPartyId + full balance + balanceVersion), `BalanceUpdatedListener` llama snapshotService.upsert(), `CreditAccountActivatedListener` llama snapshotService.initSnapshot(), pre-check en `AccrualScheduleService.chargeOpeningFee()` (snapshot.isChargeable()), `ChargeRejectedPayload` + `ChargeRejectedListener` (reversa ChargeRecord al recibir post-check fallido desde credit-portfolio), `KafkaConfig` + factory `chargeRejectedListenerContainerFactory`, `GET /api/v1/charges/accounts/{id}/balance` endpoint. credit-portfolio: `ChargeRejectedEvent` + `publishChargeRejected()` + `onChargeApplied` publica charge-rejected si cuenta en estado terminal. |
| 2026-06-26 | D3: Origination — Fase H (aprobación manual/comité) | Cerró el pendiente que quedó abierto desde 2026-06-08 ("aprobaciones manuales/comité"). Nuevo `ApprovalFlow` (AUTOMATIC/MANUAL/COMMITTEE), `RecordApprovalDecisionUseCase` + `RecordApprovalDecisionCommand`, `UnderwritingController` (`POST /applications/{id}/decision`, `GET /applications?prospectId=` filtrado a `UNDER_MANUAL_REVIEW`/`COMMITTEE_REVIEW`), `ApplicationApprovedEvent`/`ApplicationRejectedEvent`, `rejectionReason` obligatorio si `approved=false` (UW-05/CONDUSEF). `OfferExpirationJob` (expira `OFFER_PRESENTED` vencidas → `OFFER_EXPIRED`). Migración `009-add-approval-fields.sql` (decidedBy, rejectionReason, rejectedAt). `UnderwritingControllerTest` + `OfferExpirationJobTest`. Consolidado y probado end-to-end el 2026-07-06 (`CreditApplicationService`, `OriginationExceptionHandler`, `ContractToPortfolioFlowIT`, `UnderwritingApprovalFlowIT`) → origination 82→119 tests ✅. |
| 2026-07-06/07 | D7: Wallet — implementación completa | **Nuevo servicio** (no tenía entrada previa en este changelog aunque ya figuraba ✅ en Estado General). `WalletView` (proyección read-only de saldos/producto sincronizada por eventos de credit-portfolio, WV-01), `PaymentInstruction` (PENDING→SENT/CANCELLED/EXPIRED, PI-06 partial unique index por (creditAccountId, paymentMethod), PI-03 DOMICILIACION requiere CLABE), `DispositionOrchestrationService` (fail-fast `amount ≤ availableCredit` antes de emitir `DispositionRequested`, DO-02/DO-05). 3 Kafka listeners in (`credit-account-activated`, `balance-updated`, `installment-due`) + 3 publishers out (`payment-instruction-created`, `disposition-requested`, `snapshot-updated`), 4 changelogs Liquibase, 3 endpoints REST, JWT filter, puerto propio en `docker-compose.yml`. Tests: `WalletProjectionServiceTest` (6), `PaymentInstructionServiceTest` (5), `WalletControllerTest` (9 WebMvcTest), `WalletAcceptanceTest` (6 Testcontainers AC-1..AC-6) — **26 tests ✅**. **Gap detectado en este mismo repaso:** credit-portfolio no tiene listener para `wallet.disposition-requested` — el evento se publica pero nadie lo consume todavía (ver Módulo 9 §Notas). **Resuelto 2026-07-08 — ver fila siguiente.** |
| 2026-07-08 | D7 Wallet + D4★ Credit Portfolio — Wallet pasa a cuenta digital con saldo propio, cierre del gap de disposición | Repensado a partir de la pregunta: "¿el wallet es un mecanismo de tarjeta de débito o cuenta digital?". Decisión: `walletBalance` se acredita al disponer contra la línea (SELF_USE), sigue siendo proyección (acumula 2 flujos de eventos, no espejea 1) — no se construyó un dominio de captación/débito real (no existe en ningún otro servicio, requeriría licencia distinta). **Bug encontrado y corregido en el camino:** `CreditAccountService.activate()` disbursaba el límite completo de revolventes al originar, dejando `availableCredit=0` desde el día uno — `resolveAmount()` ahora devuelve `ZERO` para `hasCreditLimit`, la cuenta abre con cupo completo disponible (no-revolventes sin cambio). **credit-portfolio:** nuevo `ProcessDispositionUseCase` (consume `wallet.disposition-requested`, idempotente por `sourceEventId` vía `BalanceEventRepository`) — SELF_USE aplica disposición sin SPEI (queda en la plataforma), THIRD_PARTY_CREDIT/PAYROLL aplica + SPEI real a `payeeAccount`; guardas SUSPENDED/terminal/`amount>availableCredit`/CP-05 publican `disposition-rejected` en vez de perder el mensaje; nuevo `CreditAccount.applyDisposition()`; nuevos eventos `DispositionCompleted`/`DispositionRejected`; migración `010-add-disposition-source-event-id.sql`. **wallet:** `WalletView.walletBalance` (migración `005`) + `credit()`/`debit()`; nuevo listener `DispositionCompletedListener` (credita solo SELF_USE); nuevo agregado `WalletWithdrawal` (migración `006`) + `WalletWithdrawalService` + `NoopWalletDispatchAdapter` + endpoint `POST /withdrawals`; `dispositionRequestId` añadido a `DispositionRequested` como idempotency key. **Wallet general por party:** `WalletViewRepository.findByObligorPartyId`, `GET /wallet?partyId=` y `GET /wallet/summary?partyId=` (rollup calculado en el momento, no almacenado) — un party con varios productos de crédito activos ahora se ve como un solo wallet con varios instrumentos, discriminados por `productType` existente (sin agregar un tipo de instrumento nuevo). Tests: credit-portfolio 80→87 (`CreditAccountServiceTest` 5→12), wallet 26→38 (`WalletProjectionServiceTest` +4, nuevo `WalletWithdrawalServiceTest` +3, `WalletControllerTest` +5). `WalletAcceptanceTest` (Testcontainers) no se corrió — sin Docker en el entorno de desarrollo; pendiente verificación end-to-end real con `docker-compose up`. |
| 2026-07-08 | D8 Collections robustecido + **nuevo D9: Risk** — solo diseño/spec, sin código todavía | A petición explícita: cobranza temprana, etapa IFRS-9 por cuenta, estimación preventiva de reservas (EPR/ECL) con algoritmo predecible e interpretable, y capacidad de generar reestructuras/quitas + reportarlas a Círculo de Crédito. **Decisión — dominio nuevo (D9 Risk), no dentro de credit-portfolio ni Collections:** tres razones de cambio distintas (ledger / operación de cobranza / metodología contable-regulatoria), mismo criterio que ya separó `credit-product` de `credit-portfolio` en ADR-001 — 16→17 servicios. **Decisión — algoritmo EPR:** tabla de tasas `(productType, bucket) → %` versionada (`ProvisionPolicy`, mismo patrón que `ScoringPolicy` de D2), NO un modelo estadístico — es la interpretación directa de "predecible e interpretable" que pidió el usuario; PD×LGD queda como extensión aditiva futura, no se construye sin datos históricos de pérdida. **D9 Risk (spec completa, `docs/dominios/09_risk_domain.md` + Módulo 17 del tracker):** `RiskProfile` (bucket derivado localmente de `daysDelinquent`, `ifrs9Stage` STAGE_1/2/3 con corte de 90d como presunción rebatible de default IFRS-9/Basel, forbearance fuerza STAGE_2 mínimo `cureMonths`), `ProvisionPolicy`/`ProvisionRateBand` versionados, job nocturno `RiskAssessmentJob` (full-recompute, después del `DelinquencyCalculationJob` de credit-portfolio), evento único `RiskAssessmentUpdated` (siempre, no solo en transición — Accounting necesita el monto cada período). Sin ACL síncrona — 100% event-driven. Distingue explícitamente `riskTier` (Scoring, estático al originar) de `ifrs9Stage` (Risk, dinámico). **D8 Collections robustecido (`docs/dominios/08_collections_domain.md` + Módulo 13 del tracker):** buckets extendidos (B91_PLUS dividido en B91_120/B121_180/B181_PLUS para granularidad de reserva); cobranza temprana nueva (`PreDueReminderTriggered` desde un `UpcomingInstallmentJob` T5-configurable en credit-portfolio, sin segmentación de riesgo en v1); nuevo agregado `CollectionAgreement` (RESTRUCTURE\|QUITA_PARCIAL, ciclo PROPOSED→ACCEPTED→EXECUTED, unifica ambos porque son acuerdos bilaterales con el deudor — distinto de `WriteOff` que es unilateral); terminología explícita "quita total" = write-off; nuevo `BureauReport` + `BureauReportingAdapter` (stub, mismo patrón que `NoopSpeiDispatchAdapter`) para reportar write-offs y quitas parciales al Círculo de Crédito, con reintento indefinido (obligación regulatoria, no best-effort). **Prerequisitos anotados en Módulo 9 (credit-portfolio):** `UpcomingInstallmentJob` nuevo, y consumer nuevo para `collections.collection-agreement-executed` (RESTRUCTURE aplica nuevos términos, QUITA_PARCIAL reduce saldo sin llegar a cero) — ninguno de los dos existe todavía. **Alcance de esta sesión:** solo documentación/diseño (`docs/dominios/08_collections_domain.md` reescrito, `09_risk_domain.md` nuevo, `core_crediticio_dominios.md` actualizado, tracker Módulos 13/17 + Estado General) — sin código, sin nuevo módulo Gradle, a la espera de confirmación explícita para implementar. |
| 2026-07-10 | T4 Accounting/GL — revisión holística del impacto de Wallet/Collections/Risk — solo diseño/spec | A petición explícita de revisar el impacto en contabilidad de los cambios del 2026-07-08/09. **3 hallazgos nuevos, ninguno cubierto por el spec original de T4:** (1) Wallet con `walletBalance` propio rompe el supuesto "todo desembolso sale por SPEI" — nueva cuenta de pasivo **"Fondos de clientes por disponer"**: `DISPOSITION_SELF_USE` acredita ahí en vez de Bancos; `wallet.withdrawal-completed` (nuevo consumo) la liquida cuando el cliente retira; nueva conciliación diaria GL-07 (saldo GL vs Σ `walletBalance` de Wallet). (2) D9 Risk publica el **monto absoluto** de provisión cada noche, no el delta — nuevo read-model local `ProvisionLedger` (creditAccountId, lastBookedProvision) para asentar solo el cambio (GL-09); nueva conciliación GL-11 (reserva GL vs agregado de Risk). (3) `CollectionAgreementExecuted` (D8, nuevo) no estaba en el catálogo original: QUITA_PARCIAL sigue la misma lógica que quebranto pero parcial, y ahora **consume primero la reserva ya provisionada para esa cuenta** antes de golpear P&L directo por el excedente (GL-10, más preciso que el spec viejo que asumía reserva siempre suficiente); RESTRUCTURE se dejó **sin asiento contable al ejecutarse** (GL-08) — decisión de alcance explícita: tratarlo como modificación no sustancial evita construir un motor de NPV/flujos descontados que no existe en el sistema, documentado como limitación conocida a revisar antes de auditoría externa. **Simplificación de integración:** T4 pasa a consumir `credit-portfolio.balance-updated` como fuente primaria (ya trae `triggerEvent` etiquetando cada evento económico) en vez de suscribirse por separado a Charges/Payments/credit-portfolio como asumía el spec original — menos acoplamiento, cero eventos económicos fuera de cobertura porque credit-portfolio ya los centraliza. Reconciliación diaria pasa de 1 check a 3 (cartera, fondos de cliente, reserva). Actualizado: `docs/dominios/T4_accounting_gl.md` (reescrito) y tracker Módulo 15 — sin código, T4 sigue ⬜ Pendiente. |
| 2026-07-10 | D8: Collections — implementación completa (**nuevo servicio**, 17º) | A petición explícita de continuar con la spec robustecida del 2026-07-08. **Nuevo módulo Gradle `collections`** (puerto 8093, siguiente libre tras wallet 8092 — corrige el puerto de D9 Risk en el tracker, que decía 8092 desde antes de que Collections existiera, ahora 8094). Dominio: `CollectionCase` (CC-01 una activa por cuenta, escalación automática OPEN→MANAGED, `DelinquencyBucket` de 7 niveles), `PaymentPromise` (PP-01), `ContactAttempt` (CONDUSEF: 3/día, 08:00–20:00), `WriteOffRecord` (quita total, unilateral, sin persistencia hasta la aprobación — WO-01/WO-02), `CollectionAgreement` (reestructura/quita parcial, bilateral, PROPOSED→ACCEPTED→EXECUTED, `RestructureTerms` como JSONB nativo), `BureauReport` (reintento indefinido, obligación regulatoria). **Gap de datos resuelto:** `DelinquencyStatusUpdated` no trae `productType`/`totalDebt`/split de saldo — nuevo read-model local `AccountBalanceSnapshot` alimentado por `credit-account-activated` + `balance-updated` (mismo patrón que charges/payments/wallet). **Desviación real de spec confirmada al implementar:** `DelinquencyCleared` y `ProductSettled` nunca existieron como eventos propios de credit-portfolio — se derivan de `DelinquencyStatusUpdated(days=0)` y `BalanceUpdated(accountStatus=SETTLED)` respectivamente (pendiente corregir `08_collections_domain.md`, que aún describe la versión con eventos separados). 4 jobs nocturnos (`PromiseBrokenCheckJob`, `AgreementExpirationJob`, `WriteOffCandidatesJob`, `BureauReportingJob`), 11 endpoints REST, 5 Kafka listeners in + 12 publishers out. **Dos prerequisitos cerrados en credit-portfolio (D4★):** `UpcomingInstallmentJob` (nuevo, T-lead_days antes de `InstallmentDueJob`) y `ProcessAgreementExecutedUseCase`/`CollectionAgreementExecutedListener` (QUITA_PARCIAL aplica `CreditAccount.applyForgiveness`; RESTRUCTURE aplica `CreditAccount.applyRestructure` — solo tasa/plazo, sin regenerar calendario, misma limitación documentada que la decisión GL-08 de T4). Tests: `CaseManagementServiceTest` (7, incl. 1 de concurrencia), `PromiseServiceTest` (8), `ContactServiceTest` (4), `AgreementServiceTest` (9), `WriteOffServiceTest` (5), `BureauReportingServiceTest` (5, incl. 1 de concurrencia), `EarlyCollectionsServiceTest` (2) — **40 tests ✅** (incluye 2 pruebas de thread-safety con `ExecutorService`+`CountDownLatch`, mismo patrón que `BalanceReconciliationServiceTest` de credit-portfolio); más 3 tests nuevos en `CreditAccountServiceTest` (credit-portfolio) para `ProcessAgreementExecutedUseCase` — **82 tests ✅** en credit-portfolio (79 unit + 3 IT sin Docker, sin regresiones). Acceptance/IT con Testcontainers no escritos en esta sesión (sin Docker en el entorno). Actualizado: tracker Módulos 9, 13 y 17 (puerto), Estado General. |
| 2026-07-11 | Barrida de tests E2E (aceptación + integración) en todos los servicios | A petición explícita de revisar todos los servicios y dar cobertura de aceptación/integración pareja. Con Docker Desktop encendido se corrieron los Testcontainers reales (Postgres 16 + EmbeddedKafka). **54 tests E2E nuevos** en 10 servicios: `CollectionsAcceptanceTest` (9) + `CollectionsFlowIT` (2); `PaymentsAcceptanceTest` (8) + `PaymentsFlowIT` (2); `AuditAcceptanceTest` (6) + `AuditFlowIT` (1); `ChannelsAcceptanceTest` (7) + `ChannelsFlowIT` (1); `PartyManagementAcceptanceTest` (6); `ScoringAcceptanceTest` (3); `CreditPortfolioAcceptanceTest` (6); `ChargesFlowIT` (1); `WalletFlowIT` (1); `ConfigurationFlowIT` (1). **3 servicios con CERO cobertura E2E previa cerrados** (collections, payments, audit, channels — este último tenía 0). **3 bugs latentes de producción encontrados y corregidos** (nunca detectados porque los tests previos eran unitarios/WebMvcTest, sin arranque JPA real): (1) `channels.sessions.ip_country` era `CHAR(2)` (bpchar) vs la entidad `varchar` → Hibernate `validate` fallaba al bootear con schema real; corregido a `VARCHAR(2)`. (2) `channels` tenía `spring-modulith-starter-jpa` pero nunca creó la tabla `event_publication` → fallaba al arrancar con `ddl-auto=validate`; añadido `008-create-event-publication.sql`. (3) `scoring` `GET /policies` y `/policies/{id}` devolvían **500** por `LazyInitializationException` en `ScoringPolicy.rules`/`thresholds` (dos bags LAZY serializadas fuera de sesión, `open-in-view=false`); corregido con `@Transactional(readOnly=true)` + inicialización en sesión (no EAGER: dos bags darían `MultipleBagFetchException`). **Cobertura por servicio ahora:** cada servicio implementado tiene contrato HTTP (acceptance) y, donde aplica, flujo de eventos (FlowIT). Excepciones razonadas: `channel-mobile` no usa Kafka (BFF puro REST — su `OnboardingAcceptanceTest` es cobertura completa); `credit-product` ya tenía `CreditProductCatalogIT` (acceptance HTTP de facto, 20+ casos) — no se duplicó; `identity` queda cubierto por `ClientAuthAcceptanceTest` (register→authenticate→tokens con Redis Testcontainer) — un FlowIT de emisión de `identity.login-attempted` queda como trabajo futuro opcional. Totales por módulo tras la barrida (todos verdes): collections 51, payments 33, audit 19, channels 29, party 32, scoring 51, credit-portfolio 96, charges 27, wallet 39, configuration 22. Sin regresiones en los módulos con código productivo tocado (scoring servicio, channels migración). |
| 2026-07-12 | D9: Risk — implementación completa (**nuevo servicio**, se agrega a `settings.gradle.kts`) | Ejecutando la spec del 2026-07-08 (`09_risk_domain.md` + Módulo 17). **Nuevo módulo Gradle `risk`** (puerto 8094), misma estructura hexagonal desacoplada y patrones que el resto (ports/adapters, header-trust JWT, Kafka JSON con `USE_TYPE_INFO_HEADERS=false`, Liquibase `--formatted sql`, Modulith `@ApplicationModule`). Dominio: `RiskProfile` (raíz 1:1 con CreditAccount — bucket derivado local RC-01, `ifrs9Stage` STAGE_1/2/3, forbearance/cura RC-04, STAGE_3 sticky RC-05, close congela RC-06), `ProvisionPolicy` versionada (una ACTIVE por productType PP-01, `rateBands` EAGER — un solo bag, evitando el bug de LazyInit que encontré en scoring), `ProvisionRateBand` (7 buckets obligatorios PP-02), `Ifrs9StageResolver` (función pura de staging). Algoritmo EPR = **tabla de tasas determinística** `(productType, bucket) → %` (RC-07, "predecible e interpretable"), `provisionAmount = ead × rate` (RC-02). Job nocturno `RiskAssessmentJob` (01:00, full-recompute con aislamiento por cuenta vía `RiskProfileAssessor` `REQUIRES_NEW`) publica `risk.assessment-updated` por cuenta siempre (PR-01) — Accounting necesita el monto cada período. 4 listeners in (`credit-account-activated`, `balance-updated`, `delinquency-status-updated`, `collections.agreement-executed` solo RESTRUCTURE), 6 endpoints REST, 6 migraciones + seed placeholder (PERSONAL_LOAN/REVOLVING_CREDIT/PAYROLL_LOAN/SME_LOAN). **Misma desviación real de spec que Collections:** `DelinquencyCleared`/`ProductSettled`/`ProductWrittenOff` no existen como eventos — derivados de `DelinquencyStatusUpdated(days=0)` y `BalanceUpdated.accountStatus`. Tests: `Ifrs9StageResolverTest` (13), `RiskProfileTest` (6), `ProvisionPolicyServiceTest` (3), `RiskProfileAssessorTest` (3), `RiskAssessmentServiceTest` (2), `RiskProfileServiceTest` (8), `RiskAcceptanceTest` (9, Testcontainers), `RiskFlowIT` (2, Testcontainers) — **46 tests ✅** verificados con Docker. `risk.assessment-updated` queda publicado sin consumidor hasta que exista T4 Accounting. Actualizado: `settings.gradle.kts`, `docker-compose.yml`, tracker Módulo 17 + Estado General. **14/17 servicios de dominio completos** (faltan T2 Notifications, T4 Accounting, T6 Commission). |
| 2026-07-12 | T4 Accounting + nuevo servicio de Facturación (CFDI) + Party fiscal | A petición del usuario, con dos decisiones de negocio confirmadas primero (análisis antes de código): **(1) asientos a nivel préstamo** (auxiliar CNBV R04-C / IFRS-9 por instrumento, agregado al mayor) y **(2) facturación consolidada un CFDI por party/período** (el receptor fiscal es el party por RFC — un préstamo no es entidad fiscal, y "negocio" no es un receptor distinto en México). **T4 Accounting (`accounting`, puerto 8095):** libro mayor de partida doble; el monto de cada asiento se deriva del **delta** de saldos (`AccountBalanceShadow` local) porque `balance-updated` solo trae saldos nuevos; catálogo de cuentas + reglas de posteo `triggerEvent → (cargo, abono)` seedeadas y configurables (T5); provisión EPR asentada por **delta** contra `ProvisionLedger` (GL-09) y quebranto/quita **consumen la reserva provisionada antes de golpear P&L** (GL-10, `PostingService.postWriteOffOrQuita`); reconocimiento de ingresos → `InvoiceableItem` acumulados y `BillingRunJob` (mensual) emite `accounting.invoice-requested`. 4 listeners (`balance-updated`, `risk.assessment-updated`, `recovery-payment-applied`, `wallet.withdrawal-completed`), 4 endpoints (auxiliar por crédito/party, balanza/mayor, corrida de facturación). **Facturación (`invoicing`, puerto 8096, 18º servicio, nuevo en `settings.gradle.kts`):** consume `accounting.invoice-requested` + `party.fiscal-profile-updated` (read-model `FiscalProfile`), genera `Invoice` (CFDI 4.0) con receptor real o **RFC genérico "público en general"** (XAXX010101000) si el party aún no tiene perfil fiscal; **timbrado stub `NoopPacAdapter`** (folio fiscal simulado — el PAC real se integra después sin tocar el dominio, como pidió el usuario). `Invoice` con `InvoiceLine` (EAGER, un bag), 2 endpoints. **Party enriquecido** (party-service): campos fiscales CFDI (`taxName`/`taxRegime`/`taxZipCode`/`cfdiUse`) + `PUT /parties/{id}/fiscal-profile` + evento `party.fiscal-profile-updated`. Tests: accounting `PostingServiceTest` (4), `ProvisionPostingServiceTest` (3), `BillingServiceTest` (2), `AccountingAcceptanceTest` (5), `AccountingFlowIT` (2) = **16 ✅**; invoicing `InvoiceServiceTest` (3), `InvoicingAcceptanceTest` (4), `InvoicingFlowIT` (1) = **8 ✅**; party-service **33 ✅** (sin regresión, +1 fiscal). Verificados con Docker. Actualizado: `settings.gradle.kts`, `docker-compose.yml` (accounting+invoicing), `docs/dominios/T4_accounting_gl.md`, tracker Módulos 15/18 + Estado General. **`accounting.reconciliation-alert` y las 3 conciliaciones (GL-04/07/11) quedan parcialmente como trabajo futuro** (requieren agregados cross-servicio); el mecanismo (`ProvisionLedger`, `AccountBalanceShadow`) ya está listo. **16/18 servicios completos** (faltan T2 Notifications, T6 Commission). |
| 2026-07-14 | T6: Commission — implementación completa (**nuevo servicio**, se agrega a `settings.gradle.kts`) + corrección de modelo del distribuidor B2B2C | El usuario corrigió el spec original **antes de que se escribiera código**: la comisión del distribuidor B2B2C no puede cobrarse por adelantado contra el monto dispuesto (`DISPOSITION_FEE`) porque crea el incentivo de colocar crédito sin validar su calidad — el distribuidor debe compartir el riesgo de recuperación. Modelo corregido e implementado: **`DISTRIBUTOR_INTEREST_SHARE`**, un % del **interés efectivamente cobrado** (nunca capital ni monto dispuesto), devengado **plazo a plazo contra cada pago** ("la comisión va contra el pago"), con tasa configurable por producto (y opcionalmente por distribuidor). **Prerequisito implementado primero — propagación de `promoterCode` end-to-end** (no existía ningún camino desde la intención de canal hasta el crédito activo): `channels.CustomerIntent.promoterCode` (capturado, nunca propagado) ahora viaja por `channels.application-started` → `origination.CreditApplication.promoterCode` (migración `010-add-promoter-code.sql`, overload de compatibilidad en `CreditApplication.start()` para no tocar ~24 sitios de test no relacionados) → `origination.credit-product-creation-requested` → `credit-portfolio.CreateCreditAccountCommand.promoterCode` → `credit-portfolio.credit-account-activated`. **Limitación documentada:** no existe directorio distribuidor↔código; Commission exige que `promoterCode` ya sea el UUID de Party del beneficiario (CM-07 omite silenciosamente si no parsea). **Nuevo módulo Gradle `commission`** (puerto 8097, misma estructura hexagonal/JWT/Kafka/Liquibase que el resto). Dominio: `CommissionPolicy` versionada (CP-01, una ACTIVE por producto+tipo+distribuidor opcional, distribuidor NULL = default), `CommissionRecord` (`amount = basis × rate` CR-01, tasa en snapshot CR-02, idempotente por `sourceEventId` CR-04), `LiquidationBatch`, `AccountBalanceShadow` (deriva interés cobrado del delta de `accruedInterestBalance`, mismo patrón que Accounting), `CreditPromoterAssignment`. `CommissionAccrualService.onBalanceUpdated`: `PAYMENT_APPLIED` con caída de interés devenga (CM-01, nunca `CHARGE_*`); `PAYMENT_RETURNED` con alza de interés reversa el registro ACCRUED más reciente (CM-05, **aproximación conservadora documentada en código** — credit-portfolio hoy restaura lo devuelto en `penaltyBalance`, no en `accruedInterestBalance`, y `PAYMENT_RETURNED` no correlaciona `sourceEventId` al pago original; match exacto por pago queda pendiente de un fix futuro upstream). `LiquidationService` agrupa por beneficiario/período con mínimo configurable (LB-03). Job `CommissionLiquidationJob` (día 5, 03:30). 2 listeners in, 5 endpoints REST, 7 migraciones Liquibase (seed: `DISTRIBUTOR_LINE` 0.30, `SME_LOAN` 0.20, placeholder comercial). **T4 Accounting wiring:** 2 cuentas nuevas en el catálogo (`2120 Comisiones por pagar` pasivo, `5104 Gasto por comisiones` gasto — editadas directo en `005-seed-catalog.sql`, no producción), `CommissionPostingService` (3 métodos idempotentes, mismo patrón que `ProvisionPostingService`), 3 listeners nuevos (`commission-accrued`/`reversed`/`liquidated`). Tests: commission `CommissionRecordTest` (5), `PromoterAssignmentServiceTest` (4), `CommissionAccrualServiceTest` (8), `CommissionPolicyServiceTest` (3), `LiquidationServiceTest` (3), `CommissionAcceptanceTest` (7, Testcontainers), `CommissionFlowIT` (1, Testcontainers) = **31 ✅**; accounting `CommissionPostingServiceTest` (5 nuevos) = **21 ✅** (sin regresión); prerequisitos sin regresión: channels 29 ✅, origination-service 119 ✅, credit-portfolio-service 96 ✅. **Suite completa verificada con Docker: 383 tests, 0 fallos en 8 módulos.** Actualizado: `settings.gradle.kts`, `docker-compose.yml` (servicio commission), `docs/IMPLEMENTATION_TRACKER.md` (Módulos 15/16 + Estado General). **17/18 servicios completos** (falta solo T2 Notifications). |
| 2026-07-15 | T2: Notifications — plan ampliado con lente de valor de marketing (solo diseño/spec, sin código) | A petición explícita del usuario, con encargo de análisis tipo "senior marketing strategist": identificar los eventos del sistema con mayor valor de comunicación al cliente (ej. primer crédito aprobado, recordatorios de pago) y mapearlos a una estrategia de canal para T2 Notifications. **Inventario real verificado contra código** (no la "muestra representativa" que tenía el doc original — varios nombres no correspondían a topics reales): ~45 eventos catalogados y tiereados — 🟢 Alto (adquisición/activación: `origination.offer-presented`, `application-approved` —el pico emocional del ciclo de vida—, `contract-signed`, `credit-portfolio.credit-account-activated`; servicing/retención: `installment-upcoming/due`, `payment-applied/returned`, `delinquency-status-updated` temprano, `collections.payment-promise-made/broken`, `agreement-proposed/executed`), ⚪ Compliance (`application-rejected`, `write-off-executed`, `case-escalated`, `bureau-report-submitted`, `party-blacklisted` — obligatorios, no opt-outables), 🔵 Partner B2B2C (`commission.commission-accrued/liquidated/reversed`, `channels.lead-created/converted` — audiencia distinta al cliente final, tono distinto, hallazgo directamente derivado del modelo de distribuidor de T6). **3 canales externos priorizados** (a petición explícita): PUSH, EMAIL, WHATSAPP (SMS/IVR quedan en el enum, fuera de v1 — sin caso de uso real hoy); WhatsApp elegido sobre SMS como canal de refuerzo por tasas de apertura/respuesta en México. **Estrategia de canal no hardcodeada**: `NotificationPolicy` versionada por `(eventType, valueTier)` (mismo patrón que `CommissionPolicy`), con dos modos — `SIMULTANEOUS` (eventos de pico emocional, todos los canales a la vez) vs. `SEQUENTIAL_FALLBACK` (`PUSH→WHATSAPP→EMAIL`, eventos rutinarios de alto valor como recordatorios). **Hallazgo crítico de prerequisito** (verificado leyendo código, no supuesto): ningún servicio persiste `phone`/`email` contra un `partyId` — `PartyService.createFromProspect` recibe el contacto en el payload de `origination.prospect-created` pero `Party.create()` lo descarta; sin resolver esto, T2 no puede notificar nada posterior a la activación. **Mecanismo propuesto, 100% event-driven, sin tocar ningún otro servicio:** shadow local de 4 pasos en Notifications (`prospect_contact_shadow` desde `prospect-created` → `application_prospect_link` desde `application-approved` → join con `credit-account-activated` por `contractId=applicationId` → `party_contact_directory` final); gap menor adicional resuelto igual (`credit_account_party_shadow`, `creditAccountId→obligorPartyId`, para `payment-applied/returned` e `installment-due/upcoming` que no llevan partyId directo). Alternativa complementaria no bloqueante anotada: enriquecer `Party` con `phone`/`email` (mismo criterio que el perfil fiscal CFDI). **Eventos derivados/hitos** (no piden evento nuevo a ningún dominio, se calculan localmente): primer pago realizado, mitad del crédito pagada, elegibilidad a renovación (ligado a `RENEWAL_BONUS` de T6, no implementado todavía). 4 reglas nuevas (NT-07..NT-10). Reescrito por completo `docs/dominios/T2_notifications.md`; actualizado tracker Módulo 14 (entidades, prerequisitos, reglas — todo como plan, `[ ]`, sin implementar). **Alcance de esta sesión: solo documentación/diseño**, sin nuevo módulo Gradle ni código — a la espera de confirmación explícita para implementar, mismo patrón que Collections/Risk antes de construirse. |
| 2026-07-16 | T2: Notifications — alcance v1 acotado a 4 notificaciones (solo diseño/spec, sin código) | A petición explícita del usuario: de las ~28 notificaciones catalogadas el día anterior, v1 se construye con **solo 4** — ofertas de crédito (`origination.offer-presented`), bienvenida (`credit-portfolio.credit-account-activated`), desembolso (`credit-portfolio.disposition-completed`) y recordatorio de pago (`collections.pre-due-reminder-triggered`, no `installment-due` — queda para fase 2). **Verificado en código antes de fijar el alcance** (no supuesto): `disposition-completed` se publica tanto en la activación inicial de productos no revolventes (desembolso único) como en disposiciones posteriores de línea revolvente (`CreditAccountService.activate()`), confirmando que es el evento correcto para "desembolso" en ambos casos. **Simplificación de prerequisito derivada del acotamiento:** los 4 eventos de v1 ya traen `prospectId` u `obligorPartyId` directo en el payload — v1 solo necesita 3 read-models de correlación (`prospect_contact_shadow`, `application_prospect_link`, `party_contact_directory`), no los 5 que exigiría el catálogo completo (`credit_account_party_shadow` y `case_party_shadow` quedan diferidos, ninguna notificación de v1 los necesita). Reglas NT-01/06/09/10 (regulatorias, retención CONDUSEF, segmentación partner, hitos derivados) quedan diferidas — v1 no incluye eventos ⚪ compliance ni 🔵 partner ni hitos. Reescrito `docs/dominios/T2_notifications.md` (nueva sección §Alcance v1 al inicio, resto del catálogo marcado explícitamente como backlog sin borrarlo) y tracker Módulo 14 (entidades/changelogs/endpoints/reglas/tests reducidos al alcance real). **Solo documentación — sin código todavía.** |
| 2026-07-16 | T2: Notifications — se agregan 2 notificaciones de celebración a v1 (solo diseño/spec, sin código) | A petición explícita del usuario, mismo día que el acotamiento a 4: agregar notificación de **cuota pagada completamente** (por cada abono que cubre una cuota) y **crédito liquidado por completo** (fin del crédito), ambas con tono "felicidades", copy de ejemplo incluido. v1 pasa de 4 a 6 notificaciones. **Hallazgo verificado en código al diseñar #5 (cuota pagada):** `credit-portfolio.InstallmentStatus` define `PAID`/`PARTIAL` en el enum pero **ningún código del sistema los asigna nunca** — `Installment` se crea en `PENDING` y no existe `markPaid()` ni transición posterior en todo `credit-portfolio-service`; los pagos se aplican contra el saldo agregado de la cuenta, no contra una cuota específica. Por eso "cuota pagada" se resuelve en v1 con una **aproximación local, no un dato exacto**: nuevo read-model `notifications.credit_account_progress` (creditAccountId, totalInstallments desde `offeredTerm` de `origination.offer-presented`, installmentsPaidCount, cuota vigente desde `collections.pre-due-reminder-triggered`) — si `payments.payment-applied.amount` cubre la cuota vigente conocida, se asume pagada y avanza el contador; falla en pagos parciales o que cubren varias cuotas de golpe (NT-11, documentado como best-effort, no genera `NotificationFailed` si no aplica). **#6 (crédito liquidado) no tiene este problema** — usa `credit-portfolio.balance-updated` filtrado a `accountStatus=SETTLED`, el mismo campo limpio que ya usan Risk (`RC-06`) y Collections para detectar cierre de cuenta; tratamiento multicanal simultáneo (PUSH+WHATSAPP+EMAIL), mismo nivel que `application-approved` — es el segundo pico emocional del catálogo. Actualizado `docs/dominios/T2_notifications.md` (tabla de alcance v1, ítems #29/#30 en la enumeración con copy real, sección de hitos derivados corregida para no asumir que `payment-applied` trae `installmentNumber` —no lo trae— y para documentar el mecanismo compartido) y tracker Módulo 14 (entidad `credit_account_progress`, prerequisito nuevo, regla NT-11, tests). **Solo documentación — sin código todavía.** |
| 2026-07-17 | T2: Notifications — implementación completa (**nuevo servicio**, 18º y último — se agrega a `settings.gradle.kts`, ya estaba stubbed de Fase 0) | Ejecutando el plan v1 de 6 notificaciones (acotado/ampliado el 2026-07-16). **Nuevo módulo Gradle `notifications`** (puerto 8098, misma estructura hexagonal/JWT/Kafka/Liquibase/Modulith que el resto, `spring-boot-starter-mail` agregado). Dominio: `NotificationPolicy` versionada (una ACTIVE por eventType, `fallbackChannels` como colección ordenada EAGER), `NotificationTemplate` (copy real con placeholders `{{var}}`, sustitución vía regex simple), `NotificationRecord` (idempotente por `(sourceEventId, channel)`, campo `recipientId` — no `partyId` — porque para la notificación de ofertas el destinatario es un `prospectId`, todavía no existe un Party correlacionable en ese punto del ciclo de vida), `NotificationPreference`, y los 4 read-models del prerequisito de contacto: `ProspectContactShadow`, `ApplicationProspectLink`, `PartyContactDirectory`, `CreditAccountProgress` (esta última con `tryMarkInstallmentPaid()`, la aproximación best-effort para "cuota pagada" — documentada extensamente en el javadoc de la clase). **Ajuste de diseño hecho al implementar** (mejora sobre el plan original, documentada): `ApplicationProspectLink` se pobló desde `origination.offer-presented` en vez de `application-approved` — offer-presented ya se consume para la notificación #1 y trae `applicationId`+`prospectId`+`offeredTerm` juntos, ahorrando un listener dedicado solo a la correlación. `NotificationDispatchService`: resuelve policy → filtra canales por disponibilidad real (pushToken/phone/email) → `SIMULTANEOUS` manda a todos los disponibles, `SEQUENTIAL_FALLBACK` intenta en orden y para en el primer éxito. **Bug real encontrado y corregido al escribir el test de fallback:** la primera versión limitaba `SEQUENTIAL_FALLBACK` a intentar un único canal (`List.of(candidates.get(0))`) sin importar si el adaptador fallaba — nunca probaba el segundo canal ante un fallo real del proveedor, solo ante falta de contacto. Corregido para iterar todos los candidatos y parar solo en el primer envío exitoso. `NotificationTriggerService` conecta los 7 listeners a la correlación y al dispatch. **7 listeners in** (`prospect-created`, `offer-presented`, `credit-account-activated`, `disposition-completed`, `pre-due-reminder-triggered`, `payment-applied`, `balance-updated` filtrado a `accountStatus=SETTLED`), **2 publishers out** (`notification-sent`/`notification-failed`), **5 endpoints REST** (historial, preferencias GET/PUT, políticas GET/POST rol MARKETING/ADMIN), 10 migraciones Liquibase (incl. seed de 6 policies + 15 templates con el copy real de `T2_notifications.md`). **Canales:** `NoopPushAdapter`/`NoopWhatsAppAdapter` (stub, mismo patrón `Noop*` del resto del sistema) + **`SmtpEmailAdapter` real** (vía `JavaMailSender`, funciona con cualquier SMTP gratuito — Gmail dev, Brevo/Resend free tier, SES a volumen — deshabilitado por default, activable solo por config) con `NoopEmailAdapter` como default complementario. **NT-12 verificado en la implementación:** cero anotaciones `@Scheduled` en todo el módulo — `NotificationsApplication` deliberadamente no lleva `@EnableScheduling` (a diferencia de Commission/Risk/etc.), documentado en su javadoc. Tests: `NotificationTemplateTest` (4), `CreditAccountProgressTest` (5), `NotificationPolicyTest` (3), `NotificationDispatchServiceTest` (6, incluye el caso del bug de fallback), `ContactResolutionServiceTest` (5), `NotificationTriggerServiceTest` (7), `NotificationPolicyServiceTest` (3), `NotificationAcceptanceTest` (7, Testcontainers), `NotificationFlowIT` (1, Testcontainers, flujo completo oferta→activación→liquidación) — **41 tests ✅** verificados con Docker. Verificado que el resto del monorepo compila sin regresiones (`compileJava compileTestJava` en todos los módulos, excluyendo un fallo preexistente en `identity-service` no relacionado — tests desalineados con la migración a JWT RS256 del 2026-06-28, ningún archivo de ese servicio fue tocado). Actualizado: `settings.gradle.kts`, `docker-compose.yml` (servicio notifications), tracker Módulo 14 + Estado General + banner. **🎉 18/18 servicios de dominio completos — catálogo del sistema cerrado.** |
| 2026-07-18 | T7: Observability — plan detallado (solo diseño/spec, sin código) | A petición explícita del usuario, actuando como "SR reliability engineer": OpenTelemetry + Fluent Bit + Elasticsearch + Grafana, agente auto-instrumentado incorporado a la imagen, dashboards por servicio (throughput/p95/p99/response time/error rate). **Decisión de diseño central:** las 3 señales de observabilidad NO van por un solo pipeline homogéneo — trazas por OTel Java Agent (auto-instrumentación real, cero cambio de código) → OTel Collector → Tempo; métricas RED por Micrometer (ya integrado en Spring Boot Actuator, solo hay que activarlo) → Prometheus (pull) → Grafana; logs por Fluent Bit (tail de contenedores Docker) → Elasticsearch → Grafana. Razón documentada: mandar métricas también por el SDK de OTel duplicaría la fuente de verdad y reinventaría lo que Micrometer ya resuelve mejor para Spring Boot específicamente. El pegamento entre las 3 señales: el agente de OTel inyecta `trace_id`/`span_id` en el MDC de Logback automáticamente — cada log en Elasticsearch queda correlacionado con su traza en Tempo. **Hallazgos de auditoría verificados en código antes de diseñar:** ningún servicio tiene `micrometer-registry-prometheus` (8/19 ya "exponían" `prometheus` en `exposure.include` pero el endpoint estaba muerto — 404 — sin la dependencia); sin percentiles configurados en ninguno (hace falta `percentiles-histogram.http.server.requests=true` para que Prometheus pueda calcular p95/p99, si no solo hay count/sum/max); solo 3/19 servicios tienen `logback-spring.xml` (idéntico entre sí, texto plano, no JSON) — los otros 16 usan el default de Spring Boot, logs inconsistentes entre servicios hoy; el `Dockerfile` compartido no necesita tocarse para inyectar el agente (la JVM recoge `JAVA_TOOL_OPTIONS` automáticamente, sin importar el `ENTRYPOINT`); el gateway (OpenResty/nginx) genera su propio `request_id` pero nunca lo reenvía a los servicios upstream — dos IDs de correlación que hoy no se cruzan; solo `channel-mobile-service` pasa por el gateway, las otras 18 rutas son llamadas internas directas. **Arquitectura propuesta:** agente de OTel inyectado por volumen Docker compartido (`otel-agent-init`, descarga el jar una vez) + `JAVA_TOOL_OPTIONS` vía anchor YAML — cero rebuild de imágenes, cero cambio de código para las trazas. Los 19 servicios sí necesitan un diff mecánico idéntico para métricas/logs: `micrometer-registry-prometheus` + `logstash-logback-encoder` en `build.gradle.kts`, `prometheus` en `exposure.include` + `percentiles-histogram` en `application.yml`, `logback-spring.xml` reducido a un include de un `shared/logback-base.xml` nuevo (JSON estructurado, MDC automático). **Decisión de costo:** los 6 contenedores pesados (otel-collector, tempo, prometheus, fluent-bit, elasticsearch, grafana) quedan detrás de un perfil opt-in de Docker Compose (`--profile observability`, ~2.1GB RAM adicional) — `docker compose up` sin el perfil sigue funcionando exactamente igual que hoy (OBS-04). Dashboard único parametrizado por variable `$service` (no 19 dashboards copiados) con PromQL exacto documentado para throughput/error rate/p50/p95/p99/tiempo de respuesta promedio/JVM. **Alcance excluido explícito:** gateway sin instrumentar (no es JVM, requeriría el módulo `opentelemetry-lua-resty`, no pedido), sin alertas (Alertmanager/Grafana Alerting), sin Kibana, sin autenticación más allá del default de Grafana — todo documentado como decisión, no omisión. Escrito `docs/dominios/T7_observability.md` (plan completo: inventario de componentes con imagen/puerto/RAM, diff exacto por servicio, especificación de archivos de configuración nuevos, paneles de dashboard con PromQL, reglas OBS-01..04, fases de rollout propuestas) y tracker Módulo 19 + Estado General + banner. **El usuario pidió explícitamente el plan detallado antes de tocar código** ("primero dame un plan detallado... ponlo como dominio en el docs como el último T") — interrumpiendo una implementación ya iniciada (2 archivos de config de ejemplo, `otel-collector-config.yaml` y `tempo.yaml`, quedaron escritos en `observability/` como preview, coherentes con el plan final). **Sin código de negocio tocado — solo documentación, a la espera de confirmación explícita para implementar**, mismo patrón que todos los módulos anteriores de este tracker. |
| 2026-07-18 | T7: Observability — implementación completa + verificación parcial con Docker real (trabajo pausado a petición explícita del usuario) | Ejecutando el plan del mismo día, con la petición explícita "deja espacio de 8gb de reserva y ejecuta el plan". **docker-compose.yml:** anchor `x-otel-agent` (env vars OTLP, `OTEL_METRICS_EXPORTER=none`/`OTEL_LOGS_EXPORTER=none` para no duplicar señal, OBS-01) + anchor `x-app-mem` (512M/256M por servicio Java); nuevo `otel-agent-init` (descarga el jar del agente a un volumen compartido, corre siempre); 6 contenedores de observabilidad detrás de `profiles: ["observability"]`; límites de memoria explícitos en los 23 servicios existentes también (postgres 512M, kafka 1024M, zookeeper 512M, redis 384M, gateway 64M) — presupuesto documentado en cabecera del archivo, peor caso ≈15.1GB, pensado para una asignación de Docker Desktop de 16GB (24GB host − 8GB de reserva, tal como se pidió). **19 servicios instrumentados** (`build.gradle.kts` + `application.yml` + `logback-spring.xml`, ver detalle en Módulo 19) + nuevo `shared/logback-base.xml`. **6 archivos de configuración nuevos** en `observability/` (otel-collector, tempo, prometheus con 19 scrape targets, fluent-bit, grafana provisioning + dashboard JSON parametrizado por `$service`). **6 problemas reales encontrados y corregidos al verificar con Docker (ninguno predecible solo leyendo código):** (1) `otel-agent-init` sin permiso de escritura en el volumen nuevo (fix `user: "0:0"`); (2) Fluent Bit no tiene un filtro `docker_metadata` nativo (a diferencia de `kubernetes`) — se quitó, no hacía falta porque `service_name` ya viaja en el JSON de cada log; (3) Elasticsearch murió por OOM dos veces con el límite original de 1024M (subido a 1536M); (4) Postgres se quedó sin conexiones ("too many clients") con los 19 servicios arrancando a la vez — `max_connections` subido a 250; (5) **17 de 19 `SecurityConfig.java` bloqueaban `/actuator/prometheus` con 401** (permitían `health`/`info`/`metrics` pero no `prometheus`) — agregado a la lista `permitAll()`, cambio no anticipado en el plan original; (6) builds paralelos de Gradle agotaron memoria durante la construcción de imágenes (`cannot allocate memory`) — resuelto construyendo secuencialmente. **Verificación en vivo lograda:** pipeline de trazas confirmado 100% end-to-end (request real → agente OTel → Collector, spans recibidos y exportados en logs → Tempo, trazas reales consultables por `service.name` con duración y nombre de operación correctos); Prometheus con 18-20/20 targets `UP` sostenido; logging JSON estructurado confirmado en contenedores reales. **Verificación no cerrada en esta pasada:** log correlacionado por `trace_id` en Elasticsearch, dashboard de Grafana con datos reales en el navegador — el trabajo se pausó a petición explícita del usuario antes de completar estos dos últimos puntos. **Hallazgo de capacidad confirmado empíricamente, no solo en teoría:** la asignación actual de Docker Desktop (7.65GB) no alcanza para los 19 servicios + infraestructura + el perfil `observability` completo corriendo a la vez de forma estable — se observaron crash-loops reales (`exit 137`) rotando entre distintos contenedores bajo el arranque simultáneo de los 19; con un subconjunto reducido (6 servicios + infra + observability) el stack corrió estable. Recomendación pendiente de acción manual del usuario (fuera del repo): subir la memoria de Docker Desktop a 16GB vía Settings → Resources → Memory. Actualizado: `docs/dominios/T7_observability.md` (hallazgos de verificación en vivo agregados) y tracker Módulo 19 (estado real, checklist actualizado). Stack detenido de forma segura (`docker compose down`, sin borrar volúmenes) al pausar. |
| 2026-07-18 | T7: Observability — cierre de la verificación en vivo (retomada tras la pausa) | Se retomó con "continua": se levantó de nuevo el subconjunto estable (6 servicios de negocio + infra + los 6 contenedores de observabilidad), sin tocar código — solo verificación. **Correlación log↔trace confirmada con datos reales y de forma bidireccional:** se encontró un documento real en Elasticsearch con `trace_id`/`span_id`/`trace_flags` poblados (instrumentación MDC del agente de OTel funcionando, no solo asumida) y ese mismo `trace_id` devolvió en Tempo el span exacto (`GET /actuator/prometheus`, 102ms) — confirma que "click en un log, salta a su traza" es real. **Dashboard de Grafana confirmado con datos reales:** datasources conectados (`/api/datasources`), dashboard con sus 8 paneles encontrado (`/api/search`), y las queries de throughput (0.024 req/s) y p95 (0.071s) del propio dashboard ejecutadas a través del proxy de Grafana (no contra Prometheus directo) devolvieron valores reales para `job="commission"`. Elasticsearch reinició limpio sin volver a fallar por OOM con el límite de 1536M ya aplicado. **T7 Observability queda 100% implementado y verificado.** Stack detenido de forma segura al cerrar (`docker compose down`, volúmenes intactos). Actualizado: tracker Módulo 19 (estado ✅ Completo, checklist de verificación cerrado) + banner. |
| 2026-07-25 | T7: Observability — refinamientos de APM (nombres -service, logos, ceros, filtro de traces) | Segunda pasada sobre el rediseño, a petición del usuario. **(1) Nombres consistentes `-service`:** 8 servicios exponían nombre corto (payments, wallet, collections, risk, accounting, invoicing, commission, notifications); se alineó a la convención del repo (payments/wallet ya usaban `-service` en `spring.application.name`) en las 3 capas — `spring.application.name` en el `application.yml` de los 6 que faltaban, `OTEL_SERVICE_NAME`+`SPRING_APPLICATION_NAME` en compose (los 8), y `job_name` de Prometheus (los 8, target/hostname corto intacto). Carpetas/módulos Gradle/hostnames se dejan cortos a propósito (OBS-07). Verificado: 19/19 jobs con `-service`, nodos del service graph con `-service` y sin nombres legacy. **(2) Logos en el mapa:** panel Text (HTML/SVG) "Service Dependency Map" con logos reales (PostgreSQL/Kafka/Redis + Java/Spring), data-URI coloreados con hex de marca, más `GF_PANELS_DISABLE_SANITIZE_HTML=true`; el nodeGraph "Live topology" se agrandó (h13→h18) para mejor visibilidad (OBS-06 revisado — el split es porque el nodeGraph OSS no incrusta logos). **(3) Ceros en vez de null/NaN/No data:** error rate y tablas ahora usan `... or vector(0)`, `noValue: "0"` y `decimals: 2` — el error rate muestra `0.00` y la tabla de endpoints `0.0` cuando no hay 5xx (verificado: query de servicio sin 5xx devuelve 0, no vacío). **(4) Filtro de traces:** nueva variable `route` (multi + All, `label_values(...uri)`) que filtra el panel de traces por `span.http.route =~ "${route:regex}"` — verificado en vivo (`/api/v1/auth/login` → 2 traces, `.*` → 10). Reglas nuevas OBS-05 corregido (solo `service-graphs`, sin span-metrics), OBS-06 (logos en panel HTML + topología en nodeGraph), OBS-07 (convención de nombres). |
| 2026-07-25 | T7: Observability — rediseño de dashboards a APM estilo Datadog + service graph | A petición del usuario ("dashboard tipo Datadog", trackear aplicativos): se rediseñó por completo la experiencia de dashboards y se **encendió el `metrics_generator` de Tempo** para el mapa de dependencias. **Infra/config:** `tempo.yaml` gana el bloque `metrics_generator` (procesador `service-graphs` únicamente — `span-metrics` se descartó por presionar la RAM de Tempo sin consumidor, OBS-01; `remote_write` a Prometheus con exemplars) + `overrides.defaults`; `prometheus` gana los flags `--web.enable-remote-write-receiver` y `--enable-feature=exemplar-storage`; límite de RAM de Tempo subido 512M→768M por el generator; datasource Tempo enriquecido con `serviceMap`/`nodeGraph`/`tracesToMetrics`. **Dos dashboards enlazados** (reemplazan el `service-overview.json` plano de 8 paneles): (1) **`services-apm.json`** — catálogo global: RED agregado de todos los servicios + tabla de servicios (hits/avg/p50/p95/p99/error% por `job`, con data-link por fila que abre el detalle) + node graph del service map; (2) **`service-overview.json` reescrito** — detalle por `$service` con **5 secciones estilo Datadog, todas expandidas (sin colapsar): Overview, APM, Infrastructure, Traces, Logs & Errors**. APM incluye la tabla de **todos** los endpoints (no top-10) + throughput + distribución de status + el **mapa de dependencias unificado** (node graph tipado database/messaging/service, en vez de secciones separadas de BD y Kafka). Traces = tabla TraceQL de Tempo → click abre el waterfall (camino span por span: HTTP→SQL→Kafka con su duración). **Verificado en vivo:** el metrics_generator remote-escribe a Prometheus (39 aristas de service graph reales: `service→postgres [database]`, `service→redis [database]`, `origination→notifications [messaging_system]`, `credit-portfolio↔wallet [messaging_system]`, `channel-mobile→identity [service]`); ambos dashboards provisionados sin error y sus queries devuelven datos reales vía el proxy de Grafana. **Nota honesta documentada (OBS-06):** el node graph de Grafana OSS renderiza nodos como círculos etiquetados con arcos de rate/latencia/error, pero **no** soporta incrustar logos de marca (PostgreSQL/Kafka/Redis) en los nodos — eso es propietario de Datadog; el grafo sí **tipa** cada dependencia (database/messaging/service/virtual_node). Uso de RAM tras el cambio ≈5.5GB con el subset de 6 (reserva de 8GB intacta). Actualizado: `docs/dominios/T7_observability.md` (nueva sección de rediseño + OBS-05/OBS-06) y este changelog. |
| 2026-08-09 | BO Fase 0: canal de backoffice | **Habilitadores del backoffice web** (`fintech-backoffice-web`), que hasta hoy corría 100% contra datos simulados porque no existía nada del lado servidor. **identity-service:** agregado `StaffUser` (empleado con acceso a la consola — separado de `IdentityCredential`, que cuelga de un `partyId`, es decir de un *cliente*; aquí el sujeto es la institución, así que la credencial vive dentro del agregado y los 13 roles son una colección de valores, no entidades); enums `StaffRole`/`EmployeeType`/`StaffStatus`/`Channel`; invariantes SU-01..SU-04 (correo único e inmutable, nunca sin roles, baja lógica irreversible, coherencia colaborador↔distribuidor); migraciones 009 (`staff_users` + `staff_user_roles`) y 010 (ADMIN de arranque — sin él no hay forma de crear el primer empleado, porque `POST /api/v1/staff` exige ADMIN y nadie lo tiene todavía; **credenciales de arranque a rotar**); `StaffAuthService` + `StaffDirectoryService`; endpoints `POST /api/v1/auth/staff/{login,refresh,logout}`, `GET /api/v1/auth/staff/me` y CRUD `/api/v1/staff/**` (ADMIN). **Claim `channel` end-to-end:** `TokenPort.generateAccessToken` y `TokenClaims` ganan canal (`MOBILE|BACKOFFICE|SERVICE`); `AuthToken` lo persiste para que refrescar una sesión de backoffice por el endpoint móvil no la degrade a sesión de cliente (y viceversa), con guardas en ambos flujos; los tokens previos al claim se leen como `MOBILE` (`Channel.fromClaim`), que era el único canal posible cuando se firmaron. Suspender o dar de baja a alguien surte efecto en su siguiente refresh, sin esperar a que expire el refresh token. **gateway-service:** `jwt.lua` acepta un canal requerido y propaga `X-Channel`; nuevo server block `backoffice.localhost` → `channel-backoffice-service:8099` que **exige `channel=BACKOFFICE`** (un token de la app de cliente es válido y no abre esta puerta), CORS con lista blanca explícita —nunca `*`, porque las peticiones llevan `Authorization`— y preflight resuelto en el borde (OPTIONS no lleva token, validarlo lo rompería siempre), rate limits `rl_staff_login` 5r/m y `rl_staff_refresh` 20r/m. El bloque móvil se dejó **sin** exigencia de canal a propósito: hacerlo habría roto los tokens `SERVICE` de client-credentials que hoy pasan por ahí. **Servicio nuevo `channel-backoffice-service`** (puerto 8099 — 8086 ya es de T5 Configuration): BFF stateless sin base de datos, `WebClient` por cada uno de los 17 servicios de dominio con propagación de correlación, `IdentityClient` completo, clientes de party/credit-portfolio/credit-product, sesión y directorio de personal. La traducción de errores no conserva el status a ciegas: 401/403/404/409/423/429 viajan intactos porque describen lo que pidió el operador, mientras que un 405 o un 5xx del dominio salen como **502** porque son culpa nuestra, no suya. **Verificación:** identity-service **110/110 tests verdes** (39 nuevos de staff: 13 de dominio, 12 de servicio, 14 WebMvcTest), incluida la prueba de aceptación con Postgres real vía Testcontainers, que ejerce las migraciones nuevas; config de nginx y ambos módulos Lua validados con la imagen real de OpenResty; BFF arrancado de verdad (health `UP`, 400 por validación, 401 sin token, 502 al caerse el downstream, 10 rutas en OpenAPI); los 20 módulos compilan. **Dos roturas preexistentes encontradas y corregidas** (bloqueaban compilar cualquier test de identity, así que era imposible verificar nada): `AuthServiceTest`/`ClientAuthServiceTest` llamaban `properties.setJwtSecret(...)`, que desapareció al migrar a RS256, y la llave RSA de prueba estaba en PKCS#1 (`BEGIN RSA PRIVATE KEY`) cuando `JwtAdapter` espera PKCS#8 — regenerada. **Fuera de alcance, documentado:** la bitácora de auditoría del login de staff (`LoginAttemptEvent` está atado a `LoginCommand`/`IdentityCredential` del flujo de cliente; por ahora queda en logs estructurados y se conecta a T3 en la fase 5); `scoring-service:compileTestJava` sigue roto por un cambio previo ajeno a este trabajo (`ScoringEvaluationService` ganó 2 parámetros el 2026-08-08 y su test quedó en la firma vieja). |
