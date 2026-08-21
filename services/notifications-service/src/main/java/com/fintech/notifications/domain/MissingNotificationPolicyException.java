package com.fintech.notifications.domain;

import com.fintech.shared.exception.DomainException;

/**
 * Sin NotificationPolicy ACTIVE para un eventType — nunca se inventa un canal, se registra el
 * hueco y se omite el envío (mismo criterio que CM-06/PR-03 en Commission/Risk).
 */
public class MissingNotificationPolicyException extends DomainException {
    public MissingNotificationPolicyException(EventType eventType) {
        super("NOTIFICATION_MISSING_POLICY", "No ACTIVE NotificationPolicy for eventType=" + eventType);
    }
}
