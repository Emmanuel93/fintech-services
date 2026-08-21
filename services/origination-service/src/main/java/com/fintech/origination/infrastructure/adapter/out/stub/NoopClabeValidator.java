package com.fintech.origination.infrastructure.adapter.out.stub;

import com.fintech.origination.application.port.out.ClabeValidator;
import org.springframework.stereotype.Component;

/** Stub — validates CLABE format (18 digits) only. TODO: integrate SPEI validation endpoint. */
@Component
public class NoopClabeValidator implements ClabeValidator {
    @Override
    public boolean isValid(String clabeAccount) {
        return clabeAccount != null && clabeAccount.matches("\\d{18}");
    }
}
