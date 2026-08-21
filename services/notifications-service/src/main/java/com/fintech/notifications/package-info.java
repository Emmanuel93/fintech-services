/**
 * T2 — Notifications [Transversal]
 *
 * <p>Entrega de comunicaciones a clientes por PUSH, EMAIL y WHATSAPP. Consume eventos de otros
 * dominios y decide canal + contenido — <strong>nunca modifica estado de ningún otro dominio</strong>.
 *
 * <p><strong>NT-12 — sin jobs {@code @Scheduled}.</strong> Todo trigger temporal viene de un evento
 * ya publicado por el dominio dueño del dato (p. ej. el recordatorio de pago consume
 * {@code collections.pre-due-reminder-triggered}, alimentado por un cron que ya existe en
 * credit-portfolio) — nunca de un escaneo periódico propio.
 *
 * <p>Alcance v1 (6 notificaciones, ver {@code docs/dominios/T2_notifications.md}): oferta de
 * crédito, bienvenida, desembolso, recordatorio de pago, cuota pagada (aproximación local,
 * ver {@link com.fintech.notifications.domain.CreditAccountProgress}), crédito liquidado.
 *
 * <p>Schema DB: {@code notifications} · Puerto: {@code 8098}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.notifications;
