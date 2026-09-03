package net.cumba.corej.rest.run;

import java.io.IOException;
import net.cumba.corej.core.run.StudyValidationResult;

/**
 * Seam that performs the actual validation work for a {@link CheckRun}. The production
 * implementation bridges to the engine's {@code StudyValidationService}; tests substitute a
 * controllable fake so the queue/cancellation mechanics can be exercised without real study data.
 */
@FunctionalInterface
public interface CheckRunner
{

    /**
     * Run the validation for {@code run}, honouring its cancellation flag and feeding its progress
     * counters.
     *
     * @throws IOException
     *             on unreadable data inputs
     * @throws net.cumba.corej.core.run.CancelledException
     *             if the run's cancellation flag is observed
     */
    StudyValidationResult run(CheckRun run) throws IOException;
}
