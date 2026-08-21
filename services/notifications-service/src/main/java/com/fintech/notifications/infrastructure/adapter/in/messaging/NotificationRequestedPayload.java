package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.domain.NotificationChannel;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * El contrato único para que cualquier servicio pida un aviso, en asíncrono.
 *
 * <p>Es el mismo cuerpo que {@code POST /api/v1/notifications}: un camino síncrono para quien nos
 * notifica desde fuera, uno asíncrono para los hechos internos, <b>un solo contrato</b>. Quien
 * publica decide destinatario, clave y canales; este servicio no interpreta ninguno de los tres.
 *
 * <p>Que exista este payload es lo que permite que notifications <b>deje de escuchar tópicos de
 * dominio</b>. Con un listener por hecho, cada aviso nuevo obligaba a tocar este servicio; con uno
 * genérico, publicar es suficiente.
 */
public record NotificationRequestedPayload(
        String sourceEventId,
        String recipientType,
        UUID recipientId,
        String eventKey,
        Map<String, String> variables,
        List<NotificationChannel> channels) {}
