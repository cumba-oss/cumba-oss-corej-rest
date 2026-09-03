package net.cumba.corej.rest.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.report.ReportAssembler;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.datatable.report.ValidationReport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link RuleDefsAssembler}: the run-scoped {@code rules-<runId>.json} document
 * keyed by CORE id, each carrying {@code source} (the effective rule) and {@code expanded} (the
 * generated instance, when present).
 */
class RuleDefsAssemblerTest
{

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Rule rule(String coreId, String description)
    {
        Rule r = new Rule();
        r.setId("uuid-" + coreId);
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        r.setDescription(description);
        return r;
    }


    private static StudyValidationResult result(List<Rule> rules, Map<String, Rule> generated)
    {
        ValidationReport report = new net.cumba.corej.core.report.ValidationReportBuilder()
                .libraryUri("file:///study").build();
        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard("custom").version("1-0").totalRuntimeSeconds(0.0)
                .coreEngineVersion("0.0.0-test").build();
        return new StudyValidationResult(report, conformance, List.of(), rules, 0, 0.0, List.of(),
                generated);
    }


    @Test
    void sourceRuleSerializedWithTitleCaseKeysAndNullExpanded()
    {
        String json = RuleDefsAssembler
                .assemble(result(List.of(rule("CG0001", "base rule")), Map.of()));
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.has("CG0001")).isTrue();
        JsonNode entry = root.get("CG0001");
        // source carries the title-case @JsonProperty keys; expanded is JSON null.
        assertThat(entry.get("source").get("Core").get("Id").asString()).isEqualTo("CG0001");
        assertThat(entry.get("source").get("Description").asString()).isEqualTo("base rule");
        assertThat(entry.get("expanded").isNull()).isTrue();
    }


    @Test
    void expandedGeneratedRuleKeyedByItsExpandedId()
    {
        Rule gen = rule("CG0001-AGE", "expanded for AGE");
        String json = RuleDefsAssembler
                .assemble(result(List.of(rule("CG0001", "base rule")), Map.of("CG0001-AGE", gen)));
        JsonNode root = MAPPER.readTree(json);

        // The base rule keeps source; the expanded instance lands under its own expanded id.
        assertThat(root.get("CG0001").get("source").get("Description").asString())
                .isEqualTo("base rule");
        JsonNode expandedEntry = root.get("CG0001-AGE");
        assertThat(expandedEntry.get("expanded").get("Description").asString())
                .isEqualTo("expanded for AGE");
        // The expanded entry inherits its base template (CG0001) as its source, so a click on the
        // expanded row shows the originating template rather than a null source.
        assertThat(expandedEntry.get("source").isNull()).isFalse();
        assertThat(expandedEntry.get("source").get("Core").get("Id").asString())
                .isEqualTo("CG0001");
        assertThat(expandedEntry.get("source").get("Description").asString())
                .isEqualTo("base rule");
    }


    @Test
    void expandedRuleWithNoBaseTemplateKeepsNullSource()
    {
        // A generated rule whose base template is absent from the source rules leaves source null.
        Rule gen = rule("ORPHAN-XX", "orphan expanded rule");
        String json = RuleDefsAssembler.assemble(result(List.of(), Map.of("ORPHAN-XX", gen)));
        JsonNode root = MAPPER.readTree(json);

        JsonNode entry = root.get("ORPHAN-XX");
        assertThat(entry.get("source").isNull()).isTrue();
        assertThat(entry.get("expanded").get("Core").get("Id").asString()).isEqualTo("ORPHAN-XX");
    }


    @Test
    void nullValuedRuleFieldsAreStripped()
    {
        // The sparse test rule sets only Core/Description; the serialized Rule model would
        // otherwise
        // carry many "Field": null entries (Outcome, Precondition, …). Those must be removed.
        String json = RuleDefsAssembler
                .assemble(result(List.of(rule("CG0001", "base rule")), Map.of()));
        JsonNode source = MAPPER.readTree(json).get("CG0001").get("source");

        // Populated fields survive; unset ones are absent (not present as null).
        assertThat(source.get("Description").asString()).isEqualTo("base rule");
        for (Map.Entry<String, JsonNode> e : source.properties())
        {
            assertThat(e.getValue().isNull()).as("field %s should not be a null value", e.getKey())
                    .isFalse();
        }
        assertThat(source.has("Outcome")).isFalse();
        assertThat(source.has("Precondition")).isFalse();
    }


    @Test
    void emptyResultProducesEmptyObject()
    {
        String json = RuleDefsAssembler.assemble(result(List.of(), Map.of()));
        assertThat(MAPPER.readTree(json).isObject()).isTrue();
        assertThat(MAPPER.readTree(json).size()).isZero();
    }


    @Test
    void ruleWithoutCoreIdFallsBackToEngineId()
    {
        Rule r = new Rule();
        r.setId("synthetic-1");
        r.setDescription("no core");
        String json = RuleDefsAssembler.assemble(result(List.of(r), Map.of()));
        JsonNode root = MAPPER.readTree(json);
        assertThat(root.has("synthetic-1")).isTrue();
        assertThat(root.get("synthetic-1").get("source").get("id").asString())
                .isEqualTo("synthetic-1");
    }


    /** Loads a one-rule package through the engine loader (the run pipeline's path). */
    private static Rule loadRule(String ruleBody) throws Exception
    {
        return net.cumba.corej.core.RulePackageLoader
                .loadFromString("{\"rules\":{\"x\":" + ruleBody + "}}").getRules().values()
                .iterator().next();
    }


    @Test
    void expressionNotationCheckSurvivesTheModelRoundTrip() throws Exception
    {
        // The deserializer lowers {"expression": …} to the legacy tree in the Rule model; the
        // assembled document must still display the check in expression notation.
        Rule r = loadRule("""
                {"Core":{"Id":"CG0002","Status":"Draft","Version":"1"},
                 "Check":{"expression":"ds_not_exists(ADSL)"}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(r), Map.of()));
        JsonNode check = MAPPER.readTree(json).get("CG0002").get("source").get("Check");

        assertThat(check.has("all")).isFalse();
        assertThat(check.get("expression").asString()).isEqualTo("ds_not_exists(\"ADSL\")");
    }


    @Test
    void legacyNotationCheckIsDisplayedAsExpression() throws Exception
    {
        Rule r = loadRule("""
                {"Core":{"Id":"CG0003","Status":"Draft","Version":"1"},
                 "Check":{"all":[
                   {"name":"DTHFL","operator":"equal_to","value":"Y","value_is_literal":true}
                 ]}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(r), Map.of()));
        JsonNode check = MAPPER.readTree(json).get("CG0003").get("source").get("Check");

        assertThat(check.has("all")).isFalse();
        assertThat(check.get("expression").asString()).isEqualTo("DTHFL == \"Y\"");
    }


    @Test
    void expandedRuleCheckIsDisplayedAsExpression() throws Exception
    {
        Rule gen = loadRule("""
                {"Core":{"Id":"CG0004-AGE","Status":"Draft","Version":"1"},
                 "Check":{"name":"AGE","operator":"greater_than","value":18,
                          "value_is_literal":true}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(), Map.of("CG0004-AGE", gen)));
        JsonNode check = MAPPER.readTree(json).get("CG0004-AGE").get("expanded").get("Check");

        assertThat(check.get("expression").asString()).isEqualTo("AGE > 18");
    }


    @Test
    void operationsAreDisplayedAsExpression() throws Exception
    {
        // The loader lowers operations to field form in the model; the assembled document must
        // still display them in function-call (expression) form.
        Rule r = loadRule("""
                {"Core":{"Id":"CG0005","Status":"Draft","Version":"1"},
                 "Operations":[{"id":"$VAR","operator":"variable_count","name":"--LNKGRP"}],
                 "Check":{"expression":"ds_not_exists(ADSL)"}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(r), Map.of()));
        JsonNode op = MAPPER.readTree(json).get("CG0005").get("source").get("Operations").get(0);

        assertThat(op.has("operator")).isFalse();
        assertThat(op.get("expression").asString()).isEqualTo("variable_count(--LNKGRP)");
    }


    @Test
    void expandedRuleOperationsAreDisplayedAsExpression() throws Exception
    {
        Rule gen = loadRule("""
                {"Core":{"Id":"CG0006-AGE","Status":"Draft","Version":"1"},
                 "Operations":[{"id":"$VAR","operator":"variable_count","name":"--LNKGRP"}],
                 "Check":{"expression":"ds_not_exists(ADSL)"}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(), Map.of("CG0006-AGE", gen)));
        JsonNode op = MAPPER.readTree(json).get("CG0006-AGE").get("expanded").get("Operations")
                .get(0);

        assertThat(op.get("expression").asString()).isEqualTo("variable_count(--LNKGRP)");
    }
}
