package com.fintech.commission;

import com.fintech.commission.application.port.out.CreditPromoterAssignmentRepository;
import com.fintech.commission.application.service.PromoterAssignmentService;
import com.fintech.commission.domain.CreditPromoterAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PromoterAssignmentServiceTest {

    @Mock CreditPromoterAssignmentRepository repository;

    PromoterAssignmentService service;

    private final UUID creditAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromoterAssignmentService(repository);
    }

    @Test
    void validUuidPromoterCode_createsAssignment() {
        UUID distributorPartyId = UUID.randomUUID();
        given(repository.existsById(creditAccountId)).willReturn(false);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onCreditAccountActivated(creditAccountId, "DISTRIBUTOR_LINE", distributorPartyId.toString());

        ArgumentCaptor<CreditPromoterAssignment> captor = ArgumentCaptor.forClass(CreditPromoterAssignment.class);
        then(repository).should().save(captor.capture());
        assertThat(captor.getValue().getBeneficiaryPartyId()).isEqualTo(distributorPartyId);
        assertThat(captor.getValue().getProductType()).isEqualTo("DISTRIBUTOR_LINE");
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void nullPromoterCode_createsNoAssignment() {
        given(repository.existsById(creditAccountId)).willReturn(false);

        service.onCreditAccountActivated(creditAccountId, "PERSONAL_LOAN", null);

        then(repository).should(never()).save(any());
    }

    @Test
    void nonUuidPromoterCode_createsNoAssignment_CM07() {
        given(repository.existsById(creditAccountId)).willReturn(false);

        service.onCreditAccountActivated(creditAccountId, "DISTRIBUTOR_LINE", "PROMO-2026-XYZ");

        then(repository).should(never()).save(any());
    }

    @Test
    void alreadyAssigned_isIdempotent() {
        given(repository.existsById(creditAccountId)).willReturn(true);

        service.onCreditAccountActivated(creditAccountId, "DISTRIBUTOR_LINE", UUID.randomUUID().toString());

        then(repository).should(never()).save(any());
    }

    @Test
    void creditsByPromoters_empty_returnsEmpty_withoutHittingRepo() {
        assertThat(service.creditsByPromoters(List.of())).isEmpty();
        assertThat(service.creditsByPromoters(null)).isEmpty();
        then(repository).should(never()).findByBeneficiaryPartyIdIn(anyCollection());
    }

    @Test
    void creditsByPromoters_delegatesToRepository() {
        UUID distId = UUID.randomUUID();
        CreditPromoterAssignment a = CreditPromoterAssignment.create(creditAccountId, distId, "DISTRIBUTOR_LINE");
        given(repository.findByBeneficiaryPartyIdIn(List.of(distId))).willReturn(List.of(a));

        assertThat(service.creditsByPromoters(List.of(distId))).containsExactly(a);
    }
}
