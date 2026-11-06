package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.collection.InboundCredit;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboundCreditRepository {

    InboundCredit save(InboundCredit credit);

    Optional<InboundCredit> findByStpId(UUID companyId, long stpId);

    Optional<InboundCredit> findByTrackingKey(UUID companyId, String trackingKey, LocalDate operationDate);

    List<InboundCredit> findByParty(UUID partyId);
}
