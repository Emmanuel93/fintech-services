package com.fintech.risk;

import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.application.service.RiskProfileService;
import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileNotFoundException;
import com.fintech.risk.domain.RiskProfileStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class RiskProfileServiceTest {

    @Mock RiskProfileRepository repository;

    RiskProfileService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RiskProfileService(repository);
    }

    @Test
    void onCreditAccountActivated_createsProfile_whenAbsent() {
        given(repository.existsByCreditAccountId(creditAccountId)).willReturn(false);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onCreditAccountActivated(creditAccountId, obligorPartyId, "PERSONAL_LOAN");

        ArgumentCaptor<RiskProfile> captor = ArgumentCaptor.forClass(RiskProfile.class);
        then(repository).should().save(captor.capture());
        assertThat(captor.getValue().getProductType()).isEqualTo("PERSONAL_LOAN");
        assertThat(captor.getValue().getStatus()).isEqualTo(RiskProfileStatus.ACTIVE);
    }

    @Test
    void onCreditAccountActivated_isIdempotent() {
        given(repository.existsByCreditAccountId(creditAccountId)).willReturn(true);

        service.onCreditAccountActivated(creditAccountId, obligorPartyId, "PERSONAL_LOAN");

        then(repository).should(never()).save(any());
    }

    @Test
    void onBalanceUpdated_syncsEad() {
        RiskProfile p = RiskProfile.create(creditAccountId, obligorPartyId, "PERSONAL_LOAN");
        given(repository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(p));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onBalanceUpdated(creditAccountId, new BigDecimal("7500"), "ACTIVE");

        assertThat(p.getEad()).isEqualByComparingTo("7500");
        then(repository).should().save(p);
    }

    @Test
    void onBalanceUpdated_closesProfile_whenSettled() {
        RiskProfile p = RiskProfile.create(creditAccountId, obligorPartyId, "PERSONAL_LOAN");
        given(repository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(p));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onBalanceUpdated(creditAccountId, new BigDecimal("0"), "SETTLED");

        assertThat(p.getStatus()).isEqualTo(RiskProfileStatus.CLOSED);
    }

    @Test
    void onBalanceUpdated_closesProfile_whenWrittenOff() {
        RiskProfile p = RiskProfile.create(creditAccountId, obligorPartyId, "PERSONAL_LOAN");
        given(repository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(p));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onBalanceUpdated(creditAccountId, new BigDecimal("5000"), "WRITTEN_OFF");

        assertThat(p.getStatus()).isEqualTo(RiskProfileStatus.CLOSED);
    }

    @Test
    void onDelinquencyStatusUpdated_syncsDaysAndBucket() {
        RiskProfile p = RiskProfile.create(creditAccountId, obligorPartyId, "PERSONAL_LOAN");
        given(repository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(p));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onDelinquencyStatusUpdated(creditAccountId, 45);

        assertThat(p.getDaysDelinquent()).isEqualTo(45);
        assertThat(p.getBucket().name()).isEqualTo("B31_60");
    }

    @Test
    void onRestructureExecuted_marksForborne() {
        RiskProfile p = RiskProfile.create(creditAccountId, obligorPartyId, "PERSONAL_LOAN");
        given(repository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(p));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onRestructureExecuted(creditAccountId);

        assertThat(p.isForborne()).isTrue();
    }

    @Test
    void getByCreditAccountId_notFound_throws() {
        given(repository.findByCreditAccountId(creditAccountId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByCreditAccountId(creditAccountId))
                .isInstanceOf(RiskProfileNotFoundException.class);
    }

    // ── modo consulta (search / batch) ─────────────────────────────────────────

    @Test
    void search_delegatesToRepository_normalizingBlankProductType() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<RiskProfile> page = new PageImpl<>(List.of());
        given(repository.search(obligorPartyId, null, Ifrs9Stage.STAGE_2, RiskProfileStatus.ACTIVE, pageable))
                .willReturn(page);

        // productType en blanco se normaliza a null (no filtra por cadena vacía).
        assertThat(service.search(obligorPartyId, "  ", Ifrs9Stage.STAGE_2, RiskProfileStatus.ACTIVE, pageable))
                .isSameAs(page);
    }

    @Test
    void findByCreditAccountIds_emptyInput_returnsEmpty_withoutHittingRepository() {
        assertThat(service.findByCreditAccountIds(List.of())).isEmpty();
        then(repository).should(never()).findByCreditAccountIdIn(any());
    }

    @Test
    void findByCreditAccountIds_delegatesToRepository() {
        List<UUID> ids = List.of(creditAccountId);
        RiskProfile p = RiskProfile.create(creditAccountId, obligorPartyId, "PERSONAL_LOAN");
        given(repository.findByCreditAccountIdIn(ids)).willReturn(List.of(p));

        assertThat(service.findByCreditAccountIds(ids)).containsExactly(p);
    }
}
