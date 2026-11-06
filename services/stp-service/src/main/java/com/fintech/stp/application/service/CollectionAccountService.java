package com.fintech.stp.application.service;

import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.in.IssueCollectionAccountUseCase;
import com.fintech.stp.application.port.in.ManageCollectionAccountUseCase;
import com.fintech.stp.application.port.out.CollectionAccountRepository;
import com.fintech.stp.application.port.out.CollectionEvents;
import com.fintech.stp.application.port.out.CollectionSequencePort;
import com.fintech.stp.application.port.out.InboundCreditRepository;
import com.fintech.stp.application.port.out.IntegrationEventOutbox;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.domain.CompanyNotFoundException;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.collection.CollectionAccount;
import com.fintech.stp.domain.collection.CollectionClabe;
import com.fintech.stp.domain.collection.InboundCredit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues collection CLABEs, one per person and never recycled, and lets the back office block and reactivate them.
 * Every change is announced on {@code stp.collection-account-assigned} through the outbox.
 */
@Service
public class CollectionAccountService implements IssueCollectionAccountUseCase, ManageCollectionAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(CollectionAccountService.class);

    private final CollectionAccountRepository accounts;
    private final CollectionSequencePort sequence;
    private final StpCompanyRepository companies;
    private final InboundCreditRepository credits;
    private final IntegrationEventOutbox outbox;
    private final StpProperties properties;

    public CollectionAccountService(CollectionAccountRepository accounts, CollectionSequencePort sequence,
                                    StpCompanyRepository companies, InboundCreditRepository credits,
                                    IntegrationEventOutbox outbox, StpProperties properties) {
        this.accounts = accounts;
        this.sequence = sequence;
        this.companies = companies;
        this.credits = credits;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Override
    @Transactional
    public CollectionAccount issueFor(UUID partyId, String holderName, String taxId) {
        StpCompany company = collectingCompany();
        return accounts.findByCompanyAndParty(company.getCompanyId(), partyId).orElseGet(() -> {
            CollectionClabe clabe = CollectionClabe.compose(company.getClabeBankCode(), company.getClabePlazaCode(),
                    company.getClabeClientPrefix(), sequence.next(company.getCompanyId()));
            CollectionAccount account = accounts.save(
                    CollectionAccount.issue(company.getCompanyId(), partyId, clabe, holderName, taxId));
            announce(account);
            log.info("CLABE de cobro asignada party={} clabe=****{}", partyId, clabe.value().substring(14));
            return account;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CollectionAccount> findByParty(UUID partyId) {
        return accounts.findByCompanyAndParty(collectingCompany().getCompanyId(), partyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InboundCredit> creditsOf(UUID partyId) {
        return credits.findByParty(partyId);
    }

    @Override
    @Transactional
    public CollectionAccount block(UUID partyId, String reason) {
        CollectionAccount account = require(partyId);
        account.block(reason);
        accounts.save(account);
        announce(account);
        log.warn("CLABE de cobro bloqueada party={} motivo={}", partyId, reason);
        return account;
    }

    @Override
    @Transactional
    public CollectionAccount reactivate(UUID partyId, String reason) {
        CollectionAccount account = require(partyId);
        account.reactivate(reason);
        accounts.save(account);
        announce(account);
        log.info("CLABE de cobro reactivada party={} motivo={}", partyId, reason);
        return account;
    }

    private CollectionAccount require(UUID partyId) {
        return findByParty(partyId).orElseThrow(() -> new IllegalArgumentException(
                "La persona " + partyId + " no tiene CLABE de cobro"));
    }

    private void announce(CollectionAccount account) {
        outbox.enqueue(CollectionEvents.TOPIC_COLLECTION_ACCOUNT_ASSIGNED, account.getPartyId().toString(),
                account.getCollectionAccountId(), CollectionEvents.CollectionAccountAssigned.of(account));
    }

    private StpCompany collectingCompany() {
        String code = properties.getCollection().getCompanyCode();
        return companies.findByCode(code)
                .orElseThrow(() -> new CompanyNotFoundException("No existe la empresa de cobro " + code));
    }
}
