package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.ClosePhase;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Las operaciones en lote del corte repartido.
 *
 * <p>Van aparte de {@link CloseUnitRepository} porque son de otra naturaleza: aquella trabaja una
 * unidad a la vez con su entidad; éstas mueven miles de filas en una sentencia. Traer cada cuenta a
 * memoria para planificar 100 000 cortes era el cuello de botella serial del planificador.
 */
public interface CutoffUnitStore {

    /**
     * Crea una unidad por cada cuenta viva con corte en {@code cutoffDate}, en un solo
     * {@code INSERT … SELECT}. Es idempotente: repetirla no duplica.
     *
     * @param distributorProductTypes los productos cuyo titular es una distribuidora
     * @return cuántas unidades se crearon
     */
    int materialize(UUID runId, LocalDate cutoffDate, Collection<String> distributorProductTypes,
                    Collection<UUID> heldAccounts);

    /**
     * Suelta la cuenta retenida de una corrida: de {@code HELD} a {@code PENDING}, sin despachar.
     *
     * @return si había una retenida
     */
    boolean release(UUID runId, UUID creditAccountId);

    /** Accounts of the run whose unit has not received the portfolio's snapshot yet, oldest first. */
    List<UUID> accountsWithoutSnapshot(UUID runId, int limit);

    /** Copies the snapshot onto the matching units; units that already have one are left as they are. */
    int attachSnapshots(UUID runId, Collection<PortfolioDaySnapshots.AccountSnapshot> snapshots);

    /** Cuántas unidades hay por producto, comportamiento y tipo de titular. */
    List<Breakdown> breakdown(UUID runId);

    void saveBreakdown(UUID runId, List<Breakdown> breakdown);

    /** El desglose congelado al planificar; vacío si la corrida no lo tiene. */
    List<Breakdown> savedBreakdown(UUID runId);

    /**
     * Unidades PENDING que nunca salieron al tópico o salieron antes de {@code staleBefore}.
     * Paginado por llave ({@code unit_id > after}), no por desplazamiento: con 100 000 filas, un
     * {@code OFFSET} relee todo lo anterior en cada página.
     */
    List<Dispatchable> findDispatchable(UUID runId, Instant staleBefore, UUID after, int limit);

    int markDispatched(Collection<UUID> unitIds, Instant at);

    /** Devuelve a la cola las fallidas que aún tienen intentos. */
    int requeueFailed(UUID runId, int maxAttempts);

    /** Reintento manual: devuelve todas las fallidas y les reinicia los intentos. */
    int requeueAllFailed(UUID runId);

    StatusCounts counts(UUID runId);

    List<Failure> failures(UUID runId, int limit);

    /** Las corridas abiertas (PLANNED o RUNNING) de una fase. */
    List<UUID> openRuns(ClosePhase phase);

    /** Fechas con cortes de cuentas vivas que siguen sin correr, entre {@code from} y antes de {@code toExclusive}. */
    List<LocalDate> pendingCutoffDates(LocalDate from, LocalDate toExclusive);

    record Breakdown(String productType, String productBehavior, String holderKind, int units) {}

    record Dispatchable(UUID unitId, UUID runId, UUID creditAccountId, UUID obligorPartyId,
                        String productType, String productBehavior, String holderKind) {}

    record StatusCounts(int pending, int claimed, int done, int failed, int skipped, int held) {

        public StatusCounts(int pending, int claimed, int done, int failed, int skipped) {
            this(pending, claimed, done, failed, skipped, 0);
        }

        /** Lo que todavía puede cambiar sin que nadie lo suelte: pendientes y tomadas. Las retenidas no. */
        public int open()  { return pending + claimed; }
        public int total() { return pending + claimed + done + failed + skipped + held; }
    }

    record Failure(UUID creditAccountId, String productType, int attempts, String error) {}
}
