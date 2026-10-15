package com.fintech.creditportfolio.aml;

import com.fintech.creditportfolio.application.ProcessDispositionCommand;
import com.fintech.creditportfolio.application.port.out.*;
import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.application.service.CreditAccountService;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.domain.*;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.DispositionRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El AML de la disposición comprueba <b>a los dos papeles</b>, y son dos papeles distintos.
 *
 * <h2>La confusión que estas pruebas existen para impedir</h2>
 *
 * Es fácil pensar en «el titular» de una disposición como si fuera una sola persona. No lo es:
 *
 * <table border="1">
 *   <caption>Los dos papeles por producto</caption>
 *   <tr><th>Producto</th><th>Responsable de la línea</th><th>Quien recibe el dinero</th></tr>
 *   <tr><td>Uso propio / nómina</td><td>el titular</td><td><b>el mismo titular</b></td></tr>
 *   <tr><td>Línea de distribuidor</td><td>la distribuidora</td>
 *       <td><b>la beneficiaria — nunca la distribuidora</b></td></tr>
 * </table>
 *
 * <p>Toda disposición tiene beneficiario. En uso propio <b>es</b> el responsable de la línea: no es
 * que falte, es que es la misma persona. En una línea de distribuidor es otra, y comprobar sólo al
 * responsable dejaría pagar a una beneficiaria sancionada mientras la distribuidora esté limpia —
 * que es exactamente la operación que el control existe para detener.
 *
 * <p>Y se comprueba también al responsable, aunque no reciba nada: no tiene sentido mover dinero de
 * la línea de alguien que está en listas por mucho que el destino sea un tercero.
 */
@ExtendWith(MockitoExtension.class)
class AmlChecksBothRolesTest {

    @Mock CreditAccountRepository accountRepository;
    @Mock DispositionRepository dispositionRepository;
    @Mock InstallmentRepository installmentRepository;
    @Mock BalanceEventRepository balanceEventRepository;
    @Mock CreditPortfolioEventPublisher eventPublisher;
    @Mock ProductConfigResolver configResolver;
    @Mock PartyVerificationRepository partyVerifications;

    @Captor ArgumentCaptor<DispositionRejectedEvent> rejection;
    @Captor ArgumentCaptor<Disposition> savedDisposition;

    CreditAccountService service;

    @BeforeEach
    void setUp() {
        service = new CreditAccountService(accountRepository, dispositionRepository,
                installmentRepository, balanceEventRepository, new AmortizationEngine(),
                eventPublisher, configResolver, partyVerifications, 12);
        lenient().when(balanceEventRepository.existsBySourceEventId(any())).thenReturn(false);
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(dispositionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────

    private static PartyVerification cleared(UUID partyId) {
        return PartyVerification.of(partyId, "VERIFIED", "CLEARED", true, null);
    }

    private static PartyVerification listed(UUID partyId) {
        return PartyVerification.of(partyId, "VERIFIED", "CONFIRMED_MATCH", false,
                "listas CONFIRMED_MATCH");
    }

    /** Una línea de distribuidor: su producto coloca a la beneficiaria. */
    private CreditAccount activeDistributorLine() {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-DL-1", UUID.randomUUID(),
                "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING",
                null, new BigDecimal("50000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.02"), "032180000118359719", "MEDIO", null, null);
        a.activate(BigDecimal.ZERO);
        lenient().when(accountRepository.findById(a.getCreditAccountId())).thenReturn(Optional.of(a));
        lenient().when(configResolver.resolveForAccount(org.mockito.ArgumentMatchers.eq("DL-001"), any()))
                .thenReturn(Optional.of(ProductConfigVersion.of(
                        "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING", "B2B2C",
                        new Capabilities(false, true, true, "THIRD_PARTY_CREDIT",
                                true, true, false, false, false),
                        "FRENCH", "MONTHLY", 1000,
                        new BigDecimal("0.36"), new BigDecimal("0.54"), new BigDecimal("2.0"),
                        "ACTIVE", false)));
        return a;
    }

    /** Una línea de uso propio: el dinero va a su propio titular. */
    private CreditAccount activeSelfUseLine() {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-REV-1", UUID.randomUUID(),
                "RL-001", 1, "REVOLVING_CREDIT", "REVOLVING",
                null, new BigDecimal("50000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.02"), "032180000118359719", "MEDIO", null, null);
        a.activate(BigDecimal.ZERO);
        lenient().when(accountRepository.findById(a.getCreditAccountId())).thenReturn(Optional.of(a));
        lenient().when(configResolver.resolveForAccount(org.mockito.ArgumentMatchers.eq("RL-001"), any()))
                .thenReturn(Optional.of(ProductConfigVersion.of(
                        "RL-001", 1, "REVOLVING_CREDIT", "REVOLVING", "B2C",
                        new Capabilities(false, true, true, "SELF_USE",
                                true, true, false, false, false),
                        "FRENCH", "MONTHLY", 1000,
                        new BigDecimal("0.36"), new BigDecimal("0.54"), new BigDecimal("2.0"),
                        "ACTIVE", false)));
        return a;
    }

    private ProcessDispositionCommand dispositionFor(CreditAccount account, UUID beneficiario) {
        return new ProcessDispositionCommand("evt-" + UUID.randomUUID(), account.getCreditAccountId(),
                account.getObligorPartyId(), new BigDecimal("5000"), beneficiario,
                "012180012345678905", 6);
    }

    // ── Uso propio: una sola persona, y es la misma ──────────────────────────────────────────

    @Test
    @DisplayName("uso propio: el beneficiario ES el responsable de la línea, y se comprueba una vez")
    void inSelfUseTheRecipientIsTheLineHolder() {
        // No es que «no haya beneficiario»: es que es la misma persona, y por eso la petición no lo
        // manda. Comprobar dos veces a la misma persona no añadiría nada.
        CreditAccount account = activeSelfUseLine();
        given(partyVerifications.findByPartyId(account.getObligorPartyId()))
                .willReturn(Optional.of(cleared(account.getObligorPartyId())));

        service.process(dispositionFor(account, null));

        verify(partyVerifications).findByPartyId(account.getObligorPartyId());
        verify(eventPublisher, never()).publishDispositionRejected(any());
    }

    @Test
    @DisplayName("uso propio: si el titular está en listas, no sale el dinero — es él quien lo recibe")
    void inSelfUseAlistedHolderBlocksTheMoney() {
        CreditAccount account = activeSelfUseLine();
        given(partyVerifications.findByPartyId(account.getObligorPartyId()))
                .willReturn(Optional.of(listed(account.getObligorPartyId())));

        service.process(dispositionFor(account, null));

        verify(eventPublisher).publishDispositionRejected(rejection.capture());
        assertThat(rejection.getValue().getReason()).startsWith("AML_RESPONSABLE");
    }

    // ── Distribuidor: dos personas, y el dinero va a la que no responde ──────────────────────

    @Test
    @DisplayName("distribuidor: se comprueba a la distribuidora Y a la beneficiaria, las dos")
    void inAdistributorLineBothPartiesAreChecked() {
        CreditAccount line = activeDistributorLine();
        UUID beneficiary = UUID.randomUUID();
        given(partyVerifications.findByPartyId(line.getObligorPartyId()))
                .willReturn(Optional.of(cleared(line.getObligorPartyId())));
        given(partyVerifications.findByPartyId(beneficiary))
                .willReturn(Optional.of(cleared(beneficiary)));

        service.process(dispositionFor(line, beneficiary));

        verify(partyVerifications).findByPartyId(line.getObligorPartyId());
        verify(partyVerifications).findByPartyId(beneficiary);
        verify(eventPublisher, never()).publishDispositionRejected(any());
    }

    @Test
    @DisplayName("distribuidor: la BENEFICIARIA en listas detiene el dinero, aunque la distribuidora esté limpia")
    void alistedBeneficiaryBlocksTheMoney() {
        // Es el caso central de todo esto. El dinero va a la beneficiaria: comprobar sólo a la
        // distribuidora sería comprobar a quien no lo recibe, y el control no detendría nada.
        CreditAccount line = activeDistributorLine();
        UUID beneficiary = UUID.randomUUID();
        given(partyVerifications.findByPartyId(line.getObligorPartyId()))
                .willReturn(Optional.of(cleared(line.getObligorPartyId())));
        given(partyVerifications.findByPartyId(beneficiary))
                .willReturn(Optional.of(listed(beneficiary)));

        service.process(dispositionFor(line, beneficiary));

        verify(eventPublisher).publishDispositionRejected(rejection.capture());
        assertThat(rejection.getValue().getReason()).startsWith("AML_RECEPTOR");
    }

    @Test
    @DisplayName("distribuidor: la DISTRIBUIDORA en listas también detiene, aunque reciba otra persona")
    void alistedDistributorBlocksTheMoneyToo() {
        // No tiene sentido mover dinero de la línea de alguien que está en listas por mucho que el
        // destino sea un tercero: el crédito lo está usando esa persona, y el riesgo es suyo.
        CreditAccount line = activeDistributorLine();
        UUID beneficiary = UUID.randomUUID();
        given(partyVerifications.findByPartyId(line.getObligorPartyId()))
                .willReturn(Optional.of(listed(line.getObligorPartyId())));

        service.process(dispositionFor(line, beneficiary));

        verify(eventPublisher).publishDispositionRejected(rejection.capture());
        assertThat(rejection.getValue().getReason()).startsWith("AML_RESPONSABLE");
        // Y se corta ahí: no hace falta consultar a la beneficiaria para saber que esto no sale.
        verify(partyVerifications, never()).findByPartyId(beneficiary);
    }

    @Test
    @DisplayName("distribuidor: sin beneficiaria no se dispone — no se sabe a quién se le colocó")
    void adistributorDispositionWithoutAbeneficiaryIsRejected() {
        // Sin esto, una colocación sin beneficiaria pasaría el AML comprobando sólo a la
        // distribuidora y el dinero saldría sin que nadie hubiera mirado a quien lo recibe.
        CreditAccount line = activeDistributorLine();

        service.process(dispositionFor(line, null));

        verify(eventPublisher).publishDispositionRejected(rejection.capture());
        assertThat(rejection.getValue().getReason()).isEqualTo("BENEFICIARY_REQUIRED");
    }

    @Test
    @DisplayName("distribuidor: la beneficiaria no puede ser la propia distribuidora")
    void thebeneficiaryCannotBeTheDistributorItself() {
        // Si lo fuera no habría colocación: sería la distribuidora usando su línea para sí misma, y
        // eso es un crédito de uso propio — con su producto, su expediente y su riesgo.
        CreditAccount line = activeDistributorLine();

        service.process(dispositionFor(line, line.getObligorPartyId()));

        verify(eventPublisher).publishDispositionRejected(rejection.capture());
        assertThat(rejection.getValue().getReason()).isEqualTo("BENEFICIARY_CANNOT_BE_OBLIGOR");
    }

    // ── Y la constancia se guarda de quien recibe ────────────────────────────────────────────

    @Test
    @DisplayName("la foto del veredicto es la de QUIEN RECIBE, no la del responsable")
    void thesnapshotRecordsTheRecipientsVerdict() {
        // La compuerta y la constancia resuelven al receptor con la MISMA regla. Si discreparan, se
        // bloquearía mirando a una persona y se guardaría constancia de otra — y el expediente
        // diría que se comprobó a alguien a quien no se comprobó.
        CreditAccount line = activeDistributorLine();
        UUID beneficiary = UUID.randomUUID();
        given(partyVerifications.findByPartyId(line.getObligorPartyId()))
                .willReturn(Optional.of(cleared(line.getObligorPartyId())));
        given(partyVerifications.findByPartyId(beneficiary)).willReturn(Optional.of(
                PartyVerification.of(beneficiary, "VERIFIED", "CLEARED", true, null)));

        service.process(dispositionFor(line, beneficiary));

        verify(dispositionRepository).save(savedDisposition.capture());
        assertThat(savedDisposition.getValue().getBeneficiaryPartyId()).isEqualTo(beneficiary);
        assertThat(savedDisposition.getValue().getAmlScreeningStatus()).isEqualTo("CLEARED");
    }
}
