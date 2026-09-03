package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The run's conformance metadata (the report's {@code Conformance_Details} block), projected to
 * camelCase. Values mirror the report (e.g. {@code version} is the display form like {@code V3.4},
 * {@code totalRuntime} like {@code "12.34 seconds"}); fields absent from the report are null.
 *
 * <p>
 * ⚠ <b>{@code substandard} is DISPLAY-ONLY and DERIVED</b> (Plan 2 Phase 7, owner ruling
 * 2026-08-28). The server reads it from the report's {@code Conformance_Details}, where
 * {@code StudyValidationService} put the declared TIG leg; nothing branches on it, and it must
 * never reappear as an input on {@code CheckRunRequest} — under {@code -mp} the leg is just a
 * declared product ({@code tig/1-0/adam}), so the value stays derivable after the {@code -s} /
 * {@code -v} removal.
 * </p>
 */
@Schema(description = "Conformance metadata for a run")
public record ConformanceResponse(@Schema(
        description = "CDISC standard (upper-cased), e.g. SDTMIG") @Nullable String standard,
        @Schema(description = "Substandard, if any") @Nullable String substandard,
        @Schema(description = "Standard version display form, e.g. V3.4") @Nullable String version,
        @Schema(description = "TIG use case, if any") @Nullable String tigUseCase,
        @Schema(description = "Controlled-terminology version(s)") @Nullable String ctVersion,
        @Schema(description = "Define-XML version, if known") @Nullable String defineXmlVersion,
        @Schema(description = "CORE engine version, if stamped") @Nullable String coreEngineVersion,
        @Schema(description = "Total runtime, e.g. \"12.34 seconds\"") @Nullable String totalRuntime,
        @Schema(description = "Per-rule issue limit, or \"None\"") @Nullable String issueLimitPerRule,
        @Schema(description = "Per-dataset issue limit flag") @Nullable String issueLimitPerDataset,
        @Schema(description = "Report generation timestamp (UTC, ISO-8601)") @Nullable String reportGeneration,
        @Schema(description = "Library degradation note — present only when the CDISC Library "
                + "could not be consulted for the run") @Nullable String libraryMetadataBasis,
        @Schema(description = "External-dictionary degradation note — present only when some "
                + "dictionary rule in the run could not be answered; names what loaded (with "
                + "versions), what did not and why, and the answerable count") @Nullable String dictionaryBasis)
{
}
