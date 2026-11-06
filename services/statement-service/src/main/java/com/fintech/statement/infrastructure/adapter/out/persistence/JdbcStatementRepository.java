package com.fintech.statement.infrastructure.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.statement.application.port.out.StatementRepository;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.Statement;
import com.fintech.statement.domain.StatementLayout;
import com.fintech.statement.domain.StatementStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcStatementRepository implements StatementRepository {

    private static final String COLUMNS = """
            statement_id, credit_account_id, cycle_number, run_id, obligor_party_id, holder_kind, product_type,
            product_behavior, layout, period_start, cutoff_date, payment_due_date, balance_at_cutoff, amount_due,
            minimum_payment, status, attempts, last_error, contract_number, document_bytes, template_version,
            generated_by, generated_at, created_at""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcStatementRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public UUID register(CutoffFacts f, StatementLayout layout) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("account", f.creditAccountId())
                .addValue("cycle", f.cycleNumber())
                .addValue("run", f.runId())
                .addValue("party", f.obligorPartyId())
                .addValue("holder", f.effectiveHolderKind())
                .addValue("product", f.productType())
                .addValue("behavior", f.effectiveBehavior())
                .addValue("layout", layout.name())
                .addValue("from", f.effectivePeriodStart())
                .addValue("cutoff", f.cutoffDate())
                .addValue("due", f.paymentDueDate())
                .addValue("balance", f.balanceAtCutoff())
                .addValue("event", f.sourceEventId())
                .addValue("facts", write(f));
        // La llave natural decide: si el corte ya llegó antes, se devuelve el documento que existe.
        List<UUID> nuevo = jdbc.queryForList("""
                INSERT INTO statement.statements
                       (statement_id, credit_account_id, cycle_number, run_id, obligor_party_id, holder_kind,
                        product_type, product_behavior, layout, period_start, cutoff_date, payment_due_date,
                        balance_at_cutoff, source_event_id, facts)
                VALUES (:id, :account, :cycle, :run, :party, :holder, :product, :behavior, :layout, :from,
                        :cutoff, :due, :balance, :event, CAST(:facts AS jsonb))
                ON CONFLICT (credit_account_id, cycle_number) DO NOTHING
                RETURNING statement_id
                """, p, UUID.class);
        if (!nuevo.isEmpty()) return nuevo.get(0);
        return jdbc.queryForObject("""
                SELECT statement_id FROM statement.statements WHERE credit_account_id = :account AND cycle_number = :cycle
                """, p, UUID.class);
    }

    @Override
    public Optional<Claim> claim(UUID statementId, String owner, Duration staleAfter) {
        List<Claim> tomado = jdbc.query("""
                UPDATE statement.statements
                   SET status = 'GENERATING', attempts = attempts + 1, generated_by = :owner, updated_at = now()
                 WHERE statement_id = :id
                   AND (status IN ('PENDING','FAILED')
                        OR (status = 'GENERATING' AND updated_at < now() - make_interval(secs => :stale)))
                RETURNING statement_id, facts::text AS facts, layout, attempts
                """, new MapSqlParameterSource()
                        .addValue("id", statementId)
                        .addValue("owner", owner)
                        .addValue("stale", (double) staleAfter.toSeconds()),
                (rs, i) -> new Claim(rs.getObject("statement_id", UUID.class),
                        read(rs.getString("facts")), StatementLayout.valueOf(rs.getString("layout")),
                        rs.getInt("attempts")));
        return tomado.stream().findFirst();
    }

    @Override
    public void markGenerated(UUID statementId, Generated g) {
        jdbc.update("""
                UPDATE statement.statements
                   SET status = 'GENERATED', contract_number = :contract, amount_due = :due, minimum_payment = :min,
                       snapshot = CAST(:snapshot AS jsonb), template_version = :template, document_bytes = :bytes,
                       generated_by = :owner, generated_at = now(), updated_at = now(), last_error = NULL
                 WHERE statement_id = :id
                """, new MapSqlParameterSource()
                .addValue("id", statementId)
                .addValue("contract", g.contractNumber())
                .addValue("due", g.amountDue())
                .addValue("min", g.minimumPayment())
                .addValue("snapshot", g.snapshotJson())
                .addValue("template", g.templateVersion())
                .addValue("bytes", g.documentBytes())
                .addValue("owner", g.generatedBy()));
    }

    @Override
    public Optional<String> findSnapshot(UUID statementId) {
        return jdbc.query("SELECT snapshot::text AS snapshot FROM statement.statements WHERE statement_id = :id",
                        new MapSqlParameterSource("id", statementId), (rs, i) -> rs.getString("snapshot"))
                .stream().filter(Objects::nonNull).findFirst();
    }

    @Override
    public List<UUID> generated(UUID creditAccountId, UUID after, int limit) {
        return jdbc.query("""
                SELECT statement_id FROM statement.statements
                 WHERE status = 'GENERATED'
                   AND (CAST(:account AS uuid) IS NULL OR credit_account_id = CAST(:account AS uuid))
                   AND (CAST(:after AS uuid) IS NULL OR statement_id > CAST(:after AS uuid))
                 ORDER BY statement_id
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("account", creditAccountId)
                .addValue("after", after)
                .addValue("limit", limit), (rs, i) -> rs.getObject("statement_id", UUID.class));
    }

    @Override
    public void markRegenerated(UUID statementId, String templateVersion, long documentBytes) {
        jdbc.update("""
                UPDATE statement.statements
                   SET template_version = :template, document_bytes = :bytes, updated_at = now()
                 WHERE statement_id = :id
                """, new MapSqlParameterSource()
                .addValue("id", statementId)
                .addValue("template", templateVersion)
                .addValue("bytes", documentBytes));
    }

    @Override
    public void markFailed(UUID statementId, String error) {
        jdbc.update("""
                UPDATE statement.statements SET status = 'FAILED', last_error = :error, updated_at = now()
                 WHERE statement_id = :id
                """, new MapSqlParameterSource().addValue("id", statementId)
                .addValue("error", error != null && error.length() > 500 ? error.substring(0, 500) : error));
    }

    @Override
    public Optional<Statement> findById(UUID statementId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM statement.statements WHERE statement_id = :id",
                new MapSqlParameterSource("id", statementId), STATEMENT).stream().findFirst();
    }

    @Override
    public List<Statement> findByAccount(UUID creditAccountId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM statement.statements WHERE credit_account_id = :account ORDER BY cycle_number DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("account", creditAccountId).addValue("limit", limit), STATEMENT);
    }

    @Override
    public List<Statement> findByParty(UUID obligorPartyId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM statement.statements WHERE obligor_party_id = :party ORDER BY cutoff_date DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("party", obligorPartyId).addValue("limit", limit), STATEMENT);
    }

    @Override
    public Optional<BigDecimal> previousBalance(UUID creditAccountId, int cycleNumber) {
        return jdbc.queryForList("""
                SELECT balance_at_cutoff FROM statement.statements
                 WHERE credit_account_id = :account AND cycle_number = :cycle AND status = 'GENERATED'
                """, new MapSqlParameterSource().addValue("account", creditAccountId).addValue("cycle", cycleNumber - 1),
                BigDecimal.class).stream().filter(Objects::nonNull).findFirst();
    }

    @Override
    public List<UUID> retryable(int maxAttempts, Duration staleAfter, int limit) {
        return jdbc.queryForList("""
                SELECT statement_id FROM statement.statements
                 WHERE (status = 'FAILED' AND attempts < :max)
                    OR (status IN ('PENDING','GENERATING') AND updated_at < now() - make_interval(secs => :stale))
                 ORDER BY updated_at
                 LIMIT :limit
                """, new MapSqlParameterSource().addValue("max", maxAttempts)
                .addValue("stale", (double) staleAfter.toSeconds()).addValue("limit", limit), UUID.class);
    }

    @Override
    public List<ProgressLine> progress(UUID runId) {
        return jdbc.query("""
                SELECT product_type, holder_kind, status, COUNT(*) AS n FROM statement.statements
                 WHERE run_id = :run GROUP BY product_type, holder_kind, status ORDER BY holder_kind, product_type, status
                """, new MapSqlParameterSource("run", runId),
                (rs, i) -> new ProgressLine(rs.getString("product_type"), rs.getString("holder_kind"),
                        rs.getString("status"), rs.getInt("n")));
    }

    private static final RowMapper<Statement> STATEMENT = (rs, i) -> new Statement(
            rs.getObject("statement_id", UUID.class), rs.getObject("credit_account_id", UUID.class),
            rs.getInt("cycle_number"), rs.getObject("run_id", UUID.class), rs.getObject("obligor_party_id", UUID.class),
            rs.getString("holder_kind"), rs.getString("product_type"), rs.getString("product_behavior"),
            StatementLayout.valueOf(rs.getString("layout")), rs.getObject("period_start", java.time.LocalDate.class),
            rs.getObject("cutoff_date", java.time.LocalDate.class), rs.getObject("payment_due_date", java.time.LocalDate.class),
            rs.getBigDecimal("balance_at_cutoff"), rs.getBigDecimal("amount_due"), rs.getBigDecimal("minimum_payment"),
            StatementStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getString("last_error"),
            rs.getString("contract_number"), (Long) rs.getObject("document_bytes"), rs.getString("template_version"),
            rs.getString("generated_by"), instant(rs.getTimestamp("generated_at")), instant(rs.getTimestamp("created_at")));

    private static java.time.Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }

    private String write(CutoffFacts facts) {
        try {
            return json.writeValueAsString(facts);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo guardar lo que dijo el corte", e);
        }
    }

    private CutoffFacts read(String facts) {
        try {
            return json.readValue(facts, CutoffFacts.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lo que dijo el corte quedó ilegible", e);
        }
    }
}
