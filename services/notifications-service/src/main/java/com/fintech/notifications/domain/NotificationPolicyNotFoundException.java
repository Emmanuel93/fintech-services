package com.fintech.notifications.domain;

import com.fintech.shared.exception.DomainException;

public class NotificationPolicyNotFoundException extends DomainException {
    public NotificationPolicyNotFoundException(String id) {
        super("NOTIFICATION_POLICY_NOT_FOUND", "NotificationPolicy not found: " + id);
    }
}
