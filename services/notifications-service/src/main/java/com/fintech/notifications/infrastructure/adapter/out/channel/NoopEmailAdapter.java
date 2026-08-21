package com.fintech.notifications.infrastructure.adapter.out.channel;

import com.fintech.notifications.application.port.out.EmailAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default cuando SMTP no está configurado — ver {@link SmtpEmailAdapter} para el real. */
@Component
@ConditionalOnProperty(prefix = "fintech.notifications.email", name = "smtp-enabled", havingValue = "false",
        matchIfMissing = true)
public class NoopEmailAdapter implements EmailAdapter {

    private static final Logger log = LoggerFactory.getLogger(NoopEmailAdapter.class);

    @Override
    public boolean send(String to, String subject, String body) {
        log.info("[NOOP EMAIL] to={} subject={} body={}", to, subject, body);
        return true;
    }
}
