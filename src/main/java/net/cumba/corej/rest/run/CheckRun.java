package net.cumba.corej.rest.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.run.StudyValidationResult;
import org.jspecify.annotations.Nullable;

/**
 * Mutable state of a single check run: identity, the request that drives it, lifecycle status,
 * progress counters, cancellation flag, the executor {@link Future}, timestamps, and (on success)
 * the engine result. Thread-safe — status transitions are synchronized and the live counters are
 * atomic, since progress is fed from validator worker threads while a status request reads on
 * another.
 */
public final class CheckRun
{

    private final String id;

    private final String sessionId;

    private final CheckRunRequest request;

    private final Instant createdAt;

    private final AtomicBoolean cancelRequested = new AtomicBoolean();

    private final AtomicInteger rulesExecuted = new AtomicInteger();

    /**
     * Running tally of findings across the datasets finished so far, fed live by the per-dataset
     * progress callback. A best-effort count surfaced while the run is {@code RUNNING}; on
     * {@code SUCCEEDED} the authoritative {@link #findingCount} from the report is reported
     * instead.
     */
    private final AtomicInteger findingsSoFar = new AtomicInteger();

    /**
     * Run-relevant log lines appended via the capture sink ({@link RunLogDebugCapture} taps the
     * engine's {@code System.Logger} and forwards each line to {@link #addLogLine}). Synchronized
     * because the engine may emit from rule worker threads while a terminal status reader copies
     * them.
     */
    private final List<String> logLines = Collections.synchronizedList(new ArrayList<>());

    /**
     * Guards the status transitions below. A PRIVATE lock, not {@code this}: {@link #restore} is a
     * public static factory that hands the instance out, so the intrinsic lock of a {@code
     * CheckRun} is reachable by any caller holding one, and an unrelated {@code synchronized (run)}
     * elsewhere would then contend with (or deadlock against) a status transition. Nothing outside
     * this class locks on a {@code CheckRun} today, so this is a containment change only, with
     * identical mutual exclusion among the four {@code mark*} methods. Flagged by SpotBugs 4.10 as
     * USO_UNSAFE_METHOD_SYNCHRONIZATION.
     */
    private final Object statusLock = new Object();

    private volatile RunStatus status = RunStatus.PENDING;

    private volatile int totalDatasets = -1;

    private volatile int processedDatasets;

    private volatile @Nullable Instant startedAt;

    private volatile @Nullable Instant finishedAt;

    private volatile @Nullable String failureMessage;

    private volatile @Nullable StudyValidationResult result;

    /**
     * Authoritative finding count, captured on {@code SUCCEEDED} from the engine result and kept
     * independently of {@link #result} so a run rebuilt from disk (whose result is gone) can still
     * report it.
     */
    private volatile @Nullable Integer findingCount;

    private volatile @Nullable Future<?> future;

    public CheckRun(String id, String sessionId, CheckRunRequest request)
    {
        this(id, sessionId, request, Instant.now());
    }


    private CheckRun(String id, String sessionId, CheckRunRequest request, Instant createdAt)
    {
        this.id = id;
        this.sessionId = sessionId;
        this.request = request;
        this.createdAt = createdAt;
    }


    /**
     * Rebuild a run from its persisted {@link RunRecord} (e.g. after a service restart). Restores
     * identity, status and timestamps directly; the transient runtime fields (future, cancellation
     * flag) start at their defaults, and {@link #result} stays {@code null} (the report artifacts
     * are served from disk, and {@link #findingCount()} carries the count).
     */
    public static CheckRun restore(RunRecord record)
    {
        CheckRun run = new CheckRun(record.id(), record.sessionId(), record.request(),
                Instant.parse(record.createdAt()));
        run.status = RunStatus.valueOf(record.status());
        run.startedAt = record.startedAt() == null ? null : Instant.parse(record.startedAt());
        run.finishedAt = record.finishedAt() == null ? null : Instant.parse(record.finishedAt());
        run.totalDatasets = record.totalDatasets();
        run.processedDatasets = record.processedDatasets();
        run.rulesExecuted.set(record.rulesExecuted());
        run.findingCount = record.findingCount();
        run.failureMessage = record.failureMessage();
        return run;
    }


    public String id()
    {
        return id;
    }


    public String sessionId()
    {
        return sessionId;
    }


    public CheckRunRequest request()
    {
        return request;
    }


    public RunStatus status()
    {
        return status;
    }


    public Instant createdAt()
    {
        return createdAt;
    }


    public @Nullable Instant startedAt()
    {
        return startedAt;
    }


    public @Nullable Instant finishedAt()
    {
        return finishedAt;
    }


    public @Nullable String failureMessage()
    {
        return failureMessage;
    }


    public @Nullable StudyValidationResult result()
    {
        return result;
    }


    /**
     * Authoritative finding count once the run has {@code SUCCEEDED}, else {@code null}. Survives a
     * restore from disk (unlike {@link #result()}).
     */
    public @Nullable Integer findingCount()
    {
        return findingCount;
    }


    /** Add a just-completed dataset's findings to the live running tally. */
    public void addDatasetFindings(int datasetFindings)
    {
        findingsSoFar.addAndGet(datasetFindings);
    }


    /** Findings counted across the datasets finished so far (a running, monotonic tally). */
    public int findingsSoFar()
    {
        return findingsSoFar.get();
    }


    public int totalDatasets()
    {
        return totalDatasets;
    }


    public int processedDatasets()
    {
        return processedDatasets;
    }


    public int rulesExecuted()
    {
        return rulesExecuted.get();
    }

    // ------------------------------------------------------------------
    // Progress (fed by the ProgressListener bridge)
    // ------------------------------------------------------------------


    public void setTotalDatasets(int total)
    {
        this.totalDatasets = total;
    }


    public void setProcessedDatasets(int processed)
    {
        this.processedDatasets = processed;
    }


    public void incrementRulesExecuted()
    {
        rulesExecuted.incrementAndGet();
    }


    /**
     * Appends a run-relevant log line (called from the capture sink {@link RunLogDebugCapture}).
     */
    public void addLogLine(String line)
    {
        logLines.add(line);
    }


    /** A snapshot of the run-relevant log lines collected so far, in order. */
    public List<String> logLines()
    {
        synchronized (logLines)
        {
            return List.copyOf(logLines);
        }
    }


    /**
     * An atomic snapshot of the log lines from {@code from} (clamped) to the end, paired with the
     * cursor to resume from. Both are captured under the same lock so that a line appended between
     * reading the tail and reading the size can neither be skipped nor duplicated across polls:
     * {@code nextFrom} is exactly the index past the last returned line, and (lines being
     * append-only) the next request for {@code from == nextFrom} returns precisely the lines added
     * since.
     */
    public LogTail logTailFrom(int from)
    {
        synchronized (logLines)
        {
            int size = logLines.size();
            int start = Math.min(Math.max(from, 0), size);
            return new LogTail(List.copyOf(logLines.subList(start, size)), size);
        }
    }

    /**
     * A tail of the live log: the lines from the requested offset and the cursor to resume from.
     */
    public record LogTail(List<String> lines, int nextFrom)
    {

        public LogTail
        {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    /**
     * Request cancellation; the executor pulls a queued run, a running run aborts at a boundary.
     */
    public void requestCancel()
    {
        cancelRequested.set(true);
    }


    /** Polled by the orchestrator's cancellation {@code BooleanSupplier}. */
    public boolean isCancelRequested()
    {
        return cancelRequested.get();
    }


    public @Nullable Future<?> future()
    {
        return future;
    }


    public void setFuture(Future<?> future)
    {
        this.future = future;
    }

    // ------------------------------------------------------------------
    // Status transitions (terminal states are sticky)
    // ------------------------------------------------------------------


    public void markRunning()
    {
        synchronized (statusLock)
        {
            if (status == RunStatus.PENDING)
            {
                status = RunStatus.RUNNING;
                startedAt = Instant.now();
            }
        }
    }


    public void markSucceeded(@Nullable StudyValidationResult result)
    {
        synchronized (statusLock)
        {
            if (!status.isTerminal())
            {
                this.result = result;
                this.findingCount = result == null ? null : result.findingCount();
                status = RunStatus.SUCCEEDED;
                finishedAt = Instant.now();
            }
        }
    }


    public void markFailed(String message)
    {
        synchronized (statusLock)
        {
            if (!status.isTerminal())
            {
                failureMessage = message;
                status = RunStatus.FAILED;
                finishedAt = Instant.now();
            }
        }
    }


    /** Transition to {@code CANCELLED} from any non-terminal state. */
    public void markCancelled()
    {
        synchronized (statusLock)
        {
            if (!status.isTerminal())
            {
                status = RunStatus.CANCELLED;
                finishedAt = Instant.now();
            }
        }
    }
}
