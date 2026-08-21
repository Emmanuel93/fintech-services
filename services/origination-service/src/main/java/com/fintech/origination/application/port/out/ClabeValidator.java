package com.fintech.origination.application.port.out;

/** ACL port — validates a CLABE interbancaria (CM-06, SPEI integration stub). */
public interface ClabeValidator {
    boolean isValid(String clabeAccount);
}
