package net.cumba.corej.rest.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.run.CancelledException;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.rest.config.CorejProperties;
import net.cumba.corej.rest.report.ReportStore;
import net.cumba.corej.rest.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Queue/cancellation mechanics of {@link RunExecutor}, exercised with a controllable fake runner.
 */
class RunExecutorTest
{

    private RunExecutor executor;

    @AfterEach
    void tearDown()
    {
        if (executor != null)
        {
            executor.shutdown();
        }
    }


    private static CorejProperties propsWithParallelism(int n)
    {
        CorejProperties props = new CorejProperties();
        props.getRuns().setMaxParallel(n);
        return props;
    }


    /**
     * A temp-dir-backed, initialised report store. The fake runner returns a null result so no
     * report artifacts are written, but the run registry persists run records to it; an initialised
     * store keeps those writes clean.
     */
    private static ReportStore tempReportStore()
    {
        try
        {
            Path dir = Files.createTempDirectory("corej-exec-test");
            CorejProperties props = new CorejProperties();
            props.getReports().setDir(dir.toString());
            ReportStore store = new ReportStore(props);
            var init = ReportStore.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(store);
            return store;
        }
        catch (IOException | ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }


    private static RunRegistry tempRunRegistry()
    {
        return new RunRegistry(tempReportStore(), new CorejProperties());
    }


    /**
     * A session registry stub: execution-log assembly looks up the run's session, but these tests
     * use a fake runner with no real session, so a mock (whose {@code get} returns null) is enough
     * — log persistence fails quietly and never affects the run status under test.
     */
    private static SessionRegistry unusedSessions()
    {
        return mock(SessionRegistry.class);
    }


    private static CheckRun newRun(String id)
    {
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null, null,
                null);
        return new CheckRun(id, "s1", req);
    }

    /** Runner that blocks each run on a shared latch until the test releases it. */
    private static final class GatedRunner implements CheckRunner
    {

        private final CountDownLatch firstStarted = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        private final AtomicInteger started = new AtomicInteger();

        @Override
        public StudyValidationResult run(CheckRun run)
        {
            started.incrementAndGet();
            firstStarted.countDown();
            try
            {
                if (!release.await(5, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("runner not released in time");
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
            if (run.isCancelRequested())
            {
                throw new CancelledException();
            }
            return null;
        }


        void awaitFirstStarted() throws InterruptedException
        {
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        }


        void releaseAll()
        {
            release.countDown();
        }
    }

    @Test
    void secondRunStaysPendingWhilePoolIsFull() throws InterruptedException
    {
        GatedRunner runner = new GatedRunner();
        executor = new RunExecutor(propsWithParallelism(1), tempRunRegistry(), runner,
                tempReportStore(), unusedSessions(), new RunLogDebugCapture());

        CheckRun run1 = newRun("r1");
        CheckRun run2 = newRun("r2");
        executor.submit(run1);
        runner.awaitFirstStarted();

        executor.submit(run2);
        // Single worker is busy with run1, so run2 cannot have started.
        assertThat(run1.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run2.status()).isEqualTo(RunStatus.PENDING);

        runner.releaseAll();
        executor.awaitTerminal(run1, 5);
        executor.awaitTerminal(run2, 5);
        assertThat(run1.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run2.status()).isEqualTo(RunStatus.SUCCEEDED);
    }


    @Test
    void awaitTerminalTimesOutWhileRunning() throws InterruptedException
    {
        GatedRunner runner = new GatedRunner();
        executor = new RunExecutor(propsWithParallelism(1), tempRunRegistry(), runner,
                tempReportStore(), unusedSessions(), new RunLogDebugCapture());

        CheckRun run = newRun("r1");
        executor.submit(run);
        runner.awaitFirstStarted();

        executor.awaitTerminal(run, 1);
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);

        runner.releaseAll();
        executor.awaitTerminal(run, 5);
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
    }


    @Test
    void cancelQueuedRunIsPulledBeforeStart() throws InterruptedException
    {
        GatedRunner runner = new GatedRunner();
        executor = new RunExecutor(propsWithParallelism(1), tempRunRegistry(), runner,
                tempReportStore(), unusedSessions(), new RunLogDebugCapture());

        CheckRun run1 = newRun("r1");
        CheckRun run2 = newRun("r2");
        executor.submit(run1);
        runner.awaitFirstStarted();
        executor.submit(run2); // queued behind the busy worker

        executor.requestCancel(run2);
        assertThat(run2.status()).isEqualTo(RunStatus.CANCELLED);

        runner.releaseAll();
        executor.awaitTerminal(run1, 5);
        assertThat(run1.status()).isEqualTo(RunStatus.SUCCEEDED);
    }

    /** Runner that always throws the given exception, to drive the failure path. */
    private static final class ThrowingRunner implements CheckRunner
    {

        private final RuntimeException toThrow;

        ThrowingRunner(RuntimeException toThrow)
        {
            this.toThrow = toThrow;
        }


        @Override
        public StudyValidationResult run(CheckRun run)
        {
            throw toThrow;
        }
    }

    @Test
    void runnerExceptionAppendsStackTraceToRunLogAndPersistedLog() throws Exception
    {
        // A shared store so the persisted RunLog (assembled with a null session) is readable.
        ReportStore store = tempReportStore();
        RunRegistry registry = new RunRegistry(store, new CorejProperties());
        ThrowingRunner runner = new ThrowingRunner(new IllegalStateException("boom-marker"));
        executor = new RunExecutor(propsWithParallelism(1), registry, runner, store,
                unusedSessions(), new RunLogDebugCapture());

        CheckRun run = newRun("rfail");
        executor.submit(run);
        executor.awaitTerminal(run, 5);
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);

        // The live in-memory tail carries the rendered stack trace.
        assertThat(run.logLines())
                .anyMatch(l -> l.startsWith("ERROR ") && l.contains("IllegalStateException"));
        assertThat(run.logLines()).anyMatch(l -> l.startsWith("ERROR ") && l.contains("at "));

        // The persisted RunLog carries the same lines (assembled from run.logLines()).
        var persisted = store.loadLog(run.id());
        assertThat(persisted.logLines())
                .anyMatch(l -> l.startsWith("ERROR ") && l.contains("IllegalStateException"));
        assertThat(persisted.logLines()).anyMatch(l -> l.startsWith("ERROR ") && l.contains("at "));
    }


    @Test
    void cancelRunningRunAbortsAtBoundary() throws InterruptedException
    {
        GatedRunner runner = new GatedRunner();
        executor = new RunExecutor(propsWithParallelism(1), tempRunRegistry(), runner,
                tempReportStore(), unusedSessions(), new RunLogDebugCapture());

        CheckRun run = newRun("r1");
        executor.submit(run);
        runner.awaitFirstStarted();

        executor.requestCancel(run);
        assertThat(run.isCancelRequested()).isTrue();

        runner.releaseAll();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(run.status()).isEqualTo(RunStatus.CANCELLED));
    }
}
