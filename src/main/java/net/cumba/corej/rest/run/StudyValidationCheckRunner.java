package net.cumba.corej.rest.run;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.metadata.pickle.ProductKeyResolver;
import net.cumba.corej.core.report.LibraryValidator;
import net.cumba.corej.core.run.ProgressListener;
import net.cumba.corej.core.run.StudyValidationParams;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.core.run.StudyValidationService;
import net.cumba.corej.rest.session.Session;
import net.cumba.corej.rest.session.SessionFilenames;
import net.cumba.datatable.io.FileInfo;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.local.LocalDataTableManager;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Production {@link CheckRunner}: bridges a {@link CheckRunRequest} + its session onto the engine's
 * {@link StudyValidationService}. The data library is the session's staging directory (mirroring
 * the CLI's {@code -d}); file-valued request fields resolve to files inside that directory. The
 * run's progress counters and cancellation flag are wired to the service via a
 * {@link ProgressListener} and a cancellation {@code BooleanSupplier}.
 */
@Component
public class StudyValidationCheckRunner implements CheckRunner
{

    private final net.cumba.corej.rest.session.SessionRegistry sessions;

    private final net.cumba.corej.rest.config.CorejProperties properties;

    private final RunLogDebugCapture debugCapture;

    public StudyValidationCheckRunner(net.cumba.corej.rest.session.SessionRegistry sessions,
            net.cumba.corej.rest.config.CorejProperties properties, RunLogDebugCapture debugCapture)
    {
        this.sessions = sessions;
        this.properties = properties;
        this.debugCapture = debugCapture;
    }


    @Override
    public StudyValidationResult run(CheckRun run) throws IOException
    {
        Session session = sessions.get(run.sessionId());
        // The task decorator propagates this run's log-capture sink onto every engine worker
        // thread, so engine INFO/WARN logged off the run-worker thread is still attributed here.
        return new StudyValidationService()
                .validate(buildParams(run, session, properties.getEngine().getMaxErrorsPerRule(),
                        debugCapture.contextPropagator(), configuredRunStore(properties)));
    }


    /**
     * The metadata store this deployment explicitly names, or {@code null} when it names none.
     *
     * <p>
     * ⭐ Review finding F2. {@code corej.cache-seed.target-store} is the <b>only</b> place this
     * service's configuration can name a store file, and it is by construction the same file the
     * run must read: a deployment that seeds B and then validates against an ambient
     * {@code CDISC_METADATA_STORE}=A is the reported defect, and it was silent — the seeding log
     * says B, every run uses A, nothing warns. Handing it to the engine on
     * {@code StudyValidationParams.metadataStore()} — the explicit top tier of
     * {@code StoreMetadataProviderFactory.resolveConfiguredFile} — is what closes that.
     * </p>
     *
     * <p>
     * ⚠ Deliberately <b>not</b> gated on {@code corej.cache-seed.enabled}: a deployment that
     * provisions the store out of band (baked into the image, mounted as a volume) and only
     * <em>points</em> at it would otherwise keep the identical inversion in a narrower form. An
     * operator who names a store file means that file, whether or not this process is the one that
     * writes it. Deployments that name none (the default, blank) are unaffected: the engine's own
     * environment-then-property resolution is reached exactly as before.
     * </p>
     *
     * @param aProperties
     *            the bound {@code corej.*} configuration
     * @return the absolute store path, or {@code null} when unconfigured
     */
    static @Nullable String configuredRunStore(
            net.cumba.corej.rest.config.CorejProperties aProperties)
    {
        String configured = aProperties.getCacheSeed().getTargetStore();
        if (configured == null || configured.isBlank())
        {
            return null;
        }
        // Absolutised for the same reason CacheSeedInitializer absolutises its seed target: the
        // engine resolves the path against its own working directory, not the config file's.
        return java.nio.file.Path.of(configured).toAbsolutePath().toString();
    }


    /**
     * Validate a request against its session before a run is created. Checks required fields, that
     * every referenced file is a bare name actually present in the session, and that every
     * {@code referenceDataFilenames} entry names a format some registered library supplier can
     * actually open.
     *
     * @throws BadRunRequestException
     *             on any violation
     */
    public static void validate(CheckRunRequest req, Session session)
    {
        validate(req, session, new LocalDataTableManager());
    }


    /**
     * The body of {@link #validate(CheckRunRequest, Session)}, with the manager that publishes the
     * accepted reference-data formats supplied explicitly.
     *
     * <p>
     * The accepted set comes from {@link IDataTableManager#getSupportedDataLibraryInfos()} — the
     * <em>library</em> half of the file-format SPI, not the table half — so it tracks the
     * registered {@code ILibrarySupplier}s and stays correct when one is added or dropped. A
     * literal extension list here would rot silently, which is exactly the failure this check
     * exists to prevent; {@code StudyValidationCheckRunnerTest} pins that by injecting a manager
     * publishing a format this codebase does not have and requiring the decision to follow it.
     * </p>
     *
     * @param req
     *            the request to validate
     * @param session
     *            the session its file-valued fields resolve against
     * @param manager
     *            the manager whose published library formats decide what reference data is
     *            acceptable
     * @throws BadRunRequestException
     *             on any violation
     */
    static void validate(CheckRunRequest req, Session session, IDataTableManager manager)
    {
        // ⛔ R3 / R5 — 'standard' and 'version' are gone; a run must NAME ITS RULES. Either
        // arm satisfies it: a bundled package by short name, or uploaded rule files.
        if (req.rulesPackages().isEmpty() && req.rulesFilenames().isEmpty())
        {
            throw new BadRunRequestException("'rulesPackages' is required (or 'rulesFilenames' to "
                    + "run uploaded rule files). Rules are selected by package; the package "
                    + "declares the CDISC Library standard the run resolves metadata against.");
        }
        // V4 (review R-7): an uploaded rules file declares no CDISC Library standard and
        // matches no manifest entry, so a request selecting rules ONLY by 'rulesFilenames'
        // and naming no 'metadataProducts' is certain to fail — asynchronously, after the
        // 201. Reject it here like every other malformed request, naming the field that
        // fixes it.
        if (req.rulesPackages().isEmpty() && !req.rulesFilenames().isEmpty()
                && req.metadataProducts().isEmpty())
        {
            throw new BadRunRequestException("'metadataProducts' is required when rules are "
                    + "selected only by 'rulesFilenames': an uploaded rules file declares no "
                    + "CDISC Library standard, so the run would have no metadata to resolve "
                    + "against. Name the product(s) to use (e.g. adam/adamig-1-3), or select "
                    + "a bundled package in 'rulesPackages'.");
        }
        if (req.ruleThreads() != null && req.ruleThreads() < 1)
        {
            throw new BadRunRequestException("'ruleThreads' must be >= 1");
        }
        if (!req.includeRules().isEmpty() && !req.excludeRules().isEmpty())
        {
            throw new BadRunRequestException(
                    "only one of 'includeRules' / 'excludeRules' may be set, not both");
        }
        // Plan C §3.4: NOTICE sits OUTSIDE the ladder — "a report-only kind, authored by no rule"
        // (Severity's own javadoc) — so it is not a threshold. The field binds the whole enum, so
        // without this it would be accepted here while the CLI (CdiscValidate) and the .cdt
        // #runLevel directive (RuleTestCdt) both reject it. It is not inert either: NOTICE holds
        // the highest ordinal, so it admits every rung and acts as a second, undocumented spelling
        // for enabling the INFO rung that the default threshold deliberately excludes.
        if (req.severityThreshold() == net.cumba.datatable.report.Severity.NOTICE)
        {
            throw new BadRunRequestException(
                    "'severityThreshold' expects one of Reject, Error, Warning, Info — got: Notice");
        }
        // An unresolvable metadataProducts token is a REQUEST error: reject it here with a
        // 400 like every other malformed field, not asynchronously on the run worker after a
        // 201. The resolution below is the same choke point buildParams later calls with the
        // identical inputs (resolveMetadataProducts), so the two sites cannot disagree in
        // logic. An omitted/empty list stays legal whenever a rules package is selected -
        // the packages' declared standards supply the products (R7); the file-only shape
        // was already rejected above (V4).
        try
        {
            resolveMetadataProducts(req);
        }
        catch (IllegalArgumentException e)
        {
            String message = e.getMessage();
            throw new BadRunRequestException(
                    message != null ? message : "invalid 'metadataProducts'");
        }
        requireSessionFile(session, req.defineXmlFilename(), "defineXmlFilename");
        for (String name : req.rulesFilenames())
        {
            requireSessionFile(session, name, "rulesFilenames");
        }
        if (req.referenceDataFilenames() != null)
        {
            List<FileInfo> libraryFormats = manager.getSupportedDataLibraryInfos();
            for (String name : req.referenceDataFilenames())
            {
                requireSessionFile(session, name, "referenceDataFilenames");
                requireLibraryFormat(name, libraryFormats);
            }
        }
    }


    /**
     * The single resolution path for {@link CheckRunRequest#metadataProducts()}: tokens resolve
     * against the server's configured product catalogue (Phase 7b: pickle-cache keys unioned with
     * the CDISC Library API's product list; full-key tokens resolve without either). Called by
     * {@link #validate(CheckRunRequest, Session)} - to reject a bad token with a 400 before a run
     * exists - and by {@link #buildParams(CheckRun, Session)} - to hand the engine the resolved
     * keys. One method, same inputs, so the two sites cannot diverge.
     *
     * @param req
     *            the request whose {@code metadataProducts} tokens are resolved
     * @return the resolved {@code standards/...} cache keys, in declaration order; empty when the
     *         request declares none
     * @throws IllegalArgumentException
     *             when any token fails to resolve; the message names every offending token
     */
    static List<String> resolveMetadataProducts(CheckRunRequest req)
    {
        return ProductKeyResolver.resolveAllConfigured(req.metadataProducts(), null, null);
    }


    /** Map a (validated) request + session onto engine params. */
    static StudyValidationParams buildParams(CheckRun run, Session session)
    {
        return buildParams(run, session, null);
    }


    static StudyValidationParams buildParams(CheckRun run, Session session,
            @Nullable Integer defaultMaxErrorsPerRule)
    {
        return buildParams(run, session, defaultMaxErrorsPerRule, UnaryOperator.identity());
    }


    static StudyValidationParams buildParams(CheckRun run, Session session,
            @Nullable Integer defaultMaxErrorsPerRule, UnaryOperator<Runnable> taskDecorator)
    {
        return buildParams(run, session, defaultMaxErrorsPerRule, taskDecorator, null);
    }


    /**
     * @param metadataStore
     *            the store this deployment explicitly names ({@link #configuredRunStore}), or
     *            {@code null} to let the engine resolve one from {@code CDISC_METADATA_STORE} /
     *            {@code cdisc.metadata.store}. A non-null value outranks both (finding F2).
     */
    static StudyValidationParams buildParams(CheckRun run, Session session,
            @Nullable Integer defaultMaxErrorsPerRule, UnaryOperator<Runnable> taskDecorator,
            @Nullable String metadataStore)
    {
        CheckRunRequest req = run.request();
        IDataTableManager manager = new LocalDataTableManager();
        StudyValidationParams.Builder b = StudyValidationParams.builder().manager(manager)
                .dataLibrary(session.directory().toString()).useCase(req.useCase())
                // -mp tokens are resolved against the server's configured product catalogue
                // via the same choke point validate() already ran, so a token that passed
                // validation resolves identically here.
                .metadataProducts(resolveMetadataProducts(req)).defineVersion(req.defineVersion())
                .includeRules(req.includeRules()).excludeRules(req.excludeRules())
                // datasetFilter() is normalised to a non-null (possibly empty) list in
                // CheckRunRequest's canonical constructor; an empty set is the builder's
                // "no filter" sentinel, matching the prior null-clears-it semantics.
                .datasetFilter(new LinkedHashSet<>(req.datasetFilter()))
                .ruleThreads(req.ruleThreads() != null ? req.ruleThreads() : 1)
                .maxErrorsPerRule(req.maxErrorsPerRule() != null ? req.maxErrorsPerRule()
                        : defaultMaxErrorsPerRule)
                // Plan C §3.4: null = the engine default (Warning). There is deliberately no
                // service-level default for it — the threshold decides which findings exist, so it
                // is stated by the caller who will read the report, never ambiently configured.
                .severityThreshold(req.severityThreshold())
                // F2: the deployment's explicitly named store (configuredRunStore) travels on the
                // params channel — the factory's top tier, above CDISC_METADATA_STORE. The seed
                // target and the run's store are the same file by construction; seeding one and
                // reading another is the defect this closes. null → the engine resolves its own.
                .metadataStore(metadataStore).progressListener(progressListenerFor(run))
                .cancellation(run::isCancelRequested)
                // No logListener: the RunLogDebugCapture appender now captures every engine line
                // (INFO/WARN included), so a listener here would double-record emit-routed lines.
                // The decorator carries this run's capture sink onto the engine's worker threads.
                .taskDecorator(taskDecorator);
        // Rule narrowing is derived from the include/exclude lists: empty → all rules,
        // a non-empty include/exclude switches the builder to FILTERED automatically.
        if (req.defineXmlFilename() != null)
        {
            b.defineXmlPath(session.directory().resolve(req.defineXmlFilename()).toString());
        }
        if (!req.rulesPackages().isEmpty())
        {
            b.rulesPackages(req.rulesPackages());
        }
        if (!req.rulesFilenames().isEmpty())
        {
            b.rulesFiles(req.rulesFilenames().stream()
                    .map(n -> session.directory().resolve(n).toString()).toList());
        }
        if (req.referenceDataFilenames() != null && !req.referenceDataFilenames().isEmpty())
        {
            b.referenceData(req.referenceDataFilenames().stream()
                    .map(n -> session.directory().resolve(n).toString()).toList());
        }
        return b.build();
    }


    private static ProgressListener progressListenerFor(CheckRun run)
    {
        return new ProgressListener()
        {

            @Override
            public void onDatasetsDiscovered(int totalDatasets)
            {
                run.setTotalDatasets(totalDatasets);
            }


            @Override
            public void onDatasetCompleted(int processed, int totalDatasets, String domain,
                    int datasetFindings)
            {
                run.setProcessedDatasets(processed);
                run.addDatasetFindings(datasetFindings);
            }


            @Override
            public void onRuleExecuted(LibraryValidator.RuntimeEntry entry)
            {
                // Count only rules that actually ran. The runtime listener also fires for rules
                // the generator filtered out by scope — one entry per (source rule × dataset) —
                // and counting those inflated "rules executed" by roughly an order of magnitude
                // once the generation-time scope-skip audit was repaired (it had been silently
                // inert while file-loaded rules carried no id). An ERROR rule is still counted:
                // it executed and failed, unlike a SKIPPED one, which never ran.
                if (entry.status() != RuleExecutionStatus.SKIPPED)
                {
                    run.incrementRulesExecuted();
                }
            }
        };
    }


    private static void requireSessionFile(Session session, String name, String field)
    {
        if (name == null)
        {
            return;
        }
        // F-rest-04: one shared definition of "bare file name" (SessionFilenames), the same one
        // every uploaded name passed through in SessionRegistry. This copy used to check only
        // blank and the two path separators, so its safety against '..' and a NUL byte was a
        // property of the registry's exact-match lookup rather than of its own code.
        String violation = SessionFilenames.violation(name);
        if (violation != null)
        {
            throw new BadRunRequestException(
                    "'" + field + "' must be a bare file name (" + violation + "): " + name);
        }
        if (!session.hasFile(name))
        {
            throw new BadRunRequestException(
                    "'" + field + "' references a file not in the session: " + name);
        }
    }


    /**
     * Rejects a {@code referenceDataFilenames} entry whose format no registered library supplier
     * can open.
     *
     * <p>
     * Without this the request is accepted and the run dies mid-flight with a raw
     * {@code IOException: Can not open … as library!} — a 500 on a request the server had already
     * said yes to, and with no diagnosis of which file was at fault. The asymmetry it detects is
     * deliberate rather than a gap: a library is a <em>container</em> of tables, so the
     * single-table formats (CSV, Parquet, Dataset-JSON) have a table provider but no library
     * supplier, and the honest container form is a folder.
     * </p>
     *
     * <p>
     * ⛔ <b>Parquet in particular is a ruled category error, not a missing feature.</b> Since wave
     * 38 this module depends on {@code cumba-oss-datatable-provider-parquet}, so {@code .parquet}
     * <em>datasets</em> load — yet that module registers only an {@code IProviderSupplier} and no
     * {@code ILibrarySupplier}, because a single {@code .parquet} file has no member namespace for
     * a container to enumerate. So a {@code .parquet} entry in {@code referenceDataFilenames} is
     * still refused here, deliberately. The same wave added
     * {@code cumba-oss-datatable-provider-{sas,xlsx}}, which <em>do</em> register library
     * suppliers, widening the accepted set to {@code cdt, dblib, xls, xlsx, xml, xpt} —
     * automatically, with no change to this method, because the set is read from the SPI. Pinned by
     * {@code StudyValidationCheckRunnerTest#publishedLibraryFormatsIncludeXptXlsAndXlsx} and
     * {@code #parquetReferenceDataIsStillRejectedAfterTheProviderWasAdded}.
     * </p>
     *
     * <p>
     * ⚠ The check is on the <em>format</em>, not the content: a file whose extension is supported
     * but whose bytes are not a readable library still fails at load time. Only the "no supplier
     * could ever open this" case is decidable here, and that is the one the 500 came from.
     * </p>
     */
    private static void requireLibraryFormat(@Nullable String name, List<FileInfo> libraryFormats)
    {
        if (name == null || FileInfo.findByFileName(name, libraryFormats) != null)
        {
            return;
        }
        throw new BadRunRequestException("'referenceDataFilenames' names '" + name + "', whose "
                + describeFormat(name) + " cannot be opened as a data library: no library supplier"
                + " is registered for it. Supported reference-data formats: "
                + describeSupportedFormats(libraryFormats)
                + ". 'referenceDataFilenames' is deprecated — prefer 'datasetFilter', which leaves"
                + " a non-matching dataset loaded as reference data, visible to rules but never"
                + " validated.");
    }


    /** {@code format 'csv'} for {@code DM.csv}; {@code missing file extension} for a bare name. */
    private static String describeFormat(String name)
    {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "missing file extension"
                : "format '" + name.substring(dot + 1).toLowerCase(Locale.ROOT) + "'";
    }


    /**
     * The published library extensions, lower-cased, de-duplicated and sorted. Blank-extension
     * entries are dropped: they are the folder library, and a reference-data entry is by
     * construction a bare file name inside the session, never a directory.
     */
    private static String describeSupportedFormats(List<FileInfo> libraryFormats)
    {
        String formats = libraryFormats.stream().map(FileInfo::getFileExtension)
                .filter(e -> !isBlank(e)).map(e -> e.toLowerCase(Locale.ROOT)).distinct().sorted()
                .collect(Collectors.joining(", "));
        return formats.isEmpty() ? "(none registered)" : formats;
    }


    private static boolean isBlank(String s)
    {
        return s == null || s.isBlank();
    }
}
