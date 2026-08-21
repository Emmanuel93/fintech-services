package com.fintech.notifications.domain;

/** PUSH/EMAIL/WHATSAPP son los 3 canales externos priorizados en v1 (ver T2_notifications.md). */
public enum NotificationChannel {
    PUSH_NOTIFICATION, EMAIL, WHATSAPP, SMS, IVR_CALLBACK, IN_APP
}
