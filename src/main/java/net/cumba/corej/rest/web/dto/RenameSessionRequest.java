package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Body of the session-rename endpoint: the new display name (blank/null clears it). */
@Schema(description = "New session name (blank/null clears it)")
public record RenameSessionRequest(@Schema(
        description = "Display name; blank/null leaves the session unnamed") @Nullable String name)
{
}
