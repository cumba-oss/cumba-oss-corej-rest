package net.cumba.corej.rest.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
        // ⭐ Phase 7 of PLAN-typed-expression-engine: the deserializer no longer lowers
        // {"expression": …} into the legacy leaf tree, so the assembled document displays the
        // check as it was AUTHORED rather than as the round-trip through that tree re-rendered it.
        // This expectation used to be `ds_not_exists("ADSL")` — the lowering silently normalised a
        // bare reference into a quoted name literal on the way through the leaf's single
        // name/value pair.
        //
        // ⚠ Measured before it was changed, because this assembler is what the REST rule-definition
        // document shows a user: every rule of all 58 shipped packages was rendered under the old
        // engine and the new one and diffed. 397 of 14 937 rules render differently, across 90 rule
        // ids, and all 397 are one shape — the authored date()/time() type tag surviving where the
        // one-name/one-value leaf could not hold it (D102 proved that tag verdict-neutral). NOT ONE
        // shipped rule changes bare-vs-quoted spelling, which is the shape this test keys on: the
        // corpus writes a plain name quoted (1 732 sites) and only a dotted reference bare (16).
        Rule r = loadRule("""
                {"Core":{"Id":"CG0002","Status":"Draft","Version":"1"},
                 "Check":{"expression":"ds_not_exists(ADSL)"}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(r), Map.of()));
        JsonNode check = MAPPER.readTree(json).get("CG0002").get("source").get("Check");

        assertThat(check.has("all")).isFalse();
        assertThat(check.get("expression").asString()).isEqualTo("ds_not_exists(ADSL)");
    }


    /**
     * ⭐ <b>Phase 7 of {@code PLAN-typed-expression-engine} turned this test into its own opposite,
     * and the new assertion is the more useful one at a REST boundary.</b> It used to load a legacy
     * operator-leaf Check and assert the document displayed it as an expression — the display half
     * of a conversion the engine performed on the way in. The owner retired the leaf model whole
     * (D121): <i>"incorrect rules should fail loud on load / parse / compile and not need any
     * fallback"</i>. So there is nothing left to convert, and what a caller who POSTs an old-style
     * rule needs is a message that says so.
     */
    @Test
    void legacyNotationCheckIsRejectedWithAMessageThatNamesTheReplacement() throws Exception
    {
        String legacy = """
                {"Core":{"Id":"CG0003","Status":"Draft","Version":"1"},
                 "Check":{"all":[
                   {"name":"DTHFL","operator":"equal_to","value":"Y","value_is_literal":true}
                 ]}}""";

        Throwable thrown = catchThrowable(() -> loadRule(legacy));

        assertThat(thrown).isNotNull();
        // Loud, and it tells the author what to write instead — not merely "could not deserialise".
        assertThat(thrown).hasMessageContaining("retired").hasMessageContaining("expression:")
                .hasMessageContaining("equal_to");
    }


    @Test
    void expandedRuleCheckIsDisplayedAsExpression() throws Exception
    {
        // ⚠ Phase 7: the fixture is written as an expression because the operator-leaf form no
        // longer loads (D121). The SUBJECT of this test is unchanged and was never the leaf — it
        // is that an EXPANDED (generated) rule's Check reaches the document in expression form,
        // under the "expanded" key rather than "source".
        Rule gen = loadRule("""
                {"Core":{"Id":"CG0004-AGE","Status":"Draft","Version":"1"},
                 "Check":{"expression":"AGE > 18"}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(), Map.of("CG0004-AGE", gen)));
        JsonNode check = MAPPER.readTree(json).get("CG0004-AGE").get("expanded").get("Check");

        assertThat(check.get("expression").asString()).isEqualTo("AGE > 18");
    }


    @Test
    void operationsAreDisplayedAsExpression() throws Exception
    {
        // Phase 7b: authored as a `Bindings:` entry (the only surface); the loader lowers it to
        // the internal field form, and the assembled document renders it back under `Bindings`.
        Rule r = loadRule("""
                {"Core":{"Id":"CG0005","Status":"Draft","Version":"1"},
                 "Bindings":[{"name":"$VAR","expression":"variable_count(--LNKGRP)"}],
                 "Check":{"expression":"ds_not_exists(ADSL)"}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(r), Map.of()));
        JsonNode op = MAPPER.readTree(json).get("CG0005").get("source").get("Bindings").get(0);

        assertThat(op.has("operator")).isFalse();
        assertThat(op.get("name").asString()).isEqualTo("$VAR");
        assertThat(op.get("expression").asString()).isEqualTo("variable_count(--LNKGRP)");
    }


    @Test
    void expandedRuleOperationsAreDisplayedAsExpression() throws Exception
    {
        Rule gen = loadRule("""
                {"Core":{"Id":"CG0006-AGE","Status":"Draft","Version":"1"},
                 "Bindings":[{"name":"$VAR","expression":"variable_count(--LNKGRP)"}],
                 "Check":{"expression":"ds_not_exists(ADSL)"}}""");
        String json = RuleDefsAssembler.assemble(result(List.of(), Map.of("CG0006-AGE", gen)));
        JsonNode op = MAPPER.readTree(json).get("CG0006-AGE").get("expanded").get("Bindings")
                .get(0);

        assertThat(op.get("expression").asString()).isEqualTo("variable_count(--LNKGRP)");
    }
}
