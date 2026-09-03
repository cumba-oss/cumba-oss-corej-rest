package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import net.cumba.corej.rest.run.CheckRun;
import net.cumba.corej.rest.run.RunStatus;
import org.jspecify.annotations.Nullable;

/**
 * Status snapshot of a check run, including the growing progress view. {@code totalDatasets} is
 * {@code -1} until discovered; {@code processedDatasets} jumps to the total when the run completes
 * (the engine assembles its report at the end), while {@code rulesExecuted} ticks up live.
 */
@Schema(description = "Status and progress of a check run")
public record CheckStatusResponse(@Schema(description = "Check-run id") String checkRunId,
        @Schema(description = "Owning session id") String sessionId,
        @Schema(description = "Owning session's display name, or null when unnamed/deleted") @Nullable String sessionName,
        @Schema(description = "Lifecycle state") RunStatus status,
        @Schema(description = "Target datasets discovered (-1 until known)") int totalDatasets,
        @Schema(description = "Datasets completed") int processedDatasets,
        @Schema(description = "Rules executed so far") int rulesExecuted,
        @Schema(description = "Findings: the authoritative total once SUCCEEDED; while RUNNING, a "
                + "running tally over the datasets finished so far; null otherwise") @Nullable Integer findingCount,
        @Schema(description = "Failure detail (only when FAILED)") @Nullable String message,
        @Schema(description = "Creation time (ISO-8601)") String createdAt,
        @Schema(description = "Start time (ISO-8601), null while PENDING") @Nullable String startedAt,
        @Schema(description = "Completion time (ISO-8601), null until terminal") @Nullable String finishedAt)
{

    /** Snapshot with no resolved session name (e.g. callers that do not have the registry). */
    public static CheckStatusResponse from(CheckRun run)
    {
        return from(run, null);
    }


    /**
     * Snapshot carrying the owning session's current display name ({@code null} when unnamed or the
     * session no longer exists). The name is resolved live by the caller, so renames are reflected.
     */
    public static CheckStatusResponse from(CheckRun run, @Nullable String sessionName)
    {
        // SUCCEEDED → the authoritative count (held independently of the in-memory result, so it
        // survives a restore from disk); RUNNING → the live running tally over finished datasets;
        // PENDING/FAILED/CANCELLED → no count.
        Integer findingCount;
        if (run.status() == RunStatus.SUCCEEDED)
        {
            findingCount = run.findingCount();
        }
        else if (run.status() == RunStatus.RUNNING)
        {
            findingCount = run.findingsSoFar();
        }
        else
        {
            findingCount = null;
        }
        // createdAt is set at construction (Instant.now()) and never null, so its ISO form is
        // always present; startedAt/finishedAt may be null until the run reaches that stage.
        return new CheckStatusResponse(run.id(), run.sessionId(), sessionName, run.status(),
                run.totalDatasets(), run.processedDatasets(), run.rulesExecuted(), findingCount,
                run.failureMessage(), Objects.requireNonNull(iso(run.createdAt())),
                iso(run.startedAt()), iso(run.finishedAt()));
    }


    private static @Nullable String iso(@Nullable Instant instant)
    {
        return instant == null ? null : instant.toString();
    }
}
