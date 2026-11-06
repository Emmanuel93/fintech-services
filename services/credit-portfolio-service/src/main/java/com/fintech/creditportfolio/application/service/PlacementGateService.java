package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.PlacementGate;
import com.fintech.creditportfolio.domain.StatementCoverage;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.shared.time.BusinessClock;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The distributor line's gate for new placements and the terms its commission is settled on: what the channel shows
 * before she tries, what the portfolio enforces when she does. A product that pays no distributor has no gate.
 */
@Service
public class PlacementGateService {

    private final InstallmentRepository installments;
    private final BalanceEventRepository ledger;
    private final ProductConfigResolver configResolver;

    public PlacementGateService(InstallmentRepository installments, BalanceEventRepository ledger,
                                ProductConfigResolver configResolver) {
        this.installments = installments;
        this.ledger = ledger;
        this.configResolver = configResolver;
    }

    /** The distributor's commission terms of the account's product; empty for every other product. */
    public Optional<DistributorCommission> commissionTerms(CreditAccount account) {
        if (account == null || configResolver == null || !account.isRevolving()) return Optional.empty();
        return configResolver.resolveForAccount(account.getProductCode(), account.getProductVersion())
                .map(ProductConfigVersion::getDistributorCommission);
    }

    /** Whether the line may place today; {@link PlacementGate#OPEN} for a product without a distributor. */
    public PlacementGate evaluate(CreditAccount account) {
        if (commissionTerms(account).isEmpty()) return PlacementGate.OPEN;
        List<BalanceEvent> entries = ledger.paymentsOf(account.getCreditAccountId());
        return PlacementGate.evaluate(
                StatementCoverage.obligationsFrom(installments.findByScheduleIdOrdered(account.getCreditAccountId())),
                StatementCoverage.paymentsFrom(entries), BusinessClock.current().today(), StatementCoverage.quitaOn(entries));
    }
}
