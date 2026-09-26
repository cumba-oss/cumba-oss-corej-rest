package net.cumba.corej.rest.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.cumba.corej.rest.config.CorejProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ReportStore} persistence, section projection, paging, caching, deletion.
 */
class ReportStoreTest
{

    private static ReportStore newStore(Path dir, int maxEntries, Duration ttl) throws IOException
    {
        CorejProperties props = new CorejProperties();
        props.getReports().setDir(dir.toString());
        props.getReports().getCache().setMaxEntries(maxEntries);
        props.getReports().getCache().setTtl(ttl);
        ReportStore store = new ReportStore(props);
        store.init();
        return store;
    }


    private static String sampleReport(int rows)
    {
        StringBuilder details = new StringBuilder("[");
        for (int i = 0; i < rows; i++)
        {
            if (i > 0)
            {
                details.append(',');
            }
            details.append("{\"core_id\":\"CORE-r").append(i).append("\",\"message\":\"m").append(i)
                    .append("\",\"executability\":\"executable\",\"dataset\":\"DM\",")
                    .append("\"USUBJID\":\"S").append(i).append("\",\"row\":").append(i + 1)
                    .append(",\"SEQ\":\"").append(i).append("\",\"variables\":[\"AGE\"],")
                    .append("\"values\":[\"v").append(i).append("\"]}");
        }
        details.append(']');
        return "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\","
                + "\"Sub_Standard\":\"adam\",\"Version\":\"V3.4\","
                + "\"CT_Version\":\"sdtmct-2024\",\"Total_Runtime\":\"1.50 seconds\","
                + "\"Library_Metadata_Basis\":\"unavailable — the CDISC Library …\","
                + "\"Dictionary_Basis\":\"external dictionaries degraded: 0 of 98 …\"},"
                + "\"Dataset_Details\":[{\"filename\":\"dm.xpt\",\"label\":\"Demographics\","
                + "\"path\":\"/study\",\"modification_date\":\"2024-01-01\",\"size_kb\":1.5,"
                + "\"length\":10}],"
                + "\"Issue_Summary\":[{\"dataset\":\"DM\",\"core_id\":\"CORE-000001\","
                + "\"message\":\"bad\",\"issues\":" + rows + "}]," + "\"Issue_Details\":" + details
                + "," + "\"Rules_Report\":[{\"core_id\":\"CORE-000001\",\"version\":\"1\","
                + "\"cdisc_rule_id\":\"CD1\",\"fda_rule_id\":\"\",\"message\":\"bad\","
                + "\"status\":\"SOME_ISSUES\"}]}";
    }


    /**
     * The v2 combined-finding counterpart of {@link #sampleReport(int)}: one single-row finding per
     * {@code CORE-rN}, carrying the same dataset / USUBJID / row / SEQ / values. The paged findings
     * projection reads this document, not the v1 one.
     */
    private static String sampleReportV2(int rows)
    {
        StringBuilder findings = new StringBuilder("[");
        for (int i = 0; i < rows; i++)
        {
            if (i > 0)
            {
                findings.append(',');
            }
            findings.append("{\"core_id\":\"CORE-r").append(i).append("\",\"message\":\"m")
                    .append(i).append("\",\"executability\":\"executable\",\"dataset\":\"DM\",")
                    .append("\"domain\":\"DM\",\"location\":{\"dataset\":\"DM\",")
                    .append("\"variables\":[\"AGE\"]},\"variables\":[\"AGE\"],\"rows\":[{\"row\":")
                    .append(i + 1).append(",\"USUBJID\":\"S").append(i).append("\",\"SEQ\":\"")
                    .append(i).append("\",\"values\":[\"v").append(i).append("\"]}]}");
        }
        findings.append(']');
        return "{\"Report_Version\":\"2.0\",\"Findings\":" + findings + "}";
    }


    @Test
    void persistAndProjectFindings(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-1", sampleReport(3));
        store.persistV2("run-1", sampleReportV2(3));

        FindingsPage page = store.findingsPage("run-1", 0, 10);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        FindingRow first = page.items().get(0);
        assertThat(first.coreId()).isEqualTo("CORE-r0");
        assertThat(first.row()).isEqualTo(1);
        assertThat(first.usubjid()).isEqualTo("S0");
        assertThat(first.variables()).containsExactly("AGE");
        assertThat(first.values()).containsExactly("v0");
        // No record key resolved: null, not empty, so the JSON omits the fields entirely.
        assertThat(first.keyVariables()).isNull();
        assertThat(first.keys()).isNull();
        assertThat(first.keySource()).isNull();
    }


    @Test
    void loadLogToleratesPreFeatureLogWithoutRuntimeFields(@TempDir Path dir) throws IOException
    {
        // A log persisted before the runtime feature has no runtimeMillis on the domain/rule. The
        // store's mapper disables FAIL_ON_NULL_FOR_PRIMITIVES so the absent primitive deserialises
        // to 0 rather than throwing (documented limitation: old logs render 0 ms, not "—").
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        String oldLog = "{\"runId\":\"run-old\",\"sessionId\":\"s\",\"status\":\"SUCCEEDED\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"configuration\":{\"standard\":\"sdtmig\",\"version\":\"3-4\"},\"files\":[],"
                + "\"domains\":[{\"domain\":\"DM\",\"fileName\":\"dm.xpt\",\"rulesExecuted\":1,"
                + "\"rulesTotal\":1,\"findings\":0,\"errors\":[],\"ruleExecutions\":["
                + "{\"coreId\":\"CORE-1\",\"generatedId\":\"g\",\"status\":\"EXECUTED\","
                + "\"violations\":0}]}],\"logLines\":[]}";
        Files.writeString(dir.resolve("log-run-old.json"), oldLog);

        RunLog log = store.loadLog("run-old");
        assertThat(log.domains()).hasSize(1);
        assertThat(log.domains().get(0).runtimeMillis()).isZero();
        assertThat(log.domains().get(0).ruleExecutions().get(0).runtimeMillis()).isZero();
    }


    @Test
    void findingsPagingWindows(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-1", sampleReport(5));
        store.persistV2("run-1", sampleReportV2(5));

        FindingsPage mid = store.findingsPage("run-1", 2, 2);
        assertThat(mid.total()).isEqualTo(5);
        assertThat(mid.firstIndex()).isEqualTo(2);
        assertThat(mid.count()).isEqualTo(2);
        assertThat(mid.items()).extracting(FindingRow::coreId).containsExactly("CORE-r2",
                "CORE-r3");

        FindingsPage pastEnd = store.findingsPage("run-1", 10, 2);
        assertThat(pastEnd.total()).isEqualTo(5);
        assertThat(pastEnd.firstIndex()).isEqualTo(5);
        assertThat(pastEnd.items()).isEmpty();
    }


    @Test
    void projectsConformanceDatasetsRules(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-1", sampleReport(4));

        ConformanceResponse c = store.conformance("run-1");
        assertThat(c.standard()).isEqualTo("SDTMIG");
        // Plan 2 Phase 7 — the substandard is DISPLAY-ONLY and DERIVED (the declared TIG leg,
        // stamped into the report by StudyValidationService). Plan 1 dropped the DTO field while
        // the report kept carrying Sub_Standard, so the SPA had nothing to render; this pins the
        // projection back. It must never become a CheckRunRequest input.
        // ⚠ Review R-19: the pipeline emits the leg LOWERCASE — CompanionSdtmDefaults.tigLeg
        // returns the raw key segment ("adam") and ReportAssembler passes Sub_Standard through
        // raw — so the fixture pins the real value, not a prettified one. Whether the display
        // should read "(ADaM)" is an open presentation question for the owner.
        assertThat(c.substandard()).isEqualTo("adam");
        assertThat(c.version()).isEqualTo("V3.4");
        assertThat(c.ctVersion()).isEqualTo("sdtmct-2024");
        // PLAN-dictionary-seeder Phase 6a — the two degradation-basis keys reach the REST
        // projection (previously Library_Metadata_Basis reached the JSON and nothing else —
        // the Fix #369 gap). Both are null on a healthy run's report, which simply lacks the keys.
        assertThat(c.libraryMetadataBasis()).isEqualTo("unavailable — the CDISC Library …");
        assertThat(c.dictionaryBasis()).isEqualTo("external dictionaries degraded: 0 of 98 …");

        assertThat(store.datasets("run-1")).singleElement().satisfies(d ->
        {
            assertThat(d.filename()).isEqualTo("dm.xpt");
            assertThat(d.sizeKb()).isEqualTo(1.5);
            assertThat(d.length()).isEqualTo(10L);
        });

        assertThat(store.rules("run-1")).singleElement().satisfies(r ->
        {
            assertThat(r.coreId()).isEqualTo("CORE-000001");
            assertThat(r.status()).isEqualTo("SOME_ISSUES");
        });
    }


    /**
     * PLAN-report-conformance-fields P13 / R4: the nine formerly JSON-only keys reach the REST
     * projection, each asserted with its OWN distinct value — the seven dictionary versions are
     * adjacent positional {@code String} components, so a transposed {@code text(c, …)} is the
     * realistic bug and only distinct values catch it.
     */
    @Test
    void projectsTheNineAppendedConformanceFields(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-9", "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\","
                + "\"CT_Declaration_Mismatch\":\"define declares sdtmct-2023-12-15; run used none\","
                + "\"Define_Metadata_Basis\":\"degraded — define.xml could not be parsed\","
                + "\"UNII_Version\":\"unii-1\",\"Med_RT_Version\":\"medrt-2\","
                + "\"MedDRA_Version\":\"meddra-3\",\"WHODRUG_Version\":\"whodrug-4\","
                + "\"SNOMED_Version\":\"snomed-5\",\"LOINC_Version\":\"loinc-6\","
                + "\"Neoplasm_Version\":\"neoplasm-7\"},"
                + "\"Dataset_Details\":[],\"Issue_Summary\":[],\"Issue_Details\":[],"
                + "\"Rules_Report\":[]}");

        ConformanceResponse c = store.conformance("run-9");
        assertThat(c.ctDeclarationMismatch())
                .isEqualTo("define declares sdtmct-2023-12-15; run used none");
        assertThat(c.defineMetadataBasis()).isEqualTo("degraded — define.xml could not be parsed");
        assertThat(c.uniiVersion()).isEqualTo("unii-1");
        assertThat(c.medRtVersion()).isEqualTo("medrt-2");
        assertThat(c.meddraVersion()).isEqualTo("meddra-3");
        assertThat(c.whodrugVersion()).isEqualTo("whodrug-4");
        assertThat(c.snomedVersion()).isEqualTo("snomed-5");
        assertThat(c.loincVersion()).isEqualTo("loinc-6");
        assertThat(c.neoplasmVersion()).isEqualTo("neoplasm-7");
    }


    /**
     * P13, the other half: a report persisted BEFORE the nine keys existed (the shape
     * {@link #sampleReport(int)} has always had) projects {@code null} for every one of them — the
     * change is additive and no stored report is re-rendered.
     */
    @Test
    void aPreFixStoredReportProjectsNullForTheAppendedFields(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-old", sampleReport(1));

        ConformanceResponse c = store.conformance("run-old");
        assertThat(c.standard()).isEqualTo("SDTMIG"); // the projection itself is live
        assertThat(c.ctDeclarationMismatch()).isNull();
        assertThat(c.defineMetadataBasis()).isNull();
        assertThat(c.uniiVersion()).isNull();
        assertThat(c.medRtVersion()).isNull();
        assertThat(c.meddraVersion()).isNull();
        assertThat(c.whodrugVersion()).isNull();
        assertThat(c.snomedVersion()).isNull();
        assertThat(c.loincVersion()).isNull();
        assertThat(c.neoplasmVersion()).isNull();
    }


    /**
     * P13, XLSX arm: {@code /report.xlsx} is rendered from the stored v1, so the two notes reach
     * the template rows 24 and 25 that PLAN-report-conformance-fields appended.
     */
    @Test
    void xlsxRenderedFromStoredJsonFillsTheAppendedConformanceRows(@TempDir Path dir)
        throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-x", "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\","
                + "\"CT_Declaration_Mismatch\":\"define declares sdtmct-2023-12-15; run used none\","
                + "\"Define_Metadata_Basis\":\"degraded — define.xml could not be parsed\"},"
                + "\"Dataset_Details\":[],\"Issue_Summary\":[],\"Issue_Details\":[],"
                + "\"Rules_Report\":[]}");

        byte[] workbook = store.xlsxReport("run-x");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(workbook)))
        {
            org.apache.poi.ss.usermodel.Sheet c = wb.getSheet("Conformance Details");
            assertThat(c.getRow(23).getCell(0).getStringCellValue())
                    .isEqualTo("CT Declaration Mismatch");
            assertThat(c.getRow(23).getCell(1).getStringCellValue())
                    .isEqualTo("define declares sdtmct-2023-12-15; run used none");
            assertThat(c.getRow(24).getCell(0).getStringCellValue())
                    .isEqualTo("Define Metadata Basis");
            assertThat(c.getRow(24).getCell(1).getStringCellValue())
                    .isEqualTo("degraded — define.xml could not be parsed");
        }
    }


    @Test
    void rawReportAndHasReport(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-1", sampleReport(1));
        assertThat(store.hasReport("run-1")).isTrue();
        assertThat(store.hasReport("nope")).isFalse();
        assertThat(store.rawReport("run-1")).contains("Issue_Details").contains("CORE-r0");
    }


    @Test
    void evictedReportReloadsFromDisk(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 1, Duration.ofMinutes(30));
        store.persist("run-A", sampleReport(2));
        store.persistV2("run-A", sampleReportV2(2));
        store.persist("run-B", sampleReport(3));
        store.persistV2("run-B", sampleReportV2(3));
        // run-A's parsed findings were evicted from the size-1 cache; this reloads them from disk.
        assertThat(store.findingsPage("run-A", 0, 10).total()).isEqualTo(2);
    }


    @Test
    void corruptReportThrowsUnchecked(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-1", sampleReport(1));
        store.persistV2("run-1", sampleReportV2(1));
        Files.writeString(dir.resolve("report-run-1.json"), "{ corrupt");
        assertThatThrownBy(() -> store.conformance("run-1"))
                .isInstanceOf(UncheckedIOException.class);
        // The findings projection reads the v2 sibling, so it corrupts independently.
        Files.writeString(dir.resolve("report-v2-run-1.json"), "{ corrupt");
        assertThatThrownBy(() -> store.findingsPage("run-1", 0, 10))
                .isInstanceOf(UncheckedIOException.class);
    }


    @Test
    void findingsPageWithoutAV2ReportThrowsUnchecked(@TempDir Path dir) throws IOException
    {
        // A run whose v2 artifact was never persisted (or was deleted) cannot answer the findings
        // endpoint. Failing is deliberate — an empty page would look like "no findings".
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-nov2", sampleReport(2));
        assertThatThrownBy(() -> store.findingsPage("run-nov2", 0, 10))
                .isInstanceOf(UncheckedIOException.class);
    }


    @Test
    void deleteClearsDisk(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-1", sampleReport(1));
        assertThat(store.hasReport("run-1")).isTrue();
        store.delete("run-1");
        assertThat(store.hasReport("run-1")).isFalse();
    }


    @Test
    void persistAndServeV2Report(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        String v2 = "{\"Report_Version\":\"2.0\",\"Findings\":[{\"core_id\":\"CORE-1\"}]}";
        assertThat(store.hasReportV2("run-v2")).isFalse();
        assertThat(store.reportV2Size("run-v2")).isNull();

        store.persistV2("run-v2", v2);
        assertThat(store.hasReportV2("run-v2")).isTrue();
        assertThat(store.reportV2Size("run-v2")).isNotNull().isPositive();
        assertThat(store.rawReportV2("run-v2")).isEqualTo(v2);
        // The v2 artifact is a sibling of the v1 report — both can coexist for the same run.
        assertThat(dir.resolve("report-v2-run-v2.json")).exists();
    }


    @Test
    void deleteRemovesV2Report(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-dv2", sampleReport(1));
        store.persistV2("run-dv2", "{\"Report_Version\":\"2.0\",\"Findings\":[]}");
        assertThat(store.hasReportV2("run-dv2")).isTrue();

        store.delete("run-dv2");
        assertThat(store.hasReport("run-dv2")).isFalse();
        assertThat(store.hasReportV2("run-dv2")).isFalse();
    }


    @Test
    void xlsxReportRendersWorkbookFromStoredJson(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-1", sampleReport(3));

        byte[] workbook = store.xlsxReport("run-1");
        assertThat(workbook).isNotEmpty();

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(workbook)))
        {
            // Same five sheets as the CLI output.
            assertThat(wb.getNumberOfSheets()).isEqualTo(6);
            assertThat(wb.getSheetName(0)).isEqualTo("Conformance Details");
            // Conformance value recovered from the stored JSON (Standard at row 9 / col B).
            assertThat(wb.getSheet("Conformance Details").getRow(8).getCell(1).getStringCellValue())
                    .isEqualTo("SDTMIG");
            // First Issue_Details row carries the recovered fields, lists joined with ", ".
            org.apache.poi.ss.usermodel.Row first = wb.getSheet("Issue Details").getRow(1);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("CORE-r0");
            assertThat(first.getCell(7).getStringCellValue()).isEqualTo("AGE");
            assertThat(first.getCell(8).getStringCellValue()).isEqualTo("v0");
        }
    }


    @Test
    void persistXlsxStoresAndServesFromDisk(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-px", sampleReport(2));
        assertThat(store.hasXlsx("run-px")).isFalse();
        assertThat(store.xlsxSize("run-px")).isNull();

        store.persistXlsx("run-px");
        assertThat(store.hasXlsx("run-px")).isTrue();
        Long size = store.xlsxSize("run-px");
        assertThat(size).isNotNull().isPositive();
        assertThat(dir.resolve("report-run-px.xlsx")).exists();

        byte[] served = store.xlsxReport("run-px");
        assertThat(served).hasSize(Math.toIntExact(size));
        // XLSX is a ZIP container — first two bytes are "PK".
        assertThat(served[0]).isEqualTo((byte) 'P');
        assertThat(served[1]).isEqualTo((byte) 'K');
    }


    @Test
    void deleteRemovesXlsxAndRuleDefs(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persist("run-d2", sampleReport(1));
        store.persistXlsx("run-d2");
        store.persistRuleDefs("run-d2", "{\"CG0001\":{\"source\":{},\"expanded\":null}}");
        assertThat(store.hasXlsx("run-d2")).isTrue();
        assertThat(store.hasRuleDefs("run-d2")).isTrue();

        store.delete("run-d2");
        assertThat(store.hasXlsx("run-d2")).isFalse();
        assertThat(store.hasRuleDefs("run-d2")).isFalse();
    }


    @Test
    void persistLogIsPrettyPrintedAndRawMatchesSize(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        store.persistLog("run-pl", sampleLog());

        String raw = store.rawLog("run-pl");
        // Pretty-printed JSON spans multiple lines.
        assertThat(raw).contains("\n");
        assertThat(store.logSize("run-pl"))
                .isEqualTo((long) raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        // Still parses back to the structured DTO.
        assertThat(store.loadLog("run-pl").runId()).isEqualTo("run-2");
    }


    @Test
    void ruleDefinitionDirectBaseFallbackAndMissing(@TempDir Path dir) throws IOException
    {
        ReportStore store = newStore(dir, 64, Duration.ofMinutes(30));
        assertThat(store.hasRuleDefs("run-rd")).isFalse();
        assertThat(store.ruleDefinition("run-rd", "CG0001")).isNull();

        store.persistRuleDefs("run-rd",
                "{" + "\"CG0001\":{\"source\":{\"Description\":\"base\"},\"expanded\":null},"
                        + "\"CG0002-AGE\":{\"source\":null,\"expanded\":{\"Description\":\"gen\"}}"
                        + "}");
        // Direct hit on an expanded id.
        assertThat(store.ruleDefinition("run-rd", "CG0002-AGE").get("expanded").get("Description")
                .asString()).isEqualTo("gen");
        // Expanded id with no own entry falls back to its base id for source.
        assertThat(store.ruleDefinition("run-rd", "CG0001-HEIGHT").get("source").get("Description")
                .asString()).isEqualTo("base");
        // Unknown id and unknown run both yield null.
        assertThat(store.ruleDefinition("run-rd", "NOPE")).isNull();
        assertThat(store.ruleDefinition("absent", "CG0001")).isNull();
    }


    private static RunLog sampleLog()
    {
        return new RunLog("run-2", "sess-1", "SUCCEEDED", null, null, null, 1.0, null, List.of(),
                List.of(), 0, null, List.of());
    }
}
