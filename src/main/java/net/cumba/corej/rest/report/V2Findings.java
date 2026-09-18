package net.cumba.corej.rest.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A narrow, <b>strict</b> typed façade over the v2 combined-finding report's {@code Findings} array
 * — decision D35 of {@code plans/done/PLAN-finding-record-keys-ui.md}.
 *
 * <p>
 * It covers <em>only</em> the fields {@link ReportStore#findingsPage} projects into a
 * {@link FindingRow}. It is deliberately <b>not</b> a general reader for
 * {@code StudyValidationResult}: growing it into one is a separate decision whose real deliverable
 * is a {@code model → v2 → model} round-trip test that does not exist. Anything the projection does
 * not read stays out.
 * </p>
 *
 * <p>
 * <b>Why strict.</b> A lenient parser turns a silently-absent field into a silently-empty column,
 * and nothing goes red — exactly the failure mode the typed façade exists to remove. So every field
 * the registered {@code json-2} report writer is contractually required to write must be
 * <em>present</em> (its value may still be JSON {@code null} where the writer can emit one), every
 * value must have the declared JSON type, and an unrecognised field fails the parse. All failures
 * raise {@link V2ReportFormatException} naming the exact path.
 * </p>
 */
public final class V2Findings
{

    /** Every field a v2 finding object carries; all are written unconditionally. */
    private static final Set<String> FINDING_FIELDS = Set.of("core_id", "message", "executability",
            "dataset", "domain", "location", "variables", "rows");

    /** Required location fields; {@code keyVariables}/{@code keySource} are the optional pair. */
    private static final Set<String> LOCATION_REQUIRED = Set.of("dataset", "variables");

    private static final Set<String> LOCATION_FIELDS = Set.of("dataset", "variables",
            "keyVariables", "keySource");

    /** Required row fields; {@code keys} is present exactly when the location has key variables. */
    private static final Set<String> ROW_REQUIRED = Set.of("row", "USUBJID", "SEQ", "values");

    private static final Set<String> ROW_FIELDS = Set.of("row", "USUBJID", "SEQ", "keys", "values");

    private V2Findings()
    {
    }


    /**
     * Parses the {@code Findings} array of a v2 report document.
     *
     * @param aRoot
     *            the parsed v2 report document
     * @return the findings, in document order (the writer sorts by {@code (core_id, dataset)})
     * @throws V2ReportFormatException
     *             when the document is not a recognised v2 report
     */
    public static List<Finding> parse(JsonNode aRoot)
    {
        if (aRoot == null || !aRoot.isObject())
        {
            throw new V2ReportFormatException("v2 report: root is not a JSON object");
        }
        JsonNode findings = aRoot.get("Findings");
        if (findings == null)
        {
            throw new V2ReportFormatException("v2 report: missing required field \"Findings\"");
        }
        if (!findings.isArray())
        {
            throw new V2ReportFormatException("v2 report: \"Findings\" is not an array");
        }
        List<Finding> out = new ArrayList<>(findings.size());
        for (int i = 0; i < findings.size(); i++)
        {
            out.add(finding(findings.get(i), "Findings[" + i + "]"));
        }
        return List.copyOf(out);
    }


    private static Finding finding(JsonNode aNode, String aPath)
    {
        requireObject(aNode, aPath);
        requireFields(aNode, FINDING_FIELDS, aPath);
        rejectUnknown(aNode, FINDING_FIELDS, aPath);

        Location location = location(aNode.get("location"), aPath + ".location");
        JsonNode rowsNode = aNode.get("rows");
        if (!rowsNode.isArray())
        {
            throw new V2ReportFormatException("v2 report: " + aPath + ".rows is not an array");
        }
        boolean keyed = location.keyVariables() != null;
        List<Row> rows = new ArrayList<>(rowsNode.size());
        for (int i = 0; i < rowsNode.size(); i++)
        {
            rows.add(row(rowsNode.get(i), aPath + ".rows[" + i + "]", keyed));
        }
        return new Finding(text(aNode, "core_id", aPath), text(aNode, "message", aPath),
                text(aNode, "executability", aPath), text(aNode, "dataset", aPath),
                text(aNode, "domain", aPath), location,
                strings(aNode.get("variables"), aPath + ".variables"), rows);
    }


    private static Location location(JsonNode aNode, String aPath)
    {
        requireObject(aNode, aPath);
        requireFields(aNode, LOCATION_REQUIRED, aPath);
        rejectUnknown(aNode, LOCATION_FIELDS, aPath);

        boolean hasNames = aNode.has("keyVariables");
        boolean hasSource = aNode.has("keySource");
        if (hasNames != hasSource)
        {
            throw new V2ReportFormatException("v2 report: " + aPath
                    + " carries only one of \"keyVariables\"/\"keySource\"; the writer emits both"
                    + " or neither");
        }
        List<String> keyVariables = hasNames
                ? strings(aNode.get("keyVariables"), aPath + ".keyVariables")
                : null;
        return new Location(text(aNode, "dataset", aPath),
                strings(aNode.get("variables"), aPath + ".variables"), keyVariables,
                hasSource ? text(aNode, "keySource", aPath) : null);
    }


    private static Row row(JsonNode aNode, String aPath, boolean aKeyed)
    {
        requireObject(aNode, aPath);
        requireFields(aNode, ROW_REQUIRED, aPath);
        rejectUnknown(aNode, ROW_FIELDS, aPath);

        if (aKeyed != aNode.has("keys"))
        {
            throw new V2ReportFormatException("v2 report: " + aPath
                    + (aKeyed ? " is missing \"keys\" although its location declares key variables"
                            : " carries \"keys\" although its location declares no key variables"));
        }
        return new Row(rowNumber(aNode.get("row"), aPath), text(aNode, "USUBJID", aPath),
                text(aNode, "SEQ", aPath), aKeyed ? keys(aNode.get("keys"), aPath + ".keys") : null,
                strings(aNode.get("values"), aPath + ".values"));
    }


    /**
     * The writer emits a positive 1-based row number, or {@code ""} when the finding is not
     * row-scoped. Anything else is an unrecognised shape.
     */
    private static @Nullable Integer rowNumber(JsonNode aNode, String aPath)
    {
        if (aNode.isNumber())
        {
            return aNode.intValue();
        }
        if (aNode.isString() && aNode.stringValue().isEmpty())
        {
            return null;
        }
        throw new V2ReportFormatException(
                "v2 report: " + aPath + ".row is neither a number nor the empty string");
    }


    private static List<String> strings(JsonNode aNode, String aPath)
    {
        if (!aNode.isArray())
        {
            throw new V2ReportFormatException("v2 report: " + aPath + " is not an array");
        }
        List<String> out = new ArrayList<>(aNode.size());
        for (int i = 0; i < aNode.size(); i++)
        {
            JsonNode e = aNode.get(i);
            if (!e.isString())
            {
                throw new V2ReportFormatException(
                        "v2 report: " + aPath + "[" + i + "] is not a string");
            }
            out.add(e.stringValue());
        }
        return List.copyOf(out);
    }


    /**
     * The row's record key. Values may be JSON {@code null} — the engine leaves a key column empty
     * when the row does not carry it — so the map is order-preserving and null-tolerant rather than
     * a {@code Map.copyOf}.
     */
    private static Map<String, @Nullable String> keys(JsonNode aNode, String aPath)
    {
        if (!aNode.isObject())
        {
            throw new V2ReportFormatException("v2 report: " + aPath + " is not an object");
        }
        Map<String, @Nullable String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : aNode.properties())
        {
            JsonNode v = e.getValue();
            if (!v.isString() && !v.isNull())
            {
                throw new V2ReportFormatException(
                        "v2 report: " + aPath + "." + e.getKey() + " is not a string");
            }
            out.put(e.getKey(), v.isNull() ? null : v.stringValue());
        }
        return Collections.unmodifiableMap(out);
    }


    /** A textual field whose value the writer may legitimately leave as JSON {@code null}. */
    private static @Nullable String text(JsonNode aParent, String aField, String aPath)
    {
        JsonNode f = aParent.get(aField);
        if (f.isNull())
        {
            return null;
        }
        if (!f.isString())
        {
            throw new V2ReportFormatException(
                    "v2 report: " + aPath + "." + aField + " is not a string");
        }
        return f.stringValue();
    }


    private static void requireObject(@Nullable JsonNode aNode, String aPath)
    {
        if (aNode == null || !aNode.isObject())
        {
            throw new V2ReportFormatException("v2 report: " + aPath + " is not a JSON object");
        }
    }


    private static void requireFields(JsonNode aNode, Set<String> aRequired, String aPath)
    {
        for (String field : aRequired)
        {
            if (!aNode.has(field))
            {
                throw new V2ReportFormatException(
                        "v2 report: " + aPath + " is missing required field \"" + field + "\"");
            }
        }
    }


    private static void rejectUnknown(JsonNode aNode, Set<String> aKnown, String aPath)
    {
        for (String field : aNode.propertyNames())
        {
            if (!aKnown.contains(field))
            {
                throw new V2ReportFormatException(
                        "v2 report: " + aPath + " carries unrecognised field \"" + field + "\"");
            }
        }
    }

    /**
     * One v2 finding: the rule + dataset it belongs to, its location (which carries the EC-40
     * record-key schema) and its rows.
     */
    public record Finding(@Nullable String coreId, @Nullable String message,
            @Nullable String executability, @Nullable String dataset, @Nullable String domain,
            Location location, List<String> variables, List<Row> rows)
    {

        public Finding
        {
            variables = List.copyOf(variables);
            rows = List.copyOf(rows);
        }
    }


    /**
     * A finding's location. {@code keyVariables} / {@code keySource} are {@code null} together when
     * no record key resolved (always the case under the default {@code corej.findingKeys=off}).
     */
    public record Location(@Nullable String dataset, List<String> variables,
            @Nullable List<String> keyVariables, @Nullable String keySource)
    {

        public Location
        {
            variables = List.copyOf(variables);
            keyVariables = keyVariables == null ? null : List.copyOf(keyVariables);
        }
    }


    /**
     * One flagged row of a finding. {@code row} is {@code null} when the finding is not row-scoped;
     * {@code keys} is {@code null} exactly when the location declares no key variables.
     */
    public record Row(@Nullable Integer row, @Nullable String usubjid, @Nullable String seq,
            @Nullable Map<String, @Nullable String> keys, List<String> values)
    {

        public Row
        {
            values = List.copyOf(values);
            if (keys != null)
            {
                Map<String, @Nullable String> copy = new LinkedHashMap<>(keys);
                keys = Collections.<String, @Nullable String> unmodifiableMap(copy);
            }
        }
    }
}
