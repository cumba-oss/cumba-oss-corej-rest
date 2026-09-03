package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One uploaded file as a main group: file-level metadata (size, modification date, hash) and the
 * domains/datasets it contains as sub-groups. A file that produced no validated datasets (e.g. a
 * load error, or a non-dataset file) still appears with an empty {@code domains} list.
 */
@Schema(description = "One file and the domains it contains")
public record FileGroup(@Schema(description = "Stored file name") String fileName,
        @Schema(description = "File size in bytes") @Nullable Long sizeBytes,
        @Schema(description = "SHA-256 hex digest") @Nullable String sha256,
        @Schema(description = "Last-modification timestamp") @Nullable String modificationDate,
        @Schema(description = "Domains (datasets) contained in this file") List<DomainGroup> domains)
{

    public FileGroup
    {
        domains = domains == null ? List.of() : List.copyOf(domains);
    }
}
