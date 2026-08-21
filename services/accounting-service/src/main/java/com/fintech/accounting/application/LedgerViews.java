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
            List<VoucherLineView> lines) {}

    public record VoucherPage(
            List<VoucherView> content, int page, int size, long totalElements, int totalPages) {}

    /** Un renglón de la balanza de comprobación. */
    public record TrialBalanceRowView(
            String accountCode, String accountName, String accountType,
            BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal balance) {}

    /** El movimiento de una sucursal en el período. Agregado en SQL, no en el BFF. */
    public record UnitMovementView(
            String orgUnitCode, long vouchers, long loans,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest,
            BigDecimal fees, BigDecimal provision) {}

    public record SummaryView(
            String period, String periodStatus,
            long vouchers, BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced,
            BigDecimal orderAccounts,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest, BigDecimal fees,
            /** IVA trasladado del período. No es ingreso; hace falta para conciliar contra lo facturado. */
            BigDecimal ivaAccrued,
            BigDecimal provision, BigDecimal writeOff, BigDecimal recovery,
            long loansRegistered,
            List<UnitMovementView> byUnit) {}

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
