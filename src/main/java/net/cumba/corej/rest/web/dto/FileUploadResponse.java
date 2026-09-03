package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response confirming a file was staged into a session. */
@Schema(description = "Metadata of a file staged into a session")
public record FileUploadResponse(@Schema(description = "Owning session id") String sessionId,
        @Schema(description = "Stored (bare) file name") String filename,
        @Schema(description = "Stored size in bytes") long size)
{
}
