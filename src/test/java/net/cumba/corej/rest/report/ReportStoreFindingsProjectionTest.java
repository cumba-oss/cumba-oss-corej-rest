package net.cumba.corej.rest.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.report.ReportAssembler;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.ServiceReportManager;
import net.cumba.corej.rest.config.CorejProperties;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ReportStore#findingsPage} is projected from the run's <b>v2</b> combined-finding document
 * (plan {@code PLAN-finding-record-keys-ui.md}, decisions D7 / D35), because v1's
 * {@code Issue_Details} is the frozen Python-compatible surface and EC-40 D6 forbids the record key
 * from reaching it.
 *
 * <p>
 * Both documents are rendered here from <em>one</em> {@link ValidationReport} by the engine's own
 * the registered JSON writer, so the comparisons below are against the real writer rather than a
 * hand-authored fixture.
 * </p>
 *
 * <p>
 * <b>What the row-set / order test is, and is not.</b> D7 waived API backward compatibility, so
 * matching v1 is <em>not</em> an obligation here — it is a <b>correctness</b> assertion that the
 * re-source did what it intended. v2 keeps dataset-scoped zero-row findings (engine / dataset-load
 * errors) as <em>virtual findings</em> with an empty {@code rows[]}; D34 decided they stay out of a
 * row-shaped API, since a finding with no rows cannot honestly produce a row. Reproducing v1's row
 * set exactly is how that decision is checked. <b>Do not delete this test as obsolete under D7.</b>
 * </p>
 */
class ReportStoreFindingsProjectionTest
{

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final List<String> KEY_NAMES = List.of("RDOMAIN", "IDVAR", "IDVARVAL", "QNAM");

    private static ReportStore newStore(Path aDir) throws IOException
    {
        CorejProperties props = new CorejProperties();
        props.getReports().setDir(aDir.toString());
        props.getReports().getCache().setMaxEntries(64);
        props.getReports().getCache().setTtl(Duration.ofMinutes(30));
        ReportStore store = new ReportStore(props);
        store.init();
        return store;
    }


    /**
     * Persists both renderings of {@link #mixedReport()} for {@code run-1} and returns the store.
     */
    private static ReportStore seeded(Path aDir) throws IOException
    {
        ReportStore store = newStore(aDir);
        // Rendered through the report SPI, exactly as RunExecutor does in production: this also
        // proves corej-cdisc-report-json is genuinely on the REST classpath (Fix #224).
        ServiceReportManager manager = ServiceReportManager.getInstance();
        ReportSections sections = new ReportAssembler().report(mixedReport()).sections();
        ByteArrayOutputStream v1 = new ByteArrayOutputStream();
        manager.writeReport(sections, v1,
                java.util.Objects.requireNonNull(manager.findReportFormat("json")));
        ByteArrayOutputStream v2 = new ByteArrayOutputStream();
        manager.writeReport(sections, v2,
                java.util.Objects.requireNonNull(manager.findReportFormat("json-2")));
        store.persist("run-1", v1.toString(StandardCharsets.UTF_8));
        store.persistV2("run-1", v2.toString(StandardCharsets.UTF_8));
        return store;
    }


    /** The v1 {@code Issue_Details} rows of the persisted report, in document order. */
    private static List<JsonNode> issueDetails(ReportStore aStore)
    {
        List<JsonNode> out = new ArrayList<>();
        MAPPER.readTree(aStore.rawReport("run-1")).path("Issue_Details").forEach(out::add);
        return out;
    }


    @Test
    void theV2SourcedRowSetAndOrderReproduceV1IssueDetailsExactly(@TempDir Path dir)
        throws IOException
    {
        ReportStore store = seeded(dir);
        List<JsonNode> expected = issueDetails(store);
        // The fixture really does contain a virtual finding, or this test proves nothing: v2 has
        // one more finding than v1 has distinct (core_id, dataset) row blocks.
        assertThat(expected).hasSize(5);
        assertThat(store.rawReportV2("run-1")).contains("\"CORE-0100\"");

        FindingsPage page = store.findingsPage("run-1", 0, 100);
        assertThat(page.total()).isEqualTo(expected.size());
        assertThat(page.items()).hasSize(expected.size());

        for (int i = 0; i < expected.size(); i++)
        {
            JsonNode v1 = expected.get(i);
            FindingRow row = page.items().get(i);
            assertThat(row.coreId()).as("coreId at %s", i).isEqualTo(v1.get("core_id").asString());
            assertThat(row.dataset()).as("dataset at %s", i)
                    .isEqualTo(v1.get("dataset").asString());
            assertThat(row.domain()).as("domain at %s", i).isEqualTo(v1.get("domain").asString());
            assertThat(row.usubjid()).as("usubjid at %s", i)
                    .isEqualTo(v1.get("USUBJID").asString());
            assertThat(row.seq()).as("seq at %s", i).isEqualTo(v1.get("SEQ").asString());
            assertThat(row.message()).as("message at %s", i)
                    .isEqualTo(v1.get("message").asString());
            assertThat(row.executability()).as("executability at %s", i)
                    .isEqualTo(v1.get("executability").asString());
            assertThat(row.row()).as("row at %s", i)
                    .isEqualTo(v1.get("row").isNumber() ? v1.get("row").intValue() : null);
            assertThat(row.variables()).as("variables at %s", i)
                    .containsExactlyElementsOf(strings(v1.get("variables")));
            assertThat(row.values()).as("values at %s", i)
                    .containsExactlyElementsOf(strings(v1.get("values")));
        }
    }


    @Test
    void theRecordKeyRidesOnEveryRowOfAKeyedFinding(@TempDir Path dir) throws IOException
    {
        ReportStore store = seeded(dir);

        List<FindingRow> keyed = store.findingsPage("run-1", null, null, "CORE-0100", 0, 100)
                .items();
        assertThat(keyed).hasSize(2);
        assertThat(keyed).allSatisfy(r ->
        {
            assertThat(r.keyVariables()).containsExactlyElementsOf(KEY_NAMES);
            assertThat(r.keySource()).isEqualTo("STRUCTURAL");
        });
        assertThat(keyed.get(0).keys()).containsEntry("IDVARVAL", "3").containsEntry("QNAM",
                "AESOSP");
        assertThat(keyed.get(1).keys()).containsEntry("IDVARVAL", "7");

        // An unkeyed finding carries null, not an empty list/map — "no key" stays distinguishable.
        FindingRow unkeyed = store.findingsPage("run-1", null, null, "CORE-0200", 0, 100).items()
                .get(0);
        assertThat(unkeyed.keyVariables()).isNull();
        assertThat(unkeyed.keys()).isNull();
        assertThat(unkeyed.keySource()).isNull();
    }


    @Test
    void theFileDomainAndRuleFiltersStillScopeTheRowSet(@TempDir Path dir) throws IOException
    {
        ReportStore store = seeded(dir);

        assertThat(store.findingsPage("run-1", "dm.xpt", null, null, 0, 100).total()).isEqualTo(3);
        assertThat(store.findingsPage("run-1", null, "SUPPAE", null, 0, 100).total()).isEqualTo(2);
        assertThat(store.findingsPage("run-1", "suppae.xpt", "SUPPAE", "CORE-0100", 0, 100).total())
                .isEqualTo(2);
        // A filter that matches nothing, and one that matches the virtual finding's rule in the
        // dataset where it has no rows.
        assertThat(store.findingsPage("run-1", "dm.xpt", "SUPPAE", null, 0, 100).total()).isZero();
        assertThat(store.findingsPage("run-1", "dm.xpt", "DM", "CORE-0100", 0, 100).total())
                .isZero();
        // A blank filter matches everything, exactly as an absent one does.
        assertThat(store.findingsPage("run-1", "", "", "", 0, 100).total()).isEqualTo(5);
    }


    @Test
    void aPageWindowMayStraddleTwoFindings(@TempDir Path dir) throws IOException
    {
        ReportStore store = seeded(dir);
        List<FindingRow> all = store.findingsPage("run-1", 0, 100).items();

        // Rows 1..3 span the end of CORE-0100 (2 rows), all of CORE-0200 (1 row) and the start of
        // CORE-0300 — the flattening boundary the paging arithmetic has to get right.
        FindingsPage window = store.findingsPage("run-1", 1, 3);
        assertThat(window.total()).isEqualTo(5);
        assertThat(window.firstIndex()).isEqualTo(1);
        assertThat(window.count()).isEqualTo(3);
        assertThat(window.items()).containsExactlyElementsOf(all.subList(1, 4));

        // A window past the end clamps to the total and returns nothing.
        FindingsPage pastEnd = store.findingsPage("run-1", 99, 3);
        assertThat(pastEnd.firstIndex()).isEqualTo(5);
        assertThat(pastEnd.items()).isEmpty();
        // count <= 0 is an empty window at the requested index, not "everything".
        assertThat(store.findingsPage("run-1", 2, 0).items()).isEmpty();
    }


    private static List<String> strings(JsonNode aArray)
    {
        List<String> out = new ArrayList<>();
        aArray.forEach(n -> out.add(n.asString()));
        return out;
    }


    /**
     * A report mixing the three shapes the projection has to get right:
     * <ul>
     * <li>{@code CORE-0100} on SUPPAE — two rows carrying a STRUCTURAL record key;</li>
     * <li>{@code CORE-0100} on DM — a <b>virtual</b> finding: an engine error with no rows, which
     * v2 keeps and v1 drops;</li>
     * <li>{@code CORE-0200} (one row) and {@code CORE-0300} (two rows) on DM — plain findings, in
     * an encounter order the {@code (core_id, dataset)} sort has to reverse.</li>
     * </ul>
     */
    private static ValidationReport mixedReport()
    {
        List<String> names = List.of("USUBJID", "SEQ", "QVAL");
        ValidationFinding keyed = ValidationFinding.builder().source("cumba.core")
                .ruleId("CORE-0100").severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION)
                .executability("fully executable").message("QVAL must be populated")
                .variableNames(names)
                .location(ValidationFindingLocation.builder().dataset("SUPPAE")
                        .variableNames(List.of("QVAL")).keyVariableNames(KEY_NAMES)
                        .keySource("STRUCTURAL").build())
                .rows(RowFindingSlab.builder(names.size()).addRow(6, new String[]
                {
                        "SUBJ-001", "", "Y"
                }).addRow(9, new String[]
                {
                        "SUBJ-002", "", "N"
                }).build()).keyRows(RowFindingSlab.builder(KEY_NAMES.size()).addRow(6, new String[]
                {
                        "AE", "AESEQ", "3", "AESOSP"
                }).addRow(9, new String[]
                {
                        "AE", "AESEQ", "7", "AESOSP"
                }).build()).build();

        ValidationFinding virtual = ValidationFinding.builder().source("cumba.core")
                .ruleId("CORE-0100").severity(Severity.ERROR).kind(FindingKind.ENGINE_ERROR)
                .executability("not executable").variableNames(List.of())
                .location(ValidationFindingLocation.builder().dataset("DM").variableNames(List.of())
                        .build())
                .rows(RowFindingSlab.EMPTY).build();

        return ValidationReport.builder()
                .members(List.of(
                        ValidationReportMember.builder().domain("DM").fileName("dm.xpt")
                                .findings(List.of(plain("CORE-0300", 2), virtual,
                                        plain("CORE-0200", 1)))
                                .build(),
                        ValidationReportMember.builder().domain("SUPPAE").fileName("suppae.xpt")
                                .findings(List.of(keyed)).build()))
                .build();
    }


    /** A plain DM finding on {@code AGE} with {@code aRows} flagged rows. */
    private static ValidationFinding plain(String aCoreId, int aRows)
    {
        List<String> names = List.of("USUBJID", "SEQ", "AGE");
        RowFindingSlab.Builder slab = RowFindingSlab.builder(names.size());
        for (int i = 0; i < aRows; i++)
        {
            slab.addRow(i, new String[]
            {
                    "SUBJ-10" + i, String.valueOf(i + 1), String.valueOf(20 + i)
            });
        }
        return ValidationFinding.builder().source("cumba.core").ruleId(aCoreId)
                .severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION)
                .executability("fully executable").message(aCoreId + " failed").variableNames(names)
                .location(ValidationFindingLocation.builder().dataset("DM")
                        .variableNames(List.of("AGE")).build())
                .rows(slab.build()).build();
    }
}
