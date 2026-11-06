package com.fintech.statement.infrastructure.adapter.out.persistence;

import com.fintech.statement.application.port.out.StatementDocumentStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * El PDF en {@code bytea}, con su huella SHA-256: lo que se descarga mañana es lo que se generó hoy.
 */
@Repository
class JdbcStatementDocumentStore implements StatementDocumentStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcStatementDocumentStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(UUID statementId, byte[] content, String contentType) {
        // Un reintento que llega después de un éxito parcial reemplaza el documento, no lo duplica.
        jdbc.update("""
                INSERT INTO statement.statement_documents (statement_id, content, content_type, size_bytes, sha256)
                VALUES (:id, :content, :type, :size, :sha)
                ON CONFLICT (statement_id) DO UPDATE
                   SET content = EXCLUDED.content, content_type = EXCLUDED.content_type,
                       size_bytes = EXCLUDED.size_bytes, sha256 = EXCLUDED.sha256, created_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", statementId)
                .addValue("content", content)
                .addValue("type", contentType)
                .addValue("size", content.length)
                .addValue("sha", sha256(content)));
    }

    @Override
    public Optional<StoredDocument> load(UUID statementId) {
        return jdbc.query("""
                SELECT content, content_type, sha256 FROM statement.statement_documents WHERE statement_id = :id
                """, new MapSqlParameterSource("id", statementId),
                (rs, i) -> new StoredDocument(rs.getBytes("content"), rs.getString("content_type"),
                        rs.getString("sha256"))).stream().findFirst();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
