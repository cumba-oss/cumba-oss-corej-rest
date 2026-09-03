package net.cumba.corej.rest.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies {@link RunLogDebugCapture} actually captures engine {@code System.Logger} DEBUG output
 * (the engine logs via {@code System.Logger}) into a per-run sink, including from spawned threads.
 */
@SpringBootTest
class RunLogDebugCaptureTest
{

    @TempDir
    static Path base;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("corej.sessions.dir", () -> base.resolve("sessions").toString());
        registry.add("corej.reports.dir", () -> base.resolve("reports").toString());
    }

    @Autowired
    private RunLogDebugCapture capture;

    @Test
    void capturesEngineDebugAndInfo()
    {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        capture.begin(lines::add);
        try
        {
            System.Logger log = System.getLogger("net.cumba.corej.core.TestLogger");
            log.log(System.Logger.Level.DEBUG, "engine debug line");
            log.log(System.Logger.Level.INFO, "engine info line");
        }
        finally
        {
            capture.end();
        }

        assertThat(lines).anyMatch(l -> l.contains("engine debug line"));
        // The appender is now the single, complete channel: INFO is captured here too.
        assertThat(lines).anyMatch(l -> l.startsWith("INFO") && l.contains("engine info line"));
    }


    @Test
    void capturesFromPropagatedWorkerThread() throws InterruptedException
    {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        capture.begin(lines::add);
        try
        {
            // A plain ThreadLocal no longer inherits the sink, so the worker picks it up via the
            // contextPropagator() task decorator (wrapped on the submitting thread).
            Runnable raw = () -> System.getLogger("net.cumba.corej.core.Worker")
                    .log(System.Logger.Level.DEBUG, "worker debug line");
            Runnable wrapped = capture.contextPropagator().apply(raw);
            Thread worker = new Thread(wrapped);
            worker.start();
            worker.join();
        }
        finally
        {
            capture.end();
        }

        assertThat(lines).anyMatch(l -> l.contains("worker debug line"));
    }


    @Test
    void capturesNothingWhenNoSinkActive()
    {
        // Without begin(), engine debug is not routed anywhere (no NPE, no capture).
        System.getLogger("net.cumba.corej.core.Idle").log(System.Logger.Level.DEBUG, "ignored");
    }


    @Test
    void capturesEngineErrorEvenWithoutThrowable()
    {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        capture.begin(lines::add);
        try
        {
            // This appender is the only capture channel, so ERROR lines land here too.
            System.getLogger("net.cumba.corej.core.ErrTest").log(System.Logger.Level.ERROR,
                    "engine error line");
        }
        finally
        {
            capture.end();
        }
        assertThat(lines).anyMatch(l -> l.startsWith("ERROR") && l.contains("engine error line"));
    }


    @Test
    void rendersThrowableStackTraceFrames()
    {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        capture.begin(lines::add);
        try
        {
            Throwable t = new IllegalStateException("kaboom-marker");
            System.getLogger("net.cumba.corej.core.TraceTest").log(System.Logger.Level.ERROR,
                    "rule failed", t);
        }
        finally
        {
            capture.end();
        }
        // The message line is present and the rendered trace yields the exception class + an
        // 'at ' frame.
        assertThat(lines).anyMatch(l -> l.contains("rule failed"));
        assertThat(lines)
                .anyMatch(l -> l.contains("IllegalStateException") && l.contains("kaboom-marker"));
        assertThat(lines).anyMatch(l -> l.contains("\tat ") || l.contains(" at "));
    }


    @Test
    void capturesWarnAndInfoExactlyOnce()
    {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        capture.begin(lines::add);
        try
        {
            System.Logger log = System.getLogger("net.cumba.corej.core.NoDup");
            log.log(System.Logger.Level.WARNING, "engine warn line");
            log.log(System.Logger.Level.INFO, "engine info line");
        }
        finally
        {
            capture.end();
        }
        // WARN/INFO message lines are now captured by the appender (the only channel) — and each
        // event produces exactly one message line, not a duplicate.
        assertThat(lines).filteredOn(l -> l.contains("engine warn line")).singleElement()
                .satisfies(l -> assertThat(l).startsWith("WARN"));
        assertThat(lines).filteredOn(l -> l.contains("engine info line")).singleElement()
                .satisfies(l -> assertThat(l).startsWith("INFO"));
    }


    /**
     * Two simulated runs share a single reused thread pool. With
     * {@link RunLogDebugCapture#contextPropagator()} re-binding each submitting run's sink onto the
     * worker for the task's duration, each run's sink must see only its own line — no cross-run
     * leakage even though the same pooled threads serve both runs.
     */
    @Test
    void contextPropagatorIsolatesConcurrentRunsOnAReusedPool() throws Exception
    {
        List<String> linesA = Collections.synchronizedList(new ArrayList<>());
        List<String> linesB = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(2);
        var propagator = capture.contextPropagator();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try
        {
            // Run A binds sink A on this thread, wraps + submits its task, then unbinds.
            capture.begin(linesA::add);
            Runnable taskA = propagator.apply(() -> emitOn("RunA", "line A", ready, go));
            capture.end();
            // Run B binds sink B, wraps + submits, unbinds.
            capture.begin(linesB::add);
            Runnable taskB = propagator.apply(() -> emitOn("RunB", "line B", ready, go));
            capture.end();

            var fa = pool.submit(taskA);
            var fb = pool.submit(taskB);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown(); // release both at once so they genuinely interleave on the pool
            fa.get(5, TimeUnit.SECONDS);
            fb.get(5, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdownNow();
        }
        assertThat(linesA).anyMatch(l -> l.contains("line A"));
        assertThat(linesA).noneMatch(l -> l.contains("line B"));
        assertThat(linesB).anyMatch(l -> l.contains("line B"));
        assertThat(linesB).noneMatch(l -> l.contains("line A"));
    }


    private static void emitOn(String loggerLeaf, String message,
            java.util.concurrent.CountDownLatch ready, java.util.concurrent.CountDownLatch go)
    {
        ready.countDown();
        try
        {
            if (!go.await(5, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("not released in time");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        System.getLogger("net.cumba.corej.core." + loggerLeaf).log(System.Logger.Level.INFO,
                message);
    }
}
