package net.cumba.corej.rest.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.metadata.MetadataProductCatalogue;
import net.cumba.corej.core.run.StudyValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Valid-value metadata for the non-free-text run properties, derived from the rule packs present in
 * the effective rules directory ({@code COREJ_RULES_DIR} / {@code corej.rules.dir} /
 * {@code ./rules}). The web UI uses these to render dropdowns instead of text fields. Sourcing is
 * rules-directory-only for now; surfacing the CDISC Library catalogue (e.g. a cached-only mode) is
 * a future enhancement.
 */
@RestController
@RequestMapping("/api/meta")
@Tag(name = "meta", description = "Valid values for run properties")
public class MetaController
{

    private static final Logger LOG = LoggerFactory.getLogger(MetaController.class);

    /** Published Define-XML versions offered for the run form (display-only passthrough). */
    private static final List<String> DEFINE_VERSIONS = List.of("2.1.0", "2.0.0", "1.0.0");

    /** The invariant rule-package filename prefix: {@code rules-<short>.json}. */
    private static final String RULE_PACKAGE_PREFIX = "rules-";

    /** The invariant rule-package filename suffix. */
    private static final String RULE_PACKAGE_SUFFIX = ".json";

    /** The catalogue's cache-key namespace prefix, stripped for the {@code -mp} spelling. */
    private static final String STANDARDS_PREFIX = "standards/";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @GetMapping("/run-options")
    @Operation(summary = "Valid values for the run form",
            description = "The selectable rule PACKAGES (short names, each with the CDISC Library "
                    + "standards it declares), the Define-XML version list, and the declarable "
                    + "CDISC Library metadata products (R4 — optional, ordered, metadata only). "
                    + "Plan 2 (R1/R5): a run names packages, not a standard + version, so the "
                    + "former standards / families / defaultFamily fields are gone.")
    public RunOptions runOptions()
    {
        return runOptions(MetadataProductCatalogue.configured().keys());
    }


    /**
     * The assembly half of {@link #runOptions()}, with the catalogue's keys supplied explicitly.
     * The product list is offered in the {@code -mp} spelling the CLI documents
     * ({@code <group>/<product>}, e.g. {@code adam/adamig-1-3}).
     *
     * <p>
     * ⭐ <b>Plan 2 Phase 7 / R4 — the keys come from {@link MetadataProductCatalogue}, never from
     * {@code PickleCache.standardKeys()}.</b> The catalogue is the one place that knows which of
     * the two sources contributed and applies the {@code "Implementation Guide"} type filter; a raw
     * pickle enumeration would offer sub-resources and foundational models the run can never load.
     * Since cache P4 the catalogue is the unified metadata store's, re-read per resolution (the old
     * process-wide memo was removed in 19db89e), so a re-seeded store is picked up without a
     * restart. ⚑ The list is a convenience, not a veto (same principle as R12 for packages): the
     * run form offers what the catalogue enumerates, and {@code ProductKeyResolver} — not this
     * endpoint — decides what a submitted token resolves to.
     * </p>
     *
     * <p>
     * Separated (review R-17) for the same reason as {@link #productOptions(Set)}: the catalogue
     * itself is built from the configured metadata store ({@code CDISC_METADATA_STORE} /
     * {@code cdisc.metadata.store}), so a test asserting the endpoint carries the product list
     * would pass vacuously (empty list, every per-element assertion satisfied) wherever none is
     * configured. This seam lets the test pin the assembly against a fixed key set.
     * </p>
     */
    RunOptions runOptions(Set<String> catalogueKeys)
    {
        return new RunOptions(packages(), DEFINE_VERSIONS, productOptions(catalogueKeys));
    }


    @GetMapping("/rules")
    @Operation(summary = "Rule ids (with descriptions) and use cases for one rule package",
            description = "The CORE rule ids in the named package (each with its description, for "
                    + "include/exclude selection) and any Use_Case tokens it defines. Empty when "
                    + "no such package exists.")
    public RuleOptions rules(@RequestParam("package") String packageName)
    {
        Path dir = Path.of(StudyValidationService.effectiveRulesDir());
        // Keep first-seen description per id; ids sorted for a stable dropdown order.
        Map<String, String> descriptionsById = new TreeMap<>();
        Set<String> useCases = new TreeSet<>();
        Path pack = dir.resolve(RULE_PACKAGE_PREFIX + packageName + RULE_PACKAGE_SUFFIX);
        if (Files.isRegularFile(pack))
        {
            collect(pack, descriptionsById, useCases);
        }
        List<RuleOption> rules = new ArrayList<>();
        descriptionsById.forEach((id, desc) -> rules.add(new RuleOption(id, desc)));
        return new RuleOptions(rules, new ArrayList<>(useCases));
    }


    /**
     * Loads the rules directory's {@code packages.json}; empty (never null) if absent/unreadable.
     */
    private static RulePackageManifest manifest(Path dir)
    {
        try
        {
            return RulePackageManifest.load(dir);
        }
        catch (IOException e)
        {
            LOG.warn("Failed to read {} in {}", RulePackageManifest.FILE_NAME, dir, e);
            return new RulePackageManifest(List.of());
        }
    }


    /**
     * The selectable rule packages, from the manifest, sorted by short name.
     *
     * <p>
     * ⚑ <b>R12 — the manifest is metadata, the filesystem decides.</b> This listing is for the run
     * form's picker, so it reports what the manifest describes; a package present on disk but
     * unmanifested still RUNS when named explicitly, and the engine logs it. The two are allowed to
     * differ, and the engine — not this endpoint — is the authority.
     * </p>
     */
    private List<PackageOption> packages()
    {
        Path dir = Path.of(StudyValidationService.effectiveRulesDir());
        List<PackageOption> out = new ArrayList<>();
        for (RulePackageManifest.Entry e : manifest(dir).packages())
        {
            String file = e.file() == null ? "" : e.file();
            if (!file.startsWith(RULE_PACKAGE_PREFIX) || !file.endsWith(RULE_PACKAGE_SUFFIX))
            {
                continue;
            }
            String shortName = file.substring(RULE_PACKAGE_PREFIX.length(),
                    file.length() - RULE_PACKAGE_SUFFIX.length());
            List<String> declared = new ArrayList<>();
            for (net.cumba.corej.core.model.StandardRef ref : e.standards())
            {
                declared.add(ref.id());
            }
            out.add(new PackageOption(shortName, e.family(), e.standard(), e.version(),
                    e.ruleCount(), declared));
        }
        out.sort(java.util.Comparator.comparing(PackageOption::name));
        return out;
    }


    /**
     * The pure half of the product list in {@link #runOptions()}: catalogue keys ({@code
     * standards/adam/adamig-1-3}) to the {@code -mp} spelling ({@code adam/adamig-1-3}), sorted for
     * a stable picker.
     *
     * <p>
     * Separated so the mapping is testable without an environment: the catalogue itself is built
     * from {@code CDISC_PICKLE_CACHE_DIR} / {@code CDISC_API_CACHE}, so a test asserting against
     * the live catalogue would pass vacuously wherever neither is configured.
     * </p>
     */
    static List<String> productOptions(Set<String> keys)
    {
        List<String> out = new ArrayList<>();
        for (String key : keys)
        {
            out.add(key.startsWith(STANDARDS_PREFIX) ? key.substring(STANDARDS_PREFIX.length())
                    : key);
        }
        java.util.Collections.sort(out);
        return out;
    }


    private static void collect(Path pack, Map<String, String> descriptionsById,
            Set<String> useCases)
    {
        try
        {
            JsonNode root = MAPPER.readTree(Files.readString(pack, StandardCharsets.UTF_8));
            JsonNode rules = root.get("rules");
            if (rules == null)
            {
                return;
            }
            Iterator<JsonNode> it = rules.elements();
            while (it.hasNext())
            {
                JsonNode rule = it.next();
                JsonNode id = rule.path("Core").path("Id");
                if (id.isTextual())
                {
                    JsonNode description = rule.path("Description");
                    descriptionsById.putIfAbsent(id.asText(),
                            description.isTextual() ? description.asText() : null);
                }
                JsonNode useCase = rule.path("Use_Case");
                if (useCase.isTextual())
                {
                    // Two-arg split with limit 0 is API-identical to the single-arg split(",")
                    // (trailing empties discarded) but avoids Error Prone's StringSplitter finding.
                    for (String token : useCase.asText().split(",", 0))
                    {
                        String trimmed = token.trim();
                        if (!trimmed.isEmpty())
                        {
                            useCases.add(trimmed);
                        }
                    }
                }
            }
        }
        catch (IOException e)
        {
            LOG.warn("Failed to read rule pack {}", pack, e);
        }
    }

    /** Valid values for the run form's fields. */
    @Schema(description = "Valid values for the run form")
    public record RunOptions(@Schema(
            description = "Selectable rule packages, sorted by short name") List<PackageOption> packages,
            @Schema(description = "Define-XML version options") List<String> defineVersions,
            @Schema(description = "Declarable CDISC Library metadata products, as <group>/<product>") List<String> metadataProducts)
    {

        /** Null-safe canonicalisation of the components. */
        public RunOptions
        {
            packages = packages == null ? List.of() : List.copyOf(packages);
            defineVersions = defineVersions == null ? List.of() : List.copyOf(defineVersions);
            metadataProducts = metadataProducts == null ? List.of() : List.copyOf(metadataProducts);
        }
    }


    /**
     * One selectable rule package.
     *
     * @param name
     *            the short name a run passes as {@code rulesPackages}, e.g.
     *            {@code cdisc-sdtmig-3-4} (the file is {@code rules-<name>.json})
     * @param family
     *            the rule family, e.g. {@code CDISC} — display only; it is part of {@code name}
     * @param standard
     *            the standard display name, e.g. {@code SDTMIG} — display only
     * @param version
     *            the version display form, e.g. {@code 3.4} — display only
     * @param ruleCount
     *            how many rules the package holds
     * @param standards
     *            the CDISC Library product ids the package declares (R6)
     */
    @Schema(description = "A selectable rule package")
    public record PackageOption(@Schema(
            description = "Short name to pass as rulesPackages, e.g. cdisc-sdtmig-3-4") String name,
            @Schema(description = "Rule family (display only)") String family,
            @Schema(description = "Standard display name (display only)") String standard,
            @Schema(description = "Version display form (display only)") String version,
            @Schema(description = "Number of rules in the package") int ruleCount,
            @Schema(description = "CDISC Library product ids this package declares") List<String> standards)
    {

        /** Null-safe canonicalisation of the components. */
        public PackageOption
        {
            standards = standards == null ? List.of() : List.copyOf(standards);
        }
    }


    /** Rule options (id + description) and use cases for a standard + version. */
    @Schema(description = "Rules and use cases for a standard + version")
    public record RuleOptions(@Schema(
            description = "CORE rules in the matching pack (id + description)") List<RuleOption> rules,
            @Schema(description = "Use_Case tokens defined by the pack") List<String> useCases)
    {

        public RuleOptions
        {
            rules = rules == null ? List.of() : List.copyOf(rules);
            useCases = useCases == null ? List.of() : List.copyOf(useCases);
        }
    }


    /** One selectable CORE rule: its id and human-readable description. */
    @Schema(description = "A CORE rule id and its description")
    public record RuleOption(@Schema(description = "CORE rule id") String id,
            @Schema(description = "Rule description (may be null)") String description)
    {
    }
}
