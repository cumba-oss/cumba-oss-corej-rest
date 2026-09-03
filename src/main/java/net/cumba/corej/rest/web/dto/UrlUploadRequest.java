package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request to stage a file into a session by downloading it from a URL (server-side fetch).
 *
 * @param url
 *            an {@code http} / {@code https} URL the server downloads
 * @param filename
 *            optional bare name to store the file under; when omitted the name is derived from the
 *            URL path
 */
@Schema(description = "Stage a file into a session by URL")
public record UrlUploadRequest(
        @Schema(description = "http/https URL to download",
                requiredMode = Schema.RequiredMode.REQUIRED) String url,
        @Schema(description = "Optional bare filename to store under") String filename)
{
}
