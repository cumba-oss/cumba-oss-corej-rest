package net.cumba.corej.rest.run;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Parameters for starting a check run against a session's staged files. The data library is the
 * session itself (its uploaded files); file-valued fields name files already uploaded into the
 * session (bare names, no path).
 *
 * <p>
 * Per the service's design, CDISC-Library parameters (API key, cache dir, CT fetch) are backend
 * configuration (system properties / env), not request fields — so they are intentionally absent
 * here.
 * </p>
 */
@Schema(description = "Parameters for a check run over a session's staged files")
public record CheckRunRequest(@Schema(description = "TIG use case (optional)") String useCase,
        @Schema(description = "Name of an uploaded define.xml to use as metadata overlay (optional)") String defineXmlFilename,
        @Schema(deprecated = true,
                description = "DEPRECATED — use 'datasetFilter' instead. Names of uploaded files "
                        + "to load as reference libraries (optional). A dataset the filter does "
                        + "not match is still loaded as reference data — visible to rules, never "
                        + "validated — which is what this field was for. Only formats a library "
                        + "supplier can open are accepted; any other format is rejected with 400 "
                        + "naming the file, its format and the supported list.") List<String> referenceDataFilenames,
        @Schema(description = "Explicit Define-XML version; null reads it from the library") String defineVersion,
        @Schema(description = "Include filter of CORE rule ids; when non-empty, only these rules run") List<String> includeRules,
        @Schema(description = "Exclude filter of CORE rule ids; when non-empty, all but these rules run") List<String> excludeRules,
        @Schema(description = "Names of uploaded rule-package files to load in addition to bundled rules") List<String> rulesFilenames,
        @Schema(description = "Restrict validation to these dataset/domain names; empty validates all") List<String> datasetFilter,
        @Schema(description = "Rule worker threads per dataset (>= 1); default 1") Integer ruleThreads,
        @Schema(description = "Max findings to materialise per rule (per dataset); additional "
                + "violations are counted but not listed. Null (the default) uses the service "
                + "default (corej.engine.max-errors-per-rule / MAX_ERRORS_PER_RULE); <= 0 means "
                + "unlimited.") @Nullable Integer maxErrorsPerRule,
        @Schema(description = "Ordered CDISC Library products consulted for metadata, highest "
                + "precedence first (e.g. adam/adamig-1-3; a bare product id is accepted when "
                + "unambiguous against the server's metadata cache). Selects METADATA only - "
                + "rules are selected by 'rulesPackages'. Null or empty uses the standards the "
                + "selected rule packages declare — and is therefore REQUIRED (400) when rules "
                + "are selected only by 'rulesFilenames', since an uploaded rules file declares "
                + "no standard.") List<String> metadataProducts,
        @Schema(description = "Weakest check level to evaluate: Reject, Error, Warning or Info. "
                + "A rule's declared levels below it are not evaluated, and a rule with no level "
                + "at or above it is skipped with a stated reason rather than passing silently. "
                + "Null (the default) means Warning, so Reject+Error+Warning evaluate and Info "
                + "does not. This is a RUN option only — no rule package and no rule may carry "
                + "one. Notice is NOT a threshold — it is a report-only kind outside the "
                + "ladder, authored by no rule.", allowableValues =
        {
                "Reject", "Error", "Warning", "Info"
        }, example = "Warning") net.cumba.datatable.report.@Nullable Severity severityThreshold,
        @Schema(description = "Rule packages to run, by short name (e.g. cdisc-adamig-1-3 for "
                + "rules-cdisc-adamig-1-3.json). REQUIRED unless 'rulesFilenames' names uploaded "
                + "rule files instead; the two are unioned. The selected packages declare the "
                + "CDISC Library standards the run resolves metadata against, which is why this "
                + "replaced the removed 'standard' and 'version' fields.",
                requiredMode = Schema.RequiredMode.REQUIRED) List<String> rulesPackages){

    public CheckRunRequest
    {
        referenceDataFilenames = referenceDataFilenames == null ? List.of()
                : List.copyOf(referenceDataFilenames);
        includeRules = includeRules == null ? List.of() : List.copyOf(includeRules);
        excludeRules = excludeRules == null ? List.of() : List.copyOf(excludeRules);
        rulesFilenames = rulesFilenames == null ? List.of() : List.copyOf(rulesFilenames);
        datasetFilter = datasetFilter == null ? List.of() : List.copyOf(datasetFilter);
        metadataProducts = metadataProducts == null ? List.of() : List.copyOf(metadataProducts);
        rulesPackages = rulesPackages == null ? List.of() : List.copyOf(rulesPackages);
    }


    /**
     * Convenience constructor without the {@code maxErrorsPerRule} field (defaults it to
     * {@code null} = use the service default). Keeps existing positional callers working.
     *
     * @param useCase
     *            the TIG use case
     * @param defineXmlFilename
     *            an uploaded define.xml to use as the metadata overlay
     * @param referenceDataFilenames
     *            uploaded files to load as reference libraries
     * @param defineVersion
     *            explicit Define-XML version
     * @param includeRules
     *            rule-id include filter
     * @param excludeRules
     *            rule-id exclude filter
     * @param rulesFilenames
     *            uploaded rule-package files
     * @param datasetFilter
     *            dataset/domain restriction
     * @param ruleThreads
     *            rule worker threads per dataset
     */
    public CheckRunRequest(String useCase, String defineXmlFilename,
            List<String> referenceDataFilenames, String defineVersion, List<String> includeRules,
            List<String> excludeRules, List<String> rulesFilenames, List<String> datasetFilter,
            Integer ruleThreads)
    {
        this(useCase, defineXmlFilename, referenceDataFilenames, defineVersion, includeRules,
                excludeRules, rulesFilenames, datasetFilter, ruleThreads, null, List.of(), null,
                List.of());
    }
}
