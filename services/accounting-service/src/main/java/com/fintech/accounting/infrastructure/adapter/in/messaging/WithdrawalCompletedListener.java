package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.PostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalCompletedListener.class);
    private final PostingService postingService;

    public WithdrawalCompletedListener(PostingService postingService) {
        this.postingService = postingService;
    }

    @KafkaListener(topics = "wallet.withdrawal-completed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "withdrawalCompletedListenerContainerFactory")
    public void onMessage(WithdrawalCompletedPayload p) {
        log.debug("wallet.withdrawal-completed withdrawalId={} amount={}", p.withdrawalId(), p.amount());
        postingService.onWalletWithdrawal(p.withdrawalId().toString(), p.creditAccountId(),
                p.obligorPartyId(), p.amount(), p.occurredOn());
    }
}
