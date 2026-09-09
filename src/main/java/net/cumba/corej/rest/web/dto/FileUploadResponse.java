package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import net.cumba.corej.rest.session.UrlFileFetcher;

/** Response confirming a file was staged into a session. */
@Schema(description = "Metadata of a file staged into a session")
public record FileUploadResponse(@Schema(description = "Owning session id") String sessionId,
        @Schema(description = "Stored (bare) file name") String filename,
        @Schema(description = "Stored size in bytes") long size,
        @Schema(description = "Bare names of the Define-XML-referenced datasets that were staged "
                + "alongside this file. Empty unless the staged file was a Define-XML.") List<String> stagedReferences,
        @Schema(description = "Define-XML-referenced datasets that could NOT be staged, with the "
                + "reason. A non-empty list means the session holds an INCOMPLETE dataset set, so "
                + "a check run over it would validate a partial study.") List<UrlFileFetcher.SkippedReference> skippedReferences)
{

    public FileUploadResponse
    {
        stagedReferences = List.copyOf(stagedReferences);
        skippedReferences = List.copyOf(skippedReferences);
    }


    /** A plain upload: no Define-XML reference expansion was attempted. */
    public static FileUploadResponse plain(String sessionId, String filename, long size)
    {
        return new FileUploadResponse(sessionId, filename, size, List.of(), List.of());
    }
}
