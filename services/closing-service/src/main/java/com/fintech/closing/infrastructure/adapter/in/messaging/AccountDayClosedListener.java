package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fintech.closing.application.service.cutoff.CutoffHeldRelease;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Cartera soltó una cuenta retenida: closing corta su día, con su saldo al cierre de ese día. */
@Component
class AccountDayClosedListener {

    private final CutoffHeldRelease release;

    AccountDayClosedListener(CutoffHeldRelease release) {
        this.release = release;
    }

    @KafkaListener(topics = "credit-portfolio.account-day-closed",
                   containerFactory = "accountDayClosedListenerContainerFactory")
    void onAccountDayClosed(AccountDayClosedMessage message) {
        if (message == null || message.businessDate() == null || message.creditAccountId() == null) return;
        release.release(message.businessDate(), message.creditAccountId());
    }
}
