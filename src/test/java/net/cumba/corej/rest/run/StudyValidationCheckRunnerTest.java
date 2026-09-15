package net.cumba.corej.rest.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.report.LibraryValidator;
import net.cumba.corej.core.run.StudyValidationParams;
import net.cumba.corej.core.run.StudyValidationParams.RuleSelectionMode;
import net.cumba.corej.rest.session.Session;
import net.cumba.corej.rest.session.SessionRegistry;
import net.cumba.datatable.io.FileInfo;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.local.LocalDataTableManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tests the request→params mapping and request validation of {@link StudyValidationCheckRunner}.
 */
@SpringBootTest
class StudyValidationCheckRunnerTest
{

    /**
     * ⚑ Plan 2 (R3/R5) — {@code standard} / {@code version} left the request, and a run must now
     * NAME ITS RULES. Every request below that is meant to reach its own subject carries this, so
     * validate() gets past the "'rulesPackages' is required" guard rather than failing on it.
     */
    private static final List<String> RULE_PACKAGES = List.of("cdisc-sdtmig-3-4");

    /** Copies {@code req} with {@link #RULE_PACKAGES} as its rule selection. */
    private static CheckRunRequest withRulePackages(CheckRunRequest req)
    {
        return new CheckRunRequest(req.useCase(), req.defineXmlFilename(),
                req.referenceDataFilenames(), req.defineVersion(), req.includeRules(),
                req.excludeRules(), req.rulesFilenames(), req.datasetFilter(), req.ruleThreads(),
                req.maxErrorsPerRule(), req.metadataProducts(), req.severityThreshold(),
                RULE_PACKAGES);
    }

    @TempDir
    static Path stagingBase;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("corej.sessions.dir", () -> stagingBase.resolve("sessions").toString());
    }

    @Autowired
    private SessionRegistry sessions;

    private Session session;

    @BeforeEach
    void setUp() throws Exception
    {
        session = sessions.create();
        sessions.addFile(session.id(), "DM.csv", bytes("x"));
        sessions.addFile(session.id(), "define.xml", bytes("<x/>"));
        sessions.addFile(session.id(), "extra-rules.json", bytes("{}"));
        // Reference-data fixtures. Since wave 38 registered provider-{sas,xlsx,parquet} in this
        // module's pom, three of these are openable as a library (.cdt, .xpt, .xlsx) and four are
        // not — and the split is a property of the SPI, not of the file names:
        // ⚠ .xls is in the UNOPENABLE group, deliberately: legacy binary Excel was never readable
        // by this engine (ExcelProviderSupplier's javadoc — excel-streaming-reader cannot read it),
        // and it is published by neither the table nor the library half. Kept as a fixture so
        // legacyXlsIsNotAnExcelFormatThisEngineReads below can pin that it stays rejected.
        // openable → a supplier implements ILibrarySupplier for the extension
        // unopenable→ .parquet and .ndjson have a *table* provider but no library supplier, and
        // "refnoext" resolves to no format at all.
        // ⚠ .parquet is the interesting one: adding provider-parquet made .parquet *datasets*
        // readable without making .parquet reference data acceptable. See
        // parquetIsReadableAsADatasetButStillRejectedAsReferenceData below.
        sessions.addFile(session.id(), "ref.cdt", bytes("x"));
        sessions.addFile(session.id(), "ref.xpt", bytes("x"));
        sessions.addFile(session.id(), "ref.xls", bytes("x"));
        sessions.addFile(session.id(), "ref.xlsx", bytes("x"));
        sessions.addFile(session.id(), "ref.parquet", bytes("x"));
        sessions.addFile(session.id(), "ref.ndjson", bytes("x"));
        sessions.addFile(session.id(), "ref.zzzlib", bytes("x"));
        sessions.addFile(session.id(), "refnoext", bytes("x"));
    }


    /** A request whose only non-trivial content is its {@code referenceDataFilenames} list. */
    private static CheckRunRequest withReferenceData(String... names)
    {
        return new CheckRunRequest(null, null, List.of(names), null, null, null, null, null, null,
                null, List.of(), null, RULE_PACKAGES);
    }


    private static ByteArrayInputStream bytes(String s)
    {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }


    /** A minimal request declaring the given {@code metadataProducts} tokens. */
    private static CheckRunRequest withMetadataProducts(String... tokens)
    {
        return new CheckRunRequest(null, null, null, null, null, null, null, null, null, null,
                List.of(tokens), null, List.of("cdisc-adamig-1-3"));
    }

    // ------------------------------------------------------------------
    // metadataProducts: validate-first (F2) + catalogue-branch resolution (F3b)
    // ------------------------------------------------------------------


    /**
     * Review finding F2: an unresolvable {@code metadataProducts} token used to sail through
     * {@code validate} (201 Created) and fail asynchronously on the run worker. It must be rejected
     * up front with the same {@link BadRunRequestException} shape as every other malformed field.
     * The token below resolves in NO catalogue configuration: with a configured catalogue it
     * matches no key, and with none it is a bare suffix the verbatim branch refuses.
     */
    @Test
    void validate_unresolvableMetadataProductToken_isRejectedBeforeARunExists()
    {
        assertThatThrownBy(() -> StudyValidationCheckRunner
                .validate(withMetadataProducts("adamig-9-9"), session))
                        .isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("adamig-9-9");
    }


    /** A blank token is typed-but-meaningless and must be rejected, not silently dropped. */
    @Test
    void validate_blankMetadataProductToken_isRejected()
    {
        assertThatThrownBy(
                () -> StudyValidationCheckRunner.validate(withMetadataProducts(""), session))
                        .isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("--metadata-products");
    }


    /**
     * {@code metadataProducts} is OPTIONAL — an omitted (null -&gt; empty) list stays legal at
     * validation time; the selected packages' declared standards supply the products. Pins that the
     * F2 check above cannot creep into rejecting the empty list. ⚑ Plan 2 (R5) deleted the other
     * half of the old contract (an omitted list defaulting to the -s/-v product).
     */
    @Test
    void validate_omittedMetadataProducts_staysLegal()
    {
        // 9-arg convenience constructor: no metadataProducts field at all (defaults to empty).
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null, null,
                null);
        StudyValidationCheckRunner.validate(withRulePackages(req), session); // must not throw
    }


    /**
     * Review finding F3b: every prior test used a full-form token that resolves to the SAME string
     * through the catalogue branch and through the empty-catalogue verbatim-passthrough branch of
     * {@code ProductKeyResolver} - no assertion failed if the catalogue returned empty
     * unconditionally. This test is branch-sensitive: the token is a BARE product id, so the
     * verbatim branch cannot resolve it at all, and its resolved form
     * ({@code standards/adam/adamig-1-3}) differs from the token. If the catalogue came back empty,
     * buildParams would throw instead of resolving.
     *
     * <p>
     * Requires the build environment's configured metadata catalogue — since cache 8b-1 the unified
     * metadata store ({@code CDISC_METADATA_STORE} / {@code cdisc.metadata.store}); skipped, not
     * red, where none is configured.
     * </p>
     */
    @Test
    void buildParams_bareTokenResolvesThroughTheConfiguredCatalogue_notVerbatim()
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory
                        .resolveConfiguredFile(null) != null,
                "requires a configured unified metadata store (CDISC_METADATA_STORE / "
                        + "cdisc.metadata.store)");

        StudyValidationParams p = StudyValidationCheckRunner
                .buildParams(run(withMetadataProducts("adamig-1-3")), session);

        assertThat(p.metadataProducts()).containsExactly("standards/adam/adamig-1-3");
    }


    /**
     * Review finding F2 (second half): the REST surface named its store in
     * {@code corej.cache-seed.target-store}, <b>seeded</b> that file at startup, and then handed
     * the engine nothing — so a deployment with {@code target-store=B} and an ambient
     * {@code CDISC_METADATA_STORE=A} seeded B and validated against A forever, silently
     * ({@code CacheSeedInitializer.publishDefaultIfUnconfigured} publishes nothing once anything
     * else is configured, and the system property it publishes into is the lowest tier anyway). The
     * fix routes the configured store onto {@code StudyValidationParams.metadataStore()}, the
     * explicit top tier of {@code StoreMetadataProviderFactory.resolveConfiguredFile}.
     *
     * <p>
     * ⚠ The ambient store installed here is the <b>system property</b>, not the environment
     * variable: a JVM cannot set its own environment, so the property is the only ambient tier a
     * test can install — and it ranks one <em>below</em> {@code CDISC_METADATA_STORE}. Losing to it
     * before the fix therefore implies losing to the environment variable a fortiori, so proving
     * the weaker case proves the reported one. (Same argument as the engine-side test in
     * {@code cumba-oss-corej}.)
     * </p>
     */
    @Test
    void buildParams_configuredTargetStoreOutranksAnAmbientStore(@TempDir Path dir) throws Exception
    {
        Path ambient = dir.resolve("ambient-store.zip");
        Path named = dir.resolve("named-store.zip");
        new net.cumba.corej.core.metadata.store.MetadataStoreWriter().publishedCtPackages(List.of())
                .productCatalogue(List.of()).write(ambient);
        new net.cumba.corej.core.metadata.store.MetadataStoreWriter().publishedCtPackages(List.of())
                .productCatalogue(List.of()).write(named);

        net.cumba.corej.rest.config.CorejProperties props = new net.cumba.corej.rest.config.CorejProperties();
        props.getCacheSeed().setTargetStore(named.toString());

        String key = net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory.STORE_PROPERTY;
        String saved = System.getProperty(key);
        try
        {
            System.setProperty(key, ambient.toString());
            StudyValidationParams p = StudyValidationCheckRunner.buildParams(
                    run(withRulePackages(new CheckRunRequest(null, null, null, null, null, null,
                            null, null, null))),
                    session, null, java.util.function.UnaryOperator.identity(),
                    StudyValidationCheckRunner.configuredRunStore(props));

            // The engine resolves the run's store through exactly this call; asserting on it
            // rather than on the raw field pins the PRECEDENCE, not just the plumbing.
            assertThat(net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory
                    .resolveConfiguredFile(p.metadataStore()))
                            .as("the store named by corej.cache-seed.target-store must outrank "
                                    + "an ambient one")
                            .isEqualTo(named.toAbsolutePath());
        }
        finally
        {
            if (saved == null)
            {
                System.clearProperty(key);
            }
            else
            {
                System.setProperty(key, saved);
            }
        }
    }


    private static CheckRunRequest full(List<String> include, String rulesFile, Integer threads)
    {
        return new CheckRunRequest("uc", "define.xml", List.of("DM.csv"), "2.1", include, null,
                rulesFile == null ? null : List.of(rulesFile), List.of("DM"), threads, null,
                List.of("tig/1-0/adam"), null, RULE_PACKAGES);
    }


    private CheckRun run(CheckRunRequest req)
    {
        return new CheckRun("r1", session.id(), req);
    }


    @Test
    void mapsCoreFieldsAndSessionDirectory()
    {
        StudyValidationParams p = StudyValidationCheckRunner
                .buildParams(run(full(null, null, null)), session);

        assertThat(p.manager()).isNotNull();
        assertThat(p.dataLibrary()).isEqualTo(session.directory().toString());
        assertThat(p.rulesPackages()).isEqualTo(RULE_PACKAGES);
        // -mp tokens are resolved onto full standards/... cache keys (full-form tokens
        // resolve with or without a configured metadata store).
        assertThat(p.metadataProducts()).containsExactly("standards/tig/1-0/adam");
        assertThat(p.useCase()).isEqualTo("uc");
        assertThat(p.defineVersion()).isEqualTo("2.1");
        assertThat(p.defineXmlPath())
                .isEqualTo(session.directory().resolve("define.xml").toString());
        assertThat(p.referenceData())
                .containsExactly(session.directory().resolve("DM.csv").toString());
        assertThat(p.datasetFilter()).containsExactly("DM");
        assertThat(p.ruleThreads()).isEqualTo(1);
    }


    @Test
    void mapsUploadedRulesFile()
    {
        StudyValidationParams p = StudyValidationCheckRunner
                .buildParams(run(full(null, "extra-rules.json", 4)), session);
        assertThat(p.rulesFiles())
                .containsExactly(session.directory().resolve("extra-rules.json").toString());
        assertThat(p.ruleThreads()).isEqualTo(4);
    }


    @Test
    void includeRulesImpliesFilteredMode()
    {
        StudyValidationParams p = StudyValidationCheckRunner
                .buildParams(run(full(List.of("CORE-000001"), null, null)), session);
        assertThat(p.ruleSelectionMode()).isEqualTo(RuleSelectionMode.FILTERED);
        assertThat(p.includeRules()).containsExactly("CORE-000001");
    }


    @Test
    void cancellationSupplierReflectsRunFlag()
    {
        CheckRun run = run(full(null, null, null));
        StudyValidationParams p = StudyValidationCheckRunner.buildParams(run, session);
        assertThat(p.cancellation().getAsBoolean()).isFalse();
        run.requestCancel();
        assertThat(p.cancellation().getAsBoolean()).isTrue();
    }


    /** A runtime entry with the given outcome; only {@code status} matters to the listener. */
    private static LibraryValidator.RuntimeEntry entry(RuleExecutionStatus status)
    {
        return new LibraryValidator.RuntimeEntry("DM", "dm.csv", 10L, 3, "CORE-000001", 1L, status,
                0);
    }


    @Test
    void progressListenerFeedsRunCounters()
    {
        CheckRun run = run(full(null, null, null));
        StudyValidationParams p = StudyValidationCheckRunner.buildParams(run, session);
        p.progressListener().onDatasetsDiscovered(5);
        p.progressListener().onDatasetCompleted(1, 5, "DM", 3);
        p.progressListener().onDatasetCompleted(2, 5, "AE", 4);
        p.progressListener().onRuleExecuted(entry(RuleExecutionStatus.EXECUTED));
        p.progressListener().onRuleExecuted(entry(RuleExecutionStatus.EXECUTED));
        assertThat(run.totalDatasets()).isEqualTo(5);
        assertThat(run.processedDatasets()).isEqualTo(2);
        assertThat(run.rulesExecuted()).isEqualTo(2);
        assertThat(run.findingsSoFar()).isEqualTo(7);
    }


    /**
     * "Rules executed" must count only rules that actually ran.
     *
     * <p>
     * The runtime listener also fires once per (source rule × dataset) for rules the generator
     * filtered out by scope. Those entries became real when the generation-time scope-skip audit
     * was repaired — it had been silently inert while file-loaded rules carried no {@code id} — and
     * counting them inflated the live progress figure by roughly an order of magnitude. An ERROR
     * rule still counts: it ran and failed, unlike a SKIPPED one, which never ran.
     * </p>
     */
    @Test
    void rulesExecutedCountsExecutedAndErrorButNotSkipped()
    {
        CheckRun run = run(full(null, null, null));
        StudyValidationParams p = StudyValidationCheckRunner.buildParams(run, session);

        p.progressListener().onRuleExecuted(entry(RuleExecutionStatus.EXECUTED));
        p.progressListener().onRuleExecuted(entry(RuleExecutionStatus.ERROR));
        p.progressListener().onRuleExecuted(entry(RuleExecutionStatus.SKIPPED));
        p.progressListener().onRuleExecuted(entry(RuleExecutionStatus.SKIPPED));
        p.progressListener().onRuleExecuted(entry(RuleExecutionStatus.SKIPPED));

        assertThat(run.rulesExecuted()).isEqualTo(2);
    }


    @Test
    void buildParamsDefaultsTaskDecoratorToIdentity()
    {
        // The static overloads used by these tests supply no propagator → identity (no-op),
        // so engine/CLI behaviour is unchanged for non-REST callers.
        StudyValidationParams p = StudyValidationCheckRunner
                .buildParams(run(full(null, null, null)), session);
        Runnable r = () ->
        {
        };
        assertThat(p.taskDecorator().apply(r)).isSameAs(r);
    }


    @Test
    void buildParamsThreadsSuppliedTaskDecorator()
    {
        java.util.concurrent.atomic.AtomicBoolean wrapped = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.UnaryOperator<Runnable> decorator = task -> () ->
        {
            wrapped.set(true);
            task.run();
        };
        StudyValidationParams p = StudyValidationCheckRunner
                .buildParams(run(full(null, null, null)), session, null, decorator);
        p.taskDecorator().apply(() ->
        {
        }).run();
        assertThat(wrapped).isTrue();
    }


    /**
     * ⛔ Plan 2 (R3/R5) — this replaces {@code validateRejectsMissingStandard} and
     * {@code validateRejectsMissingVersion}: those two fields no longer exist. The required-field
     * guard they occupied is now "a run must NAME ITS RULES" — {@code rulesPackages} or
     * {@code rulesFilenames}, and a request carrying neither is the same 400.
     */
    @Test
    void validateRejectsARequestNamingNoRules()
    {
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null, null,
                null);
        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(req, session))
                .isInstanceOf(BadRunRequestException.class).hasMessageContaining("rulesPackages");
    }


    /**
     * V4 (review R-7): an uploaded rules file declares no CDISC Library standard, so a request
     * selecting rules ONLY by {@code rulesFilenames} and naming no {@code metadataProducts} used to
     * return 201 and fail asynchronously on the run worker. It must be a 400 at validation time,
     * naming the field that fixes it.
     */
    @Test
    void validateRejectsRulesFilenamesOnlyWithoutMetadataProducts()
    {
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null,
                List.of("extra-rules.json"), null, null);
        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(req, session))
                .isInstanceOf(BadRunRequestException.class)
                .hasMessageContaining("'metadataProducts' is required")
                .hasMessageContaining("declares no CDISC Library standard");
    }


    /** The other half of the V4 guard: naming the products makes the file-only shape legal. */
    @Test
    void validateAcceptsRulesFilenamesOnlyWithMetadataProducts()
    {
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null,
                List.of("extra-rules.json"), null, null, null, List.of("tig/1-0/adam"), null,
                List.of());
        StudyValidationCheckRunner.validate(req, session); // must not throw
    }


    @Test
    void validateRejectsBadRuleThreads()
    {
        CheckRunRequest req = withRulePackages(
                new CheckRunRequest(null, null, null, null, null, null, null, null, 0));
        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(req, session))
                .isInstanceOf(BadRunRequestException.class);
    }


    @Test
    void validateRejectsNoticeAsSeverityThreshold()
    {
        // NOTICE binds (the field is the whole Severity enum) but is not a threshold: it sits
        // outside the ladder, is authored by no rule, and is rejected by the CLI and .cdt too.
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null, null, 1,
                null, List.of(), net.cumba.datatable.report.Severity.NOTICE, RULE_PACKAGES);
        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(req, session))
                .isInstanceOf(BadRunRequestException.class)
                .hasMessageContaining("severityThreshold");
    }


    @Test
    void validateAcceptsTheFourAuthorableThresholds()
    {
        for (net.cumba.datatable.report.Severity level : List.of(
                net.cumba.datatable.report.Severity.REJECT,
                net.cumba.datatable.report.Severity.ERROR,
                net.cumba.datatable.report.Severity.WARNING,
                net.cumba.datatable.report.Severity.INFO))
        {
            CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null,
                    null, 1, null, List.of(), level, RULE_PACKAGES);
            StudyValidationCheckRunner.validate(req, session);
        }
    }


    @Test
    void validateRejectsUnknownReferencedFile()
    {
        // ⚠ Carries RULE_PACKAGES so the V4 guard (file-only selection needs metadataProducts)
        // cannot fire first, and pins the message so the 400 is the file-existence one.
        CheckRunRequest req = withRulePackages(new CheckRunRequest(null, null, null, null, null,
                null, List.of("missing.json"), null, null));
        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(req, session))
                .isInstanceOf(BadRunRequestException.class).hasMessageContaining("missing.json");
    }


    @Test
    void validateRejectsBothIncludeAndExclude()
    {
        CheckRunRequest req = withRulePackages(new CheckRunRequest(null, null, null, null,
                List.of("CORE-1"), List.of("CORE-2"), null, null, null));
        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(req, session))
                .isInstanceOf(BadRunRequestException.class).hasMessageContaining("only one of");
    }


    @Test
    void mapsMultipleRulesFiles() throws Exception
    {
        sessions.addFile(session.id(), "more-rules.json", bytes("{}"));
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null,
                List.of("extra-rules.json", "more-rules.json"), null, null);
        StudyValidationParams p = StudyValidationCheckRunner.buildParams(run(req), session);
        assertThat(p.rulesFiles()).containsExactly(
                session.directory().resolve("extra-rules.json").toString(),
                session.directory().resolve("more-rules.json").toString());
    }


    @Test
    void validateRejectsPathInFilename()
    {
        CheckRunRequest req = withRulePackages(new CheckRunRequest(null, "../define.xml", null,
                null, null, null, null, null, null));
        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(req, session))
                .isInstanceOf(BadRunRequestException.class);
    }


    @Test
    void validateAcceptsWellFormedRequest()
    {
        // withRulePackages: since V4 a file-only selection also needs metadataProducts, and
        // this test's subject is "a well-formed request passes", not the V4 guard.
        StudyValidationCheckRunner
                .validate(
                        withRulePackages(new CheckRunRequest("uc", "define.xml", List.of("ref.cdt"),
                                "2.1", null, null, List.of("extra-rules.json"), List.of("DM"), 2)),
                        session);
    }

    // ------------------------------------------------------------------
    // referenceDataFilenames: reject a format that has no library supplier
    // (PLAN-reference-data-filenames-validation, wave-37 lane D)
    // ------------------------------------------------------------------


    /**
     * ⚠ <b>This test was inverted, not added.</b> Until wave 37 the request below — the shared
     * {@code full(...)} fixture, whose {@code referenceDataFilenames} is {@code ["DM.csv"]} — was
     * asserted to <i>pass</i> validation, by a test named {@code validateAcceptsWellFormedRequest}.
     * It was pinning the defect: CSV has a table provider but no library supplier, so the accepted
     * request then died mid-run with {@code IOException: Can not open … as library!} — a 500 the
     * server had promised not to produce. The assertion is reversed deliberately; deleting it would
     * have lost the record that this exact shape used to be accepted.
     */
    @Test
    void validateRejectsCsvReferenceDataThatUsedToBeAccepted()
    {
        assertThatThrownBy(() -> StudyValidationCheckRunner
                .validate(full(null, "extra-rules.json", 2), session))
                        .isInstanceOf(BadRunRequestException.class).hasMessageContaining("DM.csv");
    }


    /**
     * Acceptance criterion 1: the 400 has to name the file, its format and the supported list —
     * asserted on the message the response body carries, not merely on the status.
     */
    @Test
    void validateRejectionNamesTheFileItsFormatAndTheSupportedList()
    {
        assertThatThrownBy(() -> StudyValidationCheckRunner
                .validate(withReferenceData("ref.parquet"), session))
                        .isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("ref.parquet").hasMessageContaining("parquet")
                        .hasMessageContaining("Supported reference-data formats")
                        .hasMessageContaining("cdt").hasMessageContaining("datasetFilter");
    }


    /** Dataset-JSON is the third format the finding named; it has no library supplier either. */
    @Test
    void validateRejectsDatasetJsonReferenceData()
    {
        assertThatThrownBy(
                () -> StudyValidationCheckRunner.validate(withReferenceData("ref.ndjson"), session))
                        .isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("ref.ndjson");
    }


    /** A bare name with no extension resolves to no format at all, so it cannot be a library. */
    @Test
    void validateRejectsReferenceDataWithNoExtension()
    {
        assertThatThrownBy(
                () -> StudyValidationCheckRunner.validate(withReferenceData("refnoext"), session))
                        .isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("missing file extension");
    }


    /**
     * ⚑ Acceptance criterion 2 — the anti-over-reach assertion. The ruling was <i>reject by
     * format</i>, not <i>reject the field</i>: a caller succeeding today must keep succeeding. The
     * supported formats are not hard-coded here either — every extension the manager publishes is
     * driven through {@code validate}, so this fails if the check ever rejects something the SPI
     * says it can open.
     */
    @Test
    void validateAcceptsEveryFormatTheLibrarySpiPublishes() throws Exception
    {
        List<FileInfo> published = new LocalDataTableManager().getSupportedDataLibraryInfos();
        List<String> extensions = published.stream().map(FileInfo::getFileExtension)
                .filter(e -> e != null && !e.isBlank()).distinct().toList();
        // Positive control for the assertions of absence below: the published set is non-empty.
        assertThat(extensions).isNotEmpty().contains("cdt");

        for (String extension : extensions)
        {
            String name = "accepted." + extension;
            sessions.addFile(session.id(), name, bytes("x"));
            StudyValidationCheckRunner.validate(withReferenceData(name), session);
        }
    }


    /**
     * ⚠ Acceptance criterion 3 — the list must be <b>read from</b>
     * {@link net.cumba.datatable.manager.IDataTableManager#getSupportedDataLibraryInfos()}, never
     * compiled in. The injected manager publishes one format this codebase does not have and none
     * of the ones it does; if a literal list were reintroduced, {@code ref.zzzlib} would be
     * rejected and {@code ref.cdt} accepted — the exact opposite of what is asserted here.
     */
    @Test
    void theAcceptedFormatsAreReadFromTheManagerNotHardCoded()
    {
        IDataTableManager stub = mock(IDataTableManager.class);
        when(stub.getSupportedDataLibraryInfos()).thenReturn(List.of(FileInfo.createFor("zzzlib",
                "Test-only library format", UUID.randomUUID().toString())));

        StudyValidationCheckRunner.validate(withReferenceData("ref.zzzlib"), session, stub);

        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(withReferenceData("ref.cdt"),
                session, stub)).isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("zzzlib");
    }


    /**
     * The two halves of the file-format SPI are not interchangeable, and reading the wrong one
     * would make this whole check a no-op: CSV <i>is</i> a supported data <b>table</b> format and
     * is not a supported data <b>library</b> format. Both arms are asserted so the difference is
     * measured rather than assumed.
     */
    @Test
    void csvIsATableFormatButNotALibraryFormat()
    {
        LocalDataTableManager manager = new LocalDataTableManager();
        assertThat(manager.getSupportedDataTableInfos()).extracting(FileInfo::getFileExtension)
                .contains("csv");
        assertThat(manager.getSupportedDataLibraryInfos()).extracting(FileInfo::getFileExtension)
                .doesNotContain("csv");
    }

    // ------------------------------------------------------------------
    // wave 38 / W37-D1: registering cumba-oss-datatable-provider-{sas,xlsx,parquet}
    // in this module widened what reference data the server accepts.
    // (PLAN-reference-data-filenames-validation)
    // ------------------------------------------------------------------


    /**
     * ⚑⚑ <b>THE assertion this change exists to make, and the one that pins the dependencies.</b>
     *
     * <p>
     * Every pre-existing test in this module was green both before and after the three providers
     * were declared: {@link #validateAcceptsEveryFormatTheLibrarySpiPublishes} drives whatever the
     * SPI publishes and asserts only that the set is non-empty and contains {@code cdt}, and the
     * 400-message tests match with {@code containsString}. So the change could have landed — or
     * been reverted — with no test noticing. <i>Its success and its vacuity would have been the
     * same event.</i> This test names the transition explicitly:
     * </p>
     *
     * <pre>
     * before: cdt, dblib, xml
     * after : cdt, dblib, xlsx, xml, xpt
     * </pre>
     *
     * <p>
     * ⚠ <b>Inverted control — actually run, not asserted (2026-08-12, wave-38 lane C).</b> The
     * three dependencies were temporarily deleted from
     * {@code clients/cumba-oss-corej-rest/pom.xml}, this class was re-run, and the pom was
     * restored. Measured: <b>3 of the 4 wave-38 tests went red</b> (27 run, 2 failures, 1 error),
     * this one reporting
     * </p>
     *
     * <pre>
     * Expecting ListN:
     *   ["cdt", "dblib", "xml"]
     * to contain:
     *   ["xpt", "xls", "xlsx"]
     * </pre>
     *
     * <p>
     * ⚑ That block is the <b>verbatim 2026-08-12 output</b> and is left as recorded. The assertion
     * has since dropped {@code xls}: legacy binary Excel was never readable, and
     * {@code ExcelProviderSupplier} / {@code ExcelLibrarySupplier} publish only {@code xlsx}. The
     * control's finding — that the pre-change published set was exactly {@code cdt, dblib, xml} —
     * is unaffected.
     * </p>
     *
     * <p>
     * — so the control also <em>measures</em> the pre-change published set as exactly
     * {@code cdt, dblib, xml}. {@link #validateAcceptsXptAndExcelReferenceData} errored with the
     * old 400 ({@code format 'xpt' cannot be opened as a data library … Supported reference-data
     * formats: cdt, dblib, xml}), and
     * {@link #parquetIsReadableAsADatasetButStillRejectedAsReferenceData} failed on its table-half
     * arm. ⚑ {@link #parquetReferenceDataIsStillRejectedAfterTheProviderWasAdded} stayed <b>green
     * in both worlds</b> — as it must: parquet is refused with or without its provider, which is
     * exactly why it cannot be the test that pins the dependencies.
     * </p>
     *
     * <p>
     * ⛔ Do not weaken this to a size check or a status code: a status code cannot distinguish "the
     * SPI widened" from "the fixture changed".
     * </p>
     */
    @Test
    void publishedLibraryFormatsIncludeXptAndXlsx()
    {
        List<String> extensions = new LocalDataTableManager().getSupportedDataLibraryInfos()
                .stream().map(FileInfo::getFileExtension).filter(e -> e != null && !e.isBlank())
                .map(e -> e.toLowerCase(Locale.ROOT)).distinct().sorted().toList();

        // The widening itself: these two arrive with provider-sas and provider-xlsx.
        assertThat(extensions).contains("xpt", "xlsx");
        // ⛔ …and legacy binary Excel does NOT: it is unreadable, not merely unregistered.
        assertThat(extensions).doesNotContain("xls");
        // …without losing what was already published (the pre-change set).
        assertThat(extensions).contains("cdt", "dblib", "xml");
    }


    /**
     * The widening reaches {@code validate}: an {@code .xpt} / {@code .xlsx} reference-data entry
     * is now accepted where it previously produced a 400. ⚑ Before wave 38 these three were not
     * merely rejected at request time — the REST server had no reader for them at all.
     */
    @Test
    void validateAcceptsXptAndExcelReferenceData()
    {
        StudyValidationCheckRunner.validate(withReferenceData("ref.xpt"), session);
        StudyValidationCheckRunner.validate(withReferenceData("ref.xlsx"), session);
    }


    /**
     * ⛔ <b>The other half of "Excel": legacy binary {@code .xls} is REJECTED, and that is
     * deliberate.</b>
     *
     * <p>
     * Three assertions in this class used to expect {@code .xls} alongside {@code .xlsx} — in the
     * published library formats, in the published table formats, and as accepted reference data.
     * All three were wrong on the engine's own terms: {@code ExcelProviderSupplier}'s javadoc
     * records that the format "was never readable" because the underlying
     * {@code excel-streaming-reader} cannot read it, and both {@code ExcelProviderSupplier.FIS} and
     * {@code ExcelLibrarySupplier.FIS} publish {@code FI_XLSX} alone. They failed only once an
     * ordered install put the two repos in one local repository — each was committed and green on
     * its own.
     * </p>
     *
     * <p>
     * ⭐ Asserting the <b>rejection</b> rather than deleting the expectation is the point: it pins
     * the engine's actual contract, so re-introducing {@code .xls} becomes a decision someone has
     * to make here rather than something that can drift back in unnoticed.
     * </p>
     */
    @Test
    void legacyXlsIsNotAnExcelFormatThisEngineReads()
    {
        assertThatThrownBy(
                () -> StudyValidationCheckRunner.validate(withReferenceData("ref.xls"), session))
                        .isInstanceOf(BadRunRequestException.class).hasMessageContaining("xls");
    }


    /**
     * ⛔⛔ <b>Deliberate, not an oversight — do not "fix" this.</b>
     *
     * <p>
     * {@code cumba-oss-datatable-provider-parquet} is on this module's classpath since wave 38, and
     * it registers <b>only</b> an {@code IProviderSupplier} — no {@code ILibrarySupplier}. That is
     * {@code F3}'s ruled <b>category error</b>: a data library is a <i>container</i> of tables, and
     * a single {@code .parquet} file has no member namespace to enumerate, so there is nothing for
     * a library supplier to open. Adding one would have to invent a one-table container.
     * </p>
     *
     * <p>
     * ⇒ {@code .parquet} <i>datasets</i> are readable (asserted in
     * {@link #parquetIsReadableAsADatasetButStillRejectedAsReferenceData}) while {@code .parquet}
     * <i>reference data</i> keeps its 400. The two rulings compose; a future reader seeing "we
     * depend on the parquet provider but reject .parquet" is looking at the intended behaviour.
     * </p>
     */
    @Test
    void parquetReferenceDataIsStillRejectedAfterTheProviderWasAdded()
    {
        assertThatThrownBy(() -> StudyValidationCheckRunner
                .validate(withReferenceData("ref.parquet"), session))
                        .isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("ref.parquet")
                        .hasMessageContaining("format 'parquet'")
                        .hasMessageContaining("no library supplier is registered for it");
    }


    /**
     * ⚑ What the ~30 MB of parquet transitive weight actually buys, asserted rather than assumed:
     * {@code parquet} is now a supported <b>table</b> format (so a study dataset in that format
     * loads) and is still <b>not</b> a supported <b>library</b> format. Both arms, same shape as
     * {@link #csvIsATableFormatButNotALibraryFormat} — that is the whole asymmetry in one place.
     */
    @Test
    void parquetIsReadableAsADatasetButStillRejectedAsReferenceData()
    {
        LocalDataTableManager manager = new LocalDataTableManager();
        assertThat(manager.getSupportedDataTableInfos()).extracting(FileInfo::getFileExtension)
                .contains("parquet");
        assertThat(manager.getSupportedDataLibraryInfos()).extracting(FileInfo::getFileExtension)
                .doesNotContain("parquet");
        // The sas/xlsx table halves landed too — .xpt/.sas7bdat/.xlsx datasets now load.
        assertThat(manager.getSupportedDataTableInfos()).extracting(FileInfo::getFileExtension)
                .contains("xpt", "sas7bdat", "xlsx");
        // ⛔ .xls is absent from the TABLE half as well, not only the library half.
        assertThat(manager.getSupportedDataTableInfos()).extracting(FileInfo::getFileExtension)
                .doesNotContain("xls");
    }


    /** A request naming only {@code defineXmlFilename}, for the bare-file-name checks below. */
    private static CheckRunRequest withDefineXml(String name)
    {
        return new CheckRunRequest(null, name, null, null, null, null, null, null, null, null, null,
                null, RULE_PACKAGES);
    }


    /**
     * F-rest-04. The run-side name check used to be a shorter copy of
     * {@code SessionRegistry.validateFilename}: blank plus the two path separators, nothing else.
     * {@code ".."} therefore passed it and was refused one line later by {@code session.hasFile} —
     * i.e. its safety against a relative segment was a property of the <em>registry's</em>
     * exact-match lookup, not of its own code. Both now share {@code SessionFilenames}, so the
     * rejection names the actual violation. This assertion discriminates: under the old code the
     * message read "references a file not in the session".
     */
    @Test
    void validateRejectsARelativePathSegmentAsAMalformedName()
    {
        assertThatThrownBy(() -> StudyValidationCheckRunner.validate(withDefineXml(".."), session))
                .isInstanceOf(BadRunRequestException.class)
                .hasMessageContaining("must be a bare file name")
                .hasMessageContaining("must not be a relative path segment");
    }


    /** The second rule the run-side copy had lost: a NUL byte in the name. */
    @Test
    void validateRejectsAFilenameCarryingANulByte()
    {
        assertThatThrownBy(() -> StudyValidationCheckRunner
                .validate(withDefineXml("dm" + (char) 0 + ".xpt"), session))
                        .isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("must not contain a NUL character");
    }


    /** The rules both copies always shared still reject, with the shared wording. */
    @Test
    void validateStillRejectsAPathSeparatorInAName()
    {
        assertThatThrownBy(
                () -> StudyValidationCheckRunner.validate(withDefineXml("sub/dm.xpt"), session))
                        .isInstanceOf(BadRunRequestException.class)
                        .hasMessageContaining("must not contain a path separator");
    }

}
