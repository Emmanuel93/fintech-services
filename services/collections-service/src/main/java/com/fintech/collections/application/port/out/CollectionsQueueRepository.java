package com.fintech.collections.application.port.out;

import com.fintech.collections.application.ContactQueueRow;
import com.fintech.collections.application.PromiseQueueRow;
import com.fintech.collections.domain.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;

/** Las bandejas transversales de cobranza: promesas y contactos cruzando casos. */
public interface CollectionsQueueRepository {

    Page<PromiseQueueRow> searchPromises(Collection<PromiseStatus> statuses,
                                         Collection<DelinquencyBucket> buckets,
                                         Collection<String> agentIds,
                                         LocalDate dueFrom,
                                         LocalDate dueTo,
                                         Pageable pageable);

    Page<ContactQueueRow> searchContactAttempts(Collection<ContactResult> results,
                                                Collection<ContactChannel> channels,
                                                Collection<DelinquencyBucket> buckets,
                                                Collection<String> agentIds,
                                                Instant from,
                                                Instant to,
                                                Pageable pageable);
}
