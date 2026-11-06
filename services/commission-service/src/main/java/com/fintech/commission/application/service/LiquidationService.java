package com.fintech.commission.application.service;

import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.BeneficiaryProfileRepository;
import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.application.port.out.LiquidationRunLog;
import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.LiquidationBatch;
import com.fintech.commission.domain.LiquidationBatchStatus;
import com.fintech.commission.domain.LotTaxes;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * LB-03: a run takes everything accrued up to its period and not in a lot yet, by beneficiary and product, and opens a
 * lot for each group that reaches the minimum; the lot's SPEI is requested at once.
 *
 * <p><b>Carry-over.</b> A run used to take only its own period's accruals: what one month left below the minimum was
 * never taken again, and the distributor was never paid (E2E: eight months, $4,294.96 accrued, no lot). Now what does
 * not reach the minimum stays accrued and the next run adds it up; so does a group whose beneficiary cannot be paid yet
 * (no fiscal profile or no payout account), and what accrued after a period's lot was opened.
 *
 * <p>A reversal of an already paid commission (a clawback) is a negative record of the same beneficiary and product:
 * the next lot nets it.
 */
@Service
@Transactional
public class LiquidationService {

    private static final Logger log = LoggerFactory.getLogger(LiquidationService.class);

    private final CommissionRecordRepository recordRepository;
    private final LiquidationBatchRepository batchRepository;
    private final BeneficiaryProfileRepository profiles;
    private final CommissionEventPublisher eventPublisher;
    private final CommissionProperties properties;
    private final LiquidationRunLog runLog;

    public LiquidationService(CommissionRecordRepository recordRepository,
                              LiquidationBatchRepository batchRepository,
                              BeneficiaryProfileRepository profiles,
                              CommissionEventPublisher eventPublisher,
                              CommissionProperties properties,
                              LiquidationRunLog runLog) {
        this.recordRepository = recordRepository;
        this.batchRepository  = batchRepository;
        this.profiles         = profiles;
        this.eventPublisher   = eventPublisher;
        this.runLog           = runLog;
        this.properties       = properties;
    }

    /** What a run did: lots opened (and their SPEI requested), groups carried over or waiting, lots requested again. */
    public record LiquidationRun(String period, int lotsOpened, int carriedOver, int waitingForBeneficiary,
                                 int retried) {}

    private record Group(UUID beneficiaryPartyId, String productType) {}

    public LiquidationRun runLiquidation(String period) {
        int retried = retryPending();

        Map<Group, List<CommissionRecord>> open = new LinkedHashMap<>();
        for (CommissionRecord r : recordRepository.findOpenUpTo(period)) {
            open.computeIfAbsent(new Group(r.getBeneficiaryPartyId(), r.getProductType()), k -> new ArrayList<>()).add(r);
        }
        List<LiquidationBatch> ofThePeriod = batchRepository.findByPeriod(period);

        int opened = 0, carried = 0, waiting = 0;
        for (Map.Entry<Group, List<CommissionRecord>> e : open.entrySet()) {
            Group g = e.getKey();
            List<CommissionRecord> records = e.getValue();
            BigDecimal subtotal = records.stream().map(CommissionRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal iva = records.stream().map(CommissionRecord::getIvaAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

            if (subtotal.compareTo(properties.getMinLiquidationAmount()) < 0) {
                log.info("Comisiones de beneficiaria={} producto={} hasta {}: {} — bajo el mínimo {}, se arrastran",
                        g.beneficiaryPartyId(), g.productType(), period, subtotal, properties.getMinLiquidationAmount());
                carried++;
                continue;
            }
            if (ofThePeriod.stream().anyMatch(b -> b.getBeneficiaryPartyId().equals(g.beneficiaryPartyId())
                    && Objects.equals(b.getProductType(), g.productType()))) {
                // The period's lot of this beneficiary and product is open already: what accrued since goes next time.
                log.info("Beneficiaria={} producto={} ya tiene lote de {}: {} se arrastra al siguiente",
                        g.beneficiaryPartyId(), g.productType(), period, subtotal);
                carried++;
                continue;
            }
            BeneficiaryProfile beneficiary = profiles.findById(g.beneficiaryPartyId()).orElse(null);
            if (beneficiary == null || !beneficiary.isPayable()) {
                log.warn("Comisiones de beneficiaria={} producto={} por {} esperan: {}", g.beneficiaryPartyId(),
                        g.productType(), subtotal, beneficiary == null ? "sin perfil fiscal (RFC)" : beneficiary.whyNotPayable());
                waiting++;
                continue;
            }

            LotTaxes taxes = LotTaxes.of(subtotal, iva, beneficiary, properties.taxRules());
            LiquidationBatch lot = batchRepository.save(
                    LiquidationBatch.open(g.beneficiaryPartyId(), g.productType(), period, taxes, records.size()));
            records.forEach(r -> { r.markLiquidated(lot.getBatchId()); recordRepository.save(r); });
            requestPayout(lot, beneficiary);
            opened++;
            log.info("Lote de comisiones {} beneficiaria={} producto={} período={} registros={} comisión={} IVA={} "
                            + "ISR retenido={} IVA retenido={} neto={}",
                    lot.getBatchId(), g.beneficiaryPartyId(), g.productType(), period, records.size(), taxes.subtotal(),
                    taxes.iva(), taxes.isrWithheld(), taxes.ivaWithheld(), taxes.net());
        }
        LiquidationRun run = new LiquidationRun(period, opened, carried, waiting, retried);
        runLog.record(period, BusinessClock.current().now(), opened, carried, waiting, retried);
        log.info("Liquidación de comisiones {}: {} lotes, {} grupos arrastrados, {} esperan a la beneficiaria, {} reintentos",
                period, opened, carried, waiting, retried);
        return run;
    }

    /** Lots whose SPEI failed or came back: requested again when their beneficiary can be paid. */
    private int retryPending() {
        int n = 0;
        for (LiquidationBatch lot : batchRepository.findByStatus(LiquidationBatchStatus.PENDING)) {
            BeneficiaryProfile beneficiary = profiles.findById(lot.getBeneficiaryPartyId()).orElse(null);
            if (beneficiary == null || !beneficiary.isPayable()) continue;
            requestPayout(lot, beneficiary);
            n++;
        }
        return n;
    }

    private void requestPayout(LiquidationBatch lot, BeneficiaryProfile beneficiary) {
        int attempt = lot.requestPayout();
        batchRepository.save(lot);
        eventPublisher.publishLotPayoutRequested(lot, beneficiary, attempt);
    }
}
