package com.fintech.statement.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Dónde vive el PDF.
 *
 * <p>Hoy en la base ({@code bytea}), como el resto de los documentos de la plataforma: no hay un
 * almacén de objetos todavía. El puerto es el lugar donde entra uno (S3, GCS, Blob) sin tocar la
 * generación: guardar y leer por id es todo lo que ésta necesita.
 */
public interface StatementDocumentStore {

    void store(UUID statementId, byte[] content, String contentType);

    Optional<StoredDocument> load(UUID statementId);

    record StoredDocument(byte[] content, String contentType, String sha256) {}
}
