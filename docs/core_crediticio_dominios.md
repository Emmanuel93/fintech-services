# Core Crediticio — Mapa de Dominios
> Ciclo de vida completo del crédito. B2B2C con Distribuidor integrado vía `partyType`. **Microservicios totalmente desacoplados** (ver [ADR-001](../README.md)). Comportamiento gobernado por tipos explícitos, nunca por comparación de atributos.

```
          Customer/Person  ← dominio raíz (Party)
         /       |        \
    Channels  (directo)  Scoring ◄─── prefetch buró (onboarding)
         \        |         ▲
          └──► Origination ─┘  ScoreRequested (al elegir producto)
                   │ persona (Prospect) ─── crédito (CreditApplication)
                   ▼
        credit-product (catálogo) ──snapshot──► credit-portfolio ★ (cuentas vivas)
                                                 /      |      \
                                            Charges  Payments  Wallet
                                                 \      |      /
                                                  Collections  ←── (Quebranto/quita/reestructura)
                                                       │
                          credit-account-activated ────┴───► Disbursement (D10) ──► STP (D11) ──► SPEI
                          (hecho, no comando: nueve consumidores deciden por su cuenta)
                                                       |
                                                     Risk  ←── (etapa IFRS-9 + reserva EPR, D9 nuevo)
```

## Principios globales
- **Microservicios desacoplados:** database-per-service, async event-driven (Kafka), read models locales, event-carried state transfer. Única excepción síncrona: token contra T1. Ver ADR-001.
- **Tipificación explícita:** `partyType`, `productType`, `dispositionType` gobiernan toda la lógica — nunca `if obligorId == beneficiaryId`
- **Persona ≠ crédito:** Origination onboardea la persona (`Prospect`) sin producto; el producto se elige en `CreditApplication`, lo que dispara la evaluación de scoring
- **Catálogo ≠ cuenta:** `credit-product` define los productos (configuración); `credit-portfolio` ★ administra las cuentas vivas (saldos)
- **credit-portfolio = fuente de verdad de saldos** — Charges y Payments emiten eventos; credit-portfolio los aplica
- **Routing siempre:** Channels → Origination → Scoring (Scoring no recibe de Channels directamente)
- **Collections cobra siempre al `obligorPartyId`** — sin importar quién recibió el recurso

## Dominios principales

| # | Dominio | Tipo | Función | Eventos clave |
|---|---|---|---|---|
| 0 | **Party** | Core Root | Sujeto del crédito — raíz de todo | `PartyCreated`, `IdentityVerified`, `PartyActivated`, `PartyBlacklisted` |
| 1 | Channels | Supporting | Captación y enrutamiento | `ApplicationStarted`, `LeadCreated`, `SessionExpired` |
| 2 | Scoring | Core | Evaluación de riesgo + DecisionPolicy | `ScoreGenerated`, `ScoreRejected`, `RiskTierUpdated` |
| 3 | Credit — Origination | Core | Onboarding de persona (`Prospect`) + selección de producto y aprobación (`CreditApplication`) | `ProspectCreated`, `ScoreRequested`, `ApplicationApproved`, `ContractSigned`, `CreditProductCreationRequested` |
| 4 | Credit — Product | Core | **Catálogo / fábrica** de productos (definiciones, rate cards, capacidades) | `ProductDefined`, `ProductVersioned`, `ProductRetired` |
| 4★ | Credit — Portfolio | Core ★ | **El corazón** — cuenta viva, motor de saldos | `CreditAccountActivated`, `DispositionCreated`, `BalanceUpdated`, `ProductSettled` |
| 5 | Charges | Core | Devengamiento de cargos e intereses | `OrdinaryInterestAccrued`, `MoratoriumInterestCharged`, `*FeeCharged` |
| 6 | Payments | Supporting | Recepción y aplicación de pagos | `PaymentReceived`, `PaymentApplied`, `PaymentReturned`, `ReconciliationCompleted` |
| 7 | Wallet | Supporting | Saldo disponible y disposiciones UI | `PaymentInstructionCreated`, `DispositionRequested`, `WalletSnapshotUpdated` |
| 8 | Collections | Supporting | Cobranza (temprana y en mora), reestructuras, quitas (parcial/total) y recuperación | `PreDueReminderTriggered`, `CollectionCaseCreated`, `CollectionAgreementExecuted`, `WriteOffExecuted`, `BureauReportSubmitted`, `RecoveryPaymentApplied` |
| 9 | **Risk** *(nuevo, 2026-07-08)* | Supporting | Etapa IFRS-9 y estimación preventiva de reservas (EPR/ECL) por cuenta — solo clasifica, nunca cobra ni mueve saldos | `RiskAssessmentUpdated` |
| 10 | **Disbursement** *(nuevo, 2026-08-11)* | Supporting | Orquestación de pagos salientes (payouts) multi-rail y multi-empresa — decide qué, a quién, por qué rail y con qué proveedor; **no conoce el dominio de crédito** | `DisbursementAccepted`, `DisbursementCompleted`, `DisbursementFailed`, `DisbursementReturned` |
| 11 | **STP Connector** *(nuevo, 2026-08-11)* | Generic / ACL | Conector con el proveedor SPEI STP: cadena original, firma, registro de orden y consulta de conciliación. Servicio interno, sin exposición a internet | `StpOrderAccepted`, `StpOrderSettled`, `StpOrderRejected`, `StpOrderReturned` |

## Dominios transversales

| # | Dominio | Función | Consumidores |
|---|---|---|---|
| T1 | Identity & Auth | Sesiones, tokens, dispositivos, liveness | Channels, Credit Product (firma), Origination |
| T2 | Notifications | Comunicaciones salientes multicanal | Todos los dominios (suscriptor de eventos) |
| T3 | Audit & Compliance | Trazabilidad inmutable + expedientes | Todos (solo escribe, nunca retroalimenta decisiones) |
| T4 | Accounting / GL | Partida doble por cada evento económico | Origination, Product, Charges, Payments, Collections |
| T5 | Configuration | Parámetros de negocio sin deploy de código | Todos los dominios en tiempo de ejecución |
| T6 | Commission | Comisiones red comercial (promotores, distribuidores) | Product, Payments, T4 |

## Matriz transversales × dominios

| Dominio | T1 | T2 | T3 | T4 | T5 | T6 |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| Party | ✅ | ✅ | ✅ | — | — | — |
| Channels | ✅ | ✅ | ✅ | — | ✅ | — |
| Scoring | ✅ | — | ✅ | — | ✅ | — |
| Origination | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| Credit Product | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Charges | — | ✅ | ✅ | ✅ | ✅ | ✅ |
| Payments | — | ✅ | ✅ | ✅ | ✅ | ✅ |
| Wallet | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| Collections | — | ✅ | ✅ | ✅ | ✅ | ✅ |
| Risk | — | — | ✅ | ✅ | ✅ | — |
| Disbursement | — | ✅ | ✅ | ✅ | ✅ | — |
| STP Connector | — | — | ✅ | — | ✅ | — |
