package net.cumba.corej.rest.run;

import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.cumba.corej.rest.config.CorejProperties;
import net.cumba.corej.rest.report.ReportStore;
import net.cumba.corej.rest.session.SessionRunGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of all check runs across all sessions, backed by per-run {@code run-<id>.json}
 * records in the {@link ReportStore}'s directory so the index survives a restart. Also serves as
 * the {@link SessionRunGuard} the session layer consults before deleting a session: a session with
 * any {@code PENDING}/{@code RUNNING} run cannot be deleted.
 */
@Component
public class RunRegistry implements SessionRunGuard
{

    private static final Logger LOG = LoggerFactory.getLogger(RunRegistry.class);

    private final ReportStore reportStore;

    private final CorejProperties properties;

    private final ConcurrentMap<String, CheckRun> runs = new ConcurrentHashMap<>();

    public RunRegistry(ReportStore reportStore, CorejProperties properties)
    {
        this.reportStore = reportStore;
        this.properties = properties;
    }


    /**
     * Rebuild the run index from persisted records at startup. Terminal runs load as-is (their
     * report/log artifacts are already on disk). A run that was still in flight when the process
     * stopped cannot resume, so it is rehydrated as {@code FAILED}. A {@code SUCCEEDED} record
     * whose report file has gone missing is dropped entirely (and its stale record removed). This
     * runs after {@code ReportStore}'s init (a constructor dependency), so its directory is ready.
     */
    @PostConstruct
    void reload()
    {
        if (!properties.getPersistence().isRehydrateOnStartup())
        {
            return;
        }
        for (RunRecord record : reportStore.loadAllRunRecords())
        {
            CheckRun run = CheckRun.restore(record);
            if (run.status() == RunStatus.SUCCEEDED && !reportStore.hasReport(run.id()))
            {
                LOG.warn("Dropping run {}: recorded SUCCEEDED but report file is missing",
                        run.id());
                reportStore.delete(run.id());
                continue;
            }
            if (run.status().isInFlight())
            {
                run.markFailed("Run interrupted by server restart");
                reportStore.persistRunRecord(RunRecord.of(run));
            }
            runs.put(run.id(), run);
        }
        if (!runs.isEmpty())
        {
            LOG.info("Rehydrated {} run(s) from persisted records", runs.size());
        }
    }


    public void register(CheckRun run)
    {
        runs.put(run.id(), run);
        persist(run);
    }


    /** Persist a run's current state to its on-disk record (best-effort). */
    public void persist(CheckRun run)
    {
        reportStore.persistRunRecord(RunRecord.of(run));
    }


    /**
     * Returns the run with the given id.
     *
     * @throws RunNotFoundException
     *             if the id is unknown.
     */
    public CheckRun get(String runId)
    {
        CheckRun run = runs.get(runId);
        if (run == null)
        {
            throw new RunNotFoundException(runId);
        }
        return run;
    }


    /**
     * Remove a run from the in-memory index only. The caller must also remove the on-disk artifacts
     * (report, log, and the {@code run-<id>.json} record) via {@link ReportStore#delete(String)};
     * otherwise an orphaned record would rehydrate a phantom run on the next restart.
     */
    public void remove(String runId)
    {
        runs.remove(runId);
    }


    public Collection<CheckRun> all()
    {
        return runs.values();
    }


    @Override
    public boolean hasInFlightRuns(String sessionId)
    {
        return runs.values().stream()
                .anyMatch(r -> r.sessionId().equals(sessionId) && r.status().isInFlight());
    }
}
