package com.fintech.charges.application.service;

import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.domain.ChargeRecord;
import com.fintech.charges.domain.ChargeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La reversa del interés que una compra devengó mientras fue revolvente.
 *
 * <h2>El supuesto del plan que no se sostuvo</h2>
 *
 * <p>El plan decía «cada devengo diario entre la compra y el diferimiento se reversa». Al ir a
 * hacerlo, el devengo ordinario resultó ser <b>por cuenta</b>, no por disposición: un cargo diario
 * de una tarjeta cubre el saldo completo de la línea. Reversarlo entero devolvería también el
 * interés de las compras que <b>no</b> se difirieron.
 *
 * <p>Lo que se reversa es la parte atribuible: {@code importe × tasa / 360 × días}.
 */
@ExtendWith(MockitoExtension.class)
class ReversaPorDiferimientoTest {

    @Mock AccrualScheduleRepository scheduleRepo;
    @Mock ChargeRecordRepository chargeRepo;
    @Mock ChargeEventPublisher eventPublisher;

    DeferralInterestReversalService servicio;

    private final UUID cuenta = UUID.randomUUID();
    private final UUID compra = UUID.randomUUID();

    @BeforeEach
    void init() {
        ChargesProperties props = new ChargesProperties();
        props.setVatRate(new BigDecimal("0.16"));
        servicio = new DeferralInterestReversalService(scheduleRepo, chargeRepo, eventPublisher, props);
    }

    /** Tarjeta al 36 % anual con un saldo de línea de 20 000. */
    private void conCalendario() {
        AccrualSchedule s = AccrualSchedule.create(cuenta, UUID.randomUUID(),
                "CREDIT_CARD", "REVOLVING",
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                new BigDecimal("20000.00"), new BigDecimal("20000.00"), 3);
        given(scheduleRepo.findByCreditAccountId(cuenta)).willReturn(Optional.of(s));
        // `lenient`: la prueba del mismo día llega aquí y nunca guarda nada, que es justo lo que
        // comprueba.
        lenient().when(chargeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<ChargeRecord> cargosGenerados() {
        ArgumentCaptor<ChargeRecord> captor = ArgumentCaptor.forClass(ChargeRecord.class);
        verify(chargeRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("reversa SÓLO la parte atribuible a la compra, no el devengo de toda la línea")
    void soloLoAtribuible() {
        conCalendario();
        // Compra de 6 000 que fue revolvente 10 días: 6000 × 0.36/360 × 10 = 60.00
        servicio.reversarPorDiferimiento(cuenta, compra, new BigDecimal("6000.00"),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 11));

        ChargeRecord interes = cargosGenerados().stream()
                .filter(c -> ChargeType.ORDINARY_INTEREST.name().equals(c.getChargeType()))
                .findFirst().orElseThrow();

        // Sobre el saldo completo de la línea (20 000) habrían sido 200: más del triple.
        assertThat(interes.getTotalAmount()).isEqualByComparingTo("-60.00");
    }

    @Test
    @DisplayName("el IVA se reversa CON el interés")
    void elIvaVaConEl() {
        conCalendario();
        servicio.reversarPorDiferimiento(cuenta, compra, new BigDecimal("6000.00"),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 11));

        // Reversar el interés sin su IVA deja un impuesto trasladado sobre un ingreso que ya no
        // existe, y eso se descubre en la declaración.
        ChargeRecord iva = cargosGenerados().stream()
                .filter(c -> ChargeType.IVA.name().equals(c.getChargeType()))
                .findFirst().orElseThrow();

        assertThat(iva.getTotalAmount()).isEqualByComparingTo("-9.60");   // 60.00 × 0.16
    }

    @Test
    @DisplayName("diferir el MISMO día no reversa nada")
    void mismoDia() {
        conCalendario();
        LocalDate hoy = LocalDate.of(2026, 6, 1);

        servicio.reversarPorDiferimiento(cuenta, compra, new BigDecimal("6000.00"), hoy, hoy);

        verify(chargeRepo, never()).save(any());
    }

    @Test
    @DisplayName("una cuenta sin calendario de devengo no rompe nada")
    void sinCalendario() {
        given(scheduleRepo.findByCreditAccountId(cuenta)).willReturn(Optional.empty());

        servicio.reversarPorDiferimiento(cuenta, compra, new BigDecimal("6000.00"),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 11));

        verify(chargeRepo, never()).save(any());
    }

    @Test
    @DisplayName("la reversa entra como cargo NUEVO en negativo, no editando los diarios")
    void esAppendOnly() {
        conCalendario();
        servicio.reversarPorDiferimiento(cuenta, compra, new BigDecimal("6000.00"),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 11));

        // La bitácora de cargos es append-only: borrar lo que ya se cobró dejaría al mayor sin
        // poder explicar de dónde salió la diferencia.
        assertThat(cargosGenerados()).hasSize(2);
        assertThat(cargosGenerados()).allSatisfy(c ->
                assertThat(c.getTotalAmount()).isNegative());
    }
}
