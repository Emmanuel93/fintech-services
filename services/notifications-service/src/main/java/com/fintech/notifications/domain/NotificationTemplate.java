package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copy versionable por (eventType, channel, locale) — nunca hardcodeado en Java (NT-05). El cuerpo
 * usa placeholders {@code {{variable}}} sustituidos por {@link #render}.
 */
@Entity
@Table(name = "notification_templates", schema = "notifications")
public class NotificationTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    @Id
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private EventType eventType;

    /** La llave real de la plantilla. Ver {@code NotificationPolicy.eventKey}. */
    @Column(name = "event_key", nullable = false, length = 80)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationChannel channel;

    @Column(nullable = false, updatable = false)
    private String locale;

    /** Solo aplica a EMAIL — null para PUSH/WHATSAPP. */
    @Column(updatable = false)
    private String subject;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    protected NotificationTemplate() {}

    public static NotificationTemplate create(EventType eventType, NotificationChannel channel,
                                                String locale, String subject, String body) {
        NotificationTemplate t = new NotificationTemplate();
        t.templateId = UUID.randomUUID();
        t.eventType  = eventType;
        t.eventKey  = eventType == null ? null : eventType.name();
        t.channel    = channel;
        t.locale     = locale;
        t.subject    = subject;
        t.body       = body;
        return t;
    }

    /** Sustituye {@code {{var}}} por el valor en {@code variables}; deja el placeholder si falta. */
    public String renderBody(Map<String, String> variables) {
        return render(body, variables);
    }

    public String renderSubject(Map<String, String> variables) {
        return subject == null ? null : render(subject, variables);
    }

    private static String render(String text, Map<String, String> variables) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public UUID getTemplateId()             { return templateId; }
    public EventType getEventType()         { return eventType; }
    public String getEventKey()             { return eventKey; }
    public NotificationChannel getChannel() { return channel; }
    public String getLocale()               { return locale; }
    public String getSubject()              { return subject; }
    public String getBody()                 { return body; }
}
