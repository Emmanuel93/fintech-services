package com.fintech.collections.application.port.in;

import com.fintech.collections.application.RecordContactAttemptCommand;
import com.fintech.collections.domain.ContactAttempt;

public interface RecordContactAttemptUseCase {
    ContactAttempt record(RecordContactAttemptCommand command);
}
