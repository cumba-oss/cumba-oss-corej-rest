package net.cumba.corej.rest.run;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.report.ReportFormat;
import net.cumba.corej.core.report.ReportManager;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.ServiceReportManager;
import net.cumba.corej.core.run.CancelledException;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.rest.config.CorejProperties;
import net.cumba.corej.rest.report.ReportStore;
import net.cumba.corej.rest.report.RunLog;
import net.cumba.corej.rest.report.RunLogAssembler;
import net.cumba.corej.rest.session.Session;
import net.cumba.corej.rest.session.SessionRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Global, bounded run queue: a single fixed-size thread pool (size =
 * {@code corej.runs.max-parallel}) shared by every session. Submitting a run registers it
 * ({@code PENDING}) and queues it; a worker flips it to {@code RUNNING} on pickup and to a terminal
 * state when the {@link CheckRunner} returns, fails, or observes cancellation. Runs beyond the cap
 * sit {@code PENDING} until a worker frees up.
 */
@Component
public class RunExecutor
{

    private static final Logger LOG = LoggerFactory.getLogger(RunExecutor.class);

    /**
     * The report-writer registry. Both persisted report artifacts are rendered through it, so this
     * class holds no JSON-writer import and a deployment can swap or drop a format module without
     * touching the run pipeline (Fix #224).
     */
    private static final ReportManager REPORT_MANAGER = ServiceReportManager.getInstance();

    private static final String FORMAT_JSON = "json";

    private static final String FORMAT_JSON_V2 = "json-2";

    private final RunRegistry registry;

    private final CheckRunner runner;

    private final ReportStore reportStore;

    private final SessionRegistry sessions;

    private final RunLogDebugCapture debugCapture;

    private final ExecutorService pool;

    public RunExecutor(CorejProperties properties, RunRegistry registry, CheckRunner runner,
            ReportStore reportStore, SessionRegistry sessions, RunLogDebugCapture debugCapture)
    {
        this.registry = registry;
        this.runner = runner;
        this.reportStore = reportStore;
        this.sessions = sessions;
        this.debugCapture = debugCapture;
        int parallelism = Math.max(1, properties.getRuns().getMaxParallel());
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(parallelism, r ->
        {
            Thread t = new Thread(r, "corej-run-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }


    /** Register and enqueue a run; returns immediately with the run still {@code PENDING}. */
    public void submit(CheckRun run)
    {
        registry.register(run);
        // Publish the Future on the run before the task can be dequeued, so a status/cancel call
        // can
        // never observe a transient null future.
        FutureTask<Void> task = new FutureTask<>(() -> execute(run), null);
        run.setFuture(task);
        pool.execute(task);
    }


    private void execute(CheckRun run)
    {
        if (run.isCancelRequested())
        {
            // Cancelled before it started — no work was done, so no execution log is produced.
            run.markCancelled();
            registry.persist(run);
            return;
        }
        run.markRunning();
        registry.persist(run);
        StudyValidationResult result = null;
        // Capture the engine's log output for this run into the run's log. This run-worker thread
        // binds the per-run sink; the engine's worker threads pick it up via the
        // contextPropagator() task decorator wired into the run's StudyValidationParams.
        debugCapture.begin(run::addLogLine);
        try
        {
            result = runner.run(run);
            if (result == null)
            {
                // F-rest-01: a null engine result is NOT a success. None of the artifact persists
                // below can run without a result, so marking SUCCEEDED here produces precisely the
                // state the next comment forbids: a SUCCEEDED run with a null findingCount whose
                // every report endpoint answers 409. CheckRunner.run is declared to return a
                // non-null result, so a null is a broken contract - report it as a failed run.
                throw new IllegalStateException(
                        "Check runner returned no validation result for run " + run.id());
            }
            // Persist the report before flipping to SUCCEEDED, so a persistence failure surfaces as
            // a failed run rather than a SUCCEEDED run with no retrievable report.
            ReportSections sections = result.sections();
            reportStore.persist(run.id(), render(sections, FORMAT_JSON));
            // The v2 combined-finding report is a primary artifact like v1: persist it before
            // SUCCEEDED so a write failure fails the run rather than yielding a SUCCEEDED run
            // whose /report-v2 is unretrievable.
            reportStore.persistV2(run.id(), render(sections, FORMAT_JSON_V2));
            // Best-effort pre-render + rule-definition capture: failures are logged and never
            // change the run outcome (the report is already persisted).
            persistXlsxQuietly(run);
            persistRuleDefsQuietly(run, result);
            run.markSucceeded(result);
        }
        catch (CancelledException _)
        {
            run.markCancelled();
        }
        catch (Exception e)
        {
            LOG.warn("Check run {} failed", run.id(), e);
            appendStackTrace(run, e);
            run.markFailed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        catch (Throwable t)
        {
            // Errors (OutOfMemoryError, StackOverflowError, …) must still terminate the run rather
            // than leave it stuck RUNNING. Record the failure, then let the Error propagate after
            // the finally below has persisted the terminal state.
            LOG.error("Check run {} aborted by fatal error", run.id(), t);
            appendStackTrace(run, t);
            run.markFailed(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            throw t;
        }
        finally
        {
            debugCapture.end();
            // Belt-and-suspenders: the run must never remain RUNNING once the worker leaves this
            // method, even if a transition above was itself interrupted (e.g. a second OOM).
            if (!run.status().isTerminal())
            {
                run.markFailed("Run terminated abnormally");
            }
            // Persist the terminal state so the run survives a restart, regardless of how the try
            // exited (including a re-thrown Error). The run reached RUNNING, so also emit the
            // execution log for every terminal outcome (SUCCEEDED / FAILED /
            // CANCELLED-after-start).
            // A persist/log failure must not change run status.
            registry.persist(run);
            persistLogQuietly(run, result);
        }
    }


    /**
     * Append a terminating throwable's rendered stack trace to the run log, one entry per line.
     */
    private static void appendStackTrace(CheckRun run, Throwable t)
    {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        sw.toString().lines().forEach(line -> run.addLogLine("ERROR " + line));
    }


    private void persistLogQuietly(CheckRun run, @Nullable StudyValidationResult result)
    {
        try
        {
            Session session = sessions.get(run.sessionId());
            RunLog log = RunLogAssembler.assemble(run, session, result);
            reportStore.persistLog(run.id(), log);
        }
        catch (Exception e)
        {
            LOG.warn("Failed to persist execution log for run {}", run.id(), e);
        }
    }


    private void persistXlsxQuietly(CheckRun run)
    {
        try
        {
            reportStore.persistXlsx(run.id());
        }
        catch (Exception e)
        {
            LOG.warn("Failed to pre-render XLSX report for run {}", run.id(), e);
        }
    }


    private void persistRuleDefsQuietly(CheckRun run, StudyValidationResult result)
    {
        try
        {
            reportStore.persistRuleDefs(run.id(),
                    net.cumba.corej.rest.report.RuleDefsAssembler.assemble(result));
        }
        catch (Exception e)
        {
            LOG.warn("Failed to persist rule definitions for run {}", run.id(), e);
        }
    }


    /**
     * Request cancellation. A queued run is pulled before it starts (and transitioned to
     * {@code CANCELLED} here); a running run has its flag set and aborts cleanly at the next
     * rule/dataset boundary. A no-op on a terminal run.
     */
    public void requestCancel(CheckRun run)
    {
        run.requestCancel();
        Future<?> future = run.future();
        if (future != null && future.cancel(false))
        {
            // cancel(false) succeeds only while the task is still queued — it will never run.
            run.markCancelled();
            registry.persist(run);
        }
    }


    /**
     * Block up to {@code seconds} for the run to reach a terminal state. Returns early once
     * terminal; returns after the timeout if still in flight. Reading the run's status afterwards
     * gives the current snapshot.
     */
    public void awaitTerminal(CheckRun run, long seconds)
    {
        Future<?> future = run.future();
        if (future == null || seconds <= 0)
        {
            return;
        }
        try
        {
            future.get(seconds, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            LOG.trace("Run {} still in flight after {}s wait", run.id(), seconds);
        }
        catch (CancellationException | ExecutionException e)
        {
            LOG.trace("Run {} already terminal during wait", run.id());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }


    @PreDestroy
    void shutdown()
    {
        pool.shutdownNow();
    }


    /**
     * Serialises the assembled sections in one registered format and returns the UTF-8 payload —
     * the REST layer persists report <em>strings</em>, so the byte stream is a
     * {@link ByteArrayOutputStream} and never touches the filesystem.
     *
     * @throws IllegalStateException
     *             when no writer module provides the format; failing here is deliberate, because a
     *             SUCCEEDED run whose report cannot be retrieved is the worse outcome
     */
    private static String render(ReportSections sections, String formatName)
    {
        ReportFormat format = REPORT_MANAGER.findReportFormat(formatName);
        if (format == null)
        {
            throw new IllegalStateException("No report writer registered for format '" + formatName
                    + "' — add cumba-oss-corej-report-json to the classpath");
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            REPORT_MANAGER.writeReport(sections, out, format);
            return out.toString(StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            // A ByteArrayOutputStream performs no real I/O, so this is unreachable in practice.
            throw new UncheckedIOException(e);
        }
    }

}
