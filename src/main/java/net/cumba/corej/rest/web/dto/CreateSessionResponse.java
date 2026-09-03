package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Response to session creation. */
@Schema(description = "Identifier of a newly created upload session")
public record CreateSessionResponse(@Schema(
        description = "Opaque session id used in subsequent file/check calls") String sessionId,
        @Schema(description = "The session's display name, or null when unnamed") @Nullable String name)
{
}
