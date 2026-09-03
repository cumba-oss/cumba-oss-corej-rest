package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import net.cumba.corej.rest.run.CheckRunRequest;
import org.jspecify.annotations.Nullable;

/**
 * Structured per-run execution log: the uploaded files (name + size + SHA-256), the configuration
 * the run was started with, which domains were tested against how many rules, the findings per
 * domain, and any errors with their location. Persisted on disk next to the validation report
 * ({@code log-<runId>.json}) and served by {@code GET /api/checks/{id}/log}. Available for
 * SUCCEEDED and FAILED runs, and for a run cancelled after it started; the {@code domains} block is
 * empty for runs that did not complete validation.
 */
@Schema(description = "A run's execution log")
public record RunLog(@Schema(description = "Run id") String runId,
        @Schema(description = "Owning session id") String sessionId,
        @Schema(description = "Terminal status (SUCCEEDED | FAILED | CANCELLED)") String status,
        @Schema(description = "Run creation timestamp (ISO-8601)") String createdAt,
        @Schema(description = "Run start timestamp (ISO-8601)") @Nullable String startedAt,
        @Schema(description = "Run finish timestamp (ISO-8601)") @Nullable String finishedAt,
        @Schema(description = "Total runtime in seconds (null if the run did not complete)") @Nullable Double totalRuntimeSeconds,
        @Schema(description = "The configuration the run was started with") CheckRunRequest configuration,
        @Schema(description = "Manifest of the session's uploaded files") List<FileManifestEntry> files,
        @Schema(description = "Per-domain execution summary") List<DomainLogEntry> domains,
        @Schema(description = "Total findings across all domains (null if the run did not complete)") @Nullable Integer totalFindings,
        @Schema(description = "Failure detail when the run failed") @Nullable String failureMessage,
        @Schema(description = "Run-relevant engine log lines (level-prefixed), in order") List<String> logLines)
{

    public RunLog
    {
        files = files == null ? List.of() : List.copyOf(files);
        domains = domains == null ? List.of() : List.copyOf(domains);
        logLines = logLines == null ? List.of() : List.copyOf(logLines);
    }

    /** One uploaded file's identity. */
    @Schema(description = "An uploaded file's name, size and hash")
    public record FileManifestEntry(@Schema(description = "File name") String filename,
            @Schema(description = "Size in bytes") long sizeBytes,
            @Schema(description = "SHA-256 hex digest") @Nullable String sha256)
    {
    }


    /** Per-domain execution stats. */
    @Schema(description = "Execution stats for one validated domain")
    public record DomainLogEntry(@Schema(description = "Domain / dataset name") String domain,
            @Schema(description = "Source file name") @Nullable String fileName,
            @Schema(description = "Rules executed for this domain (X)") int rulesExecuted,
            @Schema(description = "Total rules available for the run (Y)") int rulesTotal,
            @Schema(description = "Findings attributed to this domain") int findings,
            @Schema(description = "Dataset wall-clock validation time in ms (-1 = not measured)") long runtimeMillis,
            @Schema(description = "Errors encountered for this domain") List<RuleErrorEntry> errors,
            @Schema(description = "Per-rule outcome of every rule run against this domain") List<RuleExecutionEntry> ruleExecutions)
    {

        public DomainLogEntry
        {
            errors = errors == null ? List.of() : List.copyOf(errors);
            ruleExecutions = ruleExecutions == null ? List.of() : List.copyOf(ruleExecutions);
        }
    }


    /** One rule's outcome against a single dataset (for the "rules by dataset" view). */
    @Schema(description = "One rule's outcome against a dataset")
    public record RuleExecutionEntry(@Schema(
            description = "Rule CORE id (expanded id for generated rules)") @Nullable String coreId,
            @Schema(description = "Engine synthetic rule id") @Nullable String generatedId,
            @Schema(description = "EXECUTED | SKIPPED | ERROR") String status,
            @Schema(description = "Violations reported for this dataset") int violations,
            @Schema(description = "Execution runtime in ms for this rule on this dataset (-1 = not measured)") long runtimeMillis,
            @Schema(description = "Variable a generated rule was expanded for (null otherwise)") @Nullable String expandedFor,
            @Schema(description = "Why the rule was not executed (null when EXECUTED)") @Nullable String notExecutedReason,
            @Schema(description = "Rule description (null when the rule carries none)") @Nullable String description,
            @Schema(description = "Rule executability in title-case display form (null when none)") @Nullable String executability)
    {
    }


    /** One error with its originating rule. */
    @Schema(description = "An error and the rule that produced it")
    public record RuleErrorEntry(
            @Schema(description = "Rule id (or synthetic load-error id)") @Nullable String ruleId,
            @Schema(description = "Error detail") @Nullable String message)
    {
    }
}
