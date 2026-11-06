package com.fintech.accounting.application.service;

import com.fintech.accounting.application.port.out.ItemProducts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attributes to their product what was booked before it was known: the bank side of an order whose payout link arrived
 * after the bank reported it, of a received credit the portfolio applied after the bank credited it, or the vouchers of
 * a loan whose activation arrived late. Only in open months: a closed month keeps the figures it closed with. The close
 * runs it before it reconciles, so the trial balance by product it keeps is complete.
 */
@Service
public class ProductAttributionService {

    private static final Logger log = LoggerFactory.getLogger(ProductAttributionService.class);

    private final ItemProducts items;

    public ProductAttributionService(ItemProducts items) {
        this.items = items;
    }

    @Transactional
    public int sweep() {
        int attributed = items.sweepOpenPeriods();
        if (attributed > 0) log.info("Pólizas atribuidas a su producto en meses abiertos: {}", attributed);
        return attributed;
    }
}
