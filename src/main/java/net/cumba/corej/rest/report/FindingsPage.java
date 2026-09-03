package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A page of finding rows. {@code total} is the full row count regardless of the page window. */
@Schema(description = "A page of finding rows")
public record FindingsPage(@Schema(description = "Total finding rows for the run") int total,
        @Schema(description = "Index of the first returned row") int firstIndex,
        @Schema(description = "Number of rows in this page") int count,
        @Schema(description = "The finding rows in this page") List<FindingRow> items)
{

    public FindingsPage
    {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
