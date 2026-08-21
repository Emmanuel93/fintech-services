package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A quién se le notifica, sin saber qué es.
 *
 * <p>Un notificador no tiene por qué distinguir a quien pide un préstamo de un asesor externo, una
 * distribuidora o un empleado. Necesita una dirección y una preferencia; <b>qué representa esa
 * entidad es asunto de quien la registra</b>. Por eso {@code recipientType} es texto libre y este
 * servicio <b>no ramifica sobre su valor en ninguna parte</b>: en el momento en que lo hiciera,
 * volvería a acoplarse a los dominios que hoy existen y quedaría cerrado ante los que no.
 *
 * <p>Antes el contacto se reconstruía con un join de tres pasos —prospecto → solicitud → cuenta—
 * que sólo tiene sentido para el journey del crédito. Ese mecanismo sigue vivo, pero ahora es
 * <b>un productor más</b> de este registro y no el modelo: alimenta destinatarios de tipo
 * {@code PARTY} igual que mañana otro alimentará los de tipo {@code STAFF}.
 */
@Entity
@Table(schema = "notifications", name = "notification_recipients")
@IdClass(NotificationRecipient.Key.class)
public class NotificationRecipient {

    /** Llave compuesta: dos entidades de tipos distintos pueden compartir id sin ser la misma. */
    public record Key(String recipientType, UUID recipientId) implements Serializable {
        public Key() { this(null, null); }
    }

    @Id
    @Column(name = "recipient_type", nullable = false, updatable = false, length = 40)
    private String recipientType;

    @Id
    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(length = 20)
    private String phone;

    @Column(length = 254)
    private String email;

    @Column(name = "push_token", length = 500)
    private String pushToken;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationRecipient() {}

    public static NotificationRecipient of(String recipientType, UUID recipientId, String displayName,
                                            String phone, String email, String pushToken, String locale) {
        requireText(recipientType, "recipientType");
        if (recipientId == null) {
            throw new InvalidRecipientException("recipientId es obligatorio");
        }
        requireReachable(phone, email, pushToken);

        NotificationRecipient r = new NotificationRecipient();
        r.recipientType = recipientType.trim().toUpperCase();
        r.recipientId   = recipientId;
        r.displayName   = displayName;
        r.phone         = blankToNull(phone);
        r.email         = blankToNull(email);
        r.pushToken     = blankToNull(pushToken);
        r.locale        = (locale == null || locale.isBlank()) ? "es-MX" : locale;
        r.createdAt     = Instant.now();
        r.updatedAt     = r.createdAt;
        return r;
    }

    /**
     * Actualiza los datos de contacto.
     *
     * <p>Un valor nulo <b>no borra</b>: significa «no traigo dato de esto». Quien registra a una
     * entidad suele conocer sólo una de sus vías —el que la dio de alta por teléfono no sabe su
     * correo—, y hacer que cada alta parcial borre lo que otro sabía dejaría destinatarios
     * inalcanzables sin que nadie tocara nada. Para quitar una vía se manda vacío, que es una
     * decisión explícita.
     */
    public void update(String displayName, String phone, String email, String pushToken, String locale) {
        if (displayName != null) this.displayName = blankToNull(displayName);
        if (phone       != null) this.phone       = blankToNull(phone);
        if (email       != null) this.email       = blankToNull(email);
        if (pushToken   != null) this.pushToken   = blankToNull(pushToken);
        if (locale != null && !locale.isBlank()) this.locale = locale;

        requireReachable(this.phone, this.email, this.pushToken);
        this.updatedAt = Instant.now();
    }

    /** Si se le puede mandar algo por el canal pedido. */
    public boolean reachableBy(NotificationChannel channel) {
        return switch (channel) {
            case PUSH_NOTIFICATION -> pushToken != null;
            case EMAIL             -> email != null;
            case WHATSAPP, SMS, IVR_CALLBACK -> phone != null;
            // El buzón de la propia consola no necesita dirección: el mensaje se queda aquí.
            case IN_APP            -> true;
        };
    }

    private static void requireReachable(String phone, String email, String pushToken) {
        if (blankToNull(phone) == null && blankToNull(email) == null && blankToNull(pushToken) == null) {
            throw new InvalidRecipientException(
                    "Un destinatario necesita al menos teléfono, correo o push token");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRecipientException(field + " es obligatorio");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public String getRecipientType() { return recipientType; }
    public UUID getRecipientId()     { return recipientId; }
    public String getDisplayName()   { return displayName; }
    public String getPhone()         { return phone; }
    public String getEmail()         { return email; }
    public String getPushToken()     { return pushToken; }
    public String getLocale()        { return locale; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getUpdatedAt()    { return updatedAt; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotificationRecipient other)) return false;
        return Objects.equals(recipientType, other.recipientType)
            && Objects.equals(recipientId, other.recipientId);
    }

    @Override public int hashCode() { return Objects.hash(recipientType, recipientId); }
}
