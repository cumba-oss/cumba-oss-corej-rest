package net.cumba.corej.rest;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails — and names the file — any test that lets the code under test write into the REAL user
 * home: {@code ~/.cumbaDataBrowser/metadata-cache.zip} (the app-default metadata store) or
 * {@code ~/.cumbaDataBrowser/config.json} (the data browser's config).
 *
 * <p>
 * Why this exists (PLAN-define-ct-evaluation review round 1, H1): no repo sandboxed
 * {@code user.home} for its test JVMs, and pitest minions inherit nothing from Surefire's
 * {@code systemPropertyVariables}, so every fallback that resolves the app default —
 * {@code MetadataCacheLocator.defaultStore()}, {@code AppConfig.initialize()} — landed on the
 * developer's real home whenever a test (or a pitest mutant) reached it. Two leaks were found on
 * 2026-09-26: a 1 KB format-2 store whose provenance named a junit temp directory, and a
 * {@code config.json} holding a test's CORE-check history entry. Every repo now redirects
 * {@code user.home} to {@code target/test-home} in Surefire AND in the pitest {@code jvmArgs}; this
 * extension is the guard that reds if that redirect is ever lost or bypassed: it watches the REAL
 * home (the {@code HOME} / {@code USERPROFILE} environment, which the redirect does not touch)
 * across each test and fails the test that made either file appear or change.
 * </p>
 */
public final class HomeStaysCleanExtension implements BeforeEachCallback, AfterEachCallback
{

    private static final List<String> WATCHED = List.of("metadata-cache.zip", "config.json");

    private final Map<Path, String> before = new LinkedHashMap<>();

    /** The real home directory: the environment's, never {@code user.home} (redirected). */
    static Path realHome()
    {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank())
        {
            home = System.getenv("USERPROFILE");
        }
        if (home == null || home.isBlank())
        {
            home = System.getProperty("user.home");
        }
        return Path.of(home).toAbsolutePath();
    }


    private static Map<Path, String> snapshot() throws IOException
    {
        Map<Path, String> state = new LinkedHashMap<>();
        Path dir = realHome().resolve(".cumbaDataBrowser");
        for (String name : WATCHED)
        {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file))
            {
                FileTime modified = Files.getLastModifiedTime(file);
                state.put(file, Files.size(file) + "@" + modified);
            }
            else
            {
                state.put(file, "absent");
            }
        }
        return state;
    }


    @Override
    public void beforeEach(ExtensionContext aContext) throws IOException
    {
        before.clear();
        before.putAll(snapshot());
    }


    @Override
    public void afterEach(ExtensionContext aContext) throws IOException
    {
        Map<Path, String> after = snapshot();
        for (Map.Entry<Path, String> entry : after.entrySet())
        {
            String was = before.get(entry.getKey());
            if (was != null && !was.equals(entry.getValue()))
            {
                fail(aContext.getDisplayName() + " wrote into the REAL user home: " + entry.getKey()
                        + " went from [" + was + "] to [" + entry.getValue()
                        + "]. Test JVMs must resolve user.home to target/test-home (Surefire"
                        + " systemPropertyVariables AND the pitest jvmArgs); a test that reaches"
                        + " the app default must set an explicit target or run under that"
                        + " redirect.");
            }
        }
    }
}
