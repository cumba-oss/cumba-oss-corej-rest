package net.cumba.corej.rest.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.RulePackageManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link MetaController}. The effective rules directory is pointed at a temp dir via
 * the {@code corej.rules.dir} system property (the {@code COREJ_RULES_DIR} env var is unset in the
 * test environment, so the property wins). The controller reads the family-scoped packs it exposes
 * from the directory's {@code packages.json} manifest, so every fixture writes one.
 */
class MetaControllerTest
{

    @TempDir
    Path rulesDir;

    @AfterEach
    void clearProperty()
    {
        System.clearProperty("corej.rules.dir");
    }


    private static String pack(String coreId, String useCase)
    {
        String uc = useCase == null ? "" : ",\"Use_Case\":\"" + useCase + "\"";
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
                        "3-4", 1),
                new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-3.json", "CDISC", "sdtmig",
                        "3-3", 1),
                new RulePackageManifest.Entry("rules-cdisc-adamig-1-3.json", "CDISC", "adamig",
                        "1-3", 1),
                new RulePackageManifest.Entry("rules-fda-sdtmig-3-4.json", "FDA", "sdtmig", "3-4",
                        1));
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


    @Test
    void rulesReturnsCoreIdsAndUseCasesForThePackage() throws IOException
    {
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-4.json"),
                "{\"rules\":{" + "\"a\":{\"Core\":{\"Id\":\"CORE-1\"},\"Use_Case\":\"INDH, PROD\"},"
                        + "\"b\":{\"Core\":{\"Id\":\"CORE-2\"},\"Use_Case\":\"INDH\"}}}");
        writeManifest(new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC",
                "sdtmig", "3-4", 2));
        System.setProperty("corej.rules.dir", rulesDir.toString());

        MetaController.RuleOptions ruleOptions = new MetaController().rules("cdisc-sdtmig-3-4");

        assertThat(ruleOptions.rules()).extracting(MetaController.RuleOption::id)
                .containsExactly("CORE-1", "CORE-2");
        assertThat(ruleOptions.useCases()).containsExactly("INDH", "PROD");
    }


    @Test
    void rulesIsScopedToTheNamedPackage() throws IOException
    {
        Files.writeString(rulesDir.resolve("rules-cdisc-sdtmig-3-4.json"), pack("CORE-1", null));
        Files.writeString(rulesDir.resolve("rules-fda-sdtmig-3-4.json"), pack("FDA-1", null));
        writeManifest(
                new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC", "sdtmig",
                        "3-4", 1),
                new RulePackageManifest.Entry("rules-fda-sdtmig-3-4.json", "FDA", "sdtmig", "3-4",
                        1));
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
                "sdtmig", "3-4", 1));
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
                "sdtmig", "3-4", 1));
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
}
