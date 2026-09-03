package net.cumba.corej.rest.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.corej.core.metadata.pickle.HttpArchivePickleSource;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.pickle.PickleCacheSeeder;
import net.cumba.corej.core.metadata.pickle.PickleSource;
import net.cumba.corej.core.metadata.pickle.SeedOptions;
import net.cumba.corej.core.metadata.pickle.SeedReport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds the CDISC Library web-api cache from the Python engine's pickle metadata at startup, so a
 * deployment without an API key still has library metadata available.
 *
 * <p>
 * Opt-in via {@code corej.cache-seed.enabled}. There is deliberately <b>no HTTP endpoint</b>:
 * seeding performs outbound network I/O, writes to a server-side directory and can take minutes.
 * Exposing that to callers would be a privileged operation needing its own authorisation story, so
 * every input is server-configured instead.
 * </p>
 *
 * <p>
 * Failures are logged and swallowed. A metadata cache is an optimisation, not a precondition — a
 * network outage or an upstream layout change must not stop the service from starting.
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
        // target-dir throws InvalidPathException, and an unset user.home makes the fallback throw
        // NPE. Both are unchecked and would otherwise escape run() and abort context startup —
        // exactly what this class promises cannot happen.
        try (PickleSource source = buildSource())
        {
            Path target = resolveTargetDir();
            if (!config.isOverwrite() && alreadySeeded(target))
            {
                LOG.info("CDISC Library cache at {} is already populated; skipping seeding "
                        + "(set corej.cache-seed.overwrite=true to force)", target);
                return;
            }
            LOG.info("Seeding CDISC Library cache at {} …", target);
            SeedReport report = new PickleCacheSeeder()
                    .seed(SeedOptions.builder(source, target, CdiscLibraryClient.getApiUrl())
                            .overwriteExisting(config.isOverwrite()).build());
            LOG.info("CDISC Library cache seeded: {}", report.summary());
            report.warnings().forEach(w -> LOG.warn("  cache seed: {}", w));
        }
        catch (IOException | RuntimeException e)
        {
            // Never fail startup: the cache is an optimisation, not a precondition. Catching both
            // the declared IOException and any unchecked failure covers every way seeding can go
            // wrong (unreachable host, malformed archive, unwritable target) without swallowing
            // Errors. Same idiom as the CLI's seedCache.
            LOG.warn("CDISC Library cache seeding failed ({}); continuing without it",
                    e.getMessage(), e);
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


    private Path resolveTargetDir()
    {
        String configured = config.getTargetDir();
        if (configured != null && !configured.isBlank())
        {
            return Path.of(configured).toAbsolutePath();
        }
        // Delegate to the client's own resolver rather than re-implementing it: it checks the
        // environment variable BEFORE the system property, and getting that order wrong would
        // seed one directory while CdiscLibraryClient reads another.
        String configuredCache = CdiscLibraryClient.retrieveSystemProperty(
                CdiscLibraryClient.ENV_CDISC_API_CACHE, CdiscLibraryClient.SP_CDISC_API_CACHE);
        if (configuredCache != null && !configuredCache.isBlank())
        {
            return Path.of(configuredCache).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home", "."), ".cdiscApiCache").toAbsolutePath();
    }


    /** Whether the target already holds cache entries, so a re-seed would be pure overhead. */
    private static boolean alreadySeeded(Path aTarget)
    {
        if (!Files.isDirectory(aTarget))
        {
            return false;
        }
        try (java.util.stream.Stream<Path> files = Files.list(aTarget))
        {
            // Path.getFileName() is null for a root path; Objects.toString keeps that off the
            // dereference path rather than relying on it never occurring.
            return files.anyMatch(
                    p -> java.util.Objects.toString(p.getFileName(), "").endsWith(".json.gz"));
        }
        catch (IOException e)
        {
            LOG.debug("could not inspect {}; assuming it needs seeding", aTarget, e);
            return false;
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
