package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** One rule's outcome (a {@code Rules_Report} entry) for a run. */
@Schema(description = "A rule's outcome in the run")
public record RuleReportRow(@Schema(description = "CORE rule id") @Nullable String coreId,
        @Schema(description = "Rule report version") @Nullable String version,
        @Schema(description = "CDISC rule id(s)") @Nullable String cdiscRuleId,
        @Schema(description = "FDA rule id(s)") @Nullable String fdaRuleId,
        @Schema(description = "Rule message") @Nullable String message,
        @Schema(description = "Outcome status (e.g. SUCCESS, SOME_ISSUES, ERROR)") @Nullable String status)
{
}
