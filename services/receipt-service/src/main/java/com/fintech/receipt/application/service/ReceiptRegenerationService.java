package com.fintech.receipt.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.application.port.out.ReceiptDocumentStore;
import com.fintech.receipt.application.port.out.ReceiptRenderer;
import com.fintech.receipt.application.port.out.ReceiptRepository;
import com.fintech.receipt.application.service.assembly.ReceiptAssembler;
import com.fintech.receipt.application.service.assembly.ReceiptInputs;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Prints generated receipts again from their stored snapshot, in the current design: the same figures, no service
 * asked. What a design change is verified with, without running another cycle.
 */
@Service
public class ReceiptRegenerationService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptRegenerationService.class);
    private static final int PAGE = 200;

    private final ReceiptRepository receipts;
    private final ReceiptAssembler assembler;
    private final ReceiptRenderer renderer;
    private final ReceiptDocumentStore documents;
    private final ReceiptProperties properties;
    private final ObjectMapper json;
    private final ZoneId zone;

    public ReceiptRegenerationService(ReceiptRepository receipts, ReceiptAssembler assembler, ReceiptRenderer renderer,
                                      ReceiptDocumentStore documents, ReceiptProperties properties, ObjectMapper json,
                                      BusinessClock clock) {
        this.receipts = receipts;
        this.assembler = assembler;
        this.renderer = renderer;
        this.documents = documents;
        this.properties = properties;
        this.json = json;
        this.zone = clock.zone();
    }

    public record Result(int regenerated, int failed) {}

    public Result regenerate(UUID creditAccountId, Integer cycleNumber) {
        int done = 0, failed = 0;
        UUID after = null;
        while (true) {
            List<UUID> page = receipts.generated(creditAccountId, cycleNumber, after, PAGE);
            if (page.isEmpty()) break;
            for (UUID id : page) {
                try {
                    ReceiptInputs inputs = json.readValue(receipts.findSnapshot(id).orElseThrow(), ReceiptInputs.class);
                    byte[] pdf = renderer.render(assembler.assemble(inputs, properties, zone));
                    documents.store(id, pdf, "application/pdf");
                    receipts.markRegenerated(id, renderer.templateVersion(), pdf.length);
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.warn("No se pudo regenerar el recibo {}: {}", id, e.getMessage());
                }
            }
            after = page.getLast();
        }
        log.info("Recibos regenerados: {} ({} fallidos)", done, failed);
        return new Result(done, failed);
    }
}
