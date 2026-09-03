package net.cumba.corej.rest.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response to starting a check run. */
@Schema(description = "Identifier of a newly started check run")
public record StartCheckResponse(@Schema(
        description = "Opaque check-run id used to poll status / findings") String checkRunId)
{
}
