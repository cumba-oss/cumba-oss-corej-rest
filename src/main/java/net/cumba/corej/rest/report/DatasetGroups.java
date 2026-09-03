package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The two-level grouped results for a run: files as main groups, each containing its domains.
 * Findings are embedded per domain up to a global cap; {@code findingsTotal} and
 * {@code findingsTruncated} report whether the full set was returned.
 */
@Schema(description = "Two-level (file then domain) grouped results for a run")
public record DatasetGroups(@Schema(description = "Files (main groups)") List<FileGroup> files,
        @Schema(description = "Total finding rows for the run") int findingsTotal,
        @Schema(description = "Whether the embedded findings were capped") boolean findingsTruncated)
{

    public DatasetGroups
    {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
