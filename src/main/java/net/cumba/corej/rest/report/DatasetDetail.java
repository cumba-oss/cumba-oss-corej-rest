package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Per-dataset metadata (a {@code Dataset_Details} entry) for a run. */
@Schema(description = "Per-dataset metadata")
public record DatasetDetail(@Schema(description = "Dataset file name") @Nullable String filename,
        @Schema(description = "Dataset label") @Nullable String label,
        @Schema(description = "Parent path of the dataset file") @Nullable String path,
        @Schema(description = "Last-modification timestamp") @Nullable String modificationDate,
        @Schema(description = "File size in KB") @Nullable Double sizeKb,
        @Schema(description = "Row count") @Nullable Long length,
        @Schema(description = "Domain / dataset name (distinguishes datasets sharing a file)") @Nullable String domain,
        @Schema(description = "Column (variable) count") @Nullable Integer columns)
{
}
