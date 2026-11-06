package com.fintech.statement.application.port.out;

import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.Statement;
import com.fintech.statement.domain.StatementLayout;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository {

    /**
     * Registra el estado de cuenta del corte, o devuelve el que ya existía para esa cuenta y ciclo.
     * Un corte repetido no crea un segundo documento.
     */
    UUID register(CutoffFacts facts, StatementLayout layout);

    /**
     * Lo toma para generarlo, de forma atómica: sólo si está PENDING, FAILED, o GENERATING de un pod
     * que dejó de dar señales hace más de {@code staleAfter}. Vacío = otro lo tiene o ya está hecho.
     */
    Optional<Claim> claim(UUID statementId, String owner, Duration staleAfter);

    void markGenerated(UUID statementId, Generated generated);

    void markFailed(UUID statementId, String error);

    Optional<Statement> findById(UUID statementId);

    List<Statement> findByAccount(UUID creditAccountId, int limit);

    List<Statement> findByParty(UUID obligorPartyId, int limit);

    /** La foto (corte + vista) con la que se generó el documento, como se guardó. */
    Optional<String> findSnapshot(UUID statementId);
    /** Los generados, por id ascendente, de una cuenta o de todas; {@code after} pagina. */
    List<UUID> generated(UUID creditAccountId, UUID after, int limit);
    /** El documento se volvió a producir con otra plantilla; la fecha de emisión no cambia. */
    void markRegenerated(UUID statementId, String templateVersion, long documentBytes);
    /** El saldo al corte del estado anterior: es el «saldo anterior» de éste. */
    Optional<BigDecimal> previousBalance(UUID creditAccountId, int cycleNumber);

    /** Los que hay que volver a intentar: fallidos con intentos, o tomados por un pod que murió. */
    List<UUID> retryable(int maxAttempts, Duration staleAfter, int limit);

    /** Cuántos hay de una corrida, por producto y estado. */
    List<ProgressLine> progress(UUID runId);

    record Claim(UUID statementId, CutoffFacts facts, StatementLayout layout, int attempts) {}

    record Generated(String contractNumber, BigDecimal amountDue, BigDecimal minimumPayment,
                     String snapshotJson, String templateVersion, long documentBytes, String generatedBy) {}

    record ProgressLine(String productType, String holderKind, String status, int count) {}
}
