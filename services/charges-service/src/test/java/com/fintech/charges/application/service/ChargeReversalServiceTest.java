package com.fintech.charges.application.service;

import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.ChargeRecord;
import com.fintech.charges.domain.ChargeRecordNotFoundException;
import com.fintech.charges.domain.ChargeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ChargeReversalServiceTest {

    @Mock ChargeRecordRepository chargeRecordRepo;
    @Mock ChargeEventPublisher eventPublisher;

    ChargeReversalService service;

    @BeforeEach
    void setUp() {
        service = new ChargeReversalService(chargeRecordRepo, eventPublisher);
        lenient().when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── reverse ───────────────────────────────────────────────────────────────

    @Test
    void reverse_changesStatusToReversed() {
        UUID accountId = UUID.randomUUID();
        ChargeRecord charge = buildRecord(accountId, ChargeType.ORDINARY_INTEREST);
        given(chargeRecordRepo.findById(charge.getChargeId())).willReturn(Optional.of(charge));
        given(chargeRecordRepo.findByLinkedChargeId(charge.getChargeId())).willReturn(Optional.empty());

        ChargeRecord result = service.reverse(charge.getChargeId(), "error correction");

        assertThat(result.getStatus()).isEqualTo("REVERSED");
        assertThat(result.getReversedAt()).isNotNull();
        then(chargeRecordRepo).should().save(charge);
        then(eventPublisher).should().publishChargeReversed(any(), eq(accountId),
                eq("ORDINARY_INTEREST"), any(), eq(false));
    }

    @Test
    void reverse_throwsNotFound_whenChargeMissing() {
        UUID chargeId = UUID.randomUUID();
        given(chargeRecordRepo.findById(chargeId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reverse(chargeId, "reason"))
                .isInstanceOf(ChargeRecordNotFoundException.class);
        then(eventPublisher).should(never()).publishChargeReversed(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void reverse_cascadesLinkedIva() {
        UUID accountId = UUID.randomUUID();
        ChargeRecord charge = buildRecord(accountId, ChargeType.ORDINARY_INTEREST);
        ChargeRecord iva    = buildRecord(accountId, ChargeType.IVA);

        given(chargeRecordRepo.findById(charge.getChargeId())).willReturn(Optional.of(charge));
        given(chargeRecordRepo.findByLinkedChargeId(charge.getChargeId())).willReturn(Optional.of(iva));

        service.reverse(charge.getChargeId(), "error correction");

        assertThat(charge.getStatus()).isEqualTo("REVERSED");
        assertThat(iva.getStatus()).isEqualTo("REVERSED");
        // Both the charge and IVA saved
        then(chargeRecordRepo).should(times(2)).save(any());
    }

    // ── waive ─────────────────────────────────────────────────────────────────

    @Test
    void waive_changesStatusToWaived() {
        UUID accountId = UUID.randomUUID();
        ChargeRecord charge = buildRecord(accountId, ChargeType.MORATORIUM_INTEREST);
        given(chargeRecordRepo.findById(charge.getChargeId())).willReturn(Optional.of(charge));
        given(chargeRecordRepo.findByLinkedChargeId(charge.getChargeId())).willReturn(Optional.empty());

        ChargeRecord result = service.waive(charge.getChargeId(), "ops-admin");

        assertThat(result.getStatus()).isEqualTo("WAIVED");
        assertThat(result.getWaivedBy()).isEqualTo("ops-admin");
        // waived=true distinguishes from reversal for T4 accounting
        then(eventPublisher).should().publishChargeReversed(any(), eq(accountId),
                eq("MORATORIUM_INTEREST"), any(), eq(true));
    }

    @Test
    void waive_throwsNotFound_whenChargeMissing() {
        UUID chargeId = UUID.randomUUID();
        given(chargeRecordRepo.findById(chargeId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.waive(chargeId, "ops-admin"))
                .isInstanceOf(ChargeRecordNotFoundException.class);
    }

    @Test
    void waive_cascadesLinkedIva() {
        UUID accountId = UUID.randomUUID();
        ChargeRecord charge = buildRecord(accountId, ChargeType.MORATORIUM_INTEREST);
        ChargeRecord iva    = buildRecord(accountId, ChargeType.IVA);

        given(chargeRecordRepo.findById(charge.getChargeId())).willReturn(Optional.of(charge));
        given(chargeRecordRepo.findByLinkedChargeId(charge.getChargeId())).willReturn(Optional.of(iva));

        service.waive(charge.getChargeId(), "ops-admin");

        assertThat(charge.getStatus()).isEqualTo("WAIVED");
        assertThat(iva.getStatus()).isEqualTo("WAIVED");
        // IVA waivedBy includes suffix
        assertThat(iva.getWaivedBy()).contains("ops-admin");
        then(chargeRecordRepo).should(times(2)).save(any());
    }

    @Test
    void reverse_vs_waive_differ_inWaivedFlag() {
        UUID accountId = UUID.randomUUID();
        ChargeRecord forReverse = buildRecord(accountId, ChargeType.ORDINARY_INTEREST);
        ChargeRecord forWaive   = buildRecord(accountId, ChargeType.ORDINARY_INTEREST);

        given(chargeRecordRepo.findById(forReverse.getChargeId())).willReturn(Optional.of(forReverse));
        given(chargeRecordRepo.findById(forWaive.getChargeId())).willReturn(Optional.of(forWaive));
        given(chargeRecordRepo.findByLinkedChargeId(any())).willReturn(Optional.empty());

        service.reverse(forReverse.getChargeId(), "reversal reason");
        service.waive(forWaive.getChargeId(), "waiver reason");

        // reverse publishes waived=false; waive publishes waived=true
        then(eventPublisher).should().publishChargeReversed(any(), any(), any(), any(), eq(false));
        then(eventPublisher).should().publishChargeReversed(any(), any(), any(), any(), eq(true));

        assertThat(forReverse.getStatus()).isEqualTo("REVERSED");
        assertThat(forWaive.getStatus()).isEqualTo("WAIVED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChargeRecord buildRecord(UUID accountId, ChargeType type) {
        return ChargeRecord.create(accountId, UUID.randomUUID(), type,
                new BigDecimal("10000"), new BigDecimal("0.24"), 1,
                new BigDecimal("6.67"), BigDecimal.ZERO, LocalDate.now(), null);
    }
}
