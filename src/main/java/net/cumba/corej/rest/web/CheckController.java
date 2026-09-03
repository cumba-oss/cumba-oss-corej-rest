package net.cumba.corej.rest.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.cumba.corej.rest.report.ConformanceResponse;
import net.cumba.corej.rest.report.DatasetGroupAssembler;
import net.cumba.corej.rest.report.FileGroup;
import net.cumba.corej.rest.report.FindingsPage;
import net.cumba.corej.rest.report.ReportStore;
import net.cumba.corej.rest.report.RuleReportRow;
import net.cumba.corej.rest.report.RunArtifacts;
import net.cumba.corej.rest.report.RunLog;
import net.cumba.corej.rest.run.CheckRun;
import net.cumba.corej.rest.run.CheckRunRequest;
import net.cumba.corej.rest.run.RunConflictException;
import net.cumba.corej.rest.run.RunExecutor;
import net.cumba.corej.rest.run.RunRegistry;
import net.cumba.corej.rest.run.RunStatus;
import net.cumba.corej.rest.run.StudyValidationCheckRunner;
import net.cumba.corej.rest.session.Session;
import net.cumba.corej.rest.session.SessionRegistry;
import net.cumba.corej.rest.web.dto.CheckStatusResponse;
import net.cumba.corej.rest.web.dto.LiveLogResponse;
import net.cumba.corej.rest.web.dto.StartCheckResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Check-run endpoints: start a run for a session, poll/await its status, and cancel it. Runs are
 * placed on a global bounded queue and execute across all sessions up to
 * {@code corej.runs.max-parallel} at once.
 */
@RestController
@Tag(name = "checks", description = "Validation runs over a session's files")
public class CheckController
{

    /** Upper bound on the status long-poll wait, to keep request threads from parking too long. */
    static final int MAX_WAIT_SECONDS = 60;

    /** Default findings page size when {@code count} is omitted. */
    static final int DEFAULT_PAGE_SIZE = 200;

    /** Hard cap on a single findings page, bounding the response size. */
    static final int MAX_PAGE_SIZE = 1000;

    private final SessionRegistry sessions;

    private final RunRegistry runs;

    private final RunExecutor executor;

    private final ReportStore reports;

    public CheckController(SessionRegistry sessions, RunRegistry runs, RunExecutor executor,
            ReportStore reports)
    {
        this.sessions = sessions;
        this.runs = runs;
        this.executor = executor;
        this.reports = reports;
    }


    @PostMapping("/api/sessions/{id}/checks")
    @Operation(summary = "Start a check run for a session",
            description = "Queues a validation run over the session's staged files; returns immediately.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "201", description = "Run queued"),
            @ApiResponse(responseCode = "400",
                    description = "Invalid request / unknown referenced file", content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content)
    })
    public ResponseEntity<StartCheckResponse> start(@PathVariable("id") String sessionId,
            @RequestBody CheckRunRequest request)
    {
        Session session = sessions.get(sessionId);
        StudyValidationCheckRunner.validate(request, session);
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), sessionId, request);
        executor.submit(run);
        return ResponseEntity.created(URI.create("/api/checks/" + run.id()))
                .body(new StartCheckResponse(run.id()));
    }


    @GetMapping("/api/checks")
    @Operation(summary = "List check runs",
            description = "All runs (most recently created first), or only those of a session when "
                    + "sessionId is given. Each row carries its owning session's current name.")
    public List<CheckStatusResponse> list(
            @RequestParam(name = "sessionId", required = false) String sessionId)
    {
        return runs.all().stream().filter(r -> sessionId == null || sessionId.equals(r.sessionId()))
                .sorted(Comparator.comparing(CheckRun::createdAt).reversed()
                        .thenComparing(CheckRun::id))
                .map(r -> CheckStatusResponse.from(r, sessions.findName(r.sessionId()))).toList();
    }


    @GetMapping("/api/checks/{id}/status")
    @Operation(summary = "Get a check run's status and progress",
            description = "With waitSeconds > 0, long-polls up to that many seconds (capped at "
                    + MAX_WAIT_SECONDS
                    + ") for the run to reach a terminal state before returning the current snapshot.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "Current status"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content)
    })
    public CheckStatusResponse status(@PathVariable("id") String runId,
            @RequestParam(name = "waitSeconds", required = false) Integer waitSeconds)
    {
        CheckRun run = runs.get(runId);
        if (waitSeconds != null && waitSeconds > 0 && !run.status().isTerminal())
        {
            executor.awaitTerminal(run, Math.min(waitSeconds, MAX_WAIT_SECONDS));
        }
        return CheckStatusResponse.from(run, sessions.findName(run.sessionId()));
    }


    @PostMapping("/api/checks/{id}/cancel")
    @Operation(summary = "Cancel a check run",
            description = "Pulls a queued run before it starts; signals a running run to abort cleanly.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "202", description = "Cancellation requested"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content)
    })
    public ResponseEntity<Void> cancel(@PathVariable("id") String runId)
    {
        executor.requestCancel(runs.get(runId));
        return ResponseEntity.accepted().build();
    }


    @GetMapping("/api/checks/{id}/dataset-groups")
    @Operation(summary = "Get a run's results grouped by file then domain",
            description = "Two-level grouping: each uploaded file (with size, modification date and "
                    + "SHA-256) contains its domains/datasets (with label, rows, columns), each "
                    + "carrying that domain's rule outcomes (incl. the per-rule violation count). "
                    + "Findings are not embedded — fetch them on demand, scoped and paged, via the "
                    + "findings endpoint.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The grouped results"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409", description = "Run did not succeed",
                    content = @Content)
    })
    public List<FileGroup> datasetGroups(@PathVariable("id") String runId)
    {
        String id = requireSucceeded(runId).id();
        RunLog log = reports.hasLog(id) ? reports.loadLog(id) : null;
        return DatasetGroupAssembler.assemble(reports.datasets(id), log);
    }


    @GetMapping("/api/checks/{id}/findings")
    @Operation(summary = "Page through a run's findings, optionally scoped to a dataset and rule",
            description = "Finding rows, optionally filtered to one file, domain and/or rule (core "
                    + "id). Used by the results drill-down to lazily load the findings of a "
                    + "specific rule in a specific dataset. Always reports the full matching "
                    + "total. Projected from the run's v2 combined-finding report, so each row "
                    + "also carries the EC-40 record key when the run resolved one; the fields are "
                    + "omitted entirely under the default corej.findingKeys=off.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "A page of finding rows"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409", description = "Run did not succeed",
                    content = @Content)
    })
    public FindingsPage findings(@PathVariable("id") String runId,
            @RequestParam(name = "file", required = false) @Nullable String file,
            @RequestParam(name = "domain", required = false) @Nullable String domain,
            @RequestParam(name = "coreId", required = false) @Nullable String coreId,
            @RequestParam(name = "firstIndex", defaultValue = "0") int firstIndex,
            @RequestParam(name = "count", defaultValue = "" + DEFAULT_PAGE_SIZE) int count)
    {
        return reports.findingsPage(requireSucceeded(runId).id(), file, domain, coreId, firstIndex,
                Math.min(count, MAX_PAGE_SIZE));
    }


    @GetMapping("/api/checks/{id}/artifacts")
    @Operation(summary = "Get sizes + generation time of a run's downloadable artifacts",
            description = "JSON report size, execution-log size (null if absent) and the run's "
                    + "finish time. The Excel report is rendered on demand, so it has no stored size.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The artifact sizes"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409", description = "Run did not succeed",
                    content = @Content)
    })
    public RunArtifacts artifacts(@PathVariable("id") String runId)
    {
        CheckRun run = requireSucceeded(runId);
        String id = run.id();
        Long logBytes = reports.hasLog(id) ? reports.logSize(id) : null;
        Long xlsxBytes = reports.hasXlsx(id) ? reports.xlsxSize(id) : null;
        Long reportV2Bytes = reports.hasReportV2(id) ? reports.reportV2Size(id) : null;
        String generatedAt = run.finishedAt() != null ? run.finishedAt().toString() : null;
        return new RunArtifacts(reports.reportSize(id), reportV2Bytes, xlsxBytes, logBytes,
                generatedAt);
    }


    @GetMapping("/api/checks/{id}/conformance")
    @Operation(summary = "Get a run's conformance metadata")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The conformance block"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409", description = "Run did not succeed",
                    content = @Content)
    })
    public ConformanceResponse conformance(@PathVariable("id") String runId)
    {
        return reports.conformance(requireSucceeded(runId).id());
    }


    @GetMapping("/api/checks/{id}/rules")
    @Operation(summary = "Get a run's per-rule outcomes")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The rules report"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409", description = "Run did not succeed",
                    content = @Content)
    })
    public List<RuleReportRow> rules(@PathVariable("id") String runId)
    {
        return reports.rules(requireSucceeded(runId).id());
    }


    @GetMapping(path = "/api/checks/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a succeeded run's full JSON report",
            description = "The assembled v1 report document (conformance + dataset details + "
                    + "rules + findings) — byte-identical to the CLI's report file.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The full report"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "Run did not succeed / not yet complete", content = @Content)
    })
    public ResponseEntity<String> report(@PathVariable("id") String runId)
    {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(reports.rawReport(requireSucceeded(runId).id()));
    }


    @GetMapping(path = "/api/checks/{id}/report-v2", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a succeeded run's v2 combined-finding JSON report",
            description = "The v2 report: one object per finding (carrying its location plus its "
                    + "rows) instead of v1's per-row expansion — byte-identical to the CLI's "
                    + "--output-format json2 (.v2.json) file.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The full v2 report"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "Run did not succeed / not yet complete", content = @Content)
    })
    public ResponseEntity<String> reportV2(@PathVariable("id") String runId)
    {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(reports.rawReportV2(requireSucceeded(runId).id()));
    }

    /** MIME type for an {@code .xlsx} workbook. */
    static final String XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @GetMapping(path = "/api/checks/{id}/report", produces = XLSX_MEDIA_TYPE)
    @Operation(summary = "Download a succeeded run's report as an XLSX workbook",
            description = "Content-negotiated variant of the report endpoint: request it with "
                    + "Accept: " + XLSX_MEDIA_TYPE + ". Structurally and content-identical to the "
                    + "CLI's --output-format xlsx output, rendered from the same stored report.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The XLSX report"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409",
                    description = "Run did not succeed / not yet complete", content = @Content)
    })
    public ResponseEntity<byte[]> reportXlsx(@PathVariable("id") String runId)
    {
        String id = requireSucceeded(runId).id();
        byte[] workbook = reports.xlsxReport(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .header("Content-Disposition",
                        "attachment; filename=\"CORE-Report-" + id + ".xlsx\"")
                .body(workbook);
    }


    @GetMapping("/api/checks/{id}/log")
    @Operation(summary = "Get a run's execution log",
            description = "Files (name + size + SHA-256), the configuration, per-domain rules "
                    + "executed/total + findings, and any errors. Available for terminal runs that "
                    + "started (SUCCEEDED / FAILED / CANCELLED-after-start).")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The execution log"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409", description = "No execution log for this run",
                    content = @Content)
    })
    public RunLog log(@PathVariable("id") String runId)
    {
        CheckRun run = runs.get(runId);
        if (!reports.hasLog(run.id()))
        {
            throw new RunConflictException(
                    "run " + runId + " is " + run.status() + "; no execution log available");
        }
        return reports.loadLog(run.id());
    }


    @GetMapping(path = "/api/checks/{id}/log/file", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Download a run's raw execution-log file",
            description = "The verbatim, pretty-printed log-<id>.json file, served as an attachment.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The raw log file"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409", description = "No execution log for this run",
                    content = @Content)
    })
    public ResponseEntity<String> logFile(@PathVariable("id") String runId)
    {
        CheckRun run = runs.get(runId);
        if (!reports.hasLog(run.id()))
        {
            throw new RunConflictException(
                    "run " + runId + " is " + run.status() + "; no execution log available");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"log-" + run.id() + ".json\"")
                .body(reports.rawLog(run.id()));
    }


    @GetMapping("/api/checks/{id}/log/lines")
    @Operation(summary = "Get a run's live execution-log lines",
            description = "The run's log lines accumulated so far (INFO/WARN/DEBUG and any "
                    + "exception stack trace), from the given offset. Available while the run is "
                    + "RUNNING; poll with the returned nextFrom to stream new lines. The structured "
                    + "/log endpoint remains the complete view once the run is terminal.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The live log lines"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content)
    })
    public LiveLogResponse logLines(@PathVariable("id") String runId,
            @RequestParam(name = "from", defaultValue = "0") int from)
    {
        CheckRun run = runs.get(runId);
        CheckRun.LogTail tail = run.logTailFrom(from);
        return new LiveLogResponse(tail.lines(), tail.nextFrom(), run.status().isTerminal());
    }


    @GetMapping(path = "/api/checks/{id}/rules/{coreId}/definition",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get the run-scoped source + expanded definition of one rule",
            description = "{ source, expanded } — source is the run's raw rule object, expanded is the "
                    + "synthetic generated rule that ran (null for non-generated rules). Either may "
                    + "be null; 404 when neither is found.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "The rule definition"),
            @ApiResponse(responseCode = "404", description = "Unknown run or no definition",
                    content = @Content),
            @ApiResponse(responseCode = "409", description = "Run did not succeed",
                    content = @Content)
    })
    public ResponseEntity<JsonNode> ruleDefinition(@PathVariable("id") String runId,
            @PathVariable("coreId") String coreId)
    {
        String id = requireSucceeded(runId).id();
        JsonNode def = reports.ruleDefinition(id, coreId);
        if (def == null)
        {
            throw new net.cumba.corej.rest.run.RunNotFoundException(
                    "no definition for rule " + coreId + " in run " + runId);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(def);
    }


    /** Resolve a run that must have a retrievable report (404 unknown, 409 if not SUCCEEDED). */
    private CheckRun requireSucceeded(String runId)
    {
        CheckRun run = runs.get(runId);
        if (run.status() != RunStatus.SUCCEEDED || !reports.hasReport(run.id()))
        {
            throw new RunConflictException(
                    "run " + runId + " is " + run.status() + "; no report available");
        }
        return run;
    }


    @DeleteMapping("/api/checks/{id}")
    @Operation(summary = "Delete a check run and its report",
            description = "Rejected (409) while the run is PENDING or RUNNING — cancel it first.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "204", description = "Run deleted"),
            @ApiResponse(responseCode = "404", description = "Unknown run", content = @Content),
            @ApiResponse(responseCode = "409", description = "Run still in flight",
                    content = @Content)
    })
    public ResponseEntity<Void> delete(@PathVariable("id") String runId)
    {
        CheckRun run = runs.get(runId);
        if (run.status().isInFlight())
        {
            throw new RunConflictException(
                    "run " + runId + " is " + run.status() + "; cancel it before deleting");
        }
        runs.remove(run.id());
        reports.delete(run.id());
        return ResponseEntity.noContent().build();
    }
}
