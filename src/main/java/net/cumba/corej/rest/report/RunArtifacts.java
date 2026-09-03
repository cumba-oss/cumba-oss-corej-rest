package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Sizes and generation time of a run's downloadable artifacts. The Excel report is pre-rendered to
 * disk at run completion (best-effort), so its size is known up front; {@code reportXlsxBytes} is
 * {@code null} only when that pre-render did not produce a file.
 */
@Schema(description = "Downloadable artifacts of a run")
public record RunArtifacts(@Schema(
        description = "JSON report size in bytes (null if absent)") @Nullable Long reportBytes,
        @Schema(description = "v2 combined-finding JSON report size in bytes (null if absent)") @Nullable Long reportV2Bytes,
        @Schema(description = "Pre-rendered XLSX report size in bytes (null if absent)") @Nullable Long reportXlsxBytes,
        @Schema(description = "Execution-log size in bytes (null if absent)") @Nullable Long logBytes,
        @Schema(description = "When the run finished / the artifacts were generated (ISO-8601)") @Nullable String generatedAt)
{
}
