package net.cumba.corej.rest.run;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Persistent snapshot of a {@link CheckRun}'s state, written next to its report artifacts as
 * {@code run-<id>.json} so the run index survives a process restart. Holds only what is needed to
 * rebuild the run for the status/list endpoints — the heavy report/log artifacts already live on
 * disk via the {@code ReportStore}. The transient runtime fields (executor future, cancellation
 * flag, in-memory {@link net.cumba.corej.core.run.StudyValidationResult}) are intentionally not
 * persisted; {@code findingCount} carries the only value the status endpoint reads back from the
 * result.
 */
public record RunRecord(String id, String sessionId, CheckRunRequest request, String status,
        String createdAt, @Nullable String startedAt, @Nullable String finishedAt,
        int totalDatasets, int processedDatasets, int rulesExecuted, @Nullable Integer findingCount,
        @Nullable String failureMessage)
{

    /** Capture a run's current state as a persistable record. */
    public static RunRecord of(CheckRun run)
    {
        return new RunRecord(run.id(), run.sessionId(), run.request(), run.status().name(),
                run.createdAt().toString(), iso(run.startedAt()), iso(run.finishedAt()),
                run.totalDatasets(), run.processedDatasets(), run.rulesExecuted(),
                run.findingCount(), run.failureMessage());
    }


    private static @Nullable String iso(@Nullable Instant instant)
    {
        return instant == null ? null : instant.toString();
    }
}
