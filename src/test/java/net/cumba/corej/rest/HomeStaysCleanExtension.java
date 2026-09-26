package net.cumba.corej.rest;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails — naming every path — any test that lets the code under test write into the REAL user
 * home's {@code .cumbaDataBrowser} tree: the app-default metadata store, the data browser's
 * {@code config.json}, the private dictionary store, anything.
 *
 * <p>
 * Why this exists (PLAN-define-ct-evaluation review round 1, H1): no repo sandboxed
 * {@code user.home} for its test JVMs, and pitest minions inherit nothing from Surefire's
 * {@code systemPropertyVariables}, so every fallback that resolves the app default —
 * {@code MetadataCacheLocator.defaultStore()}, {@code AppConfig.initialize()},
 * {@code DictionaryStoreLocator.createPrivateDirectories} — landed on the developer's real home
 * whenever a test (or a pitest mutant) reached it. Three leaks were found in 2026-09: a 1 KB
 * format-2 store whose provenance named a junit temp directory, a {@code config.json} holding a
 * test's CORE-check history entry, and an empty owner-only {@code dictionaries/} directory. Every
 * repo now redirects {@code user.home} to {@code target/test-home} in Surefire AND in the pitest
 * {@code jvmArgs}; this extension is the guard that reds if that redirect is ever lost or bypassed.
 * </p>
 *
 * <p>
 * Review round 2: the real home is read from {@code -DrealUserHome=${user.home}}, which the poms
 * pass from the Maven JVM (the one place that still sees the undirected value) — never from
 * {@code $HOME}, which is not the home Java resolves on Windows. The tree is watched RECURSIVELY,
 * and nothing is tolerated: any entry created, removed or changed fails the test that did it.
 * {@link HomeStaysCleanExtensionSelfTest} is the negative control.
 * </p>
 */
public final class HomeStaysCleanExtension implements BeforeEachCallback, AfterEachCallback
{

    /** The system property the poms pass: the Maven JVM's {@code user.home}. */
    static final String REAL_HOME_PROPERTY = "realUserHome";

    private final Map<Path, String> before = new TreeMap<>();

    /** The tail every pom's redirect ends in; a {@code user.home} ending in it IS the redirect. */
    static final Path REDIRECT_TAIL = Path.of("target", "test-home");

    /**
     * The real home directory: {@code -DrealUserHome}, passed by the poms from the Maven JVM;
     * without it (an IDE run, review round 3 M2) {@code user.home} itself, which an IDE leaves
     * undirected. Fails only when {@code user.home} IS the redirect and the property is absent — a
     * pom that kept the redirect but lost {@code realUserHome}, the one shape in which the guard
     * cannot tell which home is real.
     */
    static Path realHome()
    {
        return realHome(System.getProperty(REAL_HOME_PROPERTY), System.getProperty("user.home"));
    }


    /** The pure form of {@link #realHome()}, for the self-test's two branches. */
    static Path realHome(@Nullable String aRealUserHome, String aUserHome)
    {
        if (aRealUserHome != null && !aRealUserHome.isBlank())
        {
            return Path.of(aRealUserHome).toAbsolutePath();
        }
        Path home = Path.of(aUserHome).toAbsolutePath();
        if (home.endsWith(REDIRECT_TAIL))
        {
            fail("user.home is the test redirect (" + home + ") but -D" + REAL_HOME_PROPERTY
                    + " is not set: the poms pass the Maven JVM's user.home to every test JVM"
                    + " (Surefire systemPropertyVariables AND the pitest jvmArgs) so this guard"
                    + " knows which home is the REAL one; restore that property");
        }
        return home;
    }


    /** Every entry under the real {@code .cumbaDataBrowser}, recursively, with a change stamp. */
    static Map<Path, String> snapshot() throws IOException
    {
        Map<Path, String> state = new TreeMap<>();
        Path dir = realHome().resolve(".cumbaDataBrowser");
        if (!Files.exists(dir))
        {
            return state;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            for (Path p : walk.toList())
            {
                state.put(p, Files.isDirectory(p) ? "dir"
                        : Files.size(p) + "@" + Files.getLastModifiedTime(p));
            }
        }
        return state;
    }


    @Override
    public void beforeEach(@Nullable ExtensionContext aContext) throws IOException
    {
        before.clear();
        before.putAll(snapshot());
    }


    @Override
    public void afterEach(@Nullable ExtensionContext aContext) throws IOException
    {
        Map<Path, String> after = snapshot();
        List<String> changes = new ArrayList<>();
        for (Map.Entry<Path, String> entry : after.entrySet())
        {
            String was = before.get(entry.getKey());
            if (was == null)
            {
                changes.add("created " + entry.getKey() + " [" + entry.getValue() + "]");
            }
            else if (!was.equals(entry.getValue()))
            {
                changes.add("changed " + entry.getKey() + " [" + was + "] -> [" + entry.getValue()
                        + "]");
            }
        }
        for (Path gone : before.keySet())
        {
            if (!after.containsKey(gone))
            {
                changes.add("removed " + gone);
            }
        }
        if (!changes.isEmpty())
        {
            String name = aContext == null ? "<self-test>" : aContext.getDisplayName();
            fail(name + " wrote into the REAL user home (" + realHome() + "): "
                    + String.join("; ", changes)
                    + ". Test JVMs must resolve user.home to target/test-home (Surefire"
                    + " systemPropertyVariables AND the pitest jvmArgs); a test that reaches an"
                    + " app default must set an explicit target or run under that redirect.");
        }
    }
}
