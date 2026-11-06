package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.ClientReferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** Gives a reference to every client placed before references existed, once, at start-up. */
@Component
public class ClientReferenceBackfill {

    private static final Logger log = LoggerFactory.getLogger(ClientReferenceBackfill.class);

    private final ClientReferenceRepository references;
    private final ClientReferenceService service;

    public ClientReferenceBackfill(ClientReferenceRepository references, ClientReferenceService service) {
        this.references = references;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void assignMissing() {
        List<ClientReferenceRepository.Pair> pending = references.pairsWithoutReference();
        for (ClientReferenceRepository.Pair p : pending) {
            service.ensure(p.creditAccountId(), p.obligorPartyId(), p.beneficiaryPartyId());
        }
        if (!pending.isEmpty()) {
            log.info("Referencia de pago asignada a {} clientes colocados antes", pending.size());
        }
    }
}
