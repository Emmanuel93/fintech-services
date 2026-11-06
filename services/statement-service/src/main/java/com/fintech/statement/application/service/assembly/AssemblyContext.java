package com.fintech.statement.application.service.assembly;

import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.PartySummary;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.domain.CutoffFacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Todo lo que un ensamblador necesita para llenar su plantilla, ya reunido.
 *
 * @param previousBalance el saldo al corte del estado anterior, si lo hubo
 * @param commission      what a distributor earns on her line; {@link DistributorCommission#NONE} for any other
 */
public record AssemblyContext(UUID statementId, CutoffFacts facts, SourceView view, PartySummary holder,
                              Map<UUID, PartySummary> beneficiaries, Optional<BigDecimal> previousBalance,
                              StatementProperties properties, ZoneId zone, Instant generatedAt,
                              DistributorCommission commission) {

    public AssemblyContext {
        commission = commission == null ? DistributorCommission.NONE : commission;
    }

    /** A statement without a distributor's commission. */
    public AssemblyContext(UUID statementId, CutoffFacts facts, SourceView view, PartySummary holder,
                           Map<UUID, PartySummary> beneficiaries, Optional<BigDecimal> previousBalance,
                           StatementProperties properties, ZoneId zone, Instant generatedAt) {
        this(statementId, facts, view, holder, beneficiaries, previousBalance, properties, zone, generatedAt,
                DistributorCommission.NONE);
    }
}
