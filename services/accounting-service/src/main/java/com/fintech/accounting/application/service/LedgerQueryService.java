package com.fintech.accounting.application.service;

import com.fintech.accounting.application.LedgerViews.*;
import com.fintech.accounting.application.TrialBalanceRow;
import com.fintech.accounting.application.port.in.GetLedgerUseCase;
import com.fintech.accounting.application.port.out.AccountingPeriodRepository;
import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.application.port.out.VoucherRepository;
import com.fintech.accounting.domain.AccountingPeriod;
import com.fintech.accounting.domain.JournalEntry;
import com.fintech.accounting.domain.Voucher;
import com.fintech.accounting.infrastructure.adapter.out.persistence.LedgerQueryDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Auxiliares por crédito, libro de pólizas, balanza y resumen del período. */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService implements GetLedgerUseCase {

    private final LedgerQueryDao dao;
    private final JournalEntryRepository journalRepository;
    private final VoucherRepository voucherRepository;
    private final AccountingPeriodRepository periodRepository;

    public LedgerQueryService(LedgerQueryDao dao,
                               JournalEntryRepository journalRepository,
                               VoucherRepository voucherRepository,
                               AccountingPeriodRepository periodRepository) {
        this.dao               = dao;
        this.journalRepository = journalRepository;
        this.voucherRepository = voucherRepository;
        this.periodRepository  = periodRepository;
    }

    // ── Contrato heredado ────────────────────────────────────────────────────

    @Override
    public List<JournalEntry> journalByAccount(UUID creditAccountId) {
        return journalRepository.findByCreditAccountId(creditAccountId);
    }

    @Override
    public List<JournalEntry> journalByParty(UUID obligorPartyId) {
        return journalRepository.findByObligorPartyId(obligorPartyId);
    }

    @Override
    public List<TrialBalanceRow> trialBalance(String period) {
        return dao.trialBalance(period, List.of()).stream()
                .map(r -> new TrialBalanceRow(r.accountCode(), r.accountName(), r.accountType(),
                        r.totalDebit(), r.totalCredit(), r.balance()))
                .toList();
    }

    // ── Pólizas ──────────────────────────────────────────────────────────────

    public VoucherPage vouchers(String period, List<String> unitCodes, String type,
                                UUID creditAccountId, UUID partyId, int page, int size) {
        long total = dao.countVouchers(period, unitCodes, type, creditAccountId, partyId);
        List<VoucherView> content = total == 0
                ? List.of()
                : dao.searchVouchers(period, unitCodes, type, creditAccountId, partyId, page, size);
        int totalPages = size <= 0 ? 1 : (int) Math.max(1, Math.ceil((double) total / size));
        return new VoucherPage(content, page, size, total, totalPages);
    }

    public VoucherView voucher(UUID voucherId) {
        return dao.voucherById(voucherId);
    }

    /**
     * El período agrupado por préstamo: una fila por crédito con movimiento.
     *
     * <p>Es la misma información que el libro de pólizas, contada por el eje con el que se concilia.
     * Con devengo diario el libro plano son miles de renglones al mes, así que agruparlo fuera de la
     * base deja de ser una opción.
     */
    public LoanMovementPage loanMovements(String period, List<String> unitCodes, int page, int size) {
        long total = dao.countLoans(period, unitCodes);
        List<LoanMovementView> content = total == 0
                ? List.of()
                : dao.loanMovements(period, unitCodes, page, size);
        int totalPages = size <= 0 ? 1 : (int) Math.max(1, Math.ceil((double) total / size));
        return new LoanMovementPage(content, page, size, total, totalPages);
    }


    public List<TrialBalanceRowView> trialBalance(String period, List<String> unitCodes) {
        return dao.trialBalance(period, unitCodes);
    }

    public List<UnitMovementView> unitMovements(String period, List<String> unitCodes) {
        return dao.unitMovements(period, unitCodes);
    }

    /**
     * El panorama del período.
     *
     * <p>Los totales salen de <b>una</b> consulta agregada, no de sumar las sucursales en memoria: si
     * se derivaran del desglose, filtrar por una rama daría un total distinto del que da la balanza
     * de esa misma rama y no habría forma de saber cuál es el bueno.
     *
     * <p>{@code byUnit} sólo se calcula cuando la pregunta es institucional. Pedir el desglose por
     * sucursal de <em>una</em> sucursal devolvería una lista de un elemento repitiendo el encabezado.
     */
    public SummaryView summary(String period, List<String> unitCodes, boolean includeUnits) {
        Object[] t = dao.periodTotals(period, unitCodes);
        long vouchers        = ((Number) t[0]).longValue();
        BigDecimal balance   = (BigDecimal) t[1];
        BigDecimal order     = (BigDecimal) t[2];
        BigDecimal accrued   = (BigDecimal) t[3];
        BigDecimal moratory  = (BigDecimal) t[4];
        BigDecimal fees      = (BigDecimal) t[5];
        BigDecimal provision = (BigDecimal) t[6];
        BigDecimal writeOff  = (BigDecimal) t[7];
        BigDecimal recovery  = (BigDecimal) t[8];
        long activations     = ((Number) t[9]).longValue();
        BigDecimal iva       = (BigDecimal) t[10];

        String status = periodRepository.findById(period)
                .map(p -> p.getStatus().name())
                .orElse("OPEN");

        return new SummaryView(period, status, vouchers,
                // Cargos y abonos son la misma suma: cada par aporta su importe a los dos lados, así
                // que el cuadre está garantizado por construcción. Se devuelven las dos columnas
                // porque la pantalla las contrasta, y un cuadre que nadie enseña no tranquiliza a nadie.
                balance, balance, true, order,
                accrued, moratory, fees, iva, provision, writeOff, recovery, activations,
                includeUnits ? dao.unitMovements(period, unitCodes) : List.of());
    }

    // ── Auxiliar de un préstamo ──────────────────────────────────────────────

    public LoanLedgerView loanLedger(UUID creditAccountId) {
        List<Voucher> vouchers = voucherRepository.findByCreditAccountId(creditAccountId);
        List<VoucherView> views = vouchers.stream()
                .sorted((a, b) -> b.getVoucherDate().compareTo(a.getVoucherDate()))
                .map(v -> new VoucherView(v.getVoucherId(), v.getVoucherType().name(), v.getFolio(),
                        v.getPeriod(), v.getVoucherDate(), v.getOrgUnitCode(), v.getConcept(),
                        v.getTriggerEvent(), v.getSourceEventId(), v.getCreditAccountId(),
                        v.getObligorPartyId(), v.getTotalDebit(), v.getTotalCredit(),
                        v.getStatus().name(), v.isLatePosting(), v.getOriginalPeriod(),
                        v.getReversalRef(), List.of()))
                .toList();

        UUID partyId = vouchers.stream().map(Voucher::getObligorPartyId)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String unit = vouchers.stream().map(Voucher::getOrgUnitCode)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);

        return new LoanLedgerView(creditAccountId, partyId, unit,
                dao.loanBalances(creditAccountId), views);
    }

    // ── Períodos ─────────────────────────────────────────────────────────────

    public List<PeriodView> periods() {
        return dao.periods();
    }

    @Transactional
    public AccountingPeriod closePeriod(String period) {
        AccountingPeriod p = periodRepository.findById(period)
                .orElseGet(() -> AccountingPeriod.open(period));
        p.close();
        return periodRepository.save(p);
    }

    /**
     * Atribuye a una sucursal las pólizas de un crédito que se asentaron antes de que se supiera
     * cuál era. Idempotente: sólo rellena lo que está en nulo.
     */
    @Transactional
    public int attributeUnit(UUID creditAccountId, String orgUnitCode) {
        if (orgUnitCode == null || orgUnitCode.isBlank()) return 0;
        return dao.attributeUnit(creditAccountId, orgUnitCode);
    }

    /** Barrido: atribuye toda póliza cuyo crédito ya tenga sucursal conocida. Idempotente. */
    @Transactional
    public int sweepUnits() {
        return dao.sweepUnits();
    }

    @Transactional
    public AccountingPeriod reopenPeriod(String period) {
        AccountingPeriod p = periodRepository.findById(period)
                .orElseThrow(() -> new IllegalArgumentException("El período " + period + " no existe"));
        p.reopen();
        return periodRepository.save(p);
    }
}
