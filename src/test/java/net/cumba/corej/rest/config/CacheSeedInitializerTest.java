package net.cumba.corej.rest.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.razorvine.pickle.Pickler;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * {@link CacheSeedInitializer}: startup seeding of the unified metadata store from a local pickle
 * directory, and — the property that matters most — that a seeding failure never propagates out of
 * startup.
 */
class CacheSeedInitializerTest
{

    /**
     * ⚠ {@code CacheSeedInitializer} publishes the seeded store into {@code cdisc.metadata.store}
     * when nothing else configured one — a process-global side effect these tests must isolate and
     * restore, or they would leak a temp-file store into every later test in this JVM.
     */
    private @Nullable String savedStoreProperty;

    @BeforeEach
    void saveStoreProperty()
    {
        savedStoreProperty = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
    }


    @AfterEach
    void restoreStoreProperty()
    {
        if (savedStoreProperty == null)
        {
            System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        }
        else
        {
            System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, savedStoreProperty);
        }
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


    private static CacheSeedInitializer initializer(Path aFromDir, Path aTargetStore,
            boolean aRefresh)
    {
        CorejProperties props = new CorejProperties();
        CorejProperties.CacheSeed seed = props.getCacheSeed();
        seed.setEnabled(true);
        seed.setFromDir(aFromDir.toString());
        seed.setTargetStore(aTargetStore.toString());
        seed.setRefresh(aRefresh);
        return new CacheSeedInitializer(props);
    }


    @Test
    void seedsTheConfiguredTargetStoreAtStartup(@TempDir Path root) throws IOException
    {
        Path target = root.resolve("metadata-cache.zip");

        initializer(pickleDir(root), target, false).run(new DefaultApplicationArguments());

        // The load-bearing check: the written file must open through the ENGINE's own reader.
        try (MetadataStore opened = MetadataStore.open(target))
        {
            assertEquals(List.of("sdtmct-2024-09-27"), opened.publishedCtPackages());
        }
    }


    /**
     * The seeded store is published into {@code cdisc.metadata.store} when nothing else configured
     * one — that property is how the engine finds the store, and without the publication a seeded
     * deployment would still run degraded (every library rule skipping) with nothing red.
     */
    @Test
    void theSeededStoreIsPublishedForTheEngine(@TempDir Path root) throws IOException
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("CDISC_METADATA_STORE") == null,
                "CDISC_METADATA_STORE is set — the publication branch is untestable");
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        Path target = root.resolve("metadata-cache.zip");

        initializer(pickleDir(root), target, false).run(new DefaultApplicationArguments());

        assertEquals(target.toAbsolutePath().toString(),
                System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY));
    }


    /** An explicitly configured store property is never overridden by the publication. */
    @Test
    void anExplicitStoreConfigurationIsNotOverridden(@TempDir Path root) throws IOException
    {
        Path elsewhere = root.resolve("operator-store.zip");
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, elsewhere.toString());
        Path target = root.resolve("metadata-cache.zip");

        initializer(pickleDir(root), target, false).run(new DefaultApplicationArguments());

        assertEquals(elsewhere.toString(),
                System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY));
    }


    /**
     * The load-bearing behaviour: a metadata store is an optimisation, not a precondition, so an
     * unreachable source must not abort application startup.
     */
    @Test
    void aSeedingFailureDoesNotPropagate(@TempDir Path root)
    {
        CacheSeedInitializer initializer = initializer(root.resolve("does-not-exist"),
                root.resolve("metadata-cache.zip"), false);

        assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
        assertFalse(Files.exists(root.resolve("metadata-cache.zip")));
    }


    /**
     * H2 regression guard. Target resolution used to sit outside the try block, so a malformed
     * target threw {@code InvalidPathException} straight out of {@code run()} and aborted Spring
     * context startup — the one thing this class promises cannot happen.
     */
    @Test
    void aMalformedTargetStoreDoesNotAbortStartup(@TempDir Path root) throws IOException
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
        seed.setTargetStore(malformed);
        CacheSeedInitializer initializer = new CacheSeedInitializer(props);

        assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
    }


    /**
     * M9: with the store already present and {@code refresh} false, startup must not re-run the
     * whole download/extract/read cycle just to rebuild an existing store.
     */
    @Test
    void anExistingStoreIsNotReseeded(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);
        Path target = root.resolve("metadata-cache.zip");
        initializer(pkl, target, false).run(new DefaultApplicationArguments());
        assertTrue(Files.isRegularFile(target));
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(0));

        // A source that would explode if it were resolved proves the short-circuit fired.
        CorejProperties props = new CorejProperties();
        CorejProperties.CacheSeed seed = props.getCacheSeed();
        seed.setEnabled(true);
        seed.setFromDir(root.resolve("nonexistent-source").toString());
        seed.setTargetStore(target.toString());
        new CacheSeedInitializer(props).run(new DefaultApplicationArguments());

        assertEquals(0, Files.getLastModifiedTime(target).toMillis(),
                "an existing store must be left completely alone");
    }


    /** {@code refresh} rebuilds even an existing store, re-acquiring everything. */
    @Test
    void refreshRebuildsAnExistingStore(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);
        Path target = root.resolve("metadata-cache.zip");
        initializer(pkl, target, false).run(new DefaultApplicationArguments());
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(0));

        initializer(pkl, target, true).run(new DefaultApplicationArguments());

        assertTrue(Files.getLastModifiedTime(target).toMillis() > 0,
                "refresh must rebuild the store");
    }


    /**
     * {@code from-api} without an API key skips seeding with a WARN — never a throw, never a
     * network attempt, and startup continues.
     */
    @Test
    void fromApiWithoutAnApiKeySkipsSeeding(@TempDir Path root)
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("CDISC_API_KEY") == null
                        && System.getProperty("cdisc.library.api.key") == null,
                "a CDISC Library API key is configured — the keyless skip is untestable");
        CorejProperties props = new CorejProperties();
        CorejProperties.CacheSeed seed = props.getCacheSeed();
        seed.setEnabled(true);
        seed.setFromApi(true);
        seed.setTargetStore(root.resolve("metadata-cache.zip").toString());
        CacheSeedInitializer initializer = new CacheSeedInitializer(props);

        assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
        assertFalse(Files.exists(root.resolve("metadata-cache.zip")));
    }


    /** {@code from-api} beside {@code from-dir} is a configuration contradiction: skip, WARN. */
    @Test
    void fromApiBesideFromDirSkipsSeeding(@TempDir Path root) throws IOException
    {
        CorejProperties props = new CorejProperties();
        CorejProperties.CacheSeed seed = props.getCacheSeed();
        seed.setEnabled(true);
        seed.setFromApi(true);
        seed.setFromDir(pickleDir(root).toString());
        seed.setTargetStore(root.resolve("metadata-cache.zip").toString());
        CacheSeedInitializer initializer = new CacheSeedInitializer(props);

        assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
        assertFalse(Files.exists(root.resolve("metadata-cache.zip")));
    }


    @Test
    void defaultsLeaveSeedingDisabled()
    {
        CorejProperties.CacheSeed seed = new CorejProperties().getCacheSeed();

        assertFalse(seed.isEnabled(), "seeding must be opt-in");
        assertFalse(seed.isRefresh());
        assertFalse(seed.isFromApi());
        assertNull(seed.getRepoUri());
        assertNull(seed.getFromDir());
        assertNull(seed.getTargetStore());
    }
}
