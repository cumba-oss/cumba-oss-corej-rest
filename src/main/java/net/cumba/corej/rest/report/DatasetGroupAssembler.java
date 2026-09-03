package net.cumba.corej.rest.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Assembles the two-level file → domain groups ({@link FileGroup}s) from a run's report dataset
 * details and its execution log. Files (and their order) come from the log's file manifest;
 * per-domain metadata comes from the report's dataset details (now domain-aware); rule outcomes
 * (including the per-rule violation count) come from the log's per-domain entries.
 *
 * <p>
 * Findings are deliberately <b>not</b> embedded — they are the only unbounded part of a run and are
 * fetched on demand, scoped and paged, via {@code GET /api/checks/{id}/findings}. Pure mapping, no
 * I/O. The join is exact because the engine writes the domain onto every dataset detail, so a
 * multi-dataset file (Excel sheets, multi-member XPT) splits cleanly into one sub-group per domain.
 * </p>
 */
public final class DatasetGroupAssembler
{

    private DatasetGroupAssembler()
    {
    }


    /**
     * Builds the grouped results.
     *
     * @param datasets
     *            the report's per-(file, domain) dataset details
     * @param log
     *            the run's execution log (file manifest + per-domain rule outcomes), or
     *            {@code null}
     * @return the assembled file → domain groups, in file-manifest order
     */
    public static List<FileGroup> assemble(List<DatasetDetail> datasets, @Nullable RunLog log)
    {
        Map<String, FileAcc> files = new LinkedHashMap<>();

        // 1. Seed files (and their order) from the log's file manifest, so every uploaded file is a
        // main group even if it produced no validated dataset.
        if (log != null)
        {
            for (RunLog.FileManifestEntry f : log.files())
            {
                if (f.filename() != null)
                {
                    files.computeIfAbsent(f.filename(), FileAcc::new).apply(f.sizeBytes(),
                            f.sha256());
                }
            }
        }

        // 2. Per-domain metadata from the report's dataset details.
        for (DatasetDetail d : datasets)
        {
            String filename = d.filename();
            if (filename == null)
            {
                continue; // A dataset detail with no file name cannot be grouped under a file.
            }
            FileAcc file = files.computeIfAbsent(filename, FileAcc::new);
            file.modificationDate(d.modificationDate());
            DomainAcc domain = file.domain(domainOf(d.domain(), filename));
            domain.label = d.label();
            domain.rows = d.length();
            domain.columns = d.columns();
        }

        // 3. Per-domain rule outcomes from the execution log.
        if (log != null)
        {
            for (RunLog.DomainLogEntry e : log.domains())
            {
                String domain = e.domain();
                String fileName = e.fileName();
                if (domain == null || fileName == null)
                {
                    continue;
                }
                FileAcc file = files.computeIfAbsent(fileName, FileAcc::new);
                DomainAcc acc = file.domain(domain);
                acc.rules = e.ruleExecutions();
                acc.runtimeMillis = e.runtimeMillis();
            }
        }

        List<FileGroup> out = new ArrayList<>(files.size());
        for (FileAcc f : files.values())
        {
            out.add(f.toFileGroup());
        }
        return out;
    }


    /** The domain name, falling back to the file name when the engine left it unset. */
    private static String domainOf(@Nullable String domain, String fileName)
    {
        return domain != null && !domain.isEmpty() ? domain : fileName;
    }

    /** Mutable file-level accumulator with an insertion-ordered map of its domains. */
    private static final class FileAcc
    {

        private final String fileName;

        private @Nullable Long sizeBytes;

        private @Nullable String sha256;

        private @Nullable String modificationDate;

        private final Map<String, DomainAcc> domains = new LinkedHashMap<>();

        FileAcc(String aFileName)
        {
            fileName = aFileName;
        }


        void apply(@Nullable Long aSizeBytes, @Nullable String aSha256)
        {
            if (aSizeBytes != null)
            {
                sizeBytes = aSizeBytes;
            }
            if (aSha256 != null)
            {
                sha256 = aSha256;
            }
        }


        void modificationDate(@Nullable String aDate)
        {
            if (modificationDate == null && aDate != null)
            {
                modificationDate = aDate;
            }
        }


        DomainAcc domain(String name)
        {
            return domains.computeIfAbsent(name, DomainAcc::new);
        }


        FileGroup toFileGroup()
        {
            List<DomainGroup> groups = new ArrayList<>(domains.size());
            for (DomainAcc d : domains.values())
            {
                groups.add(d.toDomainGroup());
            }
            return new FileGroup(fileName, sizeBytes, sha256, modificationDate, groups);
        }
    }


    /** Mutable domain-level accumulator. */
    private static final class DomainAcc
    {

        private final String domain;

        private @Nullable String label;

        private @Nullable Long rows;

        private @Nullable Integer columns;

        private List<RunLog.RuleExecutionEntry> rules = List.of();

        private long runtimeMillis = -1;

        DomainAcc(String aDomain)
        {
            domain = aDomain;
        }


        DomainGroup toDomainGroup()
        {
            return new DomainGroup(domain, label, rows, columns, runtimeMillis, rules);
        }
    }
}
