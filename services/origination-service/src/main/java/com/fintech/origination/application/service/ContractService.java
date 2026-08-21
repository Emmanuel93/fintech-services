package com.fintech.origination.application.service;

import com.fintech.origination.application.GenerateContractCommand;
import com.fintech.origination.application.SignContractCommand;
import com.fintech.origination.application.port.in.ApplyDisbursementUseCase;
import com.fintech.origination.application.port.in.GenerateContractUseCase;
import com.fintech.origination.application.port.in.SignContractUseCase;
import com.fintech.origination.application.port.out.ClabeValidator;
import com.fintech.origination.application.port.out.ContractEventPublisher;
import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.application.port.out.ProspectRepository;
import com.fintech.origination.application.port.out.SignatureValidator;
import com.fintech.origination.domain.Contract;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditApplicationNotFoundException;
import com.fintech.origination.domain.CreditOffer;
import com.fintech.origination.domain.Prospect;
import com.fintech.origination.domain.event.ContractSignedEvent;
import com.fintech.origination.domain.event.CreditProductCreationRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@Transactional
public class ContractService implements GenerateContractUseCase, SignContractUseCase, ApplyDisbursementUseCase {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);
    private static final DateTimeFormatter CONTRACT_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final CreditApplicationRepository applicationRepository;
    private final ProspectRepository prospectRepository;
    private final SignatureValidator signatureValidator;
    private final ClabeValidator clabeValidator;
    private final ContractEventPublisher eventPublisher;

    public ContractService(CreditApplicationRepository applicationRepository,
                           ProspectRepository prospectRepository,
                           SignatureValidator signatureValidator,
                           ClabeValidator clabeValidator,
                           ContractEventPublisher eventPublisher) {
        this.applicationRepository = applicationRepository;
        this.prospectRepository    = prospectRepository;
        this.signatureValidator    = signatureValidator;
        this.clabeValidator        = clabeValidator;
        this.eventPublisher        = eventPublisher;
    }

    @Override
    public CreditApplication generate(GenerateContractCommand cmd) {
        CreditApplication app = loadOrThrow(cmd.applicationId());
        String contractNumber = generateContractNumber(app);
        app.generateContract(Contract.generate(contractNumber, cmd.signatureMethod()));
        CreditApplication saved = applicationRepository.save(app);
        log.info("Contract generated applicationId={} contractNumber={}", app.getApplicationId(), contractNumber);
        return saved;
    }

    @Override
    public CreditApplication sign(SignContractCommand cmd) {
        CreditApplication app = loadOrThrow(cmd.applicationId());

        if (!clabeValidator.isValid(cmd.clabeAccount())) {
            throw new IllegalArgumentException("Invalid CLABE account (CM-06): " + cmd.clabeAccount());
        }
        if (!signatureValidator.isValid(app.getContract().getSignatureMethod(), cmd.signatureProof())) {
            throw new IllegalArgumentException("Invalid signature proof for method "
                    + app.getContract().getSignatureMethod());
        }

        String docRef = cmd.documentRef() != null ? cmd.documentRef()
                : "DOC-" + app.getApplicationId().toString().substring(0, 8).toUpperCase();

        app.signContract(cmd.clabeAccount(), docRef);
        CreditApplication saved = applicationRepository.save(app);

        log.info("Contract signed applicationId={} contractNumber={}",
                app.getApplicationId(), app.getContract().getContractNumber());

        eventPublisher.publishContractSigned(new ContractSignedEvent(
                saved.getApplicationId(),
                saved.getProspectId(),
                saved.getContract().getContractNumber(),
                saved.getContract().getSignatureMethod(),
                saved.getContract().getClabeAccount()));

        eventPublisher.publishCreditProductCreationRequested(buildSnapshot(saved));
        return saved;
    }

    @Override
    public void apply(UUID applicationId) {
        applicationRepository.findById(applicationId).ifPresent(app -> {
            if (app.getStatus().name().equals("CONTRACT_SIGNED")) {
                app.markDisbursed();
                applicationRepository.save(app);
                log.info("Application marked DISBURSED applicationId={}", applicationId);
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CreditApplication loadOrThrow(UUID id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new CreditApplicationNotFoundException(id.toString()));
    }

    private String generateContractNumber(CreditApplication app) {
        String yearMonth = LocalDateTime.now(ZoneOffset.UTC).format(CONTRACT_FMT);
        String suffix    = app.getApplicationId().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "CTR-" + yearMonth + "-" + suffix;
    }

    private CreditProductCreationRequestedEvent buildSnapshot(CreditApplication app) {
        CreditOffer offer = app.getOffer();

        // Identidad del beneficiario, congelada en el hecho (2B.1). El nombre con el que se
        // desembolsa queda igual de fijo que la tasa: si el prospecto cambia luego su nombre,
        // el pago ya emitido no se altera. Se resuelve aquí, del lado que sí conoce a la persona,
        // para que ni disbursement ni stp tengan que preguntarle a party-service.
        Prospect prospect = prospectRepository.findById(app.getProspectId()).orElse(null);
        String obligorName  = prospect != null ? fullName(prospect) : null;
        String obligorTaxId = prospect != null
                ? (prospect.getRfc() != null ? prospect.getRfc() : prospect.getCurp())
                : null;

        return new CreditProductCreationRequestedEvent(
                app.getApplicationId(),
                app.getContract().getContractNumber(),
                app.getProspectId(),          // TODO: resolve actual partyId from party-service
                offer.getProductCode(),
                offer.getProductVersion(),
                app.getProductType().name(),
                offer.getProductBehavior(),
                offer.getOfferedAmount(),
                offer.getOfferedLine(),
                offer.getOfferedTerm(),
                offer.getNominalRate(),
                offer.getMoratoriumRate(),
                offer.getAmortizationType(),
                offer.getOpeningFeeRate(),
                app.getContract().getClabeAccount(),
                app.getRiskLevel(),
                app.getPromoterCode(),
                obligorName,
                obligorTaxId);
    }

    private static String fullName(Prospect p) {
        return java.util.stream.Stream.of(p.getFirstName(), p.getLastName1(), p.getLastName2())
                .filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
