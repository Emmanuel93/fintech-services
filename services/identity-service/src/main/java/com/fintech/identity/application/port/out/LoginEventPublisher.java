package com.fintech.identity.application.port.out;

import com.fintech.identity.application.event.LoginAttemptEvent;

public interface LoginEventPublisher {

    /**
     * Publica el evento de intento de inicio de sesión al topic de Kafka.
     * La publicación es fire-and-forget; el servicio no espera confirmación del consumidor.
     */
    void publish(LoginAttemptEvent event);
}
