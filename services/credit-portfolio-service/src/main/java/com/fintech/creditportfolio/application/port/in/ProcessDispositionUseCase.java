package com.fintech.creditportfolio.application.port.in;

import com.fintech.creditportfolio.application.ProcessDispositionCommand;

public interface ProcessDispositionUseCase {
    /**
     * Processes a wallet-initiated disposition (wallet.disposition-requested).
     * Idempotent by {@code command.sourceEventId()}. Rejections are published as
     * {@code credit-portfolio.disposition-rejected}, not thrown.
     */
    void process(ProcessDispositionCommand command);
}
