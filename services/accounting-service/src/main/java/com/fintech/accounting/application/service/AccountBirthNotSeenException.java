package com.fintech.accounting.application.service;

import java.util.UUID;

/**
 * A balance fact arrived for an account whose birth accounting has not seen yet.
 *
 * <p>The birth ({@code credit-account-activated}) and the first charges ({@code balance-updated}) travel on two
 * topics, consumed in parallel: the opening fee can be processed while the activation's transaction has not committed.
 * The fact is not lost and not guessed at: the listener's error handler retries it until the activation commits, and if
 * it never does it goes to the dead letter, where it can be replayed.
 */
public class AccountBirthNotSeenException extends RuntimeException {

    public AccountBirthNotSeenException(UUID creditAccountId, String triggerEvent) {
        super("Contabilidad aún no registra el alta de la cuenta " + creditAccountId + "; el hecho " + triggerEvent
                + " espera a que llegue la activación");
    }
}
