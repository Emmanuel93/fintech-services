package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.NotificationRecordRepository;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.NotificationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JpaNotificationRecordRepository
        extends JpaRepository<NotificationRecord, UUID>, NotificationRecordRepository {

    @Override
    boolean existsBySourceEventIdAndChannel(String sourceEventId, NotificationChannel channel);

    @Override
    List<NotificationRecord> findByRecipientId(UUID recipientId);

    // findById(UUID) ya lo satisface JpaRepository<NotificationRecord, UUID>.

    @Override
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE NotificationRecord n
               SET n.readAt = CURRENT_TIMESTAMP
             WHERE n.recipientId = :recipientId AND n.readAt IS NULL""")
    int markAllReadByRecipientId(@Param("recipientId") UUID recipientId);

    @Override
    @Query("""
            SELECT n FROM NotificationRecord n
             WHERE n.recipientType = :recipientType AND n.recipientId = :recipientId
             ORDER BY n.sentAt DESC""")
    List<NotificationRecord> findFeed(@Param("recipientType") String recipientType,
                                      @Param("recipientId") UUID recipientId);

    @Override
    @Query("""
            SELECT COUNT(n) FROM NotificationRecord n
             WHERE n.recipientType = :recipientType AND n.recipientId = :recipientId
               AND n.readAt IS NULL""")
    long countUnread(@Param("recipientType") String recipientType,
                     @Param("recipientId") UUID recipientId);

    @Override
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE NotificationRecord n
               SET n.readAt = CURRENT_TIMESTAMP
             WHERE n.recipientType = :recipientType AND n.recipientId = :recipientId
               AND n.readAt IS NULL""")
    int markAllRead(@Param("recipientType") String recipientType,
                    @Param("recipientId") UUID recipientId);
}
