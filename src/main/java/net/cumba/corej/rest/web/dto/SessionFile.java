package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import net.cumba.corej.rest.session.Session;
import org.jspecify.annotations.Nullable;

/** One file staged into a session. */
@Schema(description = "A file staged into a session")
public record SessionFile(@Schema(description = "Stored (bare) file name") String filename,
        @Schema(description = "Stored size in bytes") long sizeBytes,
        @Schema(description = "Upload time (ISO-8601, UTC)") @Nullable String uploadedAt)
{

    public static SessionFile from(Session.FileEntry entry)
    {
        return new SessionFile(entry.filename(), entry.size(),
                entry.uploadedAt() == null ? null : entry.uploadedAt().toString());
    }
}
