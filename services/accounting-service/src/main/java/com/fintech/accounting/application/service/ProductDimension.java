package com.fintech.accounting.application.service;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.port.out.ItemProducts;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountBalanceShadow;
import com.fintech.accounting.domain.BankSideRule;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Which product a voucher belongs to: the dimension of the trial balance by product.
 *
 * <p>In order: the product the fact names (a commission and its lot know theirs); the loan's, as its activation
 * published it; for a voucher with no loan, the product of the item it moves on a clearing account (the bank side of an
 * order or of a received credit). Otherwise none, which the trial balance shows as «Institución»: the institution's own
 * facts (its funding and its interest), and an item not known yet, which the close's sweep attributes when it is.
 *
 * <p>The loan's product is read through the shadow repository, in the posting's own persistence context: the
 * activation learns the product and books its first voucher in the same transaction.
 */
@Component
public class ProductDimension {

    private final AccountBalanceShadowRepository shadows;
    private final ItemProducts items;

    public ProductDimension(AccountBalanceShadowRepository shadows, ItemProducts items) {
        this.shadows = shadows;
        this.items = items;
    }

    public String of(VoucherRequest req) {
        if (req.productType() != null && !req.productType().isBlank()) return req.productType();
        if (req.creditAccountId() != null) {
            return shadows.findById(req.creditAccountId()).map(AccountBalanceShadow::getProductType).orElse(null);
        }
        return ofItem(req.triggerEvent(), req.clearingRef()).orElse(null);
    }

    private Optional<String> ofItem(String triggerEvent, String clearingRef) {
        if (clearingRef == null || triggerEvent == null) return Optional.empty();
        if (BankSideRule.STP_ORDER.trigger().equals(triggerEvent)) return items.ofOrder(clearingRef);
        if (BankSideRule.SPEI_CREDIT.trigger().equals(triggerEvent)
                || BankSideRule.SPEI_CREDIT_RETURNED.trigger().equals(triggerEvent)) {
            return items.ofReceivedCredit(clearingRef);
        }
        return Optional.empty();
    }
}
