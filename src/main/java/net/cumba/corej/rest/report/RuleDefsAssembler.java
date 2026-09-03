package net.cumba.corej.rest.report;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.expr.convert.RulePackageExpressionJson;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.run.StudyValidationResult;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
 */
public final class RuleDefsAssembler
{

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
        // coreId -> { source, expanded }
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (Rule r : result.rules())
        {
            // A loaded rule always carries a CORE id or a rule id (it is keyed by one); guard the
            // theoretical both-null case so the map key is non-null.
            String coreId = Objects.requireNonNull(coreIdOf(r),
                    "rule has neither a CORE id nor an id");
            ObjectNode entry = byId.computeIfAbsent(coreId, _ -> newEntry());
            entry.set("source", toNode(r));
        }
        for (Map.Entry<String, Rule> e : result.generatedRules().entrySet())
        {
            String expandedId = e.getKey();
            ObjectNode entry = byId.computeIfAbsent(expandedId, _ -> newEntry());
            entry.set("expanded", toNode(e.getValue()));
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
        ObjectNode root = MAPPER.createObjectNode();
        for (Map.Entry<String, ObjectNode> e : byId.entrySet())
        {
            root.set(e.getKey(), e.getValue());
        }
        return root.toPrettyString();
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
