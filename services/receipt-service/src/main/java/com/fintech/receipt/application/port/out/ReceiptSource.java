package com.fintech.receipt.application.port.out;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a receipt is made of, read from the services that own it: the line's statement view and its clients'
 * references (portfolio), names (party) and profiles (portfolio: type and red list).
 */
public interface ReceiptSource {

    LineView view(UUID creditAccountId, LocalDate from, LocalDate to, LocalDate dueUntil);

    /** The clients' payment references on the line, by client. */
    Map<UUID, Long> references(UUID creditAccountId);

    Map<UUID, PartySummary> parties(Collection<UUID> partyIds);

    Map<UUID, ClientProfile> profiles(Collection<UUID> partyIds);

    record PartySummary(UUID partyId, String displayName, String rfc) {}

    /** The part of the portfolio's statement view a receipt reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LineView(Account account, LocalDate from, LocalDate to, LocalDate dueUntil, List<PlacementLine> placements) {

        public List<PlacementLine> placementsOrEmpty() { return placements == null ? List.of() : placements; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Account(UUID creditAccountId, String contractNumber, String productType, UUID obligorPartyId,
                   @JsonAlias("collectionClabe") String clabe) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlacementLine(UUID dispositionId, UUID beneficiaryPartyId, BigDecimal amount, Instant placedAt,
                         int totalInstallments, int paidInstallments, BigDecimal outstandingPrincipal,
                         List<InstallmentLine> dueInPeriod, int overdueInstallments, LocalDate oldestOverdueDue,
                         boolean paidOffInPeriod, LocalDate settledOn) {

        public List<InstallmentLine> dueOrEmpty() { return dueInPeriod == null ? List.of() : dueInPeriod; }

        public boolean live() { return outstandingPrincipal != null && outstandingPrincipal.signum() > 0; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstallmentLine(int number, LocalDate dueDate, BigDecimal principal, BigDecimal interest, BigDecimal tax,
                           BigDecimal total, String status, LocalDate originalDueDate) {}

    /** What a receipt reads of a client's profile: his type and his red-list marks. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClientProfile(UUID beneficiaryPartyId, String type, List<Flag> flags, int loansGranted, int loansPaid,
                         int activeLoans) {

        public List<Flag> flagsOrEmpty() { return flags == null ? List.of() : flags; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Flag(String reason, String source, UUID distributorPartyId) {}
}
