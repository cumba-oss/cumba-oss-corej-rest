package net.cumba.corej.rest.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.metadata.pickle.HttpArchivePickleSource;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.pickle.PickleSource;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.seed.PickleStoreSeeder;
import net.cumba.corej.core.metadata.store.seed.StoreSeedOptions;
import net.cumba.corej.core.metadata.store.seed.StoreSeedReport;
import net.cumba.corej.core.metadata.store.seed.WebApiStoreSeeder;
import net.cumba.datatable.metadatacache.MetadataCacheLocator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds the unified CDISC metadata store at startup — from the Python engine's pickle metadata
 * ({@code PickleStoreSeeder}), or from the live CDISC Library API ({@code WebApiStoreSeeder},
 * opt-in via {@code corej.cache-seed.from-api}, needs an API key) — so a deployment has library
 * metadata available without any manual step.
 *
 * <p>
 * Opt-in via {@code corej.cache-seed.enabled}. There is deliberately <b>no HTTP endpoint</b>:
 * seeding performs outbound network I/O, writes to a server-side file and can take minutes.
 * Exposing that to callers would be a privileged operation needing its own authorisation story, so
 * every input is server-configured instead.
 * </p>
 *
 * <p>
 * Failures are logged and swallowed. A metadata store is an optimisation, not a precondition — a
 * network outage or an upstream layout change must not stop the service from starting. (A run
 * without a store skips the library-dependent rules, ruling R2.)
 * </p>
 *
 * <p>
 * ⭐ When the engine has no store configured (neither {@code CDISC_METADATA_STORE} nor
 * {@code cdisc.metadata.store}), the resolved target — the {@code target-store} property or the
 * application default — is also <b>published into {@code cdisc.metadata.store}</b> once the store
 * exists: the engine resolves the store through that property
 * ({@code StoreMetadataProviderFactory.resolveConfiguredFile}) and cannot know this service's
 * configuration itself. An explicitly configured environment/property is never overridden.
 * </p>
 *
 * <p>
 * ⚠⚠ That publication is <b>not</b> what makes an explicit {@code target-store} win (review finding
 * F2): the system property is the <em>lowest</em> tier of {@code resolveConfiguredFile}, below
 * {@code CDISC_METADATA_STORE}, and this method publishes nothing at all once anything else is
 * configured — which is exactly how a deployment could seed {@code target-store=B} here and then
 * validate against an ambient {@code CDISC_METADATA_STORE=A} forever, with no warning. What closes
 * that is {@code StudyValidationCheckRunner.configuredRunStore}, which hands the same
 * {@code target-store} to every run on {@code StudyValidationParams.metadataStore()} — the explicit
 * top tier. The two are additive: the publication still serves the readers that have no params of
 * their own (the product catalogue behind {@code /meta}) and the zero-configuration application
 * default, which has no other channel. Do not delete either.
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "corej.cache-seed", name = "enabled", havingValue = "true")
public class CacheSeedInitializer implements ApplicationRunner
{

    private static final Logger LOG = LoggerFactory.getLogger(CacheSeedInitializer.class);

    private final CorejProperties.CacheSeed config;

    /**
     * @param aProperties
     *            the bound {@code corej.*} configuration.
     */
    public CacheSeedInitializer(CorejProperties aProperties)
    {
        config = aProperties.getCacheSeed();
    }


    @Override
    public void run(ApplicationArguments aArgs)
    {
        // Everything, including target resolution, sits inside the try: Path.of on a malformed
        // target-store throws InvalidPathException, and an unset user.home makes the fallback
        // throw. Both are unchecked and would otherwise escape run() and abort context startup —
        // exactly what this class promises cannot happen.
        try
        {
            if (config.isFromApi() && config.getFromDir() != null && !config.getFromDir().isBlank())
            {
                LOG.warn("corej.cache-seed.from-api and corej.cache-seed.from-dir are mutually "
                        + "exclusive; skipping metadata store seeding");
                return;
            }
            Path target = resolveTargetStore();
            if (!config.isRefresh() && Files.isRegularFile(target))
            {
                LOG.info("Metadata store at {} already exists; skipping seeding "
                        + "(set corej.cache-seed.refresh=true to rebuild it)", target);
                publishDefaultIfUnconfigured(target);
                return;
            }
            LOG.info("Seeding metadata store at {} …", target);
            StoreSeedOptions options = StoreSeedOptions.of(target).withRefresh(config.isRefresh())
                    .withFetchedAt(Instant.now().toString());
            StoreSeedReport report;
            if (config.isFromApi())
            {
                CoreLibraryAccess access = CoreLibraryAccess.openIfConfigured().orElse(null);
                if (access == null)
                {
                    LOG.warn("corej.cache-seed.from-api needs a CDISC Library API key "
                            + "(CDISC_API_KEY / cdisc.library.api.key); skipping metadata store "
                            + "seeding — configure a key, or seed from pickles instead");
                    return;
                }
                report = new WebApiStoreSeeder(access).seed(options);
            }
            else
            {
                try (PickleSource source = buildSource())
                {
                    report = new PickleStoreSeeder(source).seed(options);
                }
            }
            LOG.info("Metadata store seeded: {}", report.summary());
            report.ctPackagesMissed().forEach(m -> LOG.warn("  store seed, missing: {}", m));
            report.warnings().forEach(w -> LOG.warn("  store seed: {}", w));
            publishDefaultIfUnconfigured(target);
        }
        catch (IOException | RuntimeException e)
        {
            // Never fail startup: the store is an optimisation, not a precondition. Catching both
            // the declared IOException and any unchecked failure covers every way seeding can go
            // wrong (unreachable host, malformed archive, unwritable target) without swallowing
            // Errors. Same idiom as the CLI's seedCache.
            LOG.warn("Metadata store seeding failed ({}); continuing without it", e.getMessage(),
                    e);
        }
    }


    private PickleSource buildSource()
    {
        String fromDir = config.getFromDir();
        if (fromDir != null && !fromDir.isBlank())
        {
            return new LocalPickleSource(Path.of(fromDir));
        }
        return new HttpArchivePickleSource(
                orDefault(config.getRepoUri(), HttpArchivePickleSource.DEFAULT_REPO_URI),
                blankToNull(config.getRef()),
                orDefault(config.getRepoPath(), HttpArchivePickleSource.DEFAULT_REPO_PATH),
                blankToNull(config.getArchiveUrlTemplate()), null);
    }


    /**
     * The store file to seed: the explicit {@code target-store} property, else the engine's own
     * configuration ({@code CDISC_METADATA_STORE} before {@code cdisc.metadata.store} — the same
     * order the engine reads), else the application default. Unlike a read, the target need not
     * exist — seeding is what creates it.
     */
    private Path resolveTargetStore()
    {
        String configured = config.getTargetStore();
        if (configured != null && !configured.isBlank())
        {
            return Path.of(configured).toAbsolutePath();
        }
        Path fromEnvironment = MetadataCacheLocator.configuredStore();
        if (fromEnvironment != null)
        {
            return fromEnvironment.toAbsolutePath();
        }
        return MetadataCacheLocator.defaultStore().toAbsolutePath();
    }


    /**
     * Publishes {@code aTarget} into {@code cdisc.metadata.store} when nothing outside configured a
     * store and the file exists — see the class comment. An explicit configuration (environment
     * variable or system property) always wins and is never touched.
     */
    private static void publishDefaultIfUnconfigured(Path aTarget)
    {
        if (MetadataCacheLocator.configuredStore() == null && Files.isRegularFile(aTarget))
        {
            System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, aTarget.toString());
            LOG.info("Published {} as {} for this process", aTarget,
                    StoreMetadataProviderFactory.STORE_PROPERTY);
        }
    }


    private static String orDefault(@Nullable String aValue, String aDefault)
    {
        return aValue == null || aValue.isBlank() ? aDefault : aValue;
    }


    private static @Nullable String blankToNull(@Nullable String aValue)
    {
        return aValue == null || aValue.isBlank() ? null : aValue;
    }
}
