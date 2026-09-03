package net.cumba.corej.rest.run;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Captures <em>all</em> of the engine's log output (logger {@code net.cumba.corej.core}) into the
 * currently-running check run's log. The engine logs at many sites that call
 * {@code LOGGER.log(...)} directly; this appender is the single, complete capture channel, so every
 * {@code TRACE}/{@code DEBUG}/{@code INFO}/{@code WARNING}/ {@code ERROR} message plus any rendered
 * exception trace lands in the persisted execution log.
 *
 * <p>
 * A single Logback appender is attached to the engine logger (raised to {@code DEBUG} while a
 * capture is active); each event is routed to the sink bound on the thread that produced it via a
 * plain {@link ThreadLocal}. The engine fans work out across pooled / virtual worker threads, so
 * the binding is propagated explicitly from the submitting thread onto each worker by the
 * {@link #contextPropagator()} task decorator (handed to the engine's
 * {@code StudyValidationParams.taskDecorator}); a plain {@code ThreadLocal} (rather than an
 * inheritable one) avoids leaking the sink to unrelated threads that merely happened to be created
 * under a run.
 * </p>
 */
@Component
public class RunLogDebugCapture
{

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RunLogDebugCapture.class);

    private static final String ENGINE_LOGGER = "net.cumba.corej.core";

    /** Upper bound on captured lines per run, to bound the persisted log. */
    private static final int MAX_LINES = 50_000;

    private static final ThreadLocal<Consumer<String>> SINK = new ThreadLocal<>();

    private @Nullable Logger engineLogger;

    private @Nullable AppenderBase<ILoggingEvent> appender;

    /** The engine logger's configured level, restored when no run is capturing. */
    private @Nullable Level baseLevel;

    /** Active captures; the engine logger is only at DEBUG while > 0. */
    private int activeCaptures;

    @PostConstruct
    void attach()
    {
        if (!(LoggerFactory.getLogger(ENGINE_LOGGER) instanceof Logger logback))
        {
            // Not Logback (e.g. a different SLF4J binding). The appender is the only run-log
            // capture channel, so warn once at startup that live/persisted run-log capture is
            // unavailable rather than silently dropping every engine line.
            LOG.warn("SLF4J binding is not Logback; engine run-log capture is unavailable"
                    + " (logger {} cannot be tapped).", ENGINE_LOGGER);
            return;
        }
        engineLogger = logback;
        baseLevel = engineLogger.getLevel();
        // The engine logs via java.lang.System.Logger, which the JDK routes through JUL; Spring
        // Boot's jul-to-slf4j bridge forwards those records to Logback. Open the JUL level so its
        // FINE (= System.Logger DEBUG) records are not dropped before reaching the bridge — the
        // Logback level (raised only during a run, below) then gates whether they are emitted.
        java.util.logging.Logger.getLogger(ENGINE_LOGGER).setLevel(java.util.logging.Level.FINE);
        appender = new AppenderBase<>()
        {

            @Override
            protected void append(ILoggingEvent event)
            {
                Consumer<String> sink = SINK.get();
                if (sink == null)
                {
                    return;
                }
                // This appender is the single, complete capture channel, so every level's
                // message is emitted exactly once: TRACE/DEBUG/INFO/WARN/ERROR all yield one
                // "<level> <message>" line. (It is the only path lines reach the run log, so
                // there is no INFO/WARN duplication to avoid here.)
                sink.accept(event.getLevel() + " " + event.getFormattedMessage());
                // Any level may additionally carry a throwable; render its stack trace so
                // exceptions the engine logs (e.g. a per-rule failure that does not abort the
                // run) appear live with their trace, one frame per line.
                IThrowableProxy tp = event.getThrowableProxy();
                if (tp != null)
                {
                    ThrowableProxyUtil.asString(tp).lines()
                            .forEach(line -> sink.accept(event.getLevel() + " " + line));
                }
            }
        };
        appender.setContext(engineLogger.getLoggerContext());
        appender.start();
        engineLogger.addAppender(appender);
    }


    @PreDestroy
    void detach()
    {
        if (engineLogger != null && appender != null)
        {
            engineLogger.detachAppender(appender);
            appender.stop();
            engineLogger.setLevel(baseLevel);
        }
    }


    /**
     * Re-bind THIS (submitting) thread's capture sink onto the worker thread that actually runs the
     * task. Handed to the engine as its {@code StudyValidationParams.taskDecorator}, this is what
     * makes capture parallel-safe: {@link #contextPropagator()} reads the sink on the submitting
     * thread (the correct run, evaluated when the task is wrapped), then sets it on the worker for
     * the duration of {@code task.run()} and restores the worker's prior binding afterwards.
     * Because the sink is read dynamically inside the returned lambdas (never snapshotted at
     * construction), a single shared propagator instance serves every concurrent run.
     *
     * @return a task decorator binding the submitting thread's sink onto the executing worker
     */
    public UnaryOperator<Runnable> contextPropagator()
    {
        return task ->
        {
            Consumer<String> captured = SINK.get();
            return () ->
            {
                Consumer<String> prev = SINK.get();
                if (captured != null)
                {
                    SINK.set(captured);
                }
                else
                {
                    SINK.remove();
                }
                try
                {
                    task.run();
                }
                finally
                {
                    if (prev != null)
                    {
                        SINK.set(prev);
                    }
                    else
                    {
                        SINK.remove();
                    }
                }
            };
        };
    }


    /**
     * Route engine log output produced on this thread to {@code sink} until {@link #end()} is
     * called on the same thread; worker threads pick up the sink via {@link #contextPropagator()}.
     * While any capture is active the engine logger is at {@code DEBUG}; it is restored to its
     * configured level once the last capture ends (so an idle service is not left permanently
     * verbose).
     */
    public void begin(Consumer<String> sink)
    {
        SINK.set(new BoundedSink(sink));
        if (engineLogger != null)
        {
            synchronized (this)
            {
                if (activeCaptures++ == 0)
                {
                    engineLogger.setLevel(Level.DEBUG);
                }
            }
        }
    }


    /** Stop capturing on this thread. */
    public void end()
    {
        SINK.remove();
        if (engineLogger != null)
        {
            synchronized (this)
            {
                if (activeCaptures > 0 && --activeCaptures == 0)
                {
                    engineLogger.setLevel(baseLevel);
                }
            }
        }
    }

    /** Forwards up to {@link #MAX_LINES} lines for a run, then silently drops the rest. */
    private static final class BoundedSink implements Consumer<String>
    {

        private final Consumer<String> delegate;

        private int count;

        BoundedSink(Consumer<String> delegate)
        {
            this.delegate = delegate;
        }


        @Override
        public synchronized void accept(String line)
        {
            if (count < MAX_LINES)
            {
                count++;
                delegate.accept(line);
            }
        }
    }
}
