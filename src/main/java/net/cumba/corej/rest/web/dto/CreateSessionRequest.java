package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Optional parameters when creating an upload session. */
@Schema(description = "Optional parameters when creating a session")
public record CreateSessionRequest(@Schema(
        description = "Optional human-readable session name (blank/null leaves it unnamed)") @Nullable String name)
{
}
