package com.fintech.commission.application.service;

import com.fintech.commission.application.port.out.CreditPromoterAssignmentRepository;
import com.fintech.commission.domain.CreditPromoterAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Projects {@code credit-portfolio.credit-account-activated.promoterCode} into a
 * {@link CreditPromoterAssignment} — the attribution Commission needs to know who to pay.
 *
 * <p>{@code promoterCode} is propagated opaquely as a string from channels; there is no
 * promoter/distributor directory that resolves an arbitrary code to a Party today. This requires
 * {@code promoterCode} to already be the beneficiary's Party UUID — if it isn't parseable, no
 * assignment is created and the credit simply never accrues commission (CM-07). See
 * {@link CreditPromoterAssignment} javadoc for the full rationale.
 */
@Service
@Transactional
public class PromoterAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(PromoterAssignmentService.class);

    private final CreditPromoterAssignmentRepository repository;

    public PromoterAssignmentService(CreditPromoterAssignmentRepository repository) {
        this.repository = repository;
    }

    public void onCreditAccountActivated(UUID creditAccountId, String productType, String promoterCode) {
        if (repository.existsById(creditAccountId)) return; // idempotent

        if (promoterCode == null || promoterCode.isBlank()) {
            log.debug("No promoterCode for creditAccountId={} — no CreditPromoterAssignment created", creditAccountId);
            return;
        }

        UUID beneficiaryPartyId;
        try {
            beneficiaryPartyId = UUID.fromString(promoterCode.trim());
        } catch (IllegalArgumentException e) {
            log.warn("promoterCode='{}' for creditAccountId={} is not a valid Party UUID — skipping " +
                    "assignment (CM-07)", promoterCode, creditAccountId);
            return;
        }

        repository.save(CreditPromoterAssignment.create(creditAccountId, beneficiaryPartyId, productType));
        log.info("CreditPromoterAssignment created creditAccountId={} beneficiaryPartyId={}",
                creditAccountId, beneficiaryPartyId);
    }

    /**
     * Créditos colocados por un conjunto de distribuidores/promotores. Es lo que acota la cartera al
     * alcance del backoffice: el BFF pasa los distribuidores del subárbol y recibe sus créditos, que
     * luego hidrata en credit-portfolio. Acotado por nº de distribuidores, no por filas.
     */
    @Transactional(readOnly = true)
    public List<CreditPromoterAssignment> creditsByPromoters(Collection<UUID> promoterPartyIds) {
        if (promoterPartyIds == null || promoterPartyIds.isEmpty()) {
            return List.of();
        }
        return repository.findByBeneficiaryPartyIdIn(promoterPartyIds);
    }
}
