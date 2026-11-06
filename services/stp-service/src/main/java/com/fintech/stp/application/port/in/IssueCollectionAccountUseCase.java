package com.fintech.stp.application.port.in;

import com.fintech.stp.domain.collection.CollectionAccount;

import java.util.UUID;

/** Gives a person their collection CLABE. Idempotent: a person who has one keeps it. */
public interface IssueCollectionAccountUseCase {

    CollectionAccount issueFor(UUID partyId, String holderName, String taxId);
}
