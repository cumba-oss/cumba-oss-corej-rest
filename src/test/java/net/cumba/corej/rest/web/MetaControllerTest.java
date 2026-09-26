package net.cumba.corej.rest.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.rest.config.CorejProperties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link MetaController}. The effective rules directory is pointed at a temp dir via
 * the {@code corej.rules.dir} system property (the {@code COREJ_RULES_DIR} env var is unset in the
 * test environment, so the property wins). The controller reads the family-scoped packs it exposes
 * from the directory's {@code packages.json} manifest, so every fixture writes one.
 */
@org.junit.jupiter.api.extension.ExtendWith(net.cumba.corej.rest.HomeStaysCleanExtension.class)
class MetaControllerTest
{

    @TempDir
    Path rulesDir;

    private @Nullable String savedStoreProperty;

    @org.junit.jupiter.api.BeforeEach
    void saveStoreProperty()
    {
        savedStoreProperty = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
    }


    @AfterEach
    void clearProperty()
    {
        System.clearProperty("corej.rules.dir");
        if (savedStoreProperty == null)
        {
            System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        }
        else
        {
            System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, savedStoreProperty);
        }
    }


    private static String pack(String coreId, String useCase)
    {
        String uc = useCase == null ? "" : ",\"Scope\":{\"Use_Case\":\"" + useCase + "\"}";
        return "{\"rules\":{\"u\":{\"Core\":{\"Id\":\"" + coreId + "\"}" + uc + "}}}";
    }


    /** Writes {@code packages.json} describing the given family-scoped entries. */
    private void writeManifest(RulePackageManifest.Entry... entries) throws IOException
    {
        new RulePackageManifest("test", List.of(entries)).writeTo(rulesDir);
    }


    /**
     * ⚑ Plan 2 (R1/R5) — this was {@code runOptionsGroupsFamiliesStandardsAndVersions}. The
     * endpoint no longer groups by family/standard/version and has no {@code defaultFamily}: a run
     * names PACKAGES, so the run form gets a flat list of selectable short names, each carrying its
     * display fields and the CDISC Library products it declares.
     */
    @Test
    void runOptionsListsTheSelectablePackages() throws IOException
    {
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-4.json"), pack("CORE-1", null));
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-3.json"), pack("CORE-2", null));
        Files.writeString(rulesDir.resolve("rules-cdisc-adamig-1-3.json"), pack("CORE-3", null));
        Files.writeString(rulesDir.resolve("rules-fda-sdtmig-3-4.json"), pack("FDA-1", null));
        writeManifest(
                new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC", "sdtmig",
                        "3-4", 1, List.of()),
                new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-3.json", "CDISC", "sdtmig",
                        "3-3", 1, List.of()),
                new RulePackageManifest.Entry("rules-cdisc-adamig-1-3.json", "CDISC", "adamig",
                        "1-3", 1, List.of()),
                new RulePackageManifest.Entry("rules-fda-sdtmig-3-4.json", "FDA", "sdtmig", "3-4",
                        1, List.of()));
        System.setProperty("corej.rules.dir", rulesDir.toString());

        MetaController.RunOptions opts = new MetaController().runOptions();

        // Every manifested package, by the short name a run passes as rulesPackages, sorted.
        assertThat(opts.packages()).extracting(MetaController.PackageOption::name).containsExactly(
                "cdisc-adamig-1-3", "cdisc-sdtmig-3-3", "cdisc-sdtmig-3-4", "fda-sdtmig-3-4");

        // The display fields still come straight from the manifest entry.
        MetaController.PackageOption fda = opts.packages().stream()
                .filter(o -> o.name().equals("fda-sdtmig-3-4")).findFirst().orElseThrow();
        assertThat(fda.family()).isEqualTo("FDA");
        assertThat(fda.standard()).isEqualTo("sdtmig");
        assertThat(fda.version()).isEqualTo("3-4");
        assertThat(fda.ruleCount()).isEqualTo(1);
        // These manifest entries declare no library standards (the R6 array is optional).
        assertThat(fda.standards()).isEmpty();

        assertThat(opts.defineVersions()).containsExactly("2.1.0", "2.0.0", "1.0.0");
    }


    /**
     * Review L3: the picker offers only codes a run may name — split and stripped as the engine
     * splits ({@code ScopeMatcher.useCaseCodes}), then kept only if
     * {@code StudyValidationParams.isWellFormedUseCase}. A value the loader rejects
     * ({@code "INDH;PROD"}, {@code "X1"}) would otherwise become a choice the run then refuses.
     */
    @Test
    void rulesOffersOnlyUseCaseCodesARunMayName() throws IOException
    {
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-4.json"), "{\"rules\":{"
                + "\"a\":{\"Core\":{\"Id\":\"CORE-1\"},\"Scope\":{\"Use_Case\":\"INDH;PROD, X1\"}},"
                + "\"b\":{\"Core\":{\"Id\":\"CORE-2\"},\"Scope\":{\"Use_Case\":\" NONCLIN ,\"}}}}");
        writeManifest(new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC",
                "sdtmig", "3-4", 2, List.of()));
        System.setProperty("corej.rules.dir", rulesDir.toString());

        MetaController.RuleOptions ruleOptions = new MetaController().rules("cdisc-sdtmig-3-4");

        assertThat(ruleOptions.useCases()).containsExactly("NONCLIN");
    }


    @Test
    void rulesReturnsCoreIdsAndUseCasesForThePackage() throws IOException
    {
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-4.json"), "{\"rules\":{"
                + "\"a\":{\"Core\":{\"Id\":\"CORE-1\"},\"Scope\":{\"Use_Case\":\"INDH, PROD\"}},"
                + "\"b\":{\"Core\":{\"Id\":\"CORE-2\"},\"Scope\":{\"Use_Case\":\"INDH\"}},"
                // A top-level Use_Case is not the rule model's shape (Scope binds it), so
                // it is not offered. The fixture used to put BOTH tokens here, which is
                // how the picker's real read (a key no package carries) stayed green.
                + "\"c\":{\"Core\":{\"Id\":\"CORE-3\"},\"Use_Case\":\"NONCLIN\"}}}");
        writeManifest(new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC",
                "sdtmig", "3-4", 2, List.of()));
        System.setProperty("corej.rules.dir", rulesDir.toString());

        MetaController.RuleOptions ruleOptions = new MetaController().rules("cdisc-sdtmig-3-4");

        assertThat(ruleOptions.rules()).extracting(MetaController.RuleOption::id)
                .containsExactly("CORE-1", "CORE-2", "CORE-3");
        assertThat(ruleOptions.useCases()).containsExactly("INDH", "PROD");
    }


    @Test
    void rulesIsScopedToTheNamedPackage() throws IOException
    {
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-4.json"), pack("CORE-1", null));
        Files.writeString(rulesDir.resolve("rules-fda-sdtmig-3-4.json"), pack("FDA-1", null));
        writeManifest(
                new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC", "sdtmig",
                        "3-4", 1, List.of()),
                new RulePackageManifest.Entry("rules-fda-sdtmig-3-4.json", "FDA", "sdtmig", "3-4",
                        1, List.of()));
        System.setProperty("corej.rules.dir", rulesDir.toString());

        // Naming the CDISC package sees only the CDISC pack...
        assertThat(new MetaController().rules("cdisc-sdtmig-3-4").rules())
                .extracting(MetaController.RuleOption::id).containsExactly("CORE-1");
        // ...and naming the FDA package sees only the FDA pack.
        assertThat(new MetaController().rules("fda-sdtmig-3-4").rules())
                .extracting(MetaController.RuleOption::id).containsExactly("FDA-1");
    }


    @Test
    void rulesReturnsDescriptionsWhenPresent() throws IOException
    {
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-4.json"),
                "{\"rules\":{\"u\":{\"Core\":{\"Id\":\"CORE-1\"},"
                        + "\"Description\":\"USUBJID is required\"}}}");
        writeManifest(new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC",
                "sdtmig", "3-4", 1, List.of()));
        System.setProperty("corej.rules.dir", rulesDir.toString());

        MetaController.RuleOptions ruleOptions = new MetaController().rules("cdisc-sdtmig-3-4");

        assertThat(ruleOptions.rules()).singleElement().satisfies(r ->
        {
            assertThat(r.id()).isEqualTo("CORE-1");
            assertThat(r.description()).isEqualTo("USUBJID is required");
        });
    }


    @Test
    void rulesIsEmptyForUnknownPack() throws IOException
    {
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-4.json"), pack("CORE-1", null));
        writeManifest(new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC",
                "sdtmig", "3-4", 1, List.of()));
        System.setProperty("corej.rules.dir", rulesDir.toString());

        // A known pack resolves ...
        assertThat(new MetaController().rules("cdisc-sdtmig-3-4").rules())
                .extracting(MetaController.RuleOption::id).containsExactly("CORE-1");
        // ... and an unknown pack is empty, not an error.
        assertThat(new MetaController().rules("cdisc-sendig-9-9").rules()).isEmpty();
    }


    @Test
    void runOptionsEmptyWhenRulesDirMissing()
    {
        System.setProperty("corej.rules.dir", rulesDir.resolve("does-not-exist").toString());
        assertThat(new MetaController().runOptions().packages()).isEmpty();
    }


    /**
     * ⭐ Plan 2 Phase 7 / R4 — the metadata-product picker's options.
     *
     * <p>
     * The run form offers the products in the spelling the CLI documents ({@code <group>/
     * <product>}), so the catalogue's {@code standards/} cache-key namespace is stripped and the
     * list is sorted for a stable picker. A key that does not carry the namespace passes through
     * untouched rather than being silently dropped — the catalogue is the authority on what exists,
     * and this mapping must not be able to hide one of its entries.
     * </p>
     *
     * <p>
     * ⚑ Asserted against a FIXED key set, never the live catalogue: the catalogue is built from
     * {@code CDISC_PICKLE_CACHE_DIR} / {@code CDISC_API_CACHE}, so a test reading it would pass
     * vacuously wherever neither is configured.
     * </p>
     */
    @Test
    void metadataProductOptionsStripTheNamespaceAndSort()
    {
        assertThat(MetaController.productOptions(new java.util.LinkedHashSet<>(
                List.of("standards/sdtmig/3-4", "standards/adam/adamig-1-3", "tig/1-0/adam"))))
                        .containsExactly("adam/adamig-1-3", "sdtmig/3-4", "tig/1-0/adam");
        assertThat(MetaController.productOptions(java.util.Set.of())).isEmpty();
    }


    /**
     * The form's three sources are independent: an empty rules directory must still offer the
     * Define-XML versions and the metadata products the catalogue enumerates.
     *
     * <p>
     * ⚑ Review R-17: this test's previous form read the LIVE catalogue and used {@code allSatisfy},
     * which passes vacuously on the empty list the catalogue returns wherever
     * {@code CDISC_PICKLE_CACHE_DIR} / {@code CDISC_API_CACHE} are unset — dropping the product
     * list from the endpoint entirely survived it. It now drives the assembly seam with a FIXED key
     * set (same discipline as {@link #metadataProductOptionsStripTheNamespaceAndSort}), so the
     * endpoint's assembly must actually map and carry the list.
     * </p>
     */
    @Test
    void runOptionsAlwaysCarriesTheDefineVersionsAndProductList()
    {
        System.setProperty("corej.rules.dir", rulesDir.toString());

        MetaController.RunOptions opts = new MetaController()
                .runOptions(new java.util.LinkedHashSet<>(
                        List.of("standards/sdtmig/3-4", "standards/adam/adamig-1-3")));

        assertThat(opts.packages()).isEmpty();
        assertThat(opts.defineVersions()).contains("2.1.0");
        assertThat(opts.metadataProducts()).containsExactly("adam/adamig-1-3", "sdtmig/3-4");
    }


    /**
     * REST L4 (review round 1): an OLD-FORMAT configured store must not read as "no products" —
     * that is what "no store configured" looks like, and every run on it fails. The endpoint
     * answers 503 naming the format problem and this surface's remedy, which depends on the seeding
     * switch.
     */
    @Test
    void runOptionsAnswers503ForAnOldFormatStoreInsteadOfAnEmptyProductList() throws IOException
    {
        writeManifest();
        System.setProperty("corej.rules.dir", rulesDir.toString());
        Path store = rulesDir.resolve("metadata-cache.zip");
        new MetadataStoreWriter().publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/sdtmig/3-4")).write(store);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, store.toString());
        assertThat(new MetaController().runOptions().metadataProducts())
                .containsExactly("sdtmig/3-4");

        relabelFormatVersion(store, 1);

        ResponseStatusException refused = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> new MetaController().runOptions());
        assertThat(refused.getStatusCode().value()).isEqualTo(503);
        assertThat(refused.getReason()).contains("is format 1")
                .contains("corej.cache-seed.enabled=true").doesNotContain("start-up log");

        CorejProperties seeding = new CorejProperties();
        seeding.getCacheSeed().setEnabled(true);
        ResponseStatusException seedingOn = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> new MetaController(seeding).runOptions());
        assertThat(seedingOn.getReason()).contains("did not replace this store");

        // Review round 2 (M-NEWER): a NEWER-build store answers 503 with the upgrade remedy.
        relabelFormatVersion(store, 99);
        ResponseStatusException newer = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class, () -> new MetaController().runOptions());
        assertThat(newer.getStatusCode().value()).isEqualTo(503);
        assertThat(newer.getReason()).contains("written by a newer build")
                .contains("Upgrade this service").doesNotContain("upgrade this tool")
                .doesNotContain("Set corej.cache-seed.enabled=true");
    }


    /**
     * Review round 3 (M-REST-TIER): the product list is the catalogue of the store the RUNS read. A
     * deployment naming {@code target-store=B} with seeding DISABLED (so the initializer never runs
     * and publishes nothing) used to answer an empty list - and an ambient property store A of
     * another format used to answer 503 - while every run read B. The named store outranks both,
     * exactly as it does for the run.
     */
    @Test
    void runOptionsListsTheProductsOfTheDeploymentsNamedStoreNotTheAmbientOne() throws IOException
    {
        writeManifest();
        System.setProperty("corej.rules.dir", rulesDir.toString());
        Path named = rulesDir.resolve("named-b.zip");
        new MetadataStoreWriter().publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/adam/adamig-1-3")).write(named);
        CorejProperties props = new CorejProperties();
        props.getCacheSeed().setEnabled(false);
        props.getCacheSeed().setTargetStore(named.toString());

        // Nothing ambient at all (seeding disabled ⇒ nothing was published).
        assertThat(new MetaController(props).runOptions().metadataProducts())
                .containsExactly("adam/adamig-1-3");

        // An ambient property store of another format does not veto the named one.
        Path ambient = rulesDir.resolve("ambient-old.zip");
        new MetadataStoreWriter().publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/sdtmig/3-4")).write(ambient);
        relabelFormatVersion(ambient, 1);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, ambient.toString());
        assertThat(new MetaController(props).runOptions().metadataProducts())
                .containsExactly("adam/adamig-1-3");
        // ... whereas a deployment naming NO store reads the ambient one, and answers 503.
        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> new MetaController().runOptions());
    }


    /** The relabel trick the store tests use: rewrite the manifest's formatVersion in place. */
    private static void relabelFormatVersion(Path aStore, int aVersion) throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(aStore.toFile()))
        {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements())
            {
                ZipEntry entry = names.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        String relabelled = manifest.replaceFirst("\"formatVersion\" : \\d+",
                "\"formatVersion\" : " + aVersion);
        assertThat(relabelled).isNotEqualTo(manifest);
        entries.put("manifest.json", relabelled.getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(aStore)))
        {
            for (Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
}
