package com.fintech.notifications.application.port.in;

import com.fintech.notifications.application.CreateNotificationPolicyCommand;
import com.fintech.notifications.domain.NotificationPolicy;

import java.util.List;

public interface NotificationPolicyUseCase {
    NotificationPolicy create(CreateNotificationPolicyCommand cmd);
    List<NotificationPolicy> listActive();
}
