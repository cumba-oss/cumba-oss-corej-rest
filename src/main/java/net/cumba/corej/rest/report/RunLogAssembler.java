package net.cumba.corej.rest.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.rest.run.CheckRun;
import net.cumba.corej.rest.session.Session;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link RunLog} from a finished {@link CheckRun}, its {@link Session}, and the engine
 * {@link StudyValidationResult} (which is {@code null} for a run that failed or was cancelled
 * before producing one). Pure mapping — no I/O beyond reading the already-cached per-file SHA-256.
 */
public final class RunLogAssembler
{

    private RunLogAssembler()
    {
    }


    /**
     * Assembles the execution log.
     *
     * @param run
     *            the finished run (terminal status, timing, config)
     * @param session
     *            the run's session, or {@code null} if it could not be resolved
     * @param result
     *            the engine result, or {@code null} when the run did not complete validation
     * @return the assembled log
     */
    public static RunLog assemble(CheckRun run, @Nullable Session session,
            @Nullable StudyValidationResult result)
    {
        List<RunLog.FileManifestEntry> files = new ArrayList<>();
        if (session != null)
        {
            List<Session.FileEntry> entries = new ArrayList<>(session.files());
            entries.sort(Comparator.comparing(Session.FileEntry::filename));
            for (Session.FileEntry e : entries)
            {
                files.add(new RunLog.FileManifestEntry(e.filename(), e.size(), e.sha256()));
            }
        }

        List<RunLog.DomainLogEntry> domains = new ArrayList<>();
        Integer totalFindings = null;
        Double runtime = null;
        if (result != null)
        {
            for (DatasetExecutionSummary s : result.executionSummaries())
            {
                List<RunLog.RuleErrorEntry> errors = new ArrayList<>();
                for (DatasetExecutionSummary.RuleError e : s.errors())
                {
                    errors.add(new RunLog.RuleErrorEntry(e.ruleId(), e.message()));
                }
                List<RunLog.RuleExecutionEntry> ruleExecutions = new ArrayList<>();
                for (DatasetExecutionSummary.RuleExecution e : s.ruleExecutions())
                {
                    ruleExecutions.add(new RunLog.RuleExecutionEntry(e.coreId(), e.generatedId(),
                            e.status(), e.violations(), e.runtimeMillis(), e.expandedFor(),
                            e.notExecutedReason(), e.description(), e.executability()));
                }
                domains.add(new RunLog.DomainLogEntry(s.domain(), s.fileName(), s.rulesExecuted(),
                        s.rulesTotal(), s.findings(), s.runtimeMillis(), errors, ruleExecutions));
            }
            totalFindings = result.findingCount();
            runtime = result.totalRuntimeSeconds();
        }

        // createdAt is always set (Instant.now() at run construction), so its ISO form is never
        // null; startedAt/finishedAt may be null until the run reaches that stage.
        return new RunLog(run.id(), run.sessionId(), run.status().name(),
                Objects.requireNonNull(iso(run.createdAt())), iso(run.startedAt()),
                iso(run.finishedAt()), runtime, run.request(), files, domains, totalFindings,
                run.failureMessage(), run.logLines());
    }


    private static @Nullable String iso(@Nullable Instant instant)
    {
        return instant != null ? instant.toString() : null;
    }
}
