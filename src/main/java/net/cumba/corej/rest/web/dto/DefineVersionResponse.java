package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Detected Define-XML version of a staged file. Both fields are {@code null} when the file is not a
 * recognisable Define-XML document.
 */
@Schema(description = "Detected Define-XML version of a staged file")
public record DefineVersionResponse(
        @Schema(description = "Short version label, e.g. 2.1; null if undeterminable",
                example = "2.1") @Nullable String version,
        @Schema(description = "def:DefineVersion value, e.g. 2.1.0; matches the run form's "
                + "define-version options; null if undeterminable",
                example = "2.1.0") @Nullable String defineVersion)
{
}
