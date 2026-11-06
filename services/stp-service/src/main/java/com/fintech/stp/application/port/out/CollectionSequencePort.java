package com.fintech.stp.application.port.out;

import java.util.UUID;

/** The consecutive of a company's CLABE range; transactional with the account it numbers. */
public interface CollectionSequencePort {

    long next(UUID companyId);
}
