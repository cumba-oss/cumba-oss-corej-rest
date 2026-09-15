package net.cumba.corej.rest.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.cumba.corej.core.report.ReportAssembler;
import net.cumba.corej.core.report.ValidationReportBuilder;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.rest.report.ReportStore;
import net.cumba.corej.rest.report.RunLog;
import net.cumba.corej.rest.run.CheckRun;
import net.cumba.corej.rest.run.CheckRunRequest;
import net.cumba.corej.rest.run.CheckRunner;
import net.cumba.corej.rest.run.RunRecord;
import net.cumba.corej.rest.run.RunRegistry;
import net.cumba.datatable.report.ValidationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end MVC tests for the check + report endpoints. The {@link CheckRunner} is mocked to
 * return an empty-but-real {@link StudyValidationResult} so started runs complete promptly as
 * SUCCEEDED; report-backed endpoints are seeded by registering a run and persisting a crafted
 * report JSON via the {@link ReportStore}.
 *
 * <p>
 * F-rest-01: the mock's Mockito default (a {@code null} result) used to be relied on here, which
 * pinned exactly the behaviour the ruling removes — a null engine result reading as SUCCEEDED. A
 * null now fails the run, so the stub has to produce a real result to reach the success path.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CheckApiTest
{

    @TempDir
    static Path stagingBase;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("corej.sessions.dir", () -> stagingBase.resolve("sessions").toString());
        registry.add("corej.reports.dir", () -> stagingBase.resolve("reports").toString());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRegistry runRegistry;

    @Autowired
    private ReportStore reportStore;

    @MockitoBean
    private CheckRunner runner;

    /** The smallest legitimate engine result: no findings, no dataset summaries. */
    private static StudyValidationResult emptyResult()
    {
        ValidationReport report = new ValidationReportBuilder().build();
        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard("sdtmig").version("3-4").build();
        return new StudyValidationResult(report, conformance, List.of(), List.of(), 0, 0.0,
                List.of());
    }


    @BeforeEach
    void stubRunner() throws Exception
    {
        org.mockito.Mockito.when(runner.run(org.mockito.ArgumentMatchers.any()))
                .thenReturn(emptyResult());
    }


    private static CheckRunRequest minimalRequest()
    {
        return new CheckRunRequest(null, null, null, null, null, null, null, null, null);
    }


    /** Register a SUCCEEDED run and persist {@code reportJson} as its report. */
    private CheckRun seedSucceeded(String reportJson) throws IOException
    {
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), "sess-x", minimalRequest());
        run.markSucceeded(null);
        runRegistry.register(run);
        reportStore.persist(run.id(), reportJson);
        return run;
    }


    /** Register a SUCCEEDED run carrying both report renderings of {@code rows} findings. */
    private String seedFindings(int rows) throws IOException
    {
        CheckRun run = seedSucceeded(sampleReport(rows));
        reportStore.persistV2(run.id(), sampleFindingsV2(rows));
        return run.id();
    }


    /** Register a still-PENDING (in-flight) run with no report. */
    private CheckRun seedInFlight()
    {
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), "sess-x", minimalRequest());
        runRegistry.register(run);
        return run;
    }


    /** A report document with {@code rows} per-row findings, plus all other sections. */
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
                    .append("\",\"executability\":\"executable\",\"dataset\":\"dm.xpt\",")
                    .append("\"domain\":\"DM\",\"USUBJID\":\"S").append(i).append("\",\"row\":")
                    .append(i + 1).append(",\"SEQ\":\"").append(i)
                    .append("\",\"variables\":[\"AGE\"],").append("\"values\":[\"v").append(i)
                    .append("\"]}");
        }
        details.append(']');
        return "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\",\"Sub_Standard\":\"SDTM\","
                + "\"Version\":\"V3.4\",\"CT_Version\":\"sdtmct-2024\",\"Define_XML_Version\":\"2.1\","
                + "\"CORE_Engine_Version\":\"1.0\",\"Total_Runtime\":\"1.50 seconds\","
                + "\"Issue_Limit_Per_Rule\":\"None\",\"Issue_Limit_Per_Dataset\":\"None\","
                + "\"Report_Generation\":\"2024-01-01T00:00:00\"},"
                + "\"Dataset_Details\":[{\"filename\":\"dm.xpt\",\"label\":\"Demographics\","
                + "\"path\":\"/study\",\"modification_date\":\"2024-01-01T00:00:00\","
                + "\"size_kb\":1.5,\"length\":10,\"domain\":\"DM\",\"columns\":5}],"
                + "\"Issue_Summary\":[{\"dataset\":\"DM\",\"core_id\":\"CORE-000001\","
                + "\"message\":\"bad value\",\"issues\":" + rows + "}]," + "\"Issue_Details\":"
                + details + ","
                + "\"Rules_Report\":[{\"core_id\":\"CORE-000001\",\"version\":\"1\","
                + "\"cdisc_rule_id\":\"CD1\",\"fda_rule_id\":\"\",\"message\":\"bad value\","
                + "\"status\":\"SOME_ISSUES\"}]}";
    }


    /**
     * The v2 counterpart of {@link #sampleReport(int)} — one single-row {@code CORE-rN} finding per
     * row, same dataset / domain / USUBJID / values. This is what the <em>findings</em> endpoint
     * reads (the v1 {@code Issue_Details} section is the frozen Python-compatible surface).
     */
    private static String sampleFindingsV2(int rows)
    {
        StringBuilder findings = new StringBuilder("[");
        for (int i = 0; i < rows; i++)
        {
            if (i > 0)
            {
                findings.append(',');
            }
            findings.append("{\"core_id\":\"CORE-r").append(i).append("\",\"message\":\"m")
                    .append(i).append("\",\"executability\":\"executable\",")
                    .append("\"dataset\":\"dm.xpt\",\"domain\":\"DM\",")
                    .append("\"location\":{\"dataset\":\"DM\",\"variables\":[\"AGE\"]},")
                    .append("\"variables\":[\"AGE\"],\"rows\":[{\"row\":").append(i + 1)
                    .append(",\"USUBJID\":\"S").append(i).append("\",\"SEQ\":\"").append(i)
                    .append("\",\"values\":[\"v").append(i).append("\"]}]}");
        }
        findings.append(']');
        return "{\"Report_Version\":\"2.0\",\"Findings\":" + findings + "}";
    }


    /** A v2 document whose single finding carries a resolved EC-40 record key. */
    private static String keyedFindingsV2()
    {
        return "{\"Report_Version\":\"2.0\",\"Findings\":[{\"core_id\":\"CORE-000252\","
                + "\"message\":\"QVAL must be populated\",\"executability\":\"executable\","
                + "\"dataset\":\"suppae.xpt\",\"domain\":\"SUPPAE\",\"location\":"
                + "{\"dataset\":\"SUPPAE\",\"variables\":[\"QVAL\"],"
                + "\"keyVariables\":[\"QNAM\",\"IDVARVAL\"],\"keySource\":\"STRUCTURAL\"},"
                + "\"variables\":[\"QVAL\"],\"rows\":[{\"row\":7,\"USUBJID\":\"S1\",\"SEQ\":\"\","
                + "\"keys\":{\"QNAM\":\"AESOSP\",\"IDVARVAL\":\"3\"},\"values\":[\"Y\"]}]}]}";
    }


    /**
     * A minimal v2 combined-finding report document: one finding carrying a location and one row.
     */
    private static String sampleReportV2()
    {
        return "{\"Report_Version\":\"2.0\"," + "\"Conformance_Details\":{\"Standard\":\"SDTMIG\"},"
                + "\"Dataset_Details\":[],\"Issue_Summary\":[],"
                + "\"Findings\":[{\"core_id\":\"CORE-000252\",\"message\":\"m\","
                + "\"executability\":\"executable\",\"dataset\":\"ae.xpt\",\"domain\":\"AE\","
                + "\"location\":{\"dataset\":\"AE\",\"variables\":[\"AETERM\"]},"
                + "\"variables\":[\"AETERM\"],\"rows\":[{\"row\":3,\"USUBJID\":\"S1\",\"SEQ\":\"2\","
                + "\"values\":[\"x\"]}]}]," + "\"Rules_Report\":[]}";
    }


    private String createSession() throws Exception
    {
        String location = mvc.perform(post("/api/sessions")).andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }


    private String startCheck(String sessionId, String body) throws Exception
    {
        String location = mvc
                .perform(post("/api/sessions/{id}/checks", sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.checkRunId").isNotEmpty())
                .andReturn().getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------------
    // start / status / cancel
    // ------------------------------------------------------------------


    @Test
    void startReturns201WithRunId() throws Exception
    {
        startCheck(createSession(), "{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"]}");
    }


    @Test
    void startForUnknownSessionReturns404() throws Exception
    {
        mvc.perform(post("/api/sessions/{id}/checks", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"]}"))
                .andExpect(status().isNotFound());
    }


    /**
     * ⚑ Plan 2 (R3/R5) — this was {@code startWithMissingStandardReturns400}. The required field is
     * no longer {@code standard}: a run must NAME ITS RULES, via {@code rulesPackages} or
     * {@code rulesFilenames}. A body naming neither is the same 400.
     */
    @Test
    void startWithNoRuleSelectionReturns400() throws Exception
    {
        mvc.perform(post("/api/sessions/{id}/checks", createSession())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }


    /**
     * Review finding F2: an unresolvable {@code metadataProducts} token used to return 201 and fail
     * the run asynchronously; every other malformed field is a 400. The token resolves in no
     * catalogue configuration (bare suffix, matching no published product), so this pin holds with
     * or without a configured metadata cache. The body names the offending token.
     */
    @Test
    void startWithUnresolvableMetadataProductReturns400NamingTheToken() throws Exception
    {
        mvc.perform(post("/api/sessions/{id}/checks", createSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rulesPackages\":[\"cdisc-adamig-1-3\"],"
                        + "\"metadataProducts\":[\"adamig-9-9\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("adamig-9-9")));
    }


    /**
     * V4 (review R-7): a body selecting rules only by {@code rulesFilenames} with no
     * {@code metadataProducts} used to be accepted (201) and fail asynchronously — the run is
     * certain to fail because an uploaded rules file declares no CDISC Library standard. It must be
     * a 400 at request time, naming the missing field.
     */
    @Test
    void startWithRulesFilesOnlyAndNoMetadataProductsReturns400() throws Exception
    {
        String sessionId = createSession();
        upload(sessionId, "rules-extra.json");
        mvc.perform(
                post("/api/sessions/{id}/checks", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rulesFilenames\":[\"rules-extra.json\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("metadataProducts")));
    }


    @Test
    void startReferencingUnknownFileReturns400() throws Exception
    {
        mvc.perform(post("/api/sessions/{id}/checks", createSession())
                .contentType(MediaType.APPLICATION_JSON).content(
                        "{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"],\"rulesFilenames\":[\"missing.json\"]}"))
                .andExpect(status().isBadRequest());
    }


    /** Stages one file into a session under the given bare name. */
    private void upload(String sessionId, String filename) throws Exception
    {
        mvc.perform(multipart("/api/sessions/{id}/files", sessionId)
                .file(new MockMultipartFile("file", filename, MediaType.TEXT_PLAIN_VALUE,
                        "x".getBytes(StandardCharsets.UTF_8)))
                .param("filename", filename)).andExpect(status().isCreated());
    }


    /**
     * Reference data in a format no library supplier can open is rejected at request time, and the
     * <b>response body</b> — not merely the status — names the file, its format and the formats
     * that do work (wave-37 lane D, {@code Fix #240}).
     *
     * <p>
     * Before this, the request was accepted and the run died mid-flight with a raw
     * {@code IOException: Can not open … as library!} — a 500 on a request the API had accepted.
     * </p>
     */
    @Test
    void startWithUnopenableReferenceDataReturns400NamingTheFileFormatAndSupportedList()
        throws Exception
    {
        String sessionId = createSession();
        upload(sessionId, "REF.csv");

        mvc.perform(
                post("/api/sessions/{id}/checks", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"],"
                                + "\"referenceDataFilenames\":[\"REF.csv\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("REF.csv")))
                .andExpect(jsonPath("$.detail", containsString("format 'csv'")))
                .andExpect(jsonPath("$.detail", containsString("Supported reference-data formats")))
                .andExpect(jsonPath("$.detail", containsString("cdt")))
                .andExpect(jsonPath("$.detail", containsString("datasetFilter")));
    }


    /**
     * ⚑ The anti-over-reach half at the same level. The ruling was <i>reject by format</i>, not
     * <i>reject the field</i>: a caller naming a format a library supplier <b>can</b> open still
     * gets a 201 and a queued run.
     */
    @Test
    void startWithSupportedReferenceDataStillReturns201() throws Exception
    {
        String sessionId = createSession();
        upload(sessionId, "REF.cdt");

        startCheck(sessionId, "{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"],"
                + "\"referenceDataFilenames\":[\"REF.cdt\"]}");
    }


    /**
     * ⚑ The wave-38 widening, seen through the HTTP layer (W37-D1). Registering
     * {@code cumba-oss-datatable-provider-sas} / {@code -xlsx} in this module added
     * {@code XptLibrarySupplier} and {@code ExcelLibrarySupplier} to the library SPI, so
     * {@code .xpt} and {@code .xlsx} reference data now gets a <b>201</b>. Before wave 38 both were
     * a 400 here — and, before the format check existed at all, a mid-run 500.
     *
     * <p>
     * The set membership behind this status code is asserted directly in
     * {@code StudyValidationCheckRunnerTest#publishedLibraryFormatsIncludeXptXlsAndXlsx}; a 201
     * alone cannot tell "the SPI widened" from "the fixture changed".
     * </p>
     */
    @Test
    void startWithXptAndXlsxReferenceDataNowReturns201() throws Exception
    {
        String sessionId = createSession();
        upload(sessionId, "REF.xpt");
        upload(sessionId, "REF.xlsx");

        startCheck(sessionId, "{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"],"
                + "\"referenceDataFilenames\":[\"REF.xpt\",\"REF.xlsx\"]}");
    }


    /**
     * ⛔ …and the boundary of that widening, also through HTTP.
     * {@code cumba-oss-datatable-provider-parquet} is on the classpath since wave 38, but it
     * registers only an {@code IProviderSupplier}, never an {@code ILibrarySupplier} — {@code F3}'s
     * ruled <b>category error</b>: a library is a container of tables and a single {@code .parquet}
     * file has no member namespace. So {@code .parquet} <i>datasets</i> load while {@code .parquet}
     * <i>reference data</i> stays a 400. <b>This is intended; do not "fix" it.</b>
     */
    @Test
    void startWithParquetReferenceDataStillReturns400DespiteTheProviderBeingPresent()
        throws Exception
    {
        String sessionId = createSession();
        upload(sessionId, "REF.parquet");

        mvc.perform(
                post("/api/sessions/{id}/checks", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"],"
                                + "\"referenceDataFilenames\":[\"REF.parquet\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("REF.parquet")))
                .andExpect(jsonPath("$.detail", containsString("format 'parquet'")))
                // The widened list is visible in the very message that refuses parquet.
                .andExpect(jsonPath("$.detail", containsString("xpt")));
    }


    @Test
    void statusReturnsTerminalStateAfterWait() throws Exception
    {
        String runId = startCheck(createSession(), "{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"]}");
        mvc.perform(get("/api/checks/{id}/status", runId).param("waitSeconds", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkRunId").value(runId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }


    @Test
    void statusForUnknownRunReturns404() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/status", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }


    @Test
    void cancelReturns202() throws Exception
    {
        String runId = startCheck(createSession(), "{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"]}");
        mvc.perform(post("/api/checks/{id}/cancel", runId)).andExpect(status().isAccepted());
    }


    @Test
    void cancelForUnknownRunReturns404() throws Exception
    {
        mvc.perform(post("/api/checks/{id}/cancel", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // listing
    // ------------------------------------------------------------------


    @Test
    void listRunsFilteredBySessionReturnsOnlyThatSession() throws Exception
    {
        String sid = "runlist-" + UUID.randomUUID();
        CheckRun r1 = new CheckRun(UUID.randomUUID().toString(), sid, minimalRequest());
        CheckRun r2 = new CheckRun(UUID.randomUUID().toString(), sid, minimalRequest());
        runRegistry.register(r1);
        runRegistry.register(r2);

        mvc.perform(get("/api/checks").param("sessionId", sid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].sessionId", everyItem(is(sid))))
                .andExpect(jsonPath("$[*].checkRunId", hasItems(r1.id(), r2.id())));
    }


    @Test
    void listRunsUnfilteredIncludesRegistered() throws Exception
    {
        CheckRun run = seedInFlight();
        mvc.perform(get("/api/checks")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].checkRunId", hasItems(run.id())));
    }


    @Test
    void runListCarriesSessionNameAndReflectsRename() throws Exception
    {
        // A real, named session backing the run, so its name resolves live.
        String location = mvc
                .perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My study\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        String sid = location.substring(location.lastIndexOf('/') + 1);
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), sid, minimalRequest());
        runRegistry.register(run);

        mvc.perform(get("/api/checks").param("sessionId", sid)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value(sid))
                .andExpect(jsonPath("$[0].sessionName").value("My study"));

        // Renaming the session is reflected on the next list (live resolution, not persisted).
        mvc.perform(patch("/api/sessions/{id}", sid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed study\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/checks").param("sessionId", sid)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionName").value("Renamed study"));
    }


    @Test
    void runListSessionNameIsNullForUnnamedSession() throws Exception
    {
        String sid = createSession(); // unnamed
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), sid, minimalRequest());
        runRegistry.register(run);
        mvc.perform(get("/api/checks").param("sessionId", sid)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionName").value(nullValue()));
    }


    @Test
    void runListIsNewestFirst() throws Exception
    {
        // Restore runs from records with explicit, distinct createdAt timestamps for a
        // deterministic
        // ordering assertion (the live constructor would use Instant.now()).
        String sid = "order-" + UUID.randomUUID();
        RunRecord older = new RunRecord(UUID.randomUUID().toString(), sid, minimalRequest(),
                "SUCCEEDED", "2026-01-01T00:00:00Z", null, null, 0, 0, 0, 0, null);
        RunRecord newer = new RunRecord(UUID.randomUUID().toString(), sid, minimalRequest(),
                "SUCCEEDED", "2026-06-01T00:00:00Z", null, null, 0, 0, 0, 0, null);
        runRegistry.register(CheckRun.restore(older));
        runRegistry.register(CheckRun.restore(newer));

        mvc.perform(get("/api/checks").param("sessionId", sid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].checkRunId").value(newer.id()))
                .andExpect(jsonPath("$[1].checkRunId").value(older.id()));
    }

    // ------------------------------------------------------------------
    // report section endpoints
    // ------------------------------------------------------------------


    @Test
    void datasetGroupsReturnsFileThenDomain() throws Exception
    {
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), "sess-x", minimalRequest());
        run.markSucceeded(null);
        runRegistry.register(run);
        reportStore.persist(run.id(), sampleReport(3));
        RunLog log = new RunLog(run.id(), "sess-x", "SUCCEEDED", null, null, null, 1.5,
                minimalRequest(), List.of(new RunLog.FileManifestEntry("dm.xpt", 2048L, "abc123")),
                List.of(new RunLog.DomainLogEntry("DM", "dm.xpt", 3, 10, 3, 88, List.of(),
                        List.of(new RunLog.RuleExecutionEntry("CORE-000001", "u1", "EXECUTED", 3,
                                12, null, null, "demographics rule", null)))),
                3, null, List.of());
        reportStore.persistLog(run.id(), log);

        mvc.perform(get("/api/checks/{id}/dataset-groups", run.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fileName").value("dm.xpt"))
                .andExpect(jsonPath("$[0].sizeBytes").value(2048))
                .andExpect(jsonPath("$[0].sha256").value("abc123"))
                .andExpect(jsonPath("$[0].domains.length()").value(1))
                .andExpect(jsonPath("$[0].domains[0].domain").value("DM"))
                .andExpect(jsonPath("$[0].domains[0].label").value("Demographics"))
                .andExpect(jsonPath("$[0].domains[0].rows").value(10))
                .andExpect(jsonPath("$[0].domains[0].columns").value(5))
                // Per-dataset wall clock + per-rule runtime are exposed.
                .andExpect(jsonPath("$[0].domains[0].runtimeMillis").value(88))
                .andExpect(jsonPath("$[0].domains[0].rules[0].coreId").value("CORE-000001"))
                .andExpect(jsonPath("$[0].domains[0].rules[0].runtimeMillis").value(12))
                // Findings are NOT embedded — they load via the scoped findings endpoint.
                .andExpect(jsonPath("$[0].domains[0].findings").doesNotExist());
    }


    @Test
    void datasetGroupsWhileInFlightReturns409() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/dataset-groups", seedInFlight().id()))
                .andExpect(status().isConflict());
    }


    @Test
    void datasetGroupsForUnknownRunReturns404() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/dataset-groups", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }


    @Test
    void artifactsReturnsSizesAndGeneratedAt() throws Exception
    {
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), "sess-x", minimalRequest());
        run.markSucceeded(null);
        runRegistry.register(run);
        reportStore.persist(run.id(), sampleReport(2));
        reportStore.persistV2(run.id(), sampleReportV2());
        RunLog log = new RunLog(run.id(), "sess-x", "SUCCEEDED", null, null, null, 1.0,
                minimalRequest(), List.of(), List.of(), 0, null, List.of());
        reportStore.persistLog(run.id(), log);

        mvc.perform(get("/api/checks/{id}/artifacts", run.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.reportBytes").isNumber())
                .andExpect(jsonPath("$.reportV2Bytes").isNumber())
                .andExpect(jsonPath("$.logBytes").isNumber())
                .andExpect(jsonPath("$.generatedAt").isNotEmpty());
    }


    @Test
    void artifactsWhileInFlightReturns409() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/artifacts", seedInFlight().id()))
                .andExpect(status().isConflict());
    }


    @Test
    void findingsScopedToFileDomainAndRuleAreReturnedPaged() throws Exception
    {
        String runId = seedFindings(5);
        // All 5 sample findings share dataset=dm.xpt, domain=DM, core_id=CORE-rN; scope to one.
        mvc.perform(get("/api/checks/{id}/findings", runId).param("file", "dm.xpt")
                .param("domain", "DM").param("coreId", "CORE-r2").param("count", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].coreId").value("CORE-r2"))
                .andExpect(jsonPath("$.items[0].domain").value("DM"))
                // No key resolved (corej.findingKeys=off): the three fields are omitted entirely,
                // so the payload is what a pre-EC-40 build served.
                .andExpect(jsonPath("$.items[0].keyVariables").doesNotExist())
                .andExpect(jsonPath("$.items[0].keys").doesNotExist())
                .andExpect(jsonPath("$.items[0].keySource").doesNotExist());
    }


    @Test
    void findingsPageWindowsTheUnscopedSet() throws Exception
    {
        String runId = seedFindings(5);
        mvc.perform(get("/api/checks/{id}/findings", runId).param("firstIndex", "2").param("count",
                "2")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.firstIndex").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
    }


    @Test
    void findingsCarryTheRecordKeyWhenTheRunResolvedOne() throws Exception
    {
        CheckRun run = seedSucceeded(sampleReport(1));
        reportStore.persistV2(run.id(), keyedFindingsV2());
        mvc.perform(get("/api/checks/{id}/findings", run.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].keyVariables[0]").value("QNAM"))
                .andExpect(jsonPath("$.items[0].keyVariables[1]").value("IDVARVAL"))
                .andExpect(jsonPath("$.items[0].keys.QNAM").value("AESOSP"))
                .andExpect(jsonPath("$.items[0].keys.IDVARVAL").value("3"))
                // The tier rides on the wire even though the SPA does not render it (D10).
                .andExpect(jsonPath("$.items[0].keySource").value("STRUCTURAL"));
    }


    @Test
    void findingsOverAnUnrecognisedV2DocumentReturns500() throws Exception
    {
        // A shape the strict façade does not recognise must fail loudly; an empty page would be
        // indistinguishable from "no findings".
        CheckRun run = seedSucceeded(sampleReport(1));
        reportStore.persistV2(run.id(),
                "{\"Report_Version\":\"2.0\",\"Findings\":[{\"core_id\":\"C1\"}]}");
        mvc.perform(get("/api/checks/{id}/findings", run.id()))
                .andExpect(status().isInternalServerError());
    }


    @Test
    void findingsWhileInFlightReturns409() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/findings", seedInFlight().id()))
                .andExpect(status().isConflict());
    }


    @Test
    void conformanceReturnsMetadata() throws Exception
    {
        String runId = seedSucceeded(sampleReport(1)).id();
        mvc.perform(get("/api/checks/{id}/conformance", runId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.standard").value("SDTMIG"))
                .andExpect(jsonPath("$.version").value("V3.4"))
                .andExpect(jsonPath("$.ctVersion").value("sdtmct-2024"))
                .andExpect(jsonPath("$.defineXmlVersion").value("2.1"))
                .andExpect(jsonPath("$.totalRuntime").value("1.50 seconds"));
    }


    @Test
    void rulesReturnsRows() throws Exception
    {
        String runId = seedSucceeded(sampleReport(1)).id();
        mvc.perform(get("/api/checks/{id}/rules", runId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].coreId").value("CORE-000001"))
                .andExpect(jsonPath("$[0].cdiscRuleId").value("CD1"))
                .andExpect(jsonPath("$[0].status").value("SOME_ISSUES"));
    }


    @Test
    void reportReturnsFullJson() throws Exception
    {
        String runId = seedSucceeded(sampleReport(2)).id();
        mvc.perform(get("/api/checks/{id}/report", runId)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.Conformance_Details").exists())
                .andExpect(jsonPath("$.Issue_Details.length()").value(2))
                .andExpect(jsonPath("$.Rules_Report").exists());
    }


    @Test
    void reportAsXlsxWhenAcceptingSpreadsheet() throws Exception
    {
        String xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        String runId = seedSucceeded(sampleReport(2)).id();
        byte[] body = mvc.perform(get("/api/checks/{id}/report", runId).accept(xlsx))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType(xlsx)))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        // The body must be a real, openable workbook carrying the five report sheets.
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(body)))
        {
            org.junit.jupiter.api.Assertions.assertEquals(6, wb.getNumberOfSheets());
            org.junit.jupiter.api.Assertions.assertEquals("Conformance Details",
                    wb.getSheetName(0));
        }
    }


    @Test
    void reportWhileInFlightReturns409() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/report", seedInFlight().id()))
                .andExpect(status().isConflict());
    }


    @Test
    void reportForUnknownRunReturns404() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/report", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }


    @Test
    void reportV2ReturnsCombinedFindingsWithLocation() throws Exception
    {
        CheckRun run = seedSucceeded(sampleReport(2));
        reportStore.persistV2(run.id(), sampleReportV2());
        mvc.perform(get("/api/checks/{id}/report-v2", run.id())).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.Report_Version").value("2.0"))
                .andExpect(jsonPath("$.Findings.length()").value(1))
                .andExpect(jsonPath("$.Findings[0].location.dataset").value("AE"));
    }


    @Test
    void reportV2WhileInFlightReturns409() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/report-v2", seedInFlight().id()))
                .andExpect(status().isConflict());
    }


    @Test
    void reportV2ForUnknownRunReturns404() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/report-v2", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }


    @Test
    void corruptReportFileReturns500() throws Exception
    {
        CheckRun run = seedSucceeded(sampleReport(1));
        Files.writeString(stagingBase.resolve("reports").resolve("report-" + run.id() + ".json"),
                "{ corrupt");
        mvc.perform(get("/api/checks/{id}/dataset-groups", run.id()))
                .andExpect(status().isInternalServerError())
                // F-rest-05: the 500 must name the artifact that failed and the run it belongs to.
                // It used to say "Failed to read stored findings" for every UncheckedIOException —
                // wrong artifact, and the run id (the only thing that makes it actionable) was
                // dropped.
                .andExpect(jsonPath("$.detail").value("Corrupt report file for run " + run.id()));
    }

    // ------------------------------------------------------------------
    // delete / misc
    // ------------------------------------------------------------------


    @Test
    void deleteRunRemovesItAndReport() throws Exception
    {
        String runId = seedSucceeded(sampleReport(2)).id();
        mvc.perform(delete("/api/checks/{id}", runId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/checks/{id}/status", runId)).andExpect(status().isNotFound());
    }


    @Test
    void deleteRunWhileInFlightReturns409() throws Exception
    {
        mvc.perform(delete("/api/checks/{id}", seedInFlight().id()))
                .andExpect(status().isConflict());
    }


    @Test
    void malformedJsonBodyReturns400() throws Exception
    {
        mvc.perform(post("/api/sessions/{id}/checks", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{ not valid json"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // execution log
    // ------------------------------------------------------------------


    /** Register a run and persist a crafted execution log for it. */
    private CheckRun seedWithLog() throws IOException
    {
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), "sess-x", minimalRequest());
        run.markSucceeded(null);
        runRegistry.register(run);
        RunLog log = new RunLog(run.id(), "sess-x", "SUCCEEDED", null, null, null, 1.5,
                minimalRequest(), List.of(new RunLog.FileManifestEntry("dm.xpt", 10L, "abc123")),
                List.of(new RunLog.DomainLogEntry("DM", "dm.xpt", 3, 10, 2, 88,
                        List.of(new RunLog.RuleErrorEntry("CORE-1", "boom")),
                        List.of(new RunLog.RuleExecutionEntry("CG0001-AGE", "uuid-1", "EXECUTED", 1,
                                12, "AGE", null, "age rule", null),
                                new RunLog.RuleExecutionEntry("CORE-1", "uuid-2", "ERROR", 0, -1,
                                        null, "boom", null, null)))),
                2, null, List.of("INFO Selected 10 rule(s) for validation"));
        reportStore.persistLog(run.id(), log);
        return run;
    }


    @Test
    void logReturnsManifestAndDomains() throws Exception
    {
        String runId = seedWithLog().id();
        mvc.perform(get("/api/checks/{id}/log", runId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].filename").value("dm.xpt"))
                .andExpect(jsonPath("$.files[0].sha256").value("abc123"))
                .andExpect(jsonPath("$.domains[0].rulesExecuted").value(3))
                .andExpect(jsonPath("$.domains[0].rulesTotal").value(10))
                .andExpect(jsonPath("$.domains[0].errors[0].ruleId").value("CORE-1"))
                .andExpect(
                        jsonPath("$.logLines[0]").value("INFO Selected 10 rule(s) for validation"))
                .andExpect(jsonPath("$.totalFindings").value(2));
    }


    @Test
    void logWithoutArtifactReturns409() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/log", seedInFlight().id()))
                .andExpect(status().isConflict());
    }


    @Test
    void logForUnknownRunReturns404() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/log", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // live log lines
    // ------------------------------------------------------------------


    @Test
    void liveLogLinesAccumulateAndCursorAdvances() throws Exception
    {
        // An in-flight run whose live log buffer is filled incrementally, simulating an engine
        // writing lines while RUNNING. No hasLog gate, so it is readable mid-run.
        CheckRun run = seedInFlight();
        run.addLogLine("INFO starting");
        run.addLogLine("DEBUG first");

        mvc.perform(get("/api/checks/{id}/log/lines", run.id()).param("from", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0]").value("INFO starting"))
                .andExpect(jsonPath("$.nextFrom").value(2))
                .andExpect(jsonPath("$.terminal").value(false));

        // More lines appended; polling from the previous cursor returns only the new tail.
        run.addLogLine("DEBUG second");
        run.addLogLine("ERROR boom");
        mvc.perform(get("/api/checks/{id}/log/lines", run.id()).param("from", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0]").value("DEBUG second"))
                .andExpect(jsonPath("$.lines[1]").value("ERROR boom"))
                .andExpect(jsonPath("$.nextFrom").value(4))
                .andExpect(jsonPath("$.terminal").value(false));

        // from == nextFrom yields the empty tail (cursor caught up).
        mvc.perform(get("/api/checks/{id}/log/lines", run.id()).param("from", "4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.nextFrom").value(4));

        // On terminal the flag flips true; the cursor still equals the total line count.
        run.markSucceeded(null);
        mvc.perform(get("/api/checks/{id}/log/lines", run.id()).param("from", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.nextFrom").value(4))
                .andExpect(jsonPath("$.terminal").value(true));
    }


    @Test
    void liveLogLinesDefaultsFromToZero() throws Exception
    {
        CheckRun run = seedInFlight();
        run.addLogLine("INFO one");
        mvc.perform(get("/api/checks/{id}/log/lines", run.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.nextFrom").value(1));
    }


    @Test
    void liveLogLinesForUnknownRunReturns404() throws Exception
    {
        mvc.perform(get("/api/checks/{id}/log/lines", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }


    /**
     * A direct engine {@code LOGGER.log(INFO|WARNING)} line — the kind that never reached the run's
     * (now-removed) {@code logListener} — must now appear in the live {@code /log/lines} tail and
     * in the persisted {@code RunLog.logLines}, captured by the {@code RunLogDebugCapture} appender
     * via the per-run sink bound on the run-worker thread.
     */
    @Test
    void directEngineInfoLineReachesLiveAndPersistedLog() throws Exception
    {
        // The mocked runner stands in for the engine: on the run-worker thread (where the capture
        // sink is bound) it logs an INFO line directly through System.Logger on the engine logger,
        // exactly like the engine's many direct LOGGER.log sites that bypassed the listener.
        String marker = "loaded-templates-" + UUID.randomUUID();
        // Use System.Logger MessageFormat {0} placeholders exactly like the engine's real direct
        // LOGGER.log sites, so these assertions prove the placeholder rendering survives the
        // System.Logger -> JUL -> jul-to-slf4j -> Logback bridge that the appender reads from.
        org.mockito.Mockito.when(runner.run(org.mockito.ArgumentMatchers.any())).thenAnswer(_ ->
        {
            // ⚑ Any engine logger name works here — this fixture tests the System.Logger -> JUL ->
            // slf4j -> Logback bridge, not the class. It used to name the deleted
            // gen.DatasetRuleResolver (plans/PLAN-remove-rule-generator.md).
            System.getLogger("net.cumba.corej.core.RulePackageLoader").log(System.Logger.Level.INFO,
                    "Loaded {0} rule(s) from {1}", 7, marker);
            System.getLogger("net.cumba.corej.core.run.StudyValidationService").log(
                    System.Logger.Level.WARNING, "--dataset {0} did not match: {1}", "XX", marker);
            return emptyResult();
        });

        String sid = createSession();
        String runId = startCheck(sid, "{\"rulesPackages\":[\"cdisc-sdtmig-3-4\"]}");
        // Drive to terminal so the persisted RunLog is written.
        mvc.perform(get("/api/checks/{id}/status", runId).param("waitSeconds", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

        // Live tail carries both the INFO and WARN engine lines.
        mvc.perform(get("/api/checks/{id}/log/lines", runId).param("from", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines",
                        hasItems(
                                org.hamcrest.Matchers
                                        .containsString("Loaded 7 rule(s) from " + marker),
                                org.hamcrest.Matchers
                                        .containsString("--dataset XX did not match: " + marker))));

        // Persisted RunLog carries the same lines.
        RunLog persisted = reportStore.loadLog(runId);
        org.assertj.core.api.Assertions.assertThat(persisted.logLines())
                .anyMatch(l -> l.startsWith("INFO") && l.contains(marker))
                .anyMatch(l -> l.startsWith("WARN") && l.contains(marker));
    }
}
