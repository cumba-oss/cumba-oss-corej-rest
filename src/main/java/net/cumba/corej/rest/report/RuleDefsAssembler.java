package net.cumba.corej.rest.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.expr.convert.RulePackageExpressionJson;
import net.cumba.corej.core.model.OutputVariableListing;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.run.StudyValidationResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the run-scoped rule-definitions document persisted as {@code rules-<runId>.json}: a JSON
 * object keyed by the rule's CORE id, each value {@code { "source": <rule JSON or null>,
 * "expanded": <generated rule JSON or null> }}.
 *
 * <p>
 * {@code source} is the run's effective source rule (post-filter), serialized from the engine's
 * {@link Rule} model. {@code expanded} is the synthetic generated rule that ran (only present for
 * expanded ids such as {@code CG0001-AGE}); {@code null} for plain rules. The per-rule JSON is
 * produced by {@link RulePackageExpressionJson#toExpressionJson(Rule)} so the title-case keys
 * round-trip faithfully and the {@code Check} / {@code Precondition} subtrees display in expression
 * notation (the engine model holds the lowered legacy tree; leaves with no expression surface stay
 * old-style).
 * </p>
 *
 * <p>
 * Each entry also carries {@code "outputVariables": { "effective", "authored", "excluded",
 * "derived" }} ({@code PLAN-rule-catalogue-effective-output-variables} P2; owner D1 (b) / D3,
 * 2026-10-05): the {@link OutputVariableListing} of the rule the overlay shows — the expanded rule
 * when the entry has one, else the source rule. {@code source} / {@code expanded} stay the raw
 * rules, so the overlay's JSON / YAML tabs keep showing the rule as written. A rule whose listing
 * cannot be computed loses only its own key (logged); the document — and every other entry — is
 * still written. A run persisted before this key existed has none, and the overlay shows no section
 * for it.
 * </p>
 */
public final class RuleDefsAssembler
{

    private static final Logger LOG = LoggerFactory.getLogger(RuleDefsAssembler.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private RuleDefsAssembler()
    {
    }


    /**
     * Serializes the run's rule definitions to a JSON string.
     *
     * @param result
     *            the engine result (its source {@code rules()} and {@code generatedRules()})
     * @return the {@code rules-<runId>.json} document as a string
     */
    public static String assemble(StudyValidationResult result)
    {
        // coreId -> { source, expanded, outputVariables }
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        // coreId -> the rule the overlay shows (expanded ?? source); the listing is read from it
        Map<String, Rule> shown = new LinkedHashMap<>();
        for (Rule r : result.rules())
        {
            // A loaded rule always carries a CORE id or a rule id (it is keyed by one); guard the
            // theoretical both-null case so the map key is non-null.
            String coreId = Objects.requireNonNull(coreIdOf(r),
                    "rule has neither a CORE id nor an id");
            ObjectNode entry = byId.computeIfAbsent(coreId, _ -> newEntry());
            entry.set("source", toNode(r));
            shown.put(coreId, r);
        }
        for (Map.Entry<String, Rule> e : result.generatedRules().entrySet())
        {
            String expandedId = e.getKey();
            ObjectNode entry = byId.computeIfAbsent(expandedId, _ -> newEntry());
            entry.set("expanded", toNode(e.getValue()));
            shown.put(expandedId, e.getValue());
            // The expanded row's Source tab shows its originating template. Generated rules are
            // keyed by the expanded id (e.g. CG0001-AGE) while their source template is keyed by
            // the base id (CG0001), so copy the base source onto the expanded entry — otherwise a
            // click on the expanded row would resolve to this entry directly and show a null
            // source.
            if (entry.get("source") == null || entry.get("source").isNull())
            {
                JsonNode baseSource = baseSourceFor(byId, expandedId);
                if (baseSource != null)
                {
                    entry.set("source", baseSource);
                }
            }
        }
        // After the expanded loop, so an expanded entry lists its own rule — a source copied from
        // the base template does not change which rule the overlay shows.
        for (Map.Entry<String, ObjectNode> e : byId.entrySet())
        {
            Rule rule = shown.get(e.getKey());
            if (rule != null)
            {
                putOutputVariables(e.getValue(), e.getKey(), rule);
            }
        }
        ObjectNode root = MAPPER.createObjectNode();
        for (Map.Entry<String, ObjectNode> e : byId.entrySet())
        {
            root.set(e.getKey(), e.getValue());
        }
        return root.toPrettyString();
    }


    /**
     * Sets {@code outputVariables} on {@code entry} from {@code rule}'s
     * {@link OutputVariableListing}. A listing that throws omits the key and is logged: one rule
     * must not cost the run its whole {@code rules-<runId>.json} ({@code RunExecutor} persists
     * nothing when the assembler throws).
     */
    private static void putOutputVariables(ObjectNode entry, String coreId, Rule rule)
    {
        OutputVariableListing listing;
        try
        {
            listing = OutputVariableListing.of(rule);
        }
        catch (RuntimeException e)
        {
            LOG.warn("Rule {}: no Output_Variables listing for its definition", coreId, e);
            return;
        }
        ObjectNode node = entry.putObject("outputVariables");
        putAll(node.putArray("effective"), listing.effective());
        putAll(node.putArray("authored"), listing.authored());
        putAll(node.putArray("excluded"), listing.excluded());
        node.put("derived", listing.derived());
    }


    private static void putAll(ArrayNode array, List<String> values)
    {
        for (String v : values)
        {
            array.add(v);
        }
    }


    /**
     * The {@code source} node of the base template for an expanded id (e.g. {@code CG0001} for
     * {@code CG0001-AGE}): strips the last {@code -segment} and looks the base entry up. Returns
     * {@code null} when there is no base entry or it carries no source.
     */
    private static @Nullable JsonNode baseSourceFor(Map<String, ObjectNode> byId, String expandedId)
    {
        int dash = expandedId.lastIndexOf('-');
        if (dash > 0)
        {
            ObjectNode base = byId.get(expandedId.substring(0, dash));
            if (base != null)
            {
                JsonNode src = base.get("source");
                if (src != null && !src.isNull())
                {
                    return src;
                }
            }
        }
        return null;
    }


    private static ObjectNode newEntry()
    {
        ObjectNode node = MAPPER.createObjectNode();
        node.putNull("source");
        node.putNull("expanded");
        return node;
    }


    private static JsonNode toNode(Rule rule)
    {
        JsonNode node = MAPPER.readTree(RulePackageExpressionJson.toExpressionJson(rule)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // The Rule model serializes every declared field, including the many a sparse authored rule
        // leaves unset (Precondition, Outcome, …). Strip null-valued properties recursively so the
        // overlay shows only fields the rule actually carries.
        stripNulls(node);
        return node;
    }


    /**
     * Recursively remove {@code null}-valued properties from objects (and objects inside arrays).
     */
    private static void stripNulls(JsonNode node)
    {
        if (node instanceof ObjectNode obj)
        {
            obj.removeIf(JsonNode::isNull);
            for (Map.Entry<String, JsonNode> e : obj.properties())
            {
                stripNulls(e.getValue());
            }
        }
        else if (node != null && node.isArray())
        {
            for (JsonNode child : node)
            {
                stripNulls(child);
            }
        }
    }


    private static @Nullable String coreIdOf(Rule rule)
    {
        return rule.effectiveId();
    }
}
