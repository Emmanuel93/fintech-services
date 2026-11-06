package com.fintech.receipt.infrastructure.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.receipt.application.port.out.ReceiptRepository;
import com.fintech.receipt.domain.CutoffFacts;
import com.fintech.receipt.domain.Receipt;
import com.fintech.receipt.domain.ReceiptStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcReceiptRepository implements ReceiptRepository {

    private static final String COLUMNS = """
            receipt_id, credit_account_id, cycle_number, beneficiary_party_id, distributor_party_id, period_start,
            cutoff_date, payment_due_date, status, attempts, last_error, folio, client_name, client_type, live_loans,
            total_due, payment_reference, clabe, template_version, document_bytes, generated_at, created_at""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcReceiptRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public UUID register(CutoffFacts f, UUID beneficiaryPartyId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("account", f.creditAccountId())
                .addValue("cycle", f.cycleNumber())
                .addValue("client", beneficiaryPartyId)
                .addValue("distributor", f.obligorPartyId())
                .addValue("run", f.runId())
                .addValue("from", f.effectivePeriodStart())
                .addValue("cutoff", f.cutoffDate())
                .addValue("due", f.paymentDueDate())
                .addValue("event", f.sourceEventId())
                .addValue("facts", write(f));
        List<UUID> created = jdbc.queryForList("""
                INSERT INTO receipt.receipts
                       (receipt_id, credit_account_id, cycle_number, beneficiary_party_id, distributor_party_id, run_id,
                        period_start, cutoff_date, payment_due_date, source_event_id, facts)
                VALUES (:id, :account, :cycle, :client, :distributor, :run, :from, :cutoff, :due, :event, CAST(:facts AS jsonb))
                ON CONFLICT (credit_account_id, cycle_number, beneficiary_party_id) DO NOTHING
                RETURNING receipt_id
                """, p, UUID.class);
        if (!created.isEmpty()) return created.getFirst();
        return jdbc.queryForObject("""
                SELECT receipt_id FROM receipt.receipts
                 WHERE credit_account_id = :account AND cycle_number = :cycle AND beneficiary_party_id = :client
                """, p, UUID.class);
    }

    @Override
    public Optional<Claim> claim(UUID receiptId, String owner, Duration staleAfter) {
        return jdbc.query("""
                UPDATE receipt.receipts
                   SET status = 'GENERATING', attempts = attempts + 1, generated_by = :owner, updated_at = now()
                 WHERE receipt_id = :id
                   AND (status IN ('PENDING','FAILED')
                        OR (status = 'GENERATING' AND updated_at < now() - make_interval(secs => :stale)))
                RETURNING receipt_id, facts::text AS facts, beneficiary_party_id, attempts
                """, new MapSqlParameterSource()
                        .addValue("id", receiptId)
                        .addValue("owner", owner)
                        .addValue("stale", (double) staleAfter.toSeconds()),
                (rs, i) -> new Claim(rs.getObject("receipt_id", UUID.class), read(rs.getString("facts")),
                        rs.getObject("beneficiary_party_id", UUID.class), rs.getInt("attempts")))
                .stream().findFirst();
    }

    @Override
    public void markGenerated(UUID receiptId, Generated g) {
        jdbc.update("""
                UPDATE receipt.receipts
                   SET status = 'GENERATED', folio = :folio, distributor_party_id = COALESCE(:distributor, distributor_party_id),
                       client_name = :name, client_type = :type, live_loans = :live, total_due = :total,
                       payment_reference = :reference, clabe = :clabe, snapshot = CAST(:snapshot AS jsonb),
                       template_version = :template, document_bytes = :bytes, generated_by = :owner,
                       generated_at = now(), updated_at = now(), last_error = NULL
                 WHERE receipt_id = :id
                """, new MapSqlParameterSource()
                .addValue("id", receiptId)
                .addValue("folio", g.folio())
                .addValue("distributor", g.distributorPartyId())
                .addValue("name", g.clientName())
                .addValue("type", g.clientType())
                .addValue("live", g.liveLoans())
                .addValue("total", g.totalDue())
                .addValue("reference", g.paymentReference())
                .addValue("clabe", g.clabe())
                .addValue("snapshot", g.snapshotJson())
                .addValue("template", g.templateVersion())
                .addValue("bytes", g.documentBytes())
                .addValue("owner", g.generatedBy()));
    }

    @Override
    public void markFailed(UUID receiptId, String error) {
        jdbc.update("""
                UPDATE receipt.receipts SET status = 'FAILED', last_error = :error, updated_at = now() WHERE receipt_id = :id
                """, new MapSqlParameterSource()
                .addValue("id", receiptId)
                .addValue("error", error == null ? null : error.substring(0, Math.min(500, error.length()))));
    }

    @Override
    public Optional<Receipt> findById(UUID receiptId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM receipt.receipts WHERE receipt_id = :id",
                new MapSqlParameterSource("id", receiptId), ROW).stream().findFirst();
    }

    @Override
    public List<Receipt> search(UUID creditAccountId, Integer cycleNumber, UUID partyId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM receipt.receipts
                 WHERE (CAST(:account AS uuid) IS NULL OR credit_account_id = CAST(:account AS uuid))
                   AND (CAST(:cycle AS integer) IS NULL OR cycle_number = CAST(:cycle AS integer))
                   AND (CAST(:party AS uuid) IS NULL OR beneficiary_party_id = CAST(:party AS uuid)
                        OR distributor_party_id = CAST(:party AS uuid))
                 ORDER BY cutoff_date DESC, client_name NULLS LAST, receipt_id
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("account", creditAccountId)
                .addValue("cycle", cycleNumber)
                .addValue("party", partyId)
                .addValue("limit", limit), ROW);
    }

    @Override
    public List<UUID> retryable(int maxAttempts, Duration staleAfter, int limit) {
        return jdbc.query("""
                SELECT receipt_id FROM receipt.receipts
                 WHERE (status IN ('PENDING','FAILED') AND attempts < :max)
                    OR (status = 'GENERATING' AND updated_at < now() - make_interval(secs => :stale))
                 ORDER BY updated_at
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("max", maxAttempts)
                .addValue("stale", (double) staleAfter.toSeconds())
                .addValue("limit", limit), (rs, i) -> rs.getObject("receipt_id", UUID.class));
    }

    @Override
    public Optional<String> findSnapshot(UUID receiptId) {
        return jdbc.query("SELECT snapshot::text AS snapshot FROM receipt.receipts WHERE receipt_id = :id",
                        new MapSqlParameterSource("id", receiptId), (rs, i) -> rs.getString("snapshot"))
                .stream().filter(Objects::nonNull).findFirst();
    }

    @Override
    public List<UUID> generated(UUID creditAccountId, Integer cycleNumber, UUID after, int limit) {
        return jdbc.query("""
                SELECT receipt_id FROM receipt.receipts
                 WHERE status = 'GENERATED'
                   AND (CAST(:account AS uuid) IS NULL OR credit_account_id = CAST(:account AS uuid))
                   AND (CAST(:cycle AS integer) IS NULL OR cycle_number = CAST(:cycle AS integer))
                   AND (CAST(:after AS uuid) IS NULL OR receipt_id > CAST(:after AS uuid))
                 ORDER BY receipt_id
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("account", creditAccountId)
                .addValue("cycle", cycleNumber)
                .addValue("after", after)
                .addValue("limit", limit), (rs, i) -> rs.getObject("receipt_id", UUID.class));
    }

    @Override
    public void markRegenerated(UUID receiptId, String templateVersion, long documentBytes) {
        jdbc.update("""
                UPDATE receipt.receipts SET template_version = :template, document_bytes = :bytes, updated_at = now()
                 WHERE receipt_id = :id
                """, new MapSqlParameterSource()
                .addValue("id", receiptId)
                .addValue("template", templateVersion)
                .addValue("bytes", documentBytes));
    }

    @Override
    public List<StatusCount> countByStatus() {
        return jdbc.query("SELECT status, count(*) AS n FROM receipt.receipts GROUP BY status ORDER BY status",
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("n")));
    }

    private static final RowMapper<Receipt> ROW = (rs, i) -> new Receipt(
            rs.getObject("receipt_id", UUID.class),
            rs.getObject("credit_account_id", UUID.class),
            rs.getInt("cycle_number"),
            rs.getObject("beneficiary_party_id", UUID.class),
            rs.getObject("distributor_party_id", UUID.class),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("cutoff_date", LocalDate.class),
            rs.getObject("payment_due_date", LocalDate.class),
            ReceiptStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getString("last_error"),
            rs.getString("folio"),
            rs.getString("client_name"),
            rs.getString("client_type"),
            (Integer) rs.getObject("live_loans"),
            rs.getBigDecimal("total_due"),
            (Long) rs.getObject("payment_reference"),
            rs.getString("clabe"),
            rs.getString("template_version"),
            (Long) rs.getObject("document_bytes"),
            instant(rs.getTimestamp("generated_at")),
            instant(rs.getTimestamp("created_at")));

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

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
            throw new IllegalStateException("No se pudo leer lo que dijo el corte", e);
        }
    }
}
