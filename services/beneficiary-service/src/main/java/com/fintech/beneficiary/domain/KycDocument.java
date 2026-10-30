package com.fintech.beneficiary.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Una captura del expediente: frente o reverso de la INE, o la selfie.
 *
 * <p>Vive aquí y no sólo en origination porque <b>se captura antes de que exista el prospecto</b>:
 * el prospecto nace al cerrar el KYC, y para entonces las fotos ya se tomaron. Al cerrar se
 * copian a origination, que es el expediente de la persona; ésta es la evidencia de la colocación,
 * la que mira el analista en la mesa de KYC.
 *
 * <p>Una por tipo y por colocación: volver a tomar la foto reemplaza la anterior. La que cuenta es
 * la última que ella decidió enviar.
 */
@Entity
@Table(schema = "beneficiary", name = "kyc_documents")
public class KycDocument {

    @Id
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "placement_id", nullable = false, updatable = false)
    private UUID placementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, updatable = false, length = 20)
    private KycDocumentType documentType;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    // Sin `@Lob`: sobre un `byte[]` Hibernate lo mapearía a un OID de Postgres —un objeto grande
    // fuera de la fila— y la columna es `bytea`. Es el mismo cuidado que ProspectDocumentFile.
    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected KycDocument() {}

    public static KycDocument of(UUID placementId, KycDocumentType type, String contentType,
                                 byte[] content, Instant now) {
        KycDocument d = new KycDocument();
        d.documentId   = UUID.randomUUID();
        d.placementId  = placementId;
        d.documentType = type;
        d.replaceWith(contentType, content, now);
        return d;
    }

    public void replaceWith(String contentType, byte[] content, Instant now) {
        if (content == null || content.length == 0) {
            throw new PlacementValidationException("El archivo viene vacío");
        }
        this.contentType = contentType;
        this.content     = content;
        this.sizeBytes   = content.length;
        this.uploadedAt  = now;
    }

    public UUID getDocumentId()             { return documentId; }
    public UUID getPlacementId()            { return placementId; }
    public KycDocumentType getDocumentType() { return documentType; }
    public String getContentType()          { return contentType; }
    public long getSizeBytes()              { return sizeBytes; }
    public byte[] getContent()              { return content; }
    public Instant getUploadedAt()          { return uploadedAt; }
}
