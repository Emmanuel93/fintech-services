package com.fintech.audit.infrastructure.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.audit.application.service.failure.FailedMessageRepository;
import com.fintech.audit.domain.failure.FailedMessage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcFailedMessageRepository implements FailedMessageRepository {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcFailedMessageRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean record(FailedMessage m) {
        return jdbc.update("""
                INSERT INTO audit.failed_messages
                       (failed_message_id, dlt_topic, dlt_partition, dlt_offset, original_topic, original_partition,
                        original_offset, consumer_group, service, correlation_id, message_key, payload, headers,
                        exception_class, exception_message, failed_at)
                VALUES (:id, :dltTopic, :dltPartition, :dltOffset, :topic, :partition, :offset, :group, :service,
                        :correlation, :key, :payload, CAST(:headers AS jsonb), :exClass, :exMessage, :failedAt)
                ON CONFLICT (dlt_topic, dlt_partition, dlt_offset) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", m.failedMessageId())
                .addValue("dltTopic", m.dltTopic()).addValue("dltPartition", m.dltPartition()).addValue("dltOffset", m.dltOffset())
                .addValue("topic", m.originalTopic()).addValue("partition", m.originalPartition()).addValue("offset", m.originalOffset())
                .addValue("group", m.consumerGroup()).addValue("service", m.service()).addValue("correlation", m.correlationId())
                .addValue("key", m.messageKey()).addValue("payload", m.payload()).addValue("headers", write(m.headers()))
                .addValue("exClass", m.exceptionClass()).addValue("exMessage", m.exceptionMessage())
                .addValue("failedAt", m.failedAt() == null ? null : Timestamp.from(m.failedAt()))) > 0;
    }

    @Override
    public Optional<FailedMessage> findById(UUID id) {
        return jdbc.query("SELECT * FROM audit.failed_messages WHERE failed_message_id = :id",
                new MapSqlParameterSource("id", id), ROW).stream().findFirst();
    }

    @Override
    public List<FailedMessage> search(String correlationId, String originalTopic, String status, int page, int size) {
        return jdbc.query("""
                SELECT * FROM audit.failed_messages
                 WHERE (CAST(:correlation AS text) IS NULL OR correlation_id = :correlation)
                   AND (CAST(:topic AS text) IS NULL OR original_topic = :topic)
                   AND (CAST(:status AS text) IS NULL OR status = :status)
                 ORDER BY received_at DESC
                 LIMIT :size OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("correlation", correlationId).addValue("topic", originalTopic).addValue("status", status)
                .addValue("size", size).addValue("offset", page * size), ROW);
    }

    @Override
    public void markReplayed(UUID id, String by, String note) {
        jdbc.update("""
                UPDATE audit.failed_messages
                   SET status = 'REPLAYED', replays = replays + 1, resolved_by = :by, resolved_at = now(), resolution_note = :note
                 WHERE failed_message_id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("by", by).addValue("note", note));
    }

    @Override
    public void markDiscarded(UUID id, String by, String note) {
        jdbc.update("""
                UPDATE audit.failed_messages
                   SET status = 'DISCARDED', resolved_by = :by, resolved_at = now(), resolution_note = :note
                 WHERE failed_message_id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("by", by).addValue("note", note));
    }

    private final RowMapper<FailedMessage> ROW = (rs, i) -> new FailedMessage(
            rs.getObject("failed_message_id", UUID.class),
            rs.getString("dlt_topic"), rs.getInt("dlt_partition"), rs.getLong("dlt_offset"),
            rs.getString("original_topic"), (Integer) rs.getObject("original_partition"), (Long) rs.getObject("original_offset"),
            rs.getString("consumer_group"), rs.getString("service"), rs.getString("correlation_id"),
            rs.getString("message_key"), rs.getString("payload"), read(rs.getString("headers")),
            rs.getString("exception_class"), rs.getString("exception_message"),
            instant(rs.getTimestamp("failed_at")), instant(rs.getTimestamp("received_at")),
            rs.getString("status"), rs.getInt("replays"), rs.getString("resolved_by"),
            instant(rs.getTimestamp("resolved_at")), rs.getString("resolution_note"));

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private String write(Map<String, String> headers) {
        try {
            return json.writeValueAsString(headers == null ? Map.of() : headers);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudieron guardar los headers del mensaje fallido", e);
        }
    }

    private Map<String, String> read(String headers) {
        try {
            return headers == null ? Map.of() : json.readValue(headers, HEADERS);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
