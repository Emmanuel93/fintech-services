package com.fintech.invoicing;

import com.fintech.invoicing.application.InvoiceRequestCommand;
import com.fintech.invoicing.application.InvoicingProperties;
import com.fintech.invoicing.application.port.out.FiscalProfileRepository;
import com.fintech.invoicing.application.port.out.InvoiceRepository;
import com.fintech.invoicing.application.port.out.InvoicingEventPublisher;
import com.fintech.invoicing.application.port.out.PacStampingPort;
import com.fintech.invoicing.application.service.InvoiceService;
import com.fintech.invoicing.domain.FiscalProfile;
import com.fintech.invoicing.domain.Invoice;
import com.fintech.invoicing.domain.InvoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock InvoiceRepository invoiceRepository;
    @Mock FiscalProfileRepository fiscalProfileRepository;
    @Mock PacStampingPort pacPort;
    @Mock InvoicingEventPublisher eventPublisher;

    InvoiceService service;
    final InvoicingProperties properties = new InvoicingProperties();

    private final UUID party = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InvoiceService(invoiceRepository, fiscalProfileRepository, pacPort, eventPublisher, properties);
        lenient().when(pacPort.stamp(any())).thenReturn(new PacStampingPort.StampResult(UUID.randomUUID(), "A", 42L));
        lenient().when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private InvoiceRequestCommand cmd() {
        return new InvoiceRequestCommand(requestId, party, "202607",
                List.of(new InvoiceRequestCommand.Line("ORDINARY_INTEREST", UUID.randomUUID(), new BigDecimal("100"), false),
                        new InvoiceRequestCommand.Line("IVA", UUID.randomUUID(), new BigDecimal("16"), true)),
                new BigDecimal("100"), new BigDecimal("16"), new BigDecimal("116"));
    }

    @Test
    void generatesAndStamps_withFiscalProfile() {
        given(invoiceRepository.existsByInvoiceRequestId(requestId)).willReturn(false);
        given(fiscalProfileRepository.findById(party)).willReturn(Optional.of(
                FiscalProfile.of(party, null, "INDIVIDUAL", "GARJ900101AB1", "JUAN GARCIA", "612", "06600", "G03")));

        service.onInvoiceRequested(cmd());

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        then(invoiceRepository).should().save(captor.capture());
        Invoice inv = captor.getValue();
        assertThat(inv.getReceptorRfc()).isEqualTo("GARJ900101AB1");
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.STAMPED);
        assertThat(inv.getFolioFiscal()).isNotNull();
        assertThat(inv.getTotal()).isEqualByComparingTo("116");
        assertThat(inv.getLines()).hasSize(2);
        then(eventPublisher).should().publishInvoiceGenerated(any());
    }

    /**
     * El obligado llega identificado por su prospecto, que es como lo conocen cartera y contabilidad.
     *
     * <p>Buscando sólo por {@code partyId} no se encontraba perfil y el CFDI salía a «público en
     * general»: con folio, sin error, y sin más síntoma que ver el RFC genérico repetido en todas
     * las facturas de la pantalla.
     */
    @Test
    void resolvesReceptor_whenObligorIsIdentifiedByProspectId() {
        UUID partyReal = UUID.randomUUID();
        given(invoiceRepository.existsByInvoiceRequestId(requestId)).willReturn(false);
        given(fiscalProfileRepository.findById(party)).willReturn(Optional.empty());
        given(fiscalProfileRepository.findByProspectId(party)).willReturn(Optional.of(
                FiscalProfile.of(partyReal, party, "BUSINESS", "GFP190815HM2",
                        "GRUPO FERRETERO DEL PACIFICO", "601", "80000", "G03")));

        service.onInvoiceRequested(cmd());

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        then(invoiceRepository).should().save(captor.capture());
        assertThat(captor.getValue().getReceptorRfc()).isEqualTo("GFP190815HM2");
        assertThat(captor.getValue().getReceptorName()).isEqualTo("GRUPO FERRETERO DEL PACIFICO");
    }

    @Test
    void usesGenericReceptor_whenNoFiscalProfile() {
        given(invoiceRepository.existsByInvoiceRequestId(requestId)).willReturn(false);
        given(fiscalProfileRepository.findById(party)).willReturn(Optional.empty());
        given(fiscalProfileRepository.findByProspectId(party)).willReturn(Optional.empty());

        service.onInvoiceRequested(cmd());

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        then(invoiceRepository).should().save(captor.capture());
        assertThat(captor.getValue().getReceptorRfc()).isEqualTo("XAXX010101000"); // público en general
    }

    @Test
    void isIdempotent_whenInvoiceAlreadyExists() {
        given(invoiceRepository.existsByInvoiceRequestId(requestId)).willReturn(true);

        service.onInvoiceRequested(cmd());

        then(invoiceRepository).should(never()).save(any());
        then(pacPort).shouldHaveNoInteractions();
    }
}
