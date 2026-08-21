package com.fintech.origination.application.port.in;

import com.fintech.origination.application.RecordApprovalDecisionCommand;

public interface RecordApprovalDecisionUseCase {
    void record(RecordApprovalDecisionCommand command);
}
