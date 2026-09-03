package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Incremental snapshot of a run's live execution-log lines. Lines are append-only and 0-indexed;
 * pass the returned {@code nextFrom} back as the next request's {@code from} to fetch only
 * newly-appended lines. {@code terminal} is true once the run has finished, after which the richer
 * {@code GET /api/checks/{id}/log} is also available.
 */
@Schema(description = "A page of a run's live log lines")
public record LiveLogResponse(
        @Schema(description = "Log lines from the requested offset, in order") List<String> lines,
        @Schema(description = "Cursor to pass as 'from' on the next poll") int nextFrom,
        @Schema(description = "Whether the run has reached a terminal state") boolean terminal)
{

    public LiveLogResponse
    {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
