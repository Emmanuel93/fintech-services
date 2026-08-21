package com.fintech.origination.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * El archivo de un documento del expediente.
 *
 * <p>Separado de {@link ProspectDocument} a propósito: aquella es la <b>declaración</b> de que se
 * entregó una INE —con su tipo y su sello de tiempo, que es lo que se consulta en cada listado—;
 * esta es el archivo, que pesa y sólo se pide cuando alguien lo va a mirar. Cargarlos juntos haría
 * que abrir una bandeja de treinta solicitudes arrastrara treinta fotos de credencial.
 *
 * <p>{@code storageRef} está previsto para cuando exista un almacén de objetos: entonces el
 * contenido se migra allá y esta entidad conserva el mismo contrato para quien la lee.
 */
@Entity
@Table(name = "prospect_document_files", schema = "origination")
public class ProspectDocumentFile {

    @Id
    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Column(name = "prospect_id", nullable = false)
    private UUID prospectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private ProspectDocumentType documentType;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    // Sin `@Lob`: sobre un `byte[]` Hibernate lo mapea a un OID de Postgres —un objeto grande
    // fuera de la fila, con su propio ciclo de vida— y la columna aquí es `bytea`. El desajuste
    // no se ve al compilar: el servicio no arranca.
    @Column(name = "content")
    private byte[] content;

    @Column(name = "storage_ref", length = 500)
    private String storageRef;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    // ── Dictamen ─────────────────────────────────────────────────────────────────────────────
    //
    // El juicio vive en el archivo y no en la declaración (`prospect_documents`) porque lo que se
    // revisa es lo entregado, no lo prometido. Además cubre la SELFIE, que sólo existe aquí.

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private DocumentReviewStatus reviewStatus = DocumentReviewStatus.PENDING_REVIEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_source", length = 25)
    private VerificationSource verificationSource;

    /** El analista que dictaminó. Nulo mientras nadie lo haya hecho. */
    @Column(name = "reviewed_by", length = 120)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    protected ProspectDocumentFile() {}

    public static ProspectDocumentFile of(UUID prospectId, ProspectDocumentType type,
                                          String fileName, String contentType, byte[] content) {
        ProspectDocumentFile f = new ProspectDocumentFile();
        f.fileId = UUID.randomUUID();
        f.prospectId = prospectId;
        f.documentType = type;
        f.fileName = fileName;
        f.contentType = contentType;
        f.content = content;
        f.sizeBytes = content == null ? 0 : content.length;
        f.uploadedAt = Instant.now();
        f.reviewStatus = DocumentReviewStatus.PENDING_REVIEW;
        return f;
    }

    /** Reemplaza el archivo conservando la identidad: volver a tomar la INE no crea un documento nuevo. */
    public void replaceWith(String fileName, String contentType, byte[] content) {
        this.fileName = fileName;
        this.contentType = contentType;
        this.content = content;
        this.sizeBytes = content == null ? 0 : content.length;
        this.uploadedAt = Instant.now();

        // Un archivo nuevo invalida el dictamen anterior. Conservarlo aprobaría a ciegas una foto
        // que nadie vio —que es justo el caso de quien vuelve a subir tras un rechazo—.
        this.reviewStatus       = DocumentReviewStatus.PENDING_REVIEW;
        this.verificationSource = null;
        this.reviewedBy         = null;
        this.reviewedAt         = null;
        this.rejectionReason    = null;
    }

    /**
     * Dictamina el documento.
     *
     * @param reviewedBy quién dictamina. Obligatorio siempre: un veredicto sin autor no sirve de
     *                   evidencia, y en un expediente regulatorio eso es todo lo que importa.
     */
    public void review(DocumentReviewStatus decision, VerificationSource source,
                       String reviewedBy, String rejectionReason) {
        if (decision == null || !decision.isResolved()) {
            throw new DocumentReviewException("El dictamen debe ser APPROVED o REJECTED");
        }
        if (source == null) {
            throw new DocumentReviewException("Todo dictamen declara su origen");
        }
        if (reviewedBy == null || reviewedBy.isBlank()) {
            throw new DocumentReviewException("Todo dictamen lleva autor");
        }
        if (decision == DocumentReviewStatus.REJECTED
                && (rejectionReason == null || rejectionReason.isBlank())) {
            // Sin motivo, el solicitante no sabe qué volver a subir y el analista siguiente no
            // sabe qué revisó el anterior.
            throw new DocumentReviewException("Un rechazo siempre lleva motivo");
        }

        this.reviewStatus       = decision;
        this.verificationSource = source;
        this.reviewedBy         = reviewedBy;
        this.reviewedAt         = Instant.now();
        this.rejectionReason    = decision == DocumentReviewStatus.REJECTED ? rejectionReason : null;
    }

    public UUID getFileId()                     { return fileId; }
    public UUID getProspectId()                 { return prospectId; }
    public ProspectDocumentType getDocumentType() { return documentType; }
    public String getFileName()                 { return fileName; }
    public String getContentType()              { return contentType; }
    public long getSizeBytes()                  { return sizeBytes; }
    public byte[] getContent()                  { return content; }
    public String getStorageRef()               { return storageRef; }
    public Instant getUploadedAt()              { return uploadedAt; }
    public DocumentReviewStatus getReviewStatus() { return reviewStatus; }
    public VerificationSource getVerificationSource() { return verificationSource; }
    public String getReviewedBy()               { return reviewedBy; }
    public Instant getReviewedAt()              { return reviewedAt; }
    public String getRejectionReason()          { return rejectionReason; }
}
