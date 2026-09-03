package net.cumba.corej.rest.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.cumba.corej.core.report.ReportAssembler;
import net.cumba.corej.core.report.ValidationReportBuilder;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.rest.run.CheckRun;
import net.cumba.corej.rest.run.CheckRunRequest;
import net.cumba.datatable.report.ValidationReport;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RunLogAssembler}. */
class RunLogAssemblerTest
{

    private static CheckRunRequest request()
    {
        return new CheckRunRequest(null, null, null, null, null, null, null, null, null, null,
                List.of(), null, List.of("cdisc-sdtmig-3-4"));
    }


    private static StudyValidationResult resultWith(DatasetExecutionSummary... summaries)
    {
        ValidationReport report = new ValidationReportBuilder().build();
        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard("sdtmig").version("3-4").build();
        return new StudyValidationResult(report, conformance, List.of(), List.of(), 7, 2.5,
                List.of(summaries));
    }


    @Test
    void assemblesDomainsAndTotalsForSucceededRun()
    {
        CheckRun run = new CheckRun("r1", "s1", request());
        run.markRunning();
        run.addLogLine("INFO Selected 10 rule(s) for validation");
        StudyValidationResult result = resultWith(new DatasetExecutionSummary("DM", "dm.xpt", 3, 10,
                2, 42, List.of(new DatasetExecutionSummary.RuleError("CORE-1", "boom")),
                List.of(new DatasetExecutionSummary.RuleExecution("CG0001-AGE", "uuid-1",
                        "EXECUTED", 1, 17, "AGE", null, "age rule", "Fully Executable"),
                        new DatasetExecutionSummary.RuleExecution("CORE-1", "uuid-2", "ERROR", 0,
                                -1, null, "boom", null, null))));
        run.markSucceeded(result);

        RunLog log = RunLogAssembler.assemble(run, null, result);

        assertThat(log.status()).isEqualTo("SUCCEEDED");
        assertThat(log.runId()).isEqualTo("r1");
        assertThat(log.sessionId()).isEqualTo("s1");
        assertThat(log.files()).isEmpty();
        assertThat(log.domains()).hasSize(1);
        RunLog.DomainLogEntry domain = log.domains().get(0);
        assertThat(domain.domain()).isEqualTo("DM");
        assertThat(domain.rulesExecuted()).isEqualTo(3);
        assertThat(domain.rulesTotal()).isEqualTo(10);
        assertThat(domain.findings()).isEqualTo(2);
        assertThat(domain.errors()).hasSize(1);
        assertThat(domain.errors().get(0).ruleId()).isEqualTo("CORE-1");
        // Per-dataset wall clock and per-rule runtime map straight through (-1 passes through).
        assertThat(domain.runtimeMillis()).isEqualTo(42);
        assertThat(domain.ruleExecutions()).hasSize(2);
        assertThat(domain.ruleExecutions().get(0).coreId()).isEqualTo("CG0001-AGE");
        assertThat(domain.ruleExecutions().get(0).runtimeMillis()).isEqualTo(17);
        assertThat(domain.ruleExecutions().get(1).runtimeMillis()).isEqualTo(-1);
        assertThat(domain.ruleExecutions().get(0).expandedFor()).isEqualTo("AGE");
        assertThat(domain.ruleExecutions().get(0).executability()).isEqualTo("Fully Executable");
        assertThat(domain.ruleExecutions().get(1).executability()).isNull();
        assertThat(domain.ruleExecutions().get(1).status()).isEqualTo("ERROR");
        assertThat(domain.ruleExecutions().get(1).notExecutedReason()).isEqualTo("boom");
        assertThat(log.logLines()).containsExactly("INFO Selected 10 rule(s) for validation");
        assertThat(log.totalFindings()).isEqualTo(7);
        assertThat(log.totalRuntimeSeconds()).isEqualTo(2.5);
        assertThat(log.configuration().rulesPackages()).containsExactly("cdisc-sdtmig-3-4");
    }


    @Test
    void partialLogForFailedRunHasNoDomains()
    {
        CheckRun run = new CheckRun("r2", "s2", request());
        run.markRunning();
        run.markFailed("boom");

        RunLog log = RunLogAssembler.assemble(run, null, null);

        assertThat(log.status()).isEqualTo("FAILED");
        assertThat(log.failureMessage()).isEqualTo("boom");
        assertThat(log.domains()).isEmpty();
        assertThat(log.totalFindings()).isNull();
        assertThat(log.totalRuntimeSeconds()).isNull();
    }
}
