package com.fintech.payments.infrastructure.adapter.in.api;

import com.fintech.payments.application.port.out.CollectionAccountDirectory;
import com.fintech.payments.application.port.out.ReceivedCreditRepository;
import com.fintech.payments.application.service.CustomerBalanceService;
import com.fintech.payments.domain.balance.CustomerBalanceMovement;
import com.fintech.payments.domain.credit.CreditAllocation;
import com.fintech.payments.domain.credit.ReceivedCredit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payer's collection, end to end: their CLABE, every credit received on it with where each peso went (the
 * prelation line by line, with the payment order of each), and their balance in favor with its movements.
 */
@RestController
@RequestMapping("/api/v1/payments/parties/{partyId}")
@Tag(name = "Collection", description = "Abonos por CLABE, su aplicación y el saldo a favor")
class PayerCollectionController {

    private final CollectionAccountDirectory directory;
    private final ReceivedCreditRepository credits;
    private final CustomerBalanceService balances;

    PayerCollectionController(CollectionAccountDirectory directory, ReceivedCreditRepository credits,
                              CustomerBalanceService balances) {
        this.directory = directory;
        this.credits = credits;
        this.balances = balances;
    }

    record AllocationView(int seq, String mode, UUID creditAccountId, BigDecimal amount, UUID paymentOrderId) {
        static AllocationView of(CreditAllocation a) {
            return new AllocationView(a.getSeq(), a.mode().name(), a.getCreditAccountId(), a.getAmount(),
                    a.getPaymentOrderId());
        }
    }

    record ReceivedCreditView(UUID inboundCreditId, String trackingKey, BigDecimal amount, Instant receivedAt,
                              LocalDate operationDate, Long numericReference, String referenceStatus,
                              BigDecimal toLoans, BigDecimal toCreditBalance, List<AllocationView> allocations) {}

    record BalanceView(BigDecimal balance, List<MovementView> movements) {}

    record MovementView(String type, BigDecimal amount, BigDecimal balanceAfter, LocalDate businessDate,
                        UUID inboundCreditId, UUID paymentOrderId, UUID creditAccountId, String note) {
        static MovementView of(CustomerBalanceMovement m) {
            return new MovementView(m.type().name(), m.getAmount(), m.getBalanceAfter(), m.getBusinessDate(),
                    m.getInboundCreditId(), m.getPaymentOrderId(), m.getCreditAccountId(), m.getNote());
        }
    }

    @GetMapping("/collection-account")
    @Operation(summary = "CLABE de cobro de la persona")
    ResponseEntity<CollectionAccountDirectory.CollectionAccountView> collectionAccount(@PathVariable UUID partyId) {
        return directory.findByParty(partyId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/received-credits")
    @Operation(summary = "Abonos recibidos y cómo se aplicó cada peso: referenciado, por regla o a favor")
    @Transactional(readOnly = true)
    ResponseEntity<List<ReceivedCreditView>> receivedCredits(@PathVariable UUID partyId) {
        return ResponseEntity.ok(credits.findByParty(partyId).stream().map(this::view).toList());
    }

    @GetMapping("/credit-balance")
    @Operation(summary = "Saldo a favor y sus movimientos")
    ResponseEntity<BalanceView> creditBalance(@PathVariable UUID partyId) {
        return ResponseEntity.ok(new BalanceView(balances.balanceOf(partyId),
                balances.movementsOf(partyId).stream().map(MovementView::of).toList()));
    }

    private ReceivedCreditView view(ReceivedCredit c) {
        return new ReceivedCreditView(c.getInboundCreditId(), c.getTrackingKey(), c.getAmount(), c.getReceivedAt(),
                c.getOperationDate(), c.getNumericReference(), c.referenceStatus().name(), c.getToLoans(),
                c.getToCreditBalance(), credits.allocationsOf(c.getInboundCreditId()).stream().map(AllocationView::of).toList());
    }
}
