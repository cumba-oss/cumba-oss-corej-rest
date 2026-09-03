package net.cumba.corej.rest.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.razorvine.pickle.Pickler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * {@link CacheSeedInitializer}: startup seeding from a local pickle directory, and — the property
 * that matters most — that a seeding failure never propagates out of startup.
 */
class CacheSeedInitializerTest
{

    /**
     * States the ambient configuration these tests depend on instead of inheriting it silently.
     *
     * <p>
     * {@link CacheSeedInitializer} seeds against {@link CdiscLibraryClient#getApiUrl()}, and the
     * seeder derives the cache file-name prefix from that URL's path — so the {@code api_…} names
     * asserted below hold only for the default base URL. {@code getApiUrl} consults the
     * <b>environment</b> variable {@code CDISC_API_URL} first, which a JVM cannot unset for itself;
     * the dependency can therefore only be declared, not removed. Failing here names the cause,
     * where the assertions themselves would only report a missing file.
     * </p>
     *
     * <p>
     * {@code CDISC_API_CACHE} cannot leak in: every case below sets {@code target-dir} explicitly,
     * so {@code resolveTargetDir} never reaches its environment fallback.
     * </p>
     */
    @BeforeAll
    static void theAmbientLibraryUrlMustBeTheDefault()
    {
        assertEquals(CdiscLibraryClient.DEFAULT_BASE_URL, CdiscLibraryClient.getApiUrl(),
                "these tests assert cache file names derived from the default CDISC Library base "
                        + "URL; unset CDISC_API_URL (env) / cdisc.library.api.url (system "
                        + "property) before running them");
    }


    private static Path pickleDir(Path aRoot) throws IOException
    {
        Path dir = Files.createDirectories(aRoot.resolve("pkl"));
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("package", "sdtmct-2024-09-27");
        ct.put("codelists", List.of());
        Files.write(dir.resolve("sdtmct-2024-09-27.pkl"), new Pickler().dumps(ct));
        return dir;
    }


    private static CacheSeedInitializer initializer(Path aFromDir, Path aTargetDir,
            boolean aOverwrite)
    {
        CorejProperties props = new CorejProperties();
        CorejProperties.CacheSeed seed = props.getCacheSeed();
        seed.setEnabled(true);
        seed.setFromDir(aFromDir.toString());
        seed.setTargetDir(aTargetDir.toString());
        seed.setOverwrite(aOverwrite);
        return new CacheSeedInitializer(props);
    }


    @Test
    void seedsTheConfiguredTargetDirectoryAtStartup(@TempDir Path root) throws IOException
    {
        Path target = root.resolve("cache");

        initializer(pickleDir(root), target, false).run(new DefaultApplicationArguments());

        assertTrue(Files.exists(
                target.resolve("api_mdr_ct_packages_sdtmct-2024-09-27%3Fexpand%3Dtrue.json.gz")));
        assertTrue(Files.exists(target.resolve("api_mdr_ct_packages.json.gz")));
    }


    /**
     * The load-bearing behaviour: a metadata cache is an optimisation, not a precondition, so an
     * unreachable source must not abort application startup.
     */
    @Test
    void aSeedingFailureDoesNotPropagate(@TempDir Path root)
    {
        CacheSeedInitializer initializer = initializer(root.resolve("does-not-exist"),
                root.resolve("cache"), false);

        assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
        assertFalse(Files.exists(root.resolve("cache").resolve("api_mdr_ct_packages.json.gz")));
    }


    /**
     * H2 regression guard. Target resolution used to sit outside the try block, so a malformed
     * {@code target-dir} threw {@code InvalidPathException} straight out of {@code run()} and
     * aborted Spring context startup — the one thing this class promises cannot happen.
     */
    @Test
    void aMalformedTargetDirDoesNotAbortStartup(@TempDir Path root) throws IOException
    {
        CorejProperties props = new CorejProperties();
        CorejProperties.CacheSeed seed = props.getCacheSeed();
        seed.setEnabled(true);
        seed.setFromDir(pickleDir(root).toString());
        // A NUL byte is rejected by Path.of on every platform. Built explicitly rather than
        // embedded as a raw control character in the source, and asserted so the test cannot
        // silently degrade into using a perfectly valid path.
        String malformed = "bad" + (char) 0 + "path";
        assertThrows(InvalidPathException.class, () -> Path.of(malformed));
        seed.setTargetDir(malformed);
        CacheSeedInitializer initializer = new CacheSeedInitializer(props);

        assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
    }


    /**
     * M9: with the cache already populated and {@code overwrite} false, startup must not re-run the
     * whole download/extract/read cycle just to skip every write.
     */
    @Test
    void anAlreadyPopulatedCacheIsNotReseeded(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);
        Path target = root.resolve("cache");
        initializer(pkl, target, false).run(new DefaultApplicationArguments());
        Path entry = target
                .resolve("api_mdr_ct_packages_sdtmct-2024-09-27%3Fexpand%3Dtrue.json.gz");
        assertTrue(Files.exists(entry));
        Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.fromMillis(0));

        // A source that would explode if it were resolved proves the short-circuit fired.
        CorejProperties props = new CorejProperties();
        CorejProperties.CacheSeed seed = props.getCacheSeed();
        seed.setEnabled(true);
        seed.setFromDir(root.resolve("nonexistent-source").toString());
        seed.setTargetDir(target.toString());
        new CacheSeedInitializer(props).run(new DefaultApplicationArguments());

        assertEquals(0, Files.getLastModifiedTime(entry).toMillis(),
                "a populated cache must be left completely alone");
    }


    @Test
    void defaultsLeaveSeedingDisabled()
    {
        CorejProperties.CacheSeed seed = new CorejProperties().getCacheSeed();

        assertFalse(seed.isEnabled(), "seeding must be opt-in");
        assertFalse(seed.isOverwrite());
        assertEquals(null, seed.getRepoUri());
        assertEquals(null, seed.getFromDir());
        assertEquals(null, seed.getTargetDir());
    }


    @Test
    void rerunSkipsExistingEntriesUnlessOverwriteIsSet(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);
        Path target = root.resolve("cache");
        initializer(pkl, target, false).run(new DefaultApplicationArguments());
        Path entry = target
                .resolve("api_mdr_ct_packages_sdtmct-2024-09-27%3Fexpand%3Dtrue.json.gz");
        Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.fromMillis(0));

        initializer(pkl, target, false).run(new DefaultApplicationArguments());
        assertEquals(0, Files.getLastModifiedTime(entry).toMillis(),
                "skip-existing must leave the entry untouched");

        initializer(pkl, target, true).run(new DefaultApplicationArguments());
        assertTrue(Files.getLastModifiedTime(entry).toMillis() > 0,
                "overwrite must rewrite the entry");
    }
}
