package com.fintech.creditportfolio.application.port.in;

import com.fintech.creditportfolio.application.ProcessAgreementExecutedCommand;

public interface ProcessAgreementExecutedUseCase {
    /** Idempotent by command.sourceEventId() (the agreementId). */
    void process(ProcessAgreementExecutedCommand command);
}
