package net.cumba.corej.rest.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.corej.rest.config.CorejProperties;
import net.cumba.corej.rest.report.ReportStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link RunRegistry}: its {@code SessionRunGuard} role and on-disk persistence. */
class RunRegistryTest
{

    private static CheckRun run(String id, String sessionId)
    {
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null, null,
                null);
        return new CheckRun(id, sessionId, req);
    }


    /**
     * Build an initialised, temp-dir-backed ReportStore (its {@code init()} is package-private).
     */
    private static ReportStore store(Path dir)
    {
        CorejProperties props = new CorejProperties();
        props.getReports().setDir(dir.toString());
        ReportStore store = new ReportStore(props);
        try
        {
            var init = ReportStore.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(store);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
        return store;
    }


    private static RunRegistry registry(ReportStore store)
    {
        return new RunRegistry(store, new CorejProperties());
    }


    @Test
    void registerGetRemove(@TempDir Path dir)
    {
        RunRegistry registry = registry(store(dir));
        CheckRun r = run("r1", "s1");
        registry.register(r);
        assertThat(registry.get("r1")).isSameAs(r);
        assertThat(registry.all()).containsExactly(r);

        registry.remove("r1");
        assertThatThrownBy(() -> registry.get("r1")).isInstanceOf(RunNotFoundException.class);
    }


    @Test
    void getUnknownThrows(@TempDir Path dir)
    {
        assertThatThrownBy(() -> registry(store(dir)).get("nope"))
                .isInstanceOf(RunNotFoundException.class);
    }


    @Test
    void hasInFlightRunsTracksStatusAndSession(@TempDir Path dir)
    {
        RunRegistry registry = registry(store(dir));
        CheckRun r = run("r1", "s1");
        registry.register(r);

        assertThat(registry.hasInFlightRuns("s1")).isTrue();
        assertThat(registry.hasInFlightRuns("other")).isFalse();

        r.markRunning();
        assertThat(registry.hasInFlightRuns("s1")).isTrue();

        r.markSucceeded(null);
        assertThat(registry.hasInFlightRuns("s1")).isFalse();
    }


    @Test
    void registerWritesRecordRehydratableByFreshRegistry(@TempDir Path dir) throws IOException
    {
        ReportStore store = store(dir);
        RunRegistry first = registry(store);
        CheckRun r = run("r1", "s1");
        first.register(r);
        r.markRunning();
        first.persist(r);
        r.markFailed("boom");
        first.persist(r);

        // A fresh registry over the same store rebuilds the run from its record.
        RunRegistry second = registry(store);
        second.reload();
        CheckRun restored = second.get("r1");
        assertThat(restored.status()).isEqualTo(RunStatus.FAILED);
        assertThat(restored.sessionId()).isEqualTo("s1");
        assertThat(restored.failureMessage()).isEqualTo("boom");
    }


    @Test
    void reloadMarksInterruptedInFlightRunAsFailed(@TempDir Path dir)
    {
        ReportStore store = store(dir);
        RunRegistry first = registry(store);
        CheckRun r = run("r1", "s1");
        first.register(r);
        r.markRunning();
        first.persist(r); // persisted while RUNNING — simulates a crash mid-run

        RunRegistry second = registry(store);
        second.reload();
        CheckRun restored = second.get("r1");
        assertThat(restored.status()).isEqualTo(RunStatus.FAILED);
        assertThat(restored.failureMessage()).contains("interrupted");
    }


    @Test
    void reloadDropsSucceededRunWithMissingReport(@TempDir Path dir)
    {
        ReportStore store = store(dir);
        RunRegistry first = registry(store);
        CheckRun r = run("r1", "s1");
        first.register(r);
        r.markSucceeded(null); // SUCCEEDED but no report file was ever persisted
        first.persist(r);

        RunRegistry second = registry(store);
        second.reload();
        assertThatThrownBy(() -> second.get("r1")).isInstanceOf(RunNotFoundException.class);
    }


    @Test
    void reloadSkippedWhenRehydrateDisabled(@TempDir Path dir)
    {
        ReportStore store = store(dir);
        registry(store).register(run("r1", "s1"));

        CorejProperties props = new CorejProperties();
        props.getPersistence().setRehydrateOnStartup(false);
        RunRegistry second = new RunRegistry(store, props);
        second.reload();
        assertThatThrownBy(() -> second.get("r1")).isInstanceOf(RunNotFoundException.class);
    }


    @Test
    void corruptRunRecordIsSkippedOnReload(@TempDir Path dir) throws IOException
    {
        ReportStore store = store(dir);
        registry(store).register(run("good", "s1"));
        Files.writeString(dir.resolve("run-bad.json"), "{ not valid json", StandardCharsets.UTF_8);

        RunRegistry second = registry(store);
        second.reload();
        assertThat(second.get("good")).isNotNull();
        assertThatThrownBy(() -> second.get("bad")).isInstanceOf(RunNotFoundException.class);
    }
}
