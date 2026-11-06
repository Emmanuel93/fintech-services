package com.fintech.accounting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lo que el mayor contesta hacia afuera.
 *
 * <p>Están juntas a propósito: son las proyecciones de una sola pregunta —«qué pasó en este período,
 * en esta sucursal, en este préstamo»— y repartirlas en ocho archivos esconde que comparten
 * invariantes. La principal: <b>todo lo que agrega, agrega en SQL</b>. La balanza anterior traía el
 * período entero a memoria y sumaba en Java; con la cartera real eso no aguanta y, peor, impide
 * filtrar por sucursal sin traerlo todo.
 */
public final class LedgerViews {

    private LedgerViews() {}

    /** Un renglón de la póliza, ya de un solo lado. */
    public record VoucherLineView(
            String accountCode, String accountName, String accountType,
            BigDecimal debit, BigDecimal credit, String description) {}

    public record VoucherView(
            UUID voucherId, String voucherType, long folio, String period, Instant voucherDate,
            String orgUnitCode, String concept, String triggerEvent, String sourceEventId,
            UUID creditAccountId, UUID obligorPartyId,
            BigDecimal totalDebit, BigDecimal totalCredit, String status,
            boolean latePosting, String originalPeriod, UUID reversalRef,
            /** The product the voucher belongs to; {@code null} for the institution's own facts. */
            String productType,
            List<VoucherLineView> lines) {}

    public record VoucherPage(
            List<VoucherView> content, int page, int size, long totalElements, int totalPages) {}

    /**
     * A row of the trial balance: what the account held when the period opened, its debits and credits in the period,
     * and what it holds at the close (debit balances positive, credit balances negative).
     *
     * <p>{@code balance} is the period's net movement (debits − credits), kept for older readers. It is not the
     * account's balance: a month that collected more than it lent shows the loan portfolio "negative" there, while
     * its closing balance is the portfolio.
     */
    public record TrialBalanceRowView(
            String accountCode, String accountName, String accountType,
            BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal balance,
            BigDecimal openingBalance, BigDecimal closingBalance) {}

    /** El movimiento de una sucursal en el período. Agregado en SQL, no en el BFF. */
    public record UnitMovementView(
            String orgUnitCode, long vouchers, long loans,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest,
            BigDecimal fees, BigDecimal provision) {}

    /**
     * The movement of a product in the period, measured as a branch's is. {@code INSTITUCION} is what belongs to no
     * product: the institution's funding and its interest.
     */
    public record ProductMovementView(
            String productType, long vouchers, long loans,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest,
            BigDecimal fees, BigDecimal provision) {}

    /**
     * A product's accounting scheme: the concepts it is booked with and the accounts each one moves, as the ledger
     * booked them (not as a rule says they should be). {@code from}/{@code to} bound the periods read; null, all.
     */
    public record ProductSchemeView(String productType, String from, String to, List<SchemeConceptView> concepts) {}

    /** A concept of a product's scheme: a kind of fact ({@code triggerEvent}) and the pairs of accounts it moves. */
    public record SchemeConceptView(String triggerEvent, String concept, long vouchers, BigDecimal amount,
                                    List<SchemePairView> pairs) {}

    /** A pair of accounts a concept moves: how many entries and how much, with the entries' own description. */
    public record SchemePairView(String debitAccount, String debitName, String creditAccount, String creditName,
                                 long entries, BigDecimal amount, String description) {}

    /**
     * A row of the trial balance by product and branch: what the close keeps next to the whole one. A {@code null}
     * product is the institution's; a {@code null} branch, a loan sealed before branches were.
     */
    public record DimensionBalanceView(
            String productType, String orgUnitCode, String accountCode, String accountName, String accountType,
            BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal openingBalance, BigDecimal closingBalance) {}

    public record SummaryView(
            String period, String periodStatus,
            long vouchers, BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced,
            BigDecimal orderAccounts,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest, BigDecimal fees,
            /** IVA trasladado del período. No es ingreso; hace falta para conciliar contra lo facturado. */
            BigDecimal ivaAccrued,
            BigDecimal provision, BigDecimal writeOff, BigDecimal recovery,
            long loansRegistered,
            List<UnitMovementView> byUnit,
            BillingBridgeView billing) {

        public SummaryView(String period, String periodStatus, long vouchers, BigDecimal totalDebit,
                           BigDecimal totalCredit, boolean balanced, BigDecimal orderAccounts,
                           BigDecimal accruedInterest, BigDecimal moratoriumInterest, BigDecimal fees,
                           BigDecimal ivaAccrued, BigDecimal provision, BigDecimal writeOff, BigDecimal recovery,
                           long loansRegistered, List<UnitMovementView> byUnit) {
            this(period, periodStatus, vouchers, totalDebit, totalCredit, balanced, orderAccounts, accruedInterest,
                    moratoriumInterest, fees, ivaAccrued, provision, writeOff, recovery, loansRegistered, byUnit, null);
        }
    }

    /**
     * From the revenue the books recognized (IFRS) to what was charged to the clients and has to be invoiced (the
     * fiscal base): they differ by design, and each difference has its name.
     *
     * @param charged                   what the month charged the clients, IVA included: what the CFDIs must total
     * @param revenue                   interest, moratorium and fee revenue recognized (4101, 4102, 4103, 4106)
     * @param iva                       IVA charged (2110): not revenue, it is the tax authority's
     * @param stage3NotRecognized       interest of credit-impaired loans charged and invoiced but not revenue (IFRS 9
     *                                  net basis): it went to the allowance
     * @param deferredFeesCharged       opening fees charged and invoiced this month, recognized over the loan's life
     * @param deferredFeesRecognized    the month's share of fees charged in earlier months: revenue now, invoiced then
     * @param originationCostsAmortized the month's share of origination costs: lower revenue, nothing to invoice
     * @param unexplained               what none of the above explains; zero when the bridge holds
     */
    public record BillingBridgeView(BigDecimal charged, BigDecimal revenue, BigDecimal iva,
                                    BigDecimal stage3NotRecognized, BigDecimal deferredFeesCharged,
                                    BigDecimal deferredFeesRecognized, BigDecimal originationCostsAmortized,
                                    BigDecimal unexplained) {}

    /** El saldo acumulado de un préstamo en una cuenta contable. */
    public record LedgerBalanceView(
            String accountCode, String accountName, String accountType,
            BigDecimal debit, BigDecimal credit, BigDecimal balance) {}

    public record LoanLedgerView(
            UUID creditAccountId, UUID obligorPartyId, String orgUnitCode,
            List<LedgerBalanceView> balances, List<VoucherView> vouchers) {}

    public record PeriodView(String period, String status, Instant closedAt, long vouchers) {}

    /**
     * El movimiento de <b>un préstamo</b> en el período: la fila que agrupa a sus pólizas.
     *
     * <p>Con devengo diario, un mes de una cartera de cien créditos son varios miles de pólizas. El
     * libro plano no se puede agrupar en el navegador sin traérselas todas, y traérselas todas para
     * sumar cien filas es tirar el trabajo que la base hace en una consulta.
     *
     * <p>Agrupar por <b>cliente</b> sí se hace después sobre estas filas: los préstamos de un período
     * son cientos, no miles, y sus totales ya vienen exactos de aquí.
     */
    public record LoanMovementView(
            UUID creditAccountId, UUID obligorPartyId, String orgUnitCode,
            long vouchers, Instant firstVoucherDate, Instant lastVoucherDate,
            /** Cargos patrimoniales; las cuentas de orden van aparte por no ser del balance. */
            BigDecimal totalDebit, BigDecimal orderAccounts,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest, BigDecimal fees,
            BigDecimal provision, BigDecimal writeOff,
            /** Capital colocado en el período: lo que de verdad salió de caja. */
            BigDecimal disbursed) {}

    public record LoanMovementPage(
            List<LoanMovementView> content, int page, int size, long totalElements, int totalPages) {}
}
