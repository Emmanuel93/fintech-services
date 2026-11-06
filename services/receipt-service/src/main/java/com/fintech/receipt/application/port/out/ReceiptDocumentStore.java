package com.fintech.receipt.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** The PDF of each receipt, kept next to it. */
public interface ReceiptDocumentStore {

    void store(UUID receiptId, byte[] content, String contentType);

    Optional<StoredDocument> load(UUID receiptId);

    record StoredDocument(byte[] content, String contentType, String sha256) {}
}
