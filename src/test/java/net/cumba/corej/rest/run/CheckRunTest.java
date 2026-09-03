package net.cumba.corej.rest.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link CheckRun} state transitions and progress counters. */
class CheckRunTest
{

    private static CheckRun newRun()
    {
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null, null,
                null);
        return new CheckRun("run-1", "sess-1", req);
    }


    @Test
    void startsPendingWithUnknownProgress()
    {
        CheckRun run = newRun();
        assertThat(run.status()).isEqualTo(RunStatus.PENDING);
        assertThat(run.totalDatasets()).isEqualTo(-1);
        assertThat(run.processedDatasets()).isZero();
        assertThat(run.rulesExecuted()).isZero();
        assertThat(run.startedAt()).isNull();
        assertThat(run.finishedAt()).isNull();
        assertThat(run.createdAt()).isNotNull();
    }


    @Test
    void runningThenSucceededSetsTimestamps()
    {
        CheckRun run = newRun();
        run.markRunning();
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.startedAt()).isNotNull();

        run.markSucceeded(null);
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.finishedAt()).isNotNull();
    }


    @Test
    void markFailedRecordsMessage()
    {
        CheckRun run = newRun();
        run.markRunning();
        run.markFailed("boom");
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureMessage()).isEqualTo("boom");
    }


    @Test
    void markCancelledFromPending()
    {
        CheckRun run = newRun();
        run.markCancelled();
        assertThat(run.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.finishedAt()).isNotNull();
    }


    @Test
    void terminalStateIsSticky()
    {
        CheckRun run = newRun();
        run.markRunning();
        run.markSucceeded(null);

        run.markFailed("ignored");
        run.markCancelled();
        run.markRunning();

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.failureMessage()).isNull();
    }


    @Test
    void progressCountersUpdate()
    {
        CheckRun run = newRun();
        run.setTotalDatasets(3);
        run.setProcessedDatasets(2);
        run.incrementRulesExecuted();
        run.incrementRulesExecuted();
        assertThat(run.totalDatasets()).isEqualTo(3);
        assertThat(run.processedDatasets()).isEqualTo(2);
        assertThat(run.rulesExecuted()).isEqualTo(2);
    }


    @Test
    void cancellationFlagIsObservable()
    {
        CheckRun run = newRun();
        assertThat(run.isCancelRequested()).isFalse();
        run.requestCancel();
        assertThat(run.isCancelRequested()).isTrue();
    }
}
