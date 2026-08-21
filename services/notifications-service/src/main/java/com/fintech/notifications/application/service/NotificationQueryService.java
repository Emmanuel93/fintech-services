package com.fintech.notifications.application.service;

import com.fintech.notifications.application.port.in.GetNotificationHistoryUseCase;
import com.fintech.notifications.application.port.in.ManageNotificationPreferenceUseCase;
import com.fintech.notifications.application.port.in.ManageNotificationReadStateUseCase;
import com.fintech.notifications.application.port.out.NotificationPreferenceRepository;
import com.fintech.notifications.application.port.out.NotificationRecordRepository;
import com.fintech.notifications.domain.NotificationPreference;
import com.fintech.notifications.domain.NotificationRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class NotificationQueryService implements GetNotificationHistoryUseCase, ManageNotificationPreferenceUseCase,
        ManageNotificationReadStateUseCase {

    private final NotificationRecordRepository recordRepository;
    private final NotificationPreferenceRepository preferenceRepository;

    public NotificationQueryService(NotificationRecordRepository recordRepository,
                                     NotificationPreferenceRepository preferenceRepository) {
        this.recordRepository = recordRepository;
        this.preferenceRepository = preferenceRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationRecord> getByRecipientId(UUID recipientId) {
        return recordRepository.findByRecipientId(recipientId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationPreference> get(UUID partyId) {
        return preferenceRepository.findById(partyId);
    }

    @Override
    public NotificationPreference update(UUID partyId, String pushToken, String whatsappNumber) {
        Optional<NotificationPreference> existing = preferenceRepository.findById(partyId);
        NotificationPreference preference;
        if (existing.isPresent()) {
            preference = existing.get();
            preference.update(pushToken, whatsappNumber);
        } else {
            preference = NotificationPreference.create(partyId, pushToken, whatsappNumber);
        }
        return preferenceRepository.save(preference);
    }

    @Override
    public boolean markAsRead(UUID notificationId) {
        Optional<NotificationRecord> record = recordRepository.findById(notificationId);
        if (record.isEmpty()) return false;
        record.get().markRead();
        recordRepository.save(record.get());
        return true;
    }

    @Override
    public int markAllAsRead(UUID recipientId) {
        return recordRepository.markAllReadByRecipientId(recipientId);
    }
}
