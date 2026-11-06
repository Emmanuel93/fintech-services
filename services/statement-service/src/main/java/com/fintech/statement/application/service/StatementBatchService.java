package com.fintech.statement.application.service;

import com.fintech.statement.application.port.out.StatementBatchRepository;
import com.fintech.statement.application.port.out.StatementBatchRepository.Batch;
import com.fintech.statement.application.port.out.StatementRepository;
import com.fintech.statement.application.port.out.StatementRepository.ProgressLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * El avance de los estados de cuenta de un corte: cuántos se esperan —lo que el corte planificó— y
 * cuántos van, por producto y por tipo de titular.
 */
@Service
public class StatementBatchService {

    private static final Logger log = LoggerFactory.getLogger(StatementBatchService.class);

    private final StatementBatchRepository batches;
    private final StatementRepository statements;

    public StatementBatchService(StatementBatchRepository batches, StatementRepository statements) {
        this.batches    = batches;
        this.statements = statements;
    }

    public void onRunPlanned(UUID runId, LocalDate businessDate, int expected, String breakdownJson) {
        batches.planned(runId, businessDate, expected, breakdownJson);
        log.info("Corte del {}: se esperan {} estados de cuenta runId={}", businessDate, expected, runId);
    }

    public void onRunCompleted(UUID runId, LocalDate businessDate, String status, int done, int failed) {
        batches.cutoffCompleted(runId, businessDate, status, done, failed);
    }

    /**
     * @param inFlight    registrados que aún no terminan (PENDING o GENERATING)
     * @param notReceived los que el corte planificó y cuyo corte todavía no llega
     */
    public record Progress(UUID runId, LocalDate businessDate, int expected, int generated, int failed,
                           int inFlight, int notReceived, String cutoffStatus, List<ProgressLine> lines) {
        public boolean complete() { return generated == expected; }
    }

    public Optional<Progress> progress(UUID runId) {
        return batches.find(runId).map(this::progress);
    }

    public Optional<Progress> progressByDate(LocalDate businessDate) {
        return batches.findByDate(businessDate).map(this::progress);
    }

    private Progress progress(Batch b) {
        List<ProgressLine> lines = statements.progress(b.runId());
        int generados = sum(lines, "GENERATED");
        int fallidos  = sum(lines, "FAILED");
        int enCurso   = sum(lines, "GENERATING") + sum(lines, "PENDING");
        int sinLlegar = Math.max(0, b.expected() - generados - fallidos - enCurso);
        return new Progress(b.runId(), b.businessDate(), b.expected(), generados, fallidos, enCurso,
                sinLlegar, b.cutoffStatus(), lines);
    }

    private static int sum(List<ProgressLine> lines, String status) {
        return lines.stream().filter(l -> status.equals(l.status())).mapToInt(ProgressLine::count).sum();
    }
}
