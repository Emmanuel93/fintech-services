package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.ClientNotYoursException;
import com.fintech.beneficiary.domain.PlacementNotReadyException;
import com.fintech.beneficiary.domain.PlacementRefusal;
import com.fintech.beneficiary.domain.PlacementValidationException;
import com.fintech.beneficiary.infrastructure.adapter.out.client.CreditPortfolioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A distributor and her clients on the red list: she reports a client she placed, clears her own reports, and asks
 * what a client may take on her line before she offers it.
 *
 * <p>What she sees of the red list is only hers: her reports and her line's arrears. A mark another distributor put
 * on the client is not shown to her yet; it still refuses him (the business rule), and she reads only that Kredius
 * cannot lend to him today.
 */
@Service
public class ClientReportService {

    private static final Logger log = LoggerFactory.getLogger(ClientReportService.class);

    /** The catalog a report picks from; the comments carry the rest. */
    public static final Set<String> REASONS = Set.of("VOUCHER_UNPAID", "REFUSES_TO_PAY", "NOT_LOCATABLE", "MISCONDUCT",
            "SUSPECTED_FRAUD", "OTHER");

    private final CreditPortfolioClient portfolio;
    private final PlacementRepository placements;

    public ClientReportService(CreditPortfolioClient portfolio, PlacementRepository placements) {
        this.portfolio = portfolio;
        this.placements = placements;
    }

    public CreditPortfolioClient.StoredFlag report(UUID her, UUID client, String reason, String comment) {
        if (reason == null || !REASONS.contains(reason)) {
            throw new PlacementValidationException("El motivo del reporte debe ser uno de " + REASONS + ", fue: " + reason);
        }
        if ("OTHER".equals(reason) && (comment == null || comment.isBlank())) {
            throw new PlacementValidationException("Con el motivo «otro» cuéntanos qué pasó en los comentarios");
        }
        requireHers(her, client, "reportar");
        UUID line = distributorLineOf(her);
        CreditPortfolioClient.StoredFlag flag = portfolio.report(client, her, line, reason, comment);
        log.info("La distribuidora {} reportó a su cliente {} ({})", her, client, reason);
        return flag;
    }

    public CreditPortfolioClient.StoredFlag clear(UUID her, UUID client, UUID flagId, String comment) {
        CreditPortfolioClient.StoredFlag flag = portfolio.flags(client, her).stream()
                .filter(f -> f.flagId().equals(flagId)).findFirst()
                .orElseThrow(() -> new ClientNotYoursException(client, "levantar la marca " + flagId));
        // She clears what she put, nothing else: another distributor's report or the backoffice's stays.
        if (!her.equals(flag.distributorPartyId()) || !"DISTRIBUTOR".equals(flag.source())) {
            throw new ClientNotYoursException(client, "levantar una marca que no pusiste");
        }
        if (!flag.active()) {
            throw new PlacementNotReadyException("Esa marca ya estaba levantada");
        }
        return portfolio.clearFlag(client, flagId, her, comment);
    }

    /** What this client may take on her line: his type, the limits, his headroom and, for an offer, why not. */
    public LimitsForHer limits(UUID her, UUID client, BigDecimal amount, Integer termPeriods) {
        UUID line = activeLineOf(her);
        CreditPortfolioClient.PlacementLimits l = portfolio.placementLimits(line, client, amount, termPeriods, her)
                .orElseThrow(() -> new PlacementNotReadyException("Cartera no respondió los límites de tu cliente: intenta de nuevo"));
        return forHer(her, l);
    }

    static LimitsForHer forHer(UUID her, CreditPortfolioClient.PlacementLimits l) {
        boolean marksAreHers = l.flagsOrEmpty().stream().allMatch(f -> her.equals(f.distributorPartyId()));
        List<CreditPortfolioClient.ClientFlag> visible = l.flagsOrEmpty().stream()
                .filter(f -> her.equals(f.distributorPartyId())).toList();
        return new LimitsForHer(l.beneficiaryPartyId(), l.clientType(), l.limits(), l.activeLoans(), l.outstanding(),
                l.amountAvailable(), l.loansAvailable(), l.flagsOrEmpty().isEmpty(), visible, l.canPlace(),
                l.canPlace() ? null : PlacementRefusal.inHerWords(l.reason(), marksAreHers));
    }

    /**
     * What she reads of a client's limits.
     *
     * @param canReceivePlacements no red-list mark on him, hers or anyone's
     * @param warnings             the marks that are hers to see
     * @param message              why not, in her words; null when the offer fits
     */
    public record LimitsForHer(UUID beneficiaryPartyId, String clientType, CreditPortfolioClient.Limits limits,
                               int activeLoans, BigDecimal outstanding, BigDecimal amountAvailable, Integer loansAvailable,
                               boolean canReceivePlacements, List<CreditPortfolioClient.ClientFlag> warnings,
                               boolean canPlace, String message) {}

    /** She placed him: the portfolio has a placement of his on her line, or she has one in her own records. */
    private void requireHers(UUID her, UUID client, String what) {
        boolean inHerRecords = placements.findByDistributorPartyIdOrderByCreatedAtDesc(her).stream()
                .anyMatch(p -> client.equals(p.getBeneficiaryPartyId()));
        if (inHerRecords) return;
        boolean onHerLine = portfolio.profile(client, her)
                .map(p -> p.linesOrEmpty().stream().anyMatch(s -> her.equals(s.distributorPartyId())))
                .orElse(false);
        if (!onHerLine) throw new ClientNotYoursException(client, what);
    }

    private UUID activeLineOf(UUID her) {
        return portfolio.findActiveDistributorLine(her)
                .map(CreditPortfolioClient.CreditAccount::creditAccountId)
                .orElseThrow(() -> new PlacementValidationException("No tienes una línea de colocación activa"));
    }

    /** Her line, active or not: a report outlives the line it was made on. */
    private UUID distributorLineOf(UUID her) {
        return portfolio.accountsOf(her).stream()
                .filter(a -> "DISTRIBUTOR_LINE".equalsIgnoreCase(a.productType()))
                .map(CreditPortfolioClient.CreditAccount::creditAccountId)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }
}
