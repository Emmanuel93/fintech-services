package com.fintech.collections.application.port.in;

import com.fintech.collections.application.ApproveWriteOffCommand;
import com.fintech.collections.application.RequestWriteOffCommand;
import com.fintech.collections.domain.WriteOffRecord;

public interface WriteOffUseCase {
    /** WO-01: intent only — publishes WriteOffRequested, persists nothing. */
    void request(RequestWriteOffCommand command);
    /** WO-02: authorized — creates the immutable WriteOffRecord and applies it. */
    WriteOffRecord approve(ApproveWriteOffCommand command);
}
