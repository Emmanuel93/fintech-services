package com.fintech.notifications.infrastructure.adapter.out.channel;

import com.fintech.notifications.application.NotificationsProperties;
import com.fintech.notifications.application.port.out.EmailAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Adaptador real vía SMTP (JavaMailSender) — funciona con cualquier proveedor SMTP gratuito o
 * barato sin código adicional: Gmail en dev, Brevo (300/día gratis) o Resend (3,000/mes gratis)
 * para arrancar, Amazon SES (~$0.10/1000 emails) a volumen. Se activa con
 * {@code fintech.notifications.email.smtp-enabled=true} + credenciales SMTP_* — deshabilitado por
 * default (ver {@link NoopEmailAdapter}).
 */
@Component
@ConditionalOnProperty(prefix = "fintech.notifications.email", name = "smtp-enabled", havingValue = "true")
public class SmtpEmailAdapter implements EmailAdapter {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailAdapter.class);

    private final JavaMailSender mailSender;
    private final NotificationsProperties properties;

    public SmtpEmailAdapter(JavaMailSender mailSender, NotificationsProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public boolean send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getEmail().getFromAddress());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            return true;
        } catch (Exception ex) {
            log.error("SMTP send failed to={} subject={}: {}", to, subject, ex.getMessage());
            return false;
        }
    }
}
