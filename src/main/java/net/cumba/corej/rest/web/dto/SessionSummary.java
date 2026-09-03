package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Comparator;
import java.util.List;
import net.cumba.corej.rest.session.Session;
import org.jspecify.annotations.Nullable;

/** Summary of an upload session for the session-list endpoint. */
@Schema(description = "Summary of an upload session")
public record SessionSummary(@Schema(description = "Session id") String sessionId,
        @Schema(description = "Display name, or null when unnamed") @Nullable String name,
        @Schema(description = "Creation time (ISO-8601)") String createdAt,
        @Schema(description = "Number of files staged in the session") int fileCount,
        @Schema(description = "The files staged in the session") List<SessionFile> files)
{

    public SessionSummary
    {
        files = files == null ? List.of() : List.copyOf(files);
    }


    public static SessionSummary from(Session session)
    {
        List<SessionFile> files = session.files().stream()
                .sorted(Comparator.comparing(Session.FileEntry::filename)).map(SessionFile::from)
                .toList();
        return new SessionSummary(session.id(), session.name(), session.createdAt().toString(),
                files.size(), files);
    }
}
