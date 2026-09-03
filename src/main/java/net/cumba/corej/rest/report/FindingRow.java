package net.cumba.corej.rest.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One row of a finding: the rule + dataset it belongs to and the affected record's key fields and
 * per-variable values. Projected from the run's v2 combined-finding report through
 * {@link V2Findings}.
 *
 * <p>
 * The EC-40 record key ({@code keyVariables} / {@code keys} / {@code keySource}) is <b>null, not
 * empty</b>, when no key resolved, and is omitted from the JSON entirely — so a run executed under
 * the default {@code corej.findingKeys=off} produces a payload byte-identical to a pre-EC-40 build,
 * and "no key" stays distinguishable from "empty key".
 * </p>
 */
@Schema(description = "A single finding row")
public record FindingRow(@Schema(description = "CORE rule id") @Nullable String coreId,
        @Schema(description = "Dataset / domain label") @Nullable String dataset,
        @Schema(description = "USUBJID of the affected record, if any") @Nullable String usubjid,
        @Schema(description = "1-based row number in the dataset, or null when not row-scoped") @Nullable Integer row,
        @Schema(description = "Sequence number (e.g. AESEQ), if any") @Nullable String seq,
        @Schema(description = "Rule executability status") @Nullable String executability,
        @Schema(description = "Finding message") @Nullable String message,
        @Schema(description = "Names of the variables reported for this row") List<String> variables,
        @Schema(description = "Values of those variables for this row (positional)") List<String> values,
        @Schema(description = "Domain / dataset name (disambiguates findings sharing a file)") @Nullable String domain,
        @Schema(description = "Ordered record-key column names identifying this row, when resolved") @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> keyVariables,
        @Schema(description = "Record-key values for this row, keyed by column name") @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Map<String, @Nullable String> keys,
        @Schema(description = "Which tier produced the record key: DEFINE_KEY, STRUCTURAL, NATURAL or SPONSOR_ID") @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String keySource)
{

    public FindingRow
    {
        variables = variables == null ? List.of() : List.copyOf(variables);
        values = values == null ? List.of() : List.copyOf(values);
        keyVariables = keyVariables == null ? null : List.copyOf(keyVariables);
        // Order-preserving and null-tolerant: a key column the row does not carry is a null value,
        // which Map.copyOf rejects.
        if (keys != null)
        {
            Map<String, @Nullable String> copy = new LinkedHashMap<>(keys);
            keys = Collections.<String, @Nullable String> unmodifiableMap(copy);
        }
    }
}
