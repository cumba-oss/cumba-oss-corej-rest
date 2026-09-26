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
@org.junit.jupiter.api.extension.ExtendWith(net.cumba.corej.rest.HomeStaysCleanExtension.class)
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


    /**
     * NS5 (PLAN-define-ct-evaluation S11): an existing store of an OLD FORMAT is re-seeded even
     * with {@code refresh=false} — the engine refuses such a store outright, so "target exists ⇒
     * skip" would strand the deployment on it forever. The old store is produced by the relabel
     * trick {@code MetadataStoreCorruptionTest} uses (formatVersion rewritten to 1, which no reader
     * has known since 2026-09-08), so the case is red on any build that skips an existing file and
     * stays meaningful across future bumps.
     */
    @Test
    void anOldFormatStoreIsReseededEvenWithoutRefresh(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);
        Path target = root.resolve("metadata-cache.zip");
        initializer(pkl, target, false).run(new DefaultApplicationArguments());
        relabelFormatVersion(target, 1);
        assertThrows(net.cumba.corej.core.metadata.store.StoreFormatException.class,
                () -> MetadataStore.open(target), "the relabelled store must be refused");

        initializer(pkl, target, false).run(new DefaultApplicationArguments());

        try (MetadataStore opened = MetadataStore.open(target))
        {
            assertEquals(List.of("sdtmct-2024-09-27"), opened.publishedCtPackages(),
                    "re-seeded in full: the target opens at the current format again");
        }
    }


    /**
     * Review round 2 (M-NEWER a): a store written by a NEWER build is NOT re-seeded without
     * {@code refresh=true} - a downgrade is an explicit operator action - and with it, it is.
     */
    @Test
    void aNewerFormatStoreIsLeftAloneUnlessRefreshIsExplicit(@TempDir Path root) throws IOException
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("CDISC_METADATA_STORE") == null,
                "CDISC_METADATA_STORE is set — the publication branch is untestable");
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        Path pkl = pickleDir(root);
        Path target = root.resolve("metadata-cache.zip");
        initializer(pkl, target, false).run(new DefaultApplicationArguments());
        relabelFormatVersion(target, 99);
        byte[] before = Files.readAllBytes(target);
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);

        initializer(pkl, target, false).run(new DefaultApplicationArguments());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(target)),
                "a newer-build store is left byte-for-byte alone without refresh");
        assertThrows(net.cumba.corej.core.metadata.store.StoreFormatException.class,
                () -> MetadataStore.open(target), "still the newer store");
        // Review round 3 (M1): still PUBLISHED, so a zero-configuration deployment reaches the
        // T1-10 (a) abort naming the newer-build remedy - not a silent R2 degrade and an empty
        // /meta list. The run opens its store through exactly this resolution and factory.
        assertEquals(target.toAbsolutePath().toString(),
                System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY),
                "the newer-build store is published for the engine");
        Path resolved = StoreMetadataProviderFactory.resolveConfiguredFile(null);
        assertEquals(target.toAbsolutePath(), resolved);
        net.cumba.corej.core.metadata.store.StoreFormatException abort = assertThrows(
                net.cumba.corej.core.metadata.store.StoreFormatException.class,
                () -> StoreMetadataProviderFactory.open(resolved));
        assertTrue(abort.writtenByNewerBuild());
        String failure = net.cumba.corej.rest.run.RunExecutor
                .failureMessage(new IOException(abort.describe(), abort), true);
        assertTrue(failure.contains("written by a newer build"), failure);
        assertTrue(failure.contains("Upgrade this service"), failure);
        // ... and /meta answers 503 with the same remedy instead of 200 with an empty list.
        CorejProperties seeding = new CorejProperties();
        seeding.getCacheSeed().setEnabled(true);
        org.springframework.web.server.ResponseStatusException meta = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> new net.cumba.corej.rest.web.MetaController(seeding).runOptions());
        assertEquals(503, meta.getStatusCode().value());
        assertTrue(String.valueOf(meta.getReason()).contains("Upgrade this service"),
                meta.getReason());

        initializer(pkl, target, true).run(new DefaultApplicationArguments());
        try (MetadataStore opened = MetadataStore.open(target))
        {
            assertEquals(List.of("sdtmct-2024-09-27"), opened.publishedCtPackages(),
                    "refresh=true replaces it deliberately");
        }
    }


    /** Rewrites the store's manifest {@code formatVersion} in place (a copy, entry by entry). */
    private static void relabelFormatVersion(Path aStore, int aVersion) throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(aStore.toFile()))
        {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> names = zip.entries();
            while (names.hasMoreElements())
            {
                java.util.zip.ZipEntry entry = names.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        String manifest = new String(entries.get("manifest.json"),
                java.nio.charset.StandardCharsets.UTF_8);
        String relabelled = manifest.replaceFirst("\"formatVersion\" : \\d+",
                "\"formatVersion\" : " + aVersion);
        assertTrue(!relabelled.equals(manifest), "the relabel must change the manifest");
        entries.put("manifest.json", relabelled.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(aStore)))
        {
            for (Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
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
