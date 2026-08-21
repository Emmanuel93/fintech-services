# D7 — Wallet [Supporting]

**Estado:** ⬜ Pendiente (Módulo 11)  
**Tipo:** Supporting — proyección read-only  
**Schema DB:** `wallet`  
**Paquete Java:** `com.fintech.wallet`

## Responsabilidad

Proyección read-only del estado de CreditProduct para la UI. **No es fuente de verdad.**

## Reglas

- `WalletView` 1:1 con CreditProduct — sincroniza en cada evento relevante
- Validación fail-fast: `amount ≤ availableCredit` **antes** de enviar a CreditProduct
- `WalletSnapshotUpdated` consolida múltiples eventos → un solo refresh de UI
- `PaymentInstruction` con ciclo: `PENDING → SENT → EXPIRED`

## Eventos consumidos

`BalanceUpdated` · `DispositionCreated` · `DispositionCompleted` · `AccountStatementGenerated`

## Eventos publicados

`DispositionRequested` · `PaymentInstructionCreated` · `WalletSnapshotUpdated`
