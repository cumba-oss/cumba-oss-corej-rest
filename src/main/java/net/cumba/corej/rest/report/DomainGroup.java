package net.cumba.corej.rest.report;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One domain (dataset) contained in a file: its per-domain metadata plus the rule outcomes and
 * findings attributed to it. A file may contain one or more of these (e.g. one per Excel sheet or
 * XPT member).
 */
@Schema(description = "One domain (dataset) within a file")
public record DomainGroup(@Schema(description = "Domain / dataset name") String domain,
        @Schema(description = "Dataset label") @Nullable String label,
        @Schema(description = "Row count") @Nullable Long rows,
        @Schema(description = "Column (variable) count") @Nullable Integer columns,
        @Schema(description = "Dataset wall-clock validation time in ms (-1 = not measured)") long runtimeMillis,
        @Schema(description = "Per-rule outcomes against this domain") List<RunLog.RuleExecutionEntry> rules)
{

    public DomainGroup
    {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
