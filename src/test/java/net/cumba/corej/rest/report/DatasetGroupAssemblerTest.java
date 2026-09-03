package net.cumba.corej.rest.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DatasetGroupAssembler}, focused on the multi-domain-per-file join. */
class DatasetGroupAssemblerTest
{

    private static RunLog.DomainLogEntry domainLog(String domain, String fileName, String ruleId)
    {
        return new RunLog.DomainLogEntry(domain, fileName, 1, 1, 1, 55, List.of(),
                List.of(new RunLog.RuleExecutionEntry(ruleId, "g1", "EXECUTED", 1, 17, null, null,
                        null, null)));
    }


    @Test
    void oneFileWithTwoDomains_splitsIntoTwoSubGroupsEachComplete()
    {
        // study.xlsx holds DM and AE: report dataset details carry the domain; the log carries
        // per-domain rule outcomes and the file-level size/hash.
        List<DatasetDetail> datasets = List.of(
                new DatasetDetail("study.xlsx", "Demographics", "/p", "2024-01-01", 5.0, 100L, "DM",
                        10),
                new DatasetDetail("study.xlsx", "Adverse Events", "/p", "2024-01-01", 5.0, 320L,
                        "AE", 12));
        RunLog log = new RunLog("run-1", "sess", "SUCCEEDED", null, null, null, 1.0, null,
                List.of(new RunLog.FileManifestEntry("study.xlsx", 4096L, "deadbeef")),
                List.of(domainLog("DM", "study.xlsx", "R-DM"),
                        domainLog("AE", "study.xlsx", "R-AE")),
                2, null, List.of());

        List<FileGroup> files = DatasetGroupAssembler.assemble(datasets, log);

        assertThat(files).singleElement().satisfies(file ->
        {
            assertThat(file.fileName()).isEqualTo("study.xlsx");
            assertThat(file.sizeBytes()).isEqualTo(4096L);
            assertThat(file.sha256()).isEqualTo("deadbeef");
            assertThat(file.modificationDate()).isEqualTo("2024-01-01");
            assertThat(file.domains()).hasSize(2);

            DomainGroup dm = file.domains().get(0);
            assertThat(dm.domain()).isEqualTo("DM");
            assertThat(dm.rows()).isEqualTo(100L);
            assertThat(dm.columns()).isEqualTo(10);
            // Per-dataset wall clock + per-rule runtime carried onto the dataset-groups view.
            assertThat(dm.runtimeMillis()).isEqualTo(55);
            assertThat(dm.rules()).extracting(RunLog.RuleExecutionEntry::coreId)
                    .containsExactly("R-DM");
            assertThat(dm.rules().get(0).runtimeMillis()).isEqualTo(17);

            DomainGroup ae = file.domains().get(1);
            assertThat(ae.domain()).isEqualTo("AE");
            assertThat(ae.rows()).isEqualTo(320L);
            assertThat(ae.columns()).isEqualTo(12);
            assertThat(ae.rules()).extracting(RunLog.RuleExecutionEntry::coreId)
                    .containsExactly("R-AE");
        });
    }


    @Test
    void fileInManifestWithoutDatasets_appearsWithEmptyDomains()
    {
        RunLog log = new RunLog("run-1", "sess", "SUCCEEDED", null, null, null, 1.0, null,
                List.of(new RunLog.FileManifestEntry("define.xml", 200L, "cafe")), List.of(), 0,
                null, List.of());

        List<FileGroup> files = DatasetGroupAssembler.assemble(List.of(), log);

        assertThat(files).singleElement().satisfies(file ->
        {
            assertThat(file.fileName()).isEqualTo("define.xml");
            assertThat(file.domains()).isEmpty();
        });
    }
}
