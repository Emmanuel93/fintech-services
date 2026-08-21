package com.fintech.notifications.application.port.out;

/**
 * Puerto de salida hacia el proveedor de push real (FCM/APNs — ambos gratuitos, sin costo por
 * volumen). Implementación v1: {@code NoopPushAdapter}.
 */
public interface PushNotificationAdapter {
    boolean send(String pushToken, String title, String body);
}
