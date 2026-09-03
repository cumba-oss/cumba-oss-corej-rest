package net.cumba.corej.rest.report;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.report.ReportFormat;
import net.cumba.corej.core.report.ReportManager;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.ServiceReportManager;
import net.cumba.corej.rest.config.CorejProperties;
import net.cumba.corej.rest.run.RunRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists and serves a run's full report. On success the engine's report JSON is written to a
 * per-run file under a configurable base ({@code corej.reports.dir}, or a fresh OS temp dir) — the
 * disk is the source of truth, and {@code GET /report} returns it verbatim. Section endpoints are
 * projections of that document into typed camelCase DTOs; the parsed tree is held in an LRU+TTL
 * cache ({@code corej.reports.cache.*}) and reloaded from disk on a miss.
 *
 * <p>
 * The paged findings endpoint is the one exception: it projects the sibling <b>v2</b>
 * combined-finding artifact through the typed {@link V2Findings} façade, because v1's
 * {@code Issue_Details} is the frozen Python-compatible surface and cannot carry the EC-40 record
 * key. The parsed findings get their own cache with the same size and TTL.
 * </p>
 */
@Component
public class ReportStore
{

    private static final Logger LOG = LoggerFactory.getLogger(ReportStore.class);

    /** The report-writer registry; the pre-rendered workbook is produced through it. */
    private static final ReportManager REPORT_MANAGER = ServiceReportManager.getInstance();

    private static final String FORMAT_XLSX = "xlsx";

    private final CorejProperties properties;

    // Tolerate logs persisted before a primitive field existed: an absent/null primitive
    // (e.g. runtimeMillis on a pre-feature log) deserialises to 0 rather than throwing.
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final Duration ttl;

    private final Map<String, Cached<JsonNode>> cache;

    /**
     * Parsed v2 findings, cached exactly like the v1 tree: the findings endpoint is paged, so the
     * same document is re-read once per page without it.
     */
    private final Map<String, Cached<List<V2Findings.Finding>>> findingsCache;

    private volatile Path dir;

    private volatile boolean dirIsTemp;

    public ReportStore(CorejProperties properties)
    {
        this.properties = properties;
        CorejProperties.Reports.Cache cfg = properties.getReports().getCache();
        this.ttl = cfg.getTtl();
        int max = Math.max(1, cfg.getMaxEntries());
        this.cache = Collections.synchronizedMap(new LruCache<>(max));
        this.findingsCache = Collections.synchronizedMap(new LruCache<>(max));
    }


    @PostConstruct
    void init() throws IOException
    {
        String configured = properties.getReports().getDir();
        if (configured != null && !configured.isBlank())
        {
            dir = Path.of(configured);
            Files.createDirectories(dir);
            dirIsTemp = false;
        }
        else
        {
            dir = Files.createTempDirectory("corej-reports");
            dirIsTemp = true;
        }
        LOG.info("Report base directory: {}", dir);
    }


    /** Persist a run's report JSON (the engine's assembled document). */
    public void persist(String runId, String reportJson) throws IOException
    {
        Files.writeString(fileFor(runId), reportJson, StandardCharsets.UTF_8);
        cache.remove(runId);
    }


    /** Whether a report file exists for the run. */
    public boolean hasReport(String runId)
    {
        return Files.exists(fileFor(runId));
    }


    /** The run's report JSON, verbatim from disk. */
    public String rawReport(String runId)
    {
        try
        {
            return Files.readString(fileFor(runId), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to read report for run " + runId, e);
        }
    }


    /**
     * Persist a run's v2 combined-finding report JSON (one object per finding, carrying its
     * location plus its rows) as a sibling artifact {@code report-v2-<runId>.json}. Served verbatim
     * by {@code GET /report-v2}; not parsed into section DTOs, so it is not cached.
     *
     * @param runId
     *            the run id
     * @param reportJson
     *            the serialized v2 document
     * @throws IOException
     *             on write failure
     */
    public void persistV2(String runId, String reportJson) throws IOException
    {
        Files.writeString(fileForV2(runId), reportJson, StandardCharsets.UTF_8);
        findingsCache.remove(runId);
    }


    /** Whether a v2 report file exists for the run. */
    public boolean hasReportV2(String runId)
    {
        return Files.exists(fileForV2(runId));
    }


    /** The run's v2 combined-finding report JSON, verbatim from disk. */
    public String rawReportV2(String runId)
    {
        try
        {
            return Files.readString(fileForV2(runId), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to read v2 report for run " + runId, e);
        }
    }


    /**
     * Renders the run's report as an XLSX workbook, structurally and content-identical to the CLI's
     * {@code --output-format xlsx} output. Built from the same persisted JSON document the
     * {@code /report} endpoint serves, so the JSON and Excel views never diverge. The per-sheet row
     * cap follows the {@code MAX_REPORT_ROWS} environment variable (default 10000).
     *
     * @param runId
     *            the run id
     * @return the workbook bytes
     */
    public byte[] xlsxReport(String runId)
    {
        Path stored = xlsxFileFor(runId);
        if (Files.exists(stored))
        {
            try
            {
                return Files.readAllBytes(stored);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException("Failed to read XLSX report for run " + runId, e);
            }
        }
        return renderXlsx(runId);
    }


    /**
     * Pre-renders the run's XLSX workbook and writes it to {@code report-<runId>.xlsx} so
     * subsequent downloads serve stored bytes and the size is known up front.
     *
     * @param runId
     *            the run id
     * @throws IOException
     *             on render or write failure
     */
    public void persistXlsx(String runId) throws IOException
    {
        Files.write(xlsxFileFor(runId), renderXlsx(runId));
    }


    /** Whether a pre-rendered XLSX file exists for the run. */
    public boolean hasXlsx(String runId)
    {
        return Files.exists(xlsxFileFor(runId));
    }


    /** Size in bytes of the stored XLSX file, or {@code null} when it does not exist. */
    public @Nullable Long xlsxSize(String runId)
    {
        return sizeOrNull(xlsxFileFor(runId));
    }


    @SuppressWarnings("unchecked")
    private byte[] renderXlsx(String runId)
    {
        Map<String, Object> document = mapper.convertValue(tree(runId), Map.class);
        ReportSections sections = ReportSections.fromExportDocument(document);
        // Routed through the report SPI rather than constructing a writer: this module declares
        // corej-cdisc-report-xlsx as a runtime dependency but imports nothing from it, so an
        // Excel-less deployment degrades to a named error instead of a NoClassDefFoundError
        // (Fix #224).
        ReportFormat xlsx = REPORT_MANAGER.findReportFormat(FORMAT_XLSX);
        if (xlsx == null)
        {
            throw new IllegalStateException("No XLSX report writer on the classpath — add "
                    + "corej-cdisc-report-xlsx to serve /report.xlsx (run " + runId + ")");
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            REPORT_MANAGER.writeReport(sections, out, xlsx);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to render XLSX report for run " + runId, e);
        }
    }

    // ------------------------------------------------------------------
    // Execution log — a sibling artifact stored as log-<runId>.json
    // ------------------------------------------------------------------


    /** Persist a run's execution log next to its report (pretty-printed, human-readable JSON). */
    public void persistLog(String runId, RunLog log) throws IOException
    {
        Files.writeString(logFileFor(runId),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(log),
                StandardCharsets.UTF_8);
    }


    /** The run's execution log JSON, verbatim from disk (the exact bytes a download serves). */
    public String rawLog(String runId)
    {
        try
        {
            return Files.readString(logFileFor(runId), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to read execution log for run " + runId, e);
        }
    }


    /** Whether an execution-log file exists for the run. */
    public boolean hasLog(String runId)
    {
        return Files.exists(logFileFor(runId));
    }


    /** Read and parse a run's execution log. */
    public RunLog loadLog(String runId)
    {
        try
        {
            return mapper.readValue(Files.readString(logFileFor(runId), StandardCharsets.UTF_8),
                    RunLog.class);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to read execution log for run " + runId, e);
        }
    }

    // ------------------------------------------------------------------
    // Rule definitions — a sibling artifact stored as rules-<runId>.json
    // ------------------------------------------------------------------


    /**
     * Persist the run's rule definitions: a JSON object keyed by the row's CORE id, each value an
     * object {@code { "source": <raw rule JSON or null>, "expanded": <generated rule JSON or null>
     * }}. Written verbatim (already-serialized by the caller).
     *
     * @param runId
     *            the run id
     * @param ruleDefsJson
     *            the serialized map
     * @throws IOException
     *             on write failure
     */
    public void persistRuleDefs(String runId, String ruleDefsJson) throws IOException
    {
        Files.writeString(ruleDefsFileFor(runId), ruleDefsJson, StandardCharsets.UTF_8);
    }


    /** Whether a rule-definitions file exists for the run. */
    public boolean hasRuleDefs(String runId)
    {
        return Files.exists(ruleDefsFileFor(runId));
    }


    /**
     * The {@code { source, expanded }} definition for one rule, or {@code null} when the run has no
     * rule-definitions file or no entry for the id. For an expanded id (e.g. {@code CG0001-AGE})
     * with no own entry, falls back to the base id ({@code CG0001}) for the {@code source}.
     *
     * @param runId
     *            the run id
     * @param coreId
     *            the row's CORE id (expanded id for generated rows)
     * @return the definition node, or {@code null} when neither the id nor its base resolves
     */
    public @Nullable JsonNode ruleDefinition(String runId, String coreId)
    {
        if (!hasRuleDefs(runId))
        {
            return null;
        }
        JsonNode map;
        try
        {
            map = mapper.readTree(Files.readAllBytes(ruleDefsFileFor(runId)));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to read rule definitions for run " + runId, e);
        }
        JsonNode direct = map.get(coreId);
        if (direct != null && !direct.isNull())
        {
            return direct;
        }
        int dash = coreId.lastIndexOf('-');
        if (dash > 0)
        {
            JsonNode base = map.get(coreId.substring(0, dash));
            if (base != null && !base.isNull())
            {
                return base;
            }
        }
        return null;
    }


    /** A page of all finding rows (projected from the v2 {@code Findings} array). */
    public FindingsPage findingsPage(String runId, int firstIndex, int count)
    {
        return findingsPage(runId, null, null, null, firstIndex, count);
    }


    /**
     * A page of finding rows, optionally scoped to one file, domain and/or rule. A blank
     * ({@code null}/empty) filter matches everything; supplied filters must match the finding's
     * {@code dataset} (file name), {@code domain} and {@code core_id} exactly. {@code total} is the
     * full count of matching rows, regardless of the page window.
     *
     * <p>
     * Projected from the run's <b>v2</b> combined-finding document through the typed
     * {@link V2Findings} façade, because the v1 {@code Issue_Details} section is the frozen
     * Python-compatible surface and EC-40 D6 forbids the record key from reaching it.
     * </p>
     *
     * <p>
     * <b>Row set.</b> v2 is finding-shaped and keeps dataset-scoped zero-row findings (engine /
     * dataset-load errors) as <em>virtual findings</em> with an empty {@code rows[]}, which v1
     * drops. Flattening finding-by-row therefore reproduces v1's row set exactly: a finding with no
     * rows contributes no rows, which is the honest answer for a row-shaped API (plan decision
     * D34). {@code ReportStoreFindingsProjectionTest} pins that against a report rendered both
     * ways.
     * </p>
     *
     * <p>
     * <b>Order.</b> v2 sorts findings by {@code (core_id, dataset)} and v1 stably sorts its already
     * finding-grouped rows by the same key, so the flattened sequence — and hence every page window
     * — is identical.
     * </p>
     */
    public FindingsPage findingsPage(String runId, @Nullable String file, @Nullable String domain,
            @Nullable String coreId, int firstIndex, int count)
    {
        List<V2Findings.Finding> findings = v2Findings(runId);
        int total = 0;
        for (V2Findings.Finding f : findings)
        {
            if (matches(f, file, domain, coreId))
            {
                total += f.rows().size();
            }
        }
        int from = Math.min(Math.max(firstIndex, 0), total);
        int to = count <= 0 ? from : Math.min(from + count, total);
        List<FindingRow> items = new ArrayList<>(Math.max(0, to - from));
        int index = 0;
        for (V2Findings.Finding f : findings)
        {
            if (index >= to)
            {
                break;
            }
            if (!matches(f, file, domain, coreId))
            {
                continue;
            }
            for (V2Findings.Row r : f.rows())
            {
                if (index >= from && index < to)
                {
                    items.add(toFindingRow(f, r));
                }
                index++;
            }
        }
        return new FindingsPage(total, from, items.size(), items);
    }


    /** Whether a finding passes all three (blank = match-all) filters. */
    private static boolean matches(V2Findings.Finding f, @Nullable String file,
            @Nullable String domain, @Nullable String coreId)
    {
        return matches(f.dataset(), file) && matches(f.domain(), domain)
                && matches(f.coreId(), coreId);
    }


    /** Whether {@code value} equals {@code filter}, or the filter is blank (matches all). */
    private static boolean matches(@Nullable String value, @Nullable String filter)
    {
        return filter == null || filter.isEmpty() || filter.equals(value);
    }


    /** The conformance metadata block ({@code Conformance_Details}). */
    public ConformanceResponse conformance(String runId)
    {
        JsonNode c = tree(runId).path("Conformance_Details");
        // The two degradation-basis keys are absent (null) on a healthy run by design — see
        // ReportAssembler.buildConformanceDetails. Projecting them here is what keeps the REST
        // surface from repeating the Fix #369 gap where only the JSON told the truth.
        return new ConformanceResponse(text(c, "Standard"), text(c, "Sub_Standard"),
                text(c, "Version"), text(c, "TIG_Use_Case"), text(c, "CT_Version"),
                text(c, "Define_XML_Version"), text(c, "CORE_Engine_Version"),
                text(c, "Total_Runtime"), text(c, "Issue_Limit_Per_Rule"),
                text(c, "Issue_Limit_Per_Dataset"), text(c, "Report_Generation"),
                text(c, "Library_Metadata_Basis"), text(c, "Dictionary_Basis"));
    }


    /** The per-dataset metadata ({@code Dataset_Details}). */
    public List<DatasetDetail> datasets(String runId)
    {
        List<DatasetDetail> out = new ArrayList<>();
        for (JsonNode n : array(tree(runId), "Dataset_Details"))
        {
            out.add(new DatasetDetail(text(n, "filename"), text(n, "label"), text(n, "path"),
                    text(n, "modification_date"), doubleOrNull(n.get("size_kb")),
                    longOrNull(n.get("length")), text(n, "domain"), intOrNull(n.get("columns"))));
        }
        return out;
    }


    /** The per-rule outcomes ({@code Rules_Report}). */
    public List<RuleReportRow> rules(String runId)
    {
        List<RuleReportRow> out = new ArrayList<>();
        for (JsonNode n : array(tree(runId), "Rules_Report"))
        {
            out.add(new RuleReportRow(text(n, "core_id"), text(n, "version"),
                    text(n, "cdisc_rule_id"), text(n, "fda_rule_id"), text(n, "message"),
                    text(n, "status")));
        }
        return out;
    }


    /** Drop a run's report and execution log from both the cache and the disk. */
    public void delete(String runId)
    {
        cache.remove(runId);
        findingsCache.remove(runId);
        try
        {
            Files.deleteIfExists(fileFor(runId));
            Files.deleteIfExists(fileForV2(runId));
            Files.deleteIfExists(logFileFor(runId));
            Files.deleteIfExists(xlsxFileFor(runId));
            Files.deleteIfExists(ruleDefsFileFor(runId));
            Files.deleteIfExists(runRecordFileFor(runId));
        }
        catch (IOException e)
        {
            LOG.warn("Failed to delete report/log file for run {}", runId, e);
        }
    }

    // ------------------------------------------------------------------
    // Run records — the persistent run index, stored as run-<runId>.json
    // ------------------------------------------------------------------


    /**
     * Persist a run's state record so the run index survives a restart. Best-effort: a failure is
     * logged (the run still functions in memory), never thrown — it must not change run outcome.
     */
    public void persistRunRecord(RunRecord record)
    {
        try
        {
            // Write via a sibling .tmp + atomic move so a crash mid-write never leaves a
            // half-written record (which would drop the run on reload). The .tmp sibling is ignored
            // by the run-record scan (it does not end in ".json").
            Path target = runRecordFileFor(record.id());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(record), StandardCharsets.UTF_8);
            try
            {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException e)
        {
            LOG.warn("Failed to persist run record for {}", record.id(), e);
        }
    }


    /** All persisted run records under the reports dir; corrupt/unreadable ones are skipped. */
    public List<RunRecord> loadAllRunRecords()
    {
        List<RunRecord> out = new ArrayList<>();
        if (!Files.isDirectory(dir))
        {
            return out;
        }
        try (var paths = Files.list(dir))
        {
            List<Path> recordFiles = paths.filter(p ->
            {
                Path name = p.getFileName();
                return name != null && name.toString().startsWith("run-")
                        && name.toString().endsWith(".json");
            }).toList();
            for (Path p : recordFiles)
            {
                try
                {
                    out.add(mapper.readValue(Files.readString(p, StandardCharsets.UTF_8),
                            RunRecord.class));
                }
                catch (IOException | RuntimeException e)
                {
                    LOG.warn("Failed to read run record {}", p, e);
                }
            }
        }
        catch (IOException e)
        {
            LOG.warn("Failed to scan {} for run records", dir, e);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------


    private JsonNode tree(String runId)
    {
        Cached<JsonNode> entry = cache.get(runId);
        if (entry != null && !isExpired(entry))
        {
            return entry.value();
        }
        cache.remove(runId);
        try
        {
            JsonNode node = mapper.readTree(Files.readAllBytes(fileFor(runId)));
            cache.put(runId, new Cached<>(node, Instant.now()));
            return node;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to read report for run " + runId, e);
        }
        catch (JacksonException e)
        {
            throw new UncheckedIOException("Corrupt report file for run " + runId,
                    new IOException(e));
        }
    }


    /**
     * The run's v2 findings, parsed through the strict {@link V2Findings} façade and cached. A
     * shape the façade does not recognise raises {@link V2ReportFormatException} rather than
     * yielding empty columns.
     */
    private List<V2Findings.Finding> v2Findings(String runId)
    {
        Cached<List<V2Findings.Finding>> entry = findingsCache.get(runId);
        if (entry != null && !isExpired(entry))
        {
            return entry.value();
        }
        findingsCache.remove(runId);
        List<V2Findings.Finding> findings;
        try
        {
            findings = V2Findings.parse(mapper.readTree(Files.readAllBytes(fileForV2(runId))));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to read v2 report for run " + runId, e);
        }
        catch (JacksonException e)
        {
            throw new UncheckedIOException("Corrupt v2 report file for run " + runId,
                    new IOException(e));
        }
        findingsCache.put(runId, new Cached<>(findings, Instant.now()));
        return findings;
    }


    /**
     * Flattens one v2 finding row into the row-shaped DTO: the finding-level rule / dataset /
     * message / executability / variables and its record-key schema, plus the row-level identity,
     * values and key.
     */
    private static FindingRow toFindingRow(V2Findings.Finding f, V2Findings.Row r)
    {
        return new FindingRow(f.coreId(), f.dataset(), r.usubjid(), r.row(), r.seq(),
                f.executability(), f.message(), f.variables(), r.values(), f.domain(),
                f.location().keyVariables(), r.keys(), f.location().keySource());
    }


    private static Iterable<JsonNode> array(JsonNode root, String field)
    {
        JsonNode node = root.path(field);
        return node.isArray() ? node : List.of();
    }


    private static @Nullable String text(JsonNode parent, String field)
    {
        JsonNode f = parent.get(field);
        return f == null || f.isNull() ? null : f.asString();
    }


    private static @Nullable Integer intOrNull(@Nullable JsonNode n)
    {
        return n != null && n.isNumber() ? n.intValue() : null;
    }


    private static @Nullable Double doubleOrNull(@Nullable JsonNode n)
    {
        return n != null && n.isNumber() ? n.doubleValue() : null;
    }


    private static @Nullable Long longOrNull(@Nullable JsonNode n)
    {
        return n != null && n.isNumber() ? n.longValue() : null;
    }


    private boolean isExpired(Cached<?> entry)
    {
        return ttl != null && !ttl.isZero() && Instant.now().isAfter(entry.loadedAt().plus(ttl));
    }


    /** Size in bytes of the stored report file, or {@code null} when it does not exist. */
    public @Nullable Long reportSize(String runId)
    {
        return sizeOrNull(fileFor(runId));
    }


    /** Size in bytes of the stored v2 report file, or {@code null} when it does not exist. */
    public @Nullable Long reportV2Size(String runId)
    {
        return sizeOrNull(fileForV2(runId));
    }


    /** Size in bytes of the stored execution-log file, or {@code null} when it does not exist. */
    public @Nullable Long logSize(String runId)
    {
        return sizeOrNull(logFileFor(runId));
    }


    private static @Nullable Long sizeOrNull(Path path)
    {
        try
        {
            return Files.exists(path) ? Files.size(path) : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }


    private Path fileFor(String runId)
    {
        return dir.resolve("report-" + runId + ".json");
    }


    private Path fileForV2(String runId)
    {
        return dir.resolve("report-v2-" + runId + ".json");
    }


    private Path logFileFor(String runId)
    {
        return dir.resolve("log-" + runId + ".json");
    }


    private Path xlsxFileFor(String runId)
    {
        return dir.resolve("report-" + runId + ".xlsx");
    }


    private Path ruleDefsFileFor(String runId)
    {
        return dir.resolve("rules-" + runId + ".json");
    }


    private Path runRecordFileFor(String runId)
    {
        return dir.resolve("run-" + runId + ".json");
    }


    @PreDestroy
    void shutdown()
    {
        cache.clear();
        findingsCache.clear();
        if (dirIsTemp && dir != null)
        {
            deleteRecursively(dir);
        }
    }


    private static void deleteRecursively(Path root)
    {
        if (!Files.exists(root))
        {
            return;
        }
        try (var paths = Files.walk(root))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException e)
                {
                    LOG.warn("Failed to delete {}", p, e);
                }
            });
        }
        catch (IOException e)
        {
            LOG.warn("Failed to walk {} for deletion", root, e);
        }
    }

    /** A cached parse of one run's artifact, with the instant it was loaded (for the TTL). */
    private record Cached<V>(V value, Instant loadedAt)
    {
    }


    /** Access-ordered LRU map; evicts the least-recently-used entry beyond the cap. */
    private static final class LruCache<V> extends LinkedHashMap<String, Cached<V>>
    {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        LruCache(int maxEntries)
        {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }


        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached<V>> eldest)
        {
            return size() > maxEntries;
        }
    }
}
