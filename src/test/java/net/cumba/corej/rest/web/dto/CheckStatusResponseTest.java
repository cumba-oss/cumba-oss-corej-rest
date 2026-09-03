package net.cumba.corej.rest.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import net.cumba.corej.rest.run.CheckRun;
import net.cumba.corej.rest.run.CheckRunRequest;
import net.cumba.corej.rest.run.RunRecord;
import net.cumba.corej.rest.run.RunStatus;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CheckStatusResponse}'s finding-count projection across run states. */
class CheckStatusResponseTest
{

    private static CheckRunRequest req()
    {
        return new CheckRunRequest(null, null, null, null, null, null, null, null, null);
    }


    @Test
    void pendingRunReportsNoFindingCount()
    {
        CheckRun run = new CheckRun("r1", "s1", req());
        assertThat(CheckStatusResponse.from(run).findingCount()).isNull();
        assertThat(CheckStatusResponse.from(run).status()).isEqualTo(RunStatus.PENDING);
    }


    @Test
    void runningRunReportsTheRunningTally()
    {
        CheckRun run = new CheckRun("r1", "s1", req());
        run.markRunning();
        run.addDatasetFindings(3);
        run.addDatasetFindings(4);
        CheckStatusResponse response = CheckStatusResponse.from(run);
        assertThat(response.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(response.findingCount()).isEqualTo(7);
    }


    @Test
    void succeededRestoredRunReportsAuthoritativeCount()
    {
        // A run rebuilt from disk has no in-memory result, but findingCount survives.
        RunRecord record = new RunRecord("r1", "s1", req(), "SUCCEEDED", Instant.now().toString(),
                Instant.now().toString(), Instant.now().toString(), 5, 5, 120, 42, null);
        CheckRun run = CheckRun.restore(record);
        CheckStatusResponse response = CheckStatusResponse.from(run);
        assertThat(response.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(response.findingCount()).isEqualTo(42);
    }


    @Test
    void failedRunReportsNoFindingCount()
    {
        CheckRun run = new CheckRun("r1", "s1", req());
        run.markFailed("boom");
        assertThat(CheckStatusResponse.from(run).findingCount()).isNull();
    }


    @Test
    void sessionNameDefaultsToNullAndIsCarriedWhenSupplied()
    {
        CheckRun run = new CheckRun("r1", "s1", req());
        assertThat(CheckStatusResponse.from(run).sessionName()).isNull();
        assertThat(CheckStatusResponse.from(run, "My study").sessionName()).isEqualTo("My study");
        // The session id is always preserved regardless of the name.
        assertThat(CheckStatusResponse.from(run, "My study").sessionId()).isEqualTo("s1");
    }
}
