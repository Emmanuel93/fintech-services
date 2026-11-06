package com.fintech.receipt.infrastructure.adapter.out.persistence;

import com.fintech.receipt.application.port.out.ReceiptDocumentStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcReceiptDocumentStore implements ReceiptDocumentStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcReceiptDocumentStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(UUID receiptId, byte[] content, String contentType) {
        jdbc.update("""
                INSERT INTO receipt.receipt_documents (receipt_id, content, content_type, size_bytes, sha256)
                VALUES (:id, :content, :type, :size, :sha)
                ON CONFLICT (receipt_id) DO UPDATE
                   SET content = EXCLUDED.content, content_type = EXCLUDED.content_type,
                       size_bytes = EXCLUDED.size_bytes, sha256 = EXCLUDED.sha256, created_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", receiptId)
                .addValue("content", content)
                .addValue("type", contentType)
                .addValue("size", content.length)
                .addValue("sha", sha256(content)));
    }

    @Override
    public Optional<StoredDocument> load(UUID receiptId) {
        return jdbc.query("SELECT content, content_type, sha256 FROM receipt.receipt_documents WHERE receipt_id = :id",
                new MapSqlParameterSource("id", receiptId),
                (rs, i) -> new StoredDocument(rs.getBytes("content"), rs.getString("content_type"), rs.getString("sha256")))
                .stream().findFirst();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
