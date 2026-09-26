package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The run's conformance metadata (the report's {@code Conformance_Details} block), projected to
 * camelCase. Values mirror the report (e.g. {@code version} is the display form like {@code V3.4},
 * {@code totalRuntime} like {@code "12.34 seconds"}); fields absent from the report are null.
 *
 * <p>
 * ⭐ PLAN-report-conformance-fields (T1-2): the nine components after {@code dictionaryBasis} — the
 * CT declaration mismatch, the Define-XML metadata basis and the seven dictionary versions — were
 * JSON-only until 2026-09-26. They are appended, additive, and projected from the stored v1 report
 * by key like every other field, so a report persisted before they existed simply projects
 * {@code null} for them. No {@code API_VERSION} bump (precedent: the two basis fields).
 * </p>
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
        @Schema(description = "The findings cap the run applied per rule and dataset (the engine "
                + "materialises at most this many findings per rule per dataset), or \"None\" "
                + "when the run was unlimited. Precedence, first set wins: the request's "
                + "maxErrorsPerRule, the deployment's corej.engine.max-errors-per-rule, the JVM "
                + "property corej.maxErrorsPerRule, the MAX_ERRORS_PER_RULE environment variable, "
                + "then 1000. Reports produced by an engine without this field's fix (a "
                + "coreEngineVersion predating it) always say \"None\" here, whatever cap their "
                + "run applied") @Nullable String issueLimitPerRule,
        @Schema(description = "\"True\" when a finite per-rule cap was applied (the engine's cap is "
                + "always per rule and dataset), else \"None\"") @Nullable String issueLimitPerDataset,
        @Schema(description = "Report generation timestamp (UTC, ISO-8601)") @Nullable String reportGeneration,
        @Schema(description = "Library degradation note — present only when the CDISC Library "
                + "could not be consulted for the run") @Nullable String libraryMetadataBasis,
        @Schema(description = "External-dictionary degradation note — present only when some "
                + "dictionary rule in the run could not be answered; names what loaded (with "
                + "versions), what did not and why, and the answerable count") @Nullable String dictionaryBasis,
        @Schema(description = "CT declaration mismatch note — present only when the Define-XML "
                + "declares a controlled-terminology package set that differs from the one the "
                + "run used (\"define declares X; run used Y\")") @Nullable String ctDeclarationMismatch,
        @Schema(description = "Define-XML metadata degradation note — present only when the "
                + "Define-XML could not be parsed directly and names what the define metadata "
                + "degraded to") @Nullable String defineMetadataBasis,
        @Schema(description = "UNII dictionary version the run used, if any") @Nullable String uniiVersion,
        @Schema(description = "Med-RT dictionary version the run used, if any") @Nullable String medRtVersion,
        @Schema(description = "MedDRA dictionary version the run used, if any") @Nullable String meddraVersion,
        @Schema(description = "WHODrug dictionary version the run used, if any") @Nullable String whodrugVersion,
        @Schema(description = "SNOMED dictionary version the run used, if any") @Nullable String snomedVersion,
        @Schema(description = "LOINC dictionary version the run used, if any") @Nullable String loincVersion,
        @Schema(description = "Neoplasm dictionary version the run used, if any") @Nullable String neoplasmVersion)
{
}
